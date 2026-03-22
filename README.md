# PDF Background Remover

Remove background colors from presentation PDFs for clean printing. Auto-detects the background color and replaces it with white — works with both image-based backgrounds (PowerPoint exports) and vector-drawn backgrounds.

## Features

- Auto-detects background color from page corners
- Handles image-based backgrounds (common in PPT/Keynote exports)
- Handles vector/drawn backgrounds (filled rectangles, shapes)
- Before/after preview with page navigation
- Batch processing — upload multiple PDFs at once
- Download individually or as a zip
- Adjustable color tolerance slider
- Native desktop window (via pywebview)

## Quick Start

### Run from source

```bash
python -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
python app.py
```

A native window opens automatically. If pywebview is not available, it falls back to opening in your browser at `http://127.0.0.1:7860`.

### Build standalone app

**macOS:**

```bash
pip install pyinstaller
pyinstaller --onefile --windowed \
  --name "PDFBackgroundRemover" \
  --hidden-import=fitz \
  --hidden-import=fitz_old \
  --hidden-import=pymupdf \
  --hidden-import=numpy \
  --hidden-import=PIL \
  --hidden-import=webview \
  --hidden-import=flask \
  --hidden-import=jinja2 \
  --hidden-import=jinja2.ext \
  app.py
```

Output: `dist/PDFBackgroundRemover.app` — drag to Applications.

**Windows:**

```powershell
pip install pyinstaller
pyinstaller --onefile --windowed `
  --name "PDFBackgroundRemover" `
  --hidden-import=fitz `
  --hidden-import=fitz_old `
  --hidden-import=pymupdf `
  --hidden-import=numpy `
  --hidden-import=PIL `
  --hidden-import=webview `
  --hidden-import=flask `
  --hidden-import=jinja2 `
  --hidden-import=jinja2.ext `
  app.py
```

Output: `dist/PDFBackgroundRemover.exe`

### Build via GitHub Actions

Push to GitHub and either tag a release (`git tag v1.0 && git push --tags`) or trigger manually from the Actions tab. Builds for both macOS and Windows automatically.

## Usage

1. Upload one or more PDF files
2. Adjust **Color tolerance** if needed (default 0.15 works for most files)
   - `0` = exact color match only
   - Higher = more aggressive removal (catches color gradients/variations)
3. Click **Process PDFs**
4. Preview before/after for each page
5. Download the processed files

## How It Works

1. **Detect**: Samples corner pixels of the first page to identify the dominant background color
2. **Image backgrounds**: Finds embedded images covering the page, replaces pixels within the color tolerance with white (using numpy)
3. **Vector backgrounds**: Parses the PDF content stream, replaces matching fill/stroke color commands (RGB, Gray, CMYK) with white
4. **Output**: Saves as `print_ready_<filename>.pdf`

## Dependencies

- [PyMuPDF](https://pymupdf.readthedocs.io/) — PDF parsing and manipulation
- [Flask](https://flask.palletsprojects.com/) — lightweight web UI
- [NumPy](https://numpy.org/) — fast pixel color matching
- [Pillow](https://pillow.readthedocs.io/) — image processing
- [pywebview](https://pywebview.flowrl.com/) — native desktop window (optional)
