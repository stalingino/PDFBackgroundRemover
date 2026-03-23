package com.pdfbgremover.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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

    /**
     * Detect the background color of a bitmap by sampling corner regions.
     * Mirrors the Python detect_bg_color() logic: sample 8 corner/edge points
     * and return the most common color.
     */
    fun detectBgColor(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        val margin = (minOf(w, h) * 0.05f).toInt().coerceAtLeast(1)

        // Sample positions: 4 corners + 4 edge midpoints
        val samplePoints = listOf(
            Pair(margin, margin),
            Pair(w - margin, margin),
            Pair(margin, h - margin),
            Pair(w - margin, h - margin),
            Pair(w / 2, margin),
            Pair(w / 2, h - margin),
            Pair(margin, h / 2),
            Pair(w - margin, h / 2)
        )

        val colorCounts = mutableMapOf<Int, Int>()
        for ((x, y) in samplePoints) {
            val px = x.coerceIn(0, w - 1)
            val py = y.coerceIn(0, h - 1)
            val color = bitmap.getPixel(px, py)
            colorCounts[color] = (colorCounts[color] ?: 0) + 1
        }

        return colorCounts.maxByOrNull { it.value }?.key ?: Color.WHITE
    }

    /**
     * Remove the background color from a bitmap using Euclidean distance in RGB space.
     * Pixels within [tolerance * 255] distance from [bgColor] are replaced with white.
     *
     * @param tolerance 0.0 (exact match only) to 1.0 (replace everything)
     */
    fun removeBackground(source: Bitmap, bgColor: Int, tolerance: Float): Bitmap {
        val width = source.width
        val height = source.height

        val result = source.copy(Bitmap.Config.ARGB_8888, true)

        val bgR = Color.red(bgColor).toFloat()
        val bgG = Color.green(bgColor).toFloat()
        val bgB = Color.blue(bgColor).toFloat()

        val threshold = tolerance * 255f * sqrt(3f)  // max possible distance in RGB space

        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = Color.red(pixel).toFloat()
            val g = Color.green(pixel).toFloat()
            val b = Color.blue(pixel).toFloat()

            val distance = sqrt(
                (r - bgR) * (r - bgR) +
                (g - bgG) * (g - bgG) +
                (b - bgB) * (b - bgB)
            )

            if (distance <= threshold) {
                pixels[i] = Color.WHITE
            }
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Render a single PDF page to a bitmap at the given scale.
     */
    private fun renderPage(page: PdfRenderer.Page, scale: Float = 1.5f): Bitmap {
        val width = (page.width * scale).toInt()
        val height = (page.height * scale).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Fill with white first (PdfRenderer renders transparent backgrounds as black)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    /**
     * Process a single PDF: detect background, remove it from every page,
     * save the result as a new PDF in the app's cache directory.
     */
    suspend fun processPdf(
        context: Context,
        uri: Uri,
        tolerance: Float,
        fileName: String,
        onProgress: (suspend (page: Int, total: Int) -> Unit)? = null
    ): ProcessedPdf = withContext(Dispatchers.IO) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Cannot open PDF: $uri")

        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val pageCount = renderer.pageCount
                if (pageCount == 0) error("PDF has no pages")

                val originalThumbnails = mutableListOf<Bitmap>()
                val processedThumbnails = mutableListOf<Bitmap>()

                // Detect background from first page
                val firstPage = renderer.openPage(0)
                val firstBitmap = renderPage(firstPage, scale = 1.0f)
                firstPage.close()
                val bgColor = detectBgColor(firstBitmap)
                firstBitmap.recycle()

                // Build output PDF
                val outputPdf = PdfDocument()

                for (pageIndex in 0 until pageCount) {
                    onProgress?.invoke(pageIndex + 1, pageCount)

                    val page = renderer.openPage(pageIndex)
                    val originalBitmap = renderPage(page, scale = 1.5f)
                    page.close()

                    // Thumbnail for "before" preview (smaller)
                    originalThumbnails.add(createThumbnail(originalBitmap, 300))

                    // Process background removal
                    val processedBitmap = removeBackground(originalBitmap, bgColor, tolerance)
                    originalBitmap.recycle()

                    // Thumbnail for "after" preview
                    processedThumbnails.add(createThumbnail(processedBitmap, 300))

                    // Write full-res processed page to output PDF
                    // Use original page dimensions (in points, 72 DPI)
                    val pdfPage = outputPdf.startPage(
                        PdfDocument.PageInfo.Builder(
                            processedBitmap.width,
                            processedBitmap.height,
                            pageIndex + 1
                        ).create()
                    )
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    pdfPage.canvas.drawBitmap(processedBitmap, 0f, 0f, paint)
                    outputPdf.finishPage(pdfPage)

                    processedBitmap.recycle()
                }

                // Save to cache
                val outDir = File(context.cacheDir, "processed").also { it.mkdirs() }
                val baseName = fileName.removeSuffix(".pdf").removeSuffix(".PDF")
                val outputFile = File(outDir, "${baseName}_no_bg.pdf")
                FileOutputStream(outputFile).use { fos ->
                    outputPdf.writeTo(fos)
                }
                outputPdf.close()

                ProcessedPdf(
                    outputFile = outputFile,
                    originalThumbnails = originalThumbnails,
                    processedThumbnails = processedThumbnails,
                    pageCount = pageCount,
                    fileName = fileName,
                    detectedColor = bgColor
                )
            }
        }
    }

    private fun createThumbnail(source: Bitmap, maxDimension: Int): Bitmap {
        val aspect = source.width.toFloat() / source.height.toFloat()
        val (w, h) = if (source.width > source.height) {
            Pair(maxDimension, (maxDimension / aspect).toInt())
        } else {
            Pair((maxDimension * aspect).toInt(), maxDimension)
        }
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    /**
     * Process multiple PDFs and report progress.
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
                context = context,
                uri = uri,
                tolerance = tolerance,
                fileName = fileName,
                onProgress = { page, total ->
                    onProgress?.invoke(
                        ProcessingProgress(
                            fileIndex = fileIndex,
                            totalFiles = uris.size,
                            page = page,
                            totalPages = total,
                            fileName = fileName
                        )
                    )
                }
            )
        }
    }
}
