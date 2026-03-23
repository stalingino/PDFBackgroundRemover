"""PDF Background Remover — remove backgrounds from PDFs for printing."""

import fitz  # PyMuPDF
from flask import Flask, request, render_template_string, send_file, jsonify
import tempfile
import os
import re
import io
import base64
import numpy as np
from pathlib import Path
from PIL import Image
from collections import Counter

app = Flask(__name__)
UPLOAD_DIR = tempfile.mkdtemp(prefix="pdf_bg_remover_")
OUTPUT_DIR = tempfile.mkdtemp(prefix="pdf_bg_output_")


# ─── PDF Processing Logic ─────────────────────────────────────────────

def render_page_thumbnail(page, zoom=1.0):
    mat = fitz.Matrix(zoom, zoom)
    pix = page.get_pixmap(matrix=mat)
    return pix.tobytes("png")


def detect_bg_color(page):
    """Detect the dominant background color by sampling page corners."""
    pix = page.get_pixmap(matrix=fitz.Matrix(0.25, 0.25))
    w, h = pix.width, pix.height
    # Sample corners and edges
    points = [
        (1, 1), (w-2, 1), (1, h-2), (w-2, h-2),  # corners
        (w//4, 1), (3*w//4, 1), (1, h//4), (1, 3*h//4),  # edges
    ]
    pixels = []
    for x, y in points:
        p = pix.pixel(x, y)
        pixels.append(tuple(p[:3]))  # RGB only

    # Most common corner color = background
    counter = Counter(pixels)
    bg_color = counter.most_common(1)[0][0]
    return bg_color


def _replace_image_bg(doc, page, bg_color, tolerance):
    """Replace background color in page images with white."""
    images = page.get_images(full=True)
    modified = False

    for img_info in images:
        xref = img_info[0]
        # Extract the image
        base_image = doc.extract_image(xref)
        if base_image is None:
            continue

        img_bytes = base_image["image"]
        img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
        arr = np.array(img, dtype=np.float32)

        # Calculate distance from background color
        bg = np.array(bg_color, dtype=np.float32)
        diff = np.sqrt(np.sum((arr - bg) ** 2, axis=2))

        # Pixels within tolerance get replaced with white
        tol_dist = tolerance * 441.67  # max RGB distance = sqrt(255^2*3) ≈ 441.67
        mask = diff <= tol_dist

        if not np.any(mask):
            continue

        arr[mask] = [255, 255, 255]
        modified = True

        # Encode back and replace in PDF (use JPEG to keep file size reasonable)
        new_img = Image.fromarray(arr.astype(np.uint8))
        img_buf = io.BytesIO()
        img_format = base_image.get("ext", "png").upper()
        if img_format in ("JPG", "JPEG", "JPX"):
            new_img.save(img_buf, format="JPEG", quality=90)
        else:
            new_img.save(img_buf, format="PNG")
        img_buf.seek(0)

        # Replace the image in the PDF
        page.replace_image(xref, stream=img_buf.read())

    return modified


def _recolor_vector_bg(page, bg_color, tolerance):
    """Replace background-colored vector fills/strokes with white in the content stream."""
    page.clean_contents()
    contents = page.get_contents()
    if not contents:
        return False
    xref = contents[0]
    stream = page.parent.xref_stream(xref).decode("latin-1")
    original = stream

    # Normalize detected bg color to 0-1 range
    bg_r, bg_g, bg_b = bg_color[0] / 255, bg_color[1] / 255, bg_color[2] / 255

    def color_matches_bg(vals):
        """Check if a color (0-1 range) is close to the detected bg color."""
        diff = sum((a - b) ** 2 for a, b in zip(vals, (bg_r, bg_g, bg_b))) ** 0.5
        max_diff = tolerance * (3 ** 0.5)  # max distance in 0-1 space ≈ 1.732
        return diff <= max_diff

    def replace_rgb(match):
        vals = [float(match.group(i)) for i in range(1, 4)]
        op = match.group(4)
        if color_matches_bg(vals):
            return f"1 1 1 {op}"
        return match.group(0)

    def replace_gray(match):
        val = float(match.group(1))
        op = match.group(2)
        if color_matches_bg((val, val, val)):
            return f"1 {op}"
        return match.group(0)

    def replace_cmyk(match):
        c_, m_, y_, k_ = [float(match.group(i)) for i in range(1, 5)]
        op = match.group(5)
        cr = 1 - min(1, c_ + k_)
        cg = 1 - min(1, m_ + k_)
        cb = 1 - min(1, y_ + k_)
        if color_matches_bg((cr, cg, cb)):
            return f"0 0 0 0 {op}"
        return match.group(0)

    stream = re.sub(r"([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+(rg|RG)\b", replace_rgb, stream)
    stream = re.sub(r"([\d.]+)\s+(g|G)\b", replace_gray, stream)
    stream = re.sub(r"([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+(k|K)\b", replace_cmyk, stream)

    if stream != original:
        page.parent.update_stream(xref, stream.encode("latin-1"))
        return True
    return False


def remove_backgrounds_from_pdf(pdf_path, tolerance=0.15):
    """Remove backgrounds from a PDF document.

    Auto-detects the background color from page corners, then:
    1. Replaces matching colors in embedded images (PPT export case)
    2. Replaces matching colors in vector drawing commands
    """
    doc = fitz.open(pdf_path)
    pages_modified = 0
    detected_bg = None

    for page_num in range(len(doc)):
        page = doc[page_num]

        # Detect bg color from first page, reuse for all
        if detected_bg is None:
            detected_bg = detect_bg_color(page)

        modified = False

        # Strategy 1: Replace bg color in embedded images
        if page.get_images():
            modified |= _replace_image_bg(doc, page, detected_bg, tolerance)

        # Strategy 2: Replace bg color in vector content
        modified |= _recolor_vector_bg(page, detected_bg, tolerance)

        if modified:
            pages_modified += 1

    return doc, pages_modified, detected_bg


# ─── Flask Routes ──────────────────────────────────────────────────────

HTML_TEMPLATE = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>PDF Background Remover</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
         background: #f5f5f5; color: #333; padding: 20px; }
  .container { max-width: 1100px; margin: 0 auto; }
  h1 { margin-bottom: 4px; font-size: 1.6em; }
  .subtitle { color: #666; margin-bottom: 20px; font-size: 0.95em; }
  .card { background: #fff; border-radius: 10px; padding: 24px; margin-bottom: 20px;
          box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
  .row { display: flex; gap: 20px; flex-wrap: wrap; }
  .col { flex: 1; min-width: 250px; }
  .col-narrow { flex: 0 0 300px; }
  label { display: block; font-weight: 600; margin-bottom: 6px; font-size: 0.9em; }
  .hint { color: #888; font-size: 0.8em; margin-bottom: 12px; }
  input[type="file"] { width: 100%; padding: 12px; border: 2px dashed #ccc;
                       border-radius: 8px; cursor: pointer; background: #fafafa; }
  input[type="file"]:hover { border-color: #999; }
  input[type="range"] { width: 100%; margin: 4px 0; }
  button { background: #2563eb; color: #fff; border: none; padding: 12px 32px;
           border-radius: 8px; font-size: 1em; font-weight: 600; cursor: pointer;
           width: 100%; }
  button:hover { background: #1d4ed8; }
  button:disabled { background: #94a3b8; cursor: not-allowed; }
  .status { padding: 10px; background: #f0f9ff; border-radius: 6px; font-size: 0.9em;
            color: #1e40af; min-height: 40px; white-space: pre-line; }
  .preview-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-top: 10px; }
  .preview-col h3 { font-size: 0.95em; color: #555; margin-bottom: 8px; text-align: center; }
  .preview-img { width: 100%; border: 1px solid #ddd; border-radius: 6px; background: #eee; }
  .page-nav { display: flex; gap: 8px; justify-content: center; margin-top: 8px;
              flex-wrap: wrap; }
  .page-nav button { width: auto; padding: 4px 12px; font-size: 0.8em; background: #e2e8f0;
                     color: #333; }
  .page-nav button:hover { background: #cbd5e1; }
  .page-nav button.active { background: #2563eb; color: #fff; }
  .download-section { margin-top: 12px; }
  .download-link { display: inline-block; padding: 8px 16px; background: #059669;
                   color: #fff; border-radius: 6px; text-decoration: none; font-weight: 600;
                   font-size: 0.9em; margin: 4px 4px 4px 0; }
  .download-link:hover { background: #047857; }
  .spinner { display: none; }
  .spinner.show { display: inline-block; }
  @keyframes spin { to { transform: rotate(360deg); } }
  .spinner::after { content: ""; display: inline-block; width: 16px; height: 16px;
                    border: 2px solid #fff; border-top-color: transparent;
                    border-radius: 50%; animation: spin 0.6s linear infinite;
                    vertical-align: middle; margin-left: 8px; }
  .file-list { margin-top: 8px; font-size: 0.85em; color: #555; }
  .file-list div { padding: 2px 0; }
  .detected-color { display: inline-block; width: 20px; height: 20px; border: 1px solid #999;
                    border-radius: 4px; vertical-align: middle; margin-left: 8px; }
</style>
</head>
<body>
<div class="container">
  <h1>PDF Background Remover</h1>
  <p class="subtitle">Auto-detects and removes background color from presentation PDFs for clean printing.</p>

  <div class="card">
    <div class="row">
      <div class="col">
        <label>Upload PDF(s)</label>
        <input type="file" id="fileInput" accept=".pdf" multiple>
        <div class="file-list" id="fileList"></div>
      </div>
      <div class="col-narrow">
        <label>Color tolerance: <span id="tolVal">0.15</span></label>
        <input type="range" id="tolerance" min="0" max="1" step="0.01" value="0.15"
               oninput="document.getElementById('tolVal').textContent=parseFloat(this.value).toFixed(2)">
        <div class="hint">How far from the detected background color to match.
          0 = exact match only, higher = more aggressive removal.</div>
      </div>
    </div>
  </div>

  <button id="processBtn" onclick="processFiles()" disabled>
    Process PDFs <span class="spinner" id="spinner"></span>
  </button>

  <div class="card" style="margin-top: 20px;">
    <div class="status" id="status">Upload PDF files and click Process.</div>
  </div>

  <div class="card" id="previewCard" style="display:none;">
    <div class="preview-grid">
      <div class="preview-col">
        <h3>Before</h3>
        <img class="preview-img" id="beforeImg" src="">
      </div>
      <div class="preview-col">
        <h3>After</h3>
        <img class="preview-img" id="afterImg" src="">
      </div>
    </div>
    <div class="page-nav" id="pageNav"></div>
    <div class="download-section" id="downloads"></div>
  </div>
</div>

<script>
const fileInput = document.getElementById('fileInput');
const processBtn = document.getElementById('processBtn');

fileInput.addEventListener('change', () => {
  const list = document.getElementById('fileList');
  list.innerHTML = '';
  for (const f of fileInput.files) {
    list.innerHTML += '<div>' + f.name + ' (' + (f.size/1024).toFixed(0) + ' KB)</div>';
  }
  processBtn.disabled = fileInput.files.length === 0;
});

let resultData = null;

async function saveFile(idx) {
  const status = document.getElementById('status');
  try {
    if (window.pywebview) {
      const data = await pywebview.api.save_file(idx);
      status.textContent = data.message;
    } else {
      window.open('/download/' + idx);
    }
  } catch (e) {
    status.textContent = 'Save error: ' + e.message;
  }
}

async function saveAll() {
  const status = document.getElementById('status');
  try {
    if (window.pywebview) {
      const data = await pywebview.api.save_all();
      status.textContent = data.message;
    } else {
      window.open('/download-all');
    }
  } catch (e) {
    status.textContent = 'Save error: ' + e.message;
  }
}

async function processFiles() {
  const btn = processBtn;
  const spinner = document.getElementById('spinner');
  const status = document.getElementById('status');

  btn.disabled = true;
  spinner.classList.add('show');
  status.textContent = 'Processing...';

  const formData = new FormData();
  for (const f of fileInput.files) {
    formData.append('files', f);
  }
  formData.append('tolerance', document.getElementById('tolerance').value);

  try {
    const resp = await fetch('/process', { method: 'POST', body: formData });
    const data = await resp.json();
    resultData = data;

    status.textContent = data.status;

    if (data.files && data.files.length > 0) {
      document.getElementById('previewCard').style.display = 'block';
      showFilePreviews(0);

      const dl = document.getElementById('downloads');
      dl.innerHTML = '<div style="margin-bottom:6px;font-weight:600;">Save:</div>';
      if (data.files.length > 1) {
        dl.innerHTML += '<button class="download-link" onclick="saveAll()">All files (zip)</button> ';
      }
      data.files.forEach((f, i) => {
        dl.innerHTML += '<button class="download-link" onclick="saveFile(' + i + ')">' + f.name + '</button> ';
      });
    }
  } catch (e) {
    status.textContent = 'Error: ' + e.message;
  }

  spinner.classList.remove('show');
  btn.disabled = false;
}

function showFilePreviews(fileIdx) {
  const f = resultData.files[fileIdx];
  const nav = document.getElementById('pageNav');
  showPage(fileIdx, 0);

  nav.innerHTML = '';
  if (resultData.files.length > 1) {
    resultData.files.forEach((ff, fi) => {
      const b = document.createElement('button');
      b.textContent = ff.name;
      b.className = fi === fileIdx ? 'active' : '';
      b.onclick = () => showFilePreviews(fi);
      nav.appendChild(b);
    });
  }
  if (f.total_pages > 1) {
    for (let p = 0; p < f.total_pages; p++) {
      const b = document.createElement('button');
      b.textContent = 'Page ' + (p + 1);
      b.className = p === 0 ? 'active' : '';
      b.onclick = () => {
        showPage(fileIdx, p);
        nav.querySelectorAll('button').forEach((btn, bi) => {
          if (bi >= (resultData.files.length > 1 ? resultData.files.length : 0))
            btn.className = (bi - (resultData.files.length > 1 ? resultData.files.length : 0)) === p ? 'active' : '';
        });
      };
      nav.appendChild(b);
    }
  }
}

async function showPage(fileIdx, pageIdx) {
  const resp = await fetch('/preview/' + fileIdx + '/' + pageIdx);
  const data = await resp.json();
  document.getElementById('beforeImg').src = 'data:image/png;base64,' + data.before;
  document.getElementById('afterImg').src = 'data:image/png;base64,' + data.after;
}
</script>
</body>
</html>
"""

# In-memory store for current session results
session = {"files": []}


@app.route("/")
def index():
    return render_template_string(HTML_TEMPLATE)


@app.route("/process", methods=["POST"])
def process():
    files = request.files.getlist("files")
    tolerance = float(request.form.get("tolerance", 0.15))

    session["files"] = []
    status_lines = []

    for f in files:
        upload_path = os.path.join(UPLOAD_DIR, f.filename)
        f.save(upload_path)

        processed_doc, pages_modified, detected_bg = remove_backgrounds_from_pdf(
            upload_path, tolerance=tolerance
        )

        out_name = f"print_ready_{f.filename}"
        out_path = os.path.join(OUTPUT_DIR, out_name)
        processed_doc.save(out_path, deflate=True, garbage=3)

        orig_doc = fitz.open(upload_path)
        total_pages = len(orig_doc)

        session["files"].append({
            "name": out_name,
            "original_path": upload_path,
            "output_path": out_path,
            "total_pages": total_pages,
            "pages_modified": pages_modified,
        })

        orig_doc.close()
        processed_doc.close()

        bg_str = f"rgb({detected_bg[0]},{detected_bg[1]},{detected_bg[2]})" if detected_bg else "none"
        status_lines.append(
            f"{f.filename}: detected bg {bg_str}, {pages_modified}/{total_pages} pages modified"
        )

    return jsonify({
        "status": "\n".join(status_lines),
        "files": [{"name": f["name"], "total_pages": f["total_pages"],
                    "pages_modified": f["pages_modified"]}
                   for f in session["files"]],
    })


@app.route("/preview/<int:file_idx>/<int:page_idx>")
def preview(file_idx, page_idx):
    info = session["files"][file_idx]

    orig = fitz.open(info["original_path"])
    before_png = render_page_thumbnail(orig[page_idx], zoom=1.5)
    orig.close()

    out = fitz.open(info["output_path"])
    after_png = render_page_thumbnail(out[page_idx], zoom=1.5)
    out.close()

    return jsonify({
        "before": base64.b64encode(before_png).decode(),
        "after": base64.b64encode(after_png).decode(),
    })


@app.route("/download/<int:file_idx>")
def download(file_idx):
    info = session["files"][file_idx]
    return send_file(info["output_path"], as_attachment=True, download_name=info["name"])


@app.route("/download-all")
def download_all():
    import zipfile
    zip_path = os.path.join(OUTPUT_DIR, "print_ready_all.zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for info in session["files"]:
            zf.write(info["output_path"], info["name"])
    return send_file(zip_path, as_attachment=True, download_name="print_ready_all.zip")


def find_free_port():
    """Find an available port."""
    import socket
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def start_server(port, ready_event):
    """Start Flask with a ready signal."""
    import logging
    log = logging.getLogger("werkzeug")
    log.setLevel(logging.ERROR)

    from werkzeug.serving import make_server
    srv = make_server("127.0.0.1", port, app)
    ready_event.set()  # Signal that the server is bound and ready
    srv.serve_forever()


if __name__ == "__main__":
    import threading
    import sys

    port = find_free_port()
    ready_event = threading.Event()

    server_thread = threading.Thread(target=start_server, args=(port, ready_event), daemon=True)
    server_thread.start()

    # Wait until Flask is bound and ready
    ready_event.wait(timeout=10)

    try:
        import webview
        import shutil

        def save_file(file_idx):
            """Called from JS via pywebview.api.save_file(idx)"""
            info = session["files"][int(file_idx)]
            result = window.create_file_dialog(
                webview.SAVE_DIALOG,
                directory=os.path.expanduser("~/Downloads"),
                save_filename=info["name"],
            )
            if result:
                dest = result if isinstance(result, str) else result[0]
                shutil.copy2(info["output_path"], dest)
                return {"message": "Saved to: " + dest}
            return {"message": "Save cancelled"}

        def save_all():
            """Called from JS via pywebview.api.save_all()"""
            import zipfile
            zip_tmp = os.path.join(OUTPUT_DIR, "print_ready_all.zip")
            with zipfile.ZipFile(zip_tmp, "w", zipfile.ZIP_DEFLATED) as zf:
                for info in session["files"]:
                    zf.write(info["output_path"], info["name"])
            result = window.create_file_dialog(
                webview.SAVE_DIALOG,
                directory=os.path.expanduser("~/Downloads"),
                save_filename="print_ready_all.zip",
            )
            if result:
                dest = result if isinstance(result, str) else result[0]
                shutil.copy2(zip_tmp, dest)
                return {"message": "Saved to: " + dest}
            return {"message": "Save cancelled"}

        window = webview.create_window(
            "PDF Background Remover",
            f"http://127.0.0.1:{port}",
            width=1200,
            height=850,
            min_size=(800, 600),
        )
        window.expose(save_file, save_all)
        webview.start(gui="cef" if sys.platform == "win32" else None)
    except ImportError:
        import webbrowser
        print(f"pywebview not installed, opening in browser instead.")
        print(f"PDF Background Remover: http://127.0.0.1:{port}")
        webbrowser.open(f"http://127.0.0.1:{port}")
        server_thread.join()
