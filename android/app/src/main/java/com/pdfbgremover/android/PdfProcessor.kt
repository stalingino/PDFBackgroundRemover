package com.pdfbgremover.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Pixmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.minOf
import kotlin.math.sqrt

data class ProcessedPdf(
    val outputFile: File,
    val originalThumbnails: List<Bitmap>,
    val processedThumbnails: List<Bitmap>,
    val pageCount: Int,
    val fileName: String,
    val detectedColor: Int
)

data class ProcessingProgress(
    val fileIndex: Int,
    val totalFiles: Int,
    val page: Int,
    val totalPages: Int,
    val fileName: String
)

object PdfProcessor {

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Copy a URI to a temp file so MuPDF can open it by path. */
    private fun uriToTempFile(context: Context, uri: Uri, name: String): File {
        val tmp = File(context.cacheDir, "input_${System.currentTimeMillis()}_$name")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { input.copyTo(it) }
        }
        return tmp
    }

    /**
     * Convert a MuPDF DeviceRGB (no-alpha) Pixmap to an Android Bitmap.
     * Pixmap.samples is a flat byte[] in RGB order.
     */
    private fun pixmapToBitmap(pix: Pixmap): Bitmap {
        val w = pix.width
        val h = pix.height
        val s = pix.samples
        val pixels = IntArray(w * h) { i ->
            Color.rgb(
                s[i * 3].toInt() and 0xFF,
                s[i * 3 + 1].toInt() and 0xFF,
                s[i * 3 + 2].toInt() and 0xFF
            )
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    /** Scale bitmap to fit within maxDimension, preserving aspect ratio. */
    private fun createThumbnail(src: Bitmap, maxDimension: Int): Bitmap {
        val aspect = src.width.toFloat() / src.height.toFloat()
        val (w, h) = if (src.width > src.height)
            Pair(maxDimension, (maxDimension / aspect).toInt())
        else
            Pair((maxDimension * aspect).toInt(), maxDimension)
        return Bitmap.createScaledBitmap(src, w.coerceAtLeast(1), h.coerceAtLeast(1), true)
    }

    /** Render a single page to a Bitmap via MuPDF (no rasterization side-effects). */
    private fun renderPage(pdfDoc: PDFDocument, pageNum: Int, scale: Float): Bitmap {
        val page = pdfDoc.loadPage(pageNum)
        val pix = page.toPixmap(Matrix(scale, scale), ColorSpace.DeviceRGB, false)
        val bm = pixmapToBitmap(pix)
        pix.destroy()
        page.destroy()
        return bm
    }

    /**
     * Resolve an indirect PDF reference to its actual PDFObject.
     * If the object is already direct, returns it unchanged.
     */
    private fun resolve(pdfDoc: PDFDocument, obj: PDFObject): PDFObject {
        val xref = obj.asIndirect()
        return if (xref > 0) pdfDoc.getXRefObject(xref) else obj
    }

    // ── Background detection ───────────────────────────────────────────────

    /**
     * Mirrors detect_bg_color() from app.py.
     *
     * Renders the page at 0.25× scale, samples 8 corner/edge points and
     * returns the most-common color — both as 0–1 RGB floats and as an
     * Android Color int.
     */
    private fun detectBgColor(
        pdfDoc: PDFDocument,
        pageNum: Int
    ): Pair<Triple<Float, Float, Float>, Int> {
        val page = pdfDoc.loadPage(pageNum)
        val pix = page.toPixmap(Matrix(0.25f, 0.25f), ColorSpace.DeviceRGB, false)
        val w = pix.width
        val h = pix.height
        val s = pix.samples
        page.destroy()

        fun rgb(x: Int, y: Int): Triple<Int, Int, Int> {
            val cx = x.coerceIn(0, w - 1)
            val cy = y.coerceIn(0, h - 1)
            val i = (cy * w + cx) * 3
            return Triple(s[i].toInt() and 0xFF, s[i + 1].toInt() and 0xFF, s[i + 2].toInt() and 0xFF)
        }

        // 4 corners + 4 edge midpoints (mirrors Python logic)
        val points = listOf(
            Pair(1, 1), Pair(w - 2, 1), Pair(1, h - 2), Pair(w - 2, h - 2),
            Pair(w / 4, 1), Pair(3 * w / 4, 1), Pair(1, h / 4), Pair(1, 3 * h / 4)
        )
        val counts = mutableMapOf<Triple<Int, Int, Int>, Int>()
        for ((x, y) in points) counts[rgb(x, y)] = (counts[rgb(x, y)] ?: 0) + 1
        pix.destroy()

        val (r, g, b) = counts.maxByOrNull { it.value }?.key ?: Triple(255, 255, 255)
        return Triple(r / 255f, g / 255f, b / 255f) to Color.rgb(r, g, b)
    }

    // ── Color matching ─────────────────────────────────────────────────────

    /**
     * Returns true if an RGB color (0–1 components) is within [tolerance]
     * Euclidean distance of the background color. Mirrors color_matches_bg()
     * from app.py.
     */
    private fun colorMatchesBg(
        r: Float, g: Float, b: Float,
        bgR: Float, bgG: Float, bgB: Float,
        tolerance: Float
    ): Boolean {
        val dist = sqrt(
            (r - bgR) * (r - bgR) +
            (g - bgG) * (g - bgG) +
            (b - bgB) * (b - bgB)
        )
        return dist <= tolerance * sqrt(3f)  // max distance in 0–1 RGB space ≈ 1.732
    }

    // ── Strategy 2: vector content stream recolouring ─────────────────────

    /**
     * Mirrors _recolor_vector_bg() from app.py.
     *
     * Normalises the page content stream with cleanContents(), then rewrites
     * every fill/stroke colour operator (rg/RG, g/G, k/K) whose colour falls
     * within [tolerance] of the background to white — without rasterising.
     */
    private fun recolorVectorBg(
        pdfDoc: PDFDocument,
        pageNum: Int,
        bgR: Float, bgG: Float, bgB: Float,
        tolerance: Float
    ): Boolean {
        val pdfPage = pdfDoc.loadPage(pageNum) as PDFPage
        try {
            pdfPage.cleanContents(0)
        } finally {
            pdfPage.destroy()
        }

        // After cleanContents, re-fetch the page dict to get the updated Contents entry
        val pageObj = pdfDoc.findPage(pageNum)
        val contentsRaw = pageObj.get("Contents") ?: return false
        if (contentsRaw.isNull) return false

        // Collect xrefs for all content streams (array or single ref)
        val xrefs = mutableListOf<Int>()
        if (contentsRaw.isArray) {
            for (i in 0 until contentsRaw.size()) {
                val xref = contentsRaw.get(i).asIndirect()
                if (xref > 0) xrefs.add(xref)
            }
        } else {
            val xref = contentsRaw.asIndirect()
            if (xref > 0) xrefs.add(xref)
        }
        if (xrefs.isEmpty()) return false

        var anyModified = false

        for (xref in xrefs) {
            val bytes = pdfDoc.getXRefStream(xref) ?: continue
            // Content streams are latin-1 safe (binary data uses escape sequences)
            val stream = String(bytes, Charsets.ISO_8859_1)
            var out = stream

            // RGB fills and strokes:  r g b rg|RG
            out = out.replace(
                Regex("""([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+(rg|RG)\b""")
            ) { m ->
                val r = m.groupValues[1].toFloat()
                val g = m.groupValues[2].toFloat()
                val b = m.groupValues[3].toFloat()
                val op = m.groupValues[4]
                if (colorMatchesBg(r, g, b, bgR, bgG, bgB, tolerance)) "1 1 1 $op"
                else m.value
            }

            // Grayscale fills and strokes:  v g|G
            out = out.replace(
                Regex("""([\d.]+)\s+(g|G)\b""")
            ) { m ->
                val v = m.groupValues[1].toFloat()
                val op = m.groupValues[2]
                if (colorMatchesBg(v, v, v, bgR, bgG, bgB, tolerance)) "1 $op"
                else m.value
            }

            // CMYK fills and strokes:  c m y k k|K
            out = out.replace(
                Regex("""([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+(k|K)\b""")
            ) { m ->
                val c  = m.groupValues[1].toFloat()
                val mg = m.groupValues[2].toFloat()  // magenta
                val y  = m.groupValues[3].toFloat()
                val k  = m.groupValues[4].toFloat()
                val op = m.groupValues[5]
                // CMYK → approximate RGB for distance check
                val r = 1f - minOf(1f, c + k)
                val g = 1f - minOf(1f, mg + k)
                val b = 1f - minOf(1f, y + k)
                if (colorMatchesBg(r, g, b, bgR, bgG, bgB, tolerance)) "0 0 0 0 $op"
                else m.value
            }

            if (out != stream) {
                // compress=true → MuPDF applies FlateDecode and updates /Filter + /Length
                pdfDoc.updateStream(xref, out.toByteArray(Charsets.ISO_8859_1), true)
                anyModified = true
            }
        }

        return anyModified
    }

    // ── Strategy 1: embedded-image recolouring ────────────────────────────

    /**
     * Mirrors _replace_image_bg() from app.py.
     *
     * Iterates the page's /XObject resources, finds Image sub-types, fetches
     * their decoded pixel data, replaces background-coloured pixels with white,
     * and writes the result back into the PDF stream — no rasterisation of the
     * page itself.
     *
     * Handles DeviceRGB (3-channel) and DeviceGray (1-channel) images at 8 bpc.
     * Other colourspaces and bit depths are skipped safely.
     */
    private fun replaceImageBg(
        pdfDoc: PDFDocument,
        pageNum: Int,
        bgR: Float, bgG: Float, bgB: Float,
        tolerance: Float
    ): Boolean {
        val pageObj   = pdfDoc.findPage(pageNum)
        val resRaw    = pageObj.get("Resources")  ?: return false
        if (resRaw.isNull) return false
        val resources = resolve(pdfDoc, resRaw)

        val xobjRaw = resources.get("XObject") ?: return false
        if (xobjRaw.isNull || !xobjRaw.isDictionary) return false
        val xobjects = resolve(pdfDoc, xobjRaw)

        val keys = xobjects.keys() ?: return false
        var anyModified = false

        for (key in keys) {
            try {
                val xobjRef = xobjects.get(key) ?: continue
                val xref    = xobjRef.asIndirect()
                if (xref <= 0) continue

                val imgDict = pdfDoc.getXRefObject(xref)

                // Only process Image XObjects
                val subtype = imgDict.get("Subtype") ?: continue
                if (subtype.isNull || subtype.asName() != "Image") continue

                val width  = imgDict.get("Width")?.asInt()  ?: continue
                val height = imgDict.get("Height")?.asInt() ?: continue
                val bpc    = imgDict.get("BitsPerComponent")?.asInt() ?: 8
                if (bpc != 8) continue   // only 8-bit/channel images

                // Determine channel count from /ColorSpace
                val csEntry = imgDict.get("ColorSpace")
                val csName  = when {
                    csEntry == null || csEntry.isNull -> "DeviceRGB"
                    csEntry.isArray -> csEntry.get(0)?.asName() ?: "DeviceRGB"
                    else -> csEntry.asName() ?: "DeviceRGB"
                }
                val channels = when (csName) {
                    "DeviceGray", "Gray"               -> 1
                    "DeviceCMYK", "CMYK"               -> 4
                    else                               -> 3  // DeviceRGB, CalRGB, …
                }

                // getXRefStream returns the decoded (filter-removed) pixel bytes
                val pixels       = pdfDoc.getXRefStream(xref) ?: continue
                val expectedSize = width * height * channels
                // Safety check: if size mismatch the stream is still compressed
                // (e.g. inline JBIG2 — skip those)
                if (pixels.size < expectedSize) continue

                val maxDist = tolerance * 255f * sqrt(3f)
                val bgR255  = bgR * 255f
                val bgG255  = bgG * 255f
                val bgB255  = bgB * 255f
                var modified = false

                when (channels) {
                    3 -> { // DeviceRGB
                        for (i in 0 until width * height) {
                            val base = i * 3
                            val r = pixels[base    ].toInt() and 0xFF
                            val g = pixels[base + 1].toInt() and 0xFF
                            val b = pixels[base + 2].toInt() and 0xFF
                            val d = sqrt(
                                (r - bgR255) * (r - bgR255) +
                                (g - bgG255) * (g - bgG255) +
                                (b - bgB255) * (b - bgB255)
                            )
                            if (d <= maxDist) {
                                pixels[base    ] = 255.toByte()
                                pixels[base + 1] = 255.toByte()
                                pixels[base + 2] = 255.toByte()
                                modified = true
                            }
                        }
                    }
                    1 -> { // DeviceGray
                        val bgGray = (bgR255 + bgG255 + bgB255) / 3f
                        val grayThresh = maxDist / sqrt(3f)
                        for (i in 0 until width * height) {
                            val v = pixels[i].toInt() and 0xFF
                            if (kotlin.math.abs(v - bgGray) <= grayThresh) {
                                pixels[i] = 255.toByte()
                                modified = true
                            }
                        }
                    }
                    // CMYK: skip — less common in presentations, complex to round-trip
                }

                if (modified) {
                    // compress=true: MuPDF re-encodes as FlateDecode, updates /Filter + /Length
                    pdfDoc.updateStream(xref, pixels, true)
                    anyModified = true
                }

            } catch (_: Exception) {
                // Skip individual images that can't be processed; continue with others
            }
        }

        return anyModified
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Process a single PDF:
     *   1. Detect background colour from first page corners (0.25× render)
     *   2. For every page:
     *      a. Replace bg-coloured pixels in embedded images (Strategy 1)
     *      b. Replace bg-coloured colour operators in vector streams (Strategy 2)
     *   3. Save the modified PDF with FlateDecode compression
     *
     * No page rasterisation is performed; the PDF object graph is edited in
     * place — identical behaviour to the desktop app.py.
     */
    suspend fun processPdf(
        context: Context,
        uri: Uri,
        tolerance: Float,
        fileName: String,
        onProgress: (suspend (page: Int, total: Int) -> Unit)? = null
    ): ProcessedPdf = withContext(Dispatchers.IO) {

        val inputFile = uriToTempFile(context, uri, fileName)

        try {
            val pdfDoc = Document.openDocument(inputFile.absolutePath) as PDFDocument
            val pageCount = pdfDoc.countPages()

            // Detect bg from first page, reuse for all (mirrors Python behaviour)
            val (bgTuple, bgColorInt) = detectBgColor(pdfDoc, 0)
            val (bgR, bgG, bgB) = bgTuple

            // Capture "before" thumbnails
            val originalThumbnails = (0 until pageCount).map { pageNum ->
                val bm = renderPage(pdfDoc, pageNum, 1.5f)
                createThumbnail(bm, 300).also { bm.recycle() }
            }

            // Process each page (in-place PDF object editing)
            for (pageNum in 0 until pageCount) {
                onProgress?.invoke(pageNum + 1, pageCount)

                try { replaceImageBg(pdfDoc, pageNum, bgR, bgG, bgB, tolerance) }
                catch (_: Exception) {}

                try { recolorVectorBg(pdfDoc, pageNum, bgR, bgG, bgB, tolerance) }
                catch (_: Exception) {}
            }

            // Capture "after" thumbnails (MuPDF re-renders from the modified streams)
            val processedThumbnails = (0 until pageCount).map { pageNum ->
                val bm = renderPage(pdfDoc, pageNum, 1.5f)
                createThumbnail(bm, 300).also { bm.recycle() }
            }

            // Save — "compress,garbage=3" mirrors Python's deflate=True, garbage=3
            val outDir = File(context.cacheDir, "processed").also { it.mkdirs() }
            val baseName = fileName.removeSuffix(".pdf").removeSuffix(".PDF")
            val outputFile = File(outDir, "${baseName}_no_bg.pdf")
            pdfDoc.save(outputFile.absolutePath, "compress,garbage=3")
            pdfDoc.destroy()

            ProcessedPdf(
                outputFile          = outputFile,
                originalThumbnails  = originalThumbnails,
                processedThumbnails = processedThumbnails,
                pageCount           = pageCount,
                fileName            = fileName,
                detectedColor       = bgColorInt
            )
        } finally {
            inputFile.delete()
        }
    }

    /**
     * Process multiple PDFs sequentially, reporting per-file/per-page progress.
     */
    suspend fun processMultiplePdfs(
        context: Context,
        uris: List<Uri>,
        fileNames: List<String>,
        tolerance: Float,
        onProgress: (suspend (ProcessingProgress) -> Unit)? = null
    ): List<ProcessedPdf> = withContext(Dispatchers.IO) {
        uris.mapIndexed { fileIndex, uri ->
            val fileName = fileNames.getOrElse(fileIndex) { "file_${fileIndex + 1}.pdf" }
            processPdf(
                context  = context,
                uri      = uri,
                tolerance = tolerance,
                fileName = fileName,
                onProgress = { page, total ->
                    onProgress?.invoke(
                        ProcessingProgress(
                            fileIndex  = fileIndex,
                            totalFiles = uris.size,
                            page       = page,
                            totalPages = total,
                            fileName   = fileName
                        )
                    )
                }
            )
        }
    }
}
