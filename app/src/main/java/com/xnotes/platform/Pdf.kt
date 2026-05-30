package com.xnotes.platform

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import java.io.OutputStream
import kotlin.math.roundToInt

/** Turns a loaded [PdfSource] into a paged note to annotate (spec 08 §5). */
object PdfImporter {
    fun import(bytes: ByteArray, source: PdfSource, dpi: Int = PageSize.DEFAULT_DPI): Document {
        val doc = Document(dpi = dpi)
        doc.pdfBytes = bytes
        for (i in 0 until source.pageCount) {
            val (wPts, hPts) = source.pageSizePoints(i)
            val w = wPts / 72.0 * dpi
            val h = hPts / 72.0 * dpi
            doc.pages.add(Page(w, h, pdfPage = i))
        }
        if (doc.pages.isEmpty()) doc.pages.add(Page.blank(PageSize.A4, com.xnotes.core.model.Orientation.PORTRAIT, dpi))
        return doc
    }
}

/** Flattens a document — PDF backgrounds and every item — into a new PDF (spec 08 §6). */
object PdfExporter {
    /**
     * [paperColor] gives each page's background fill (the on-screen paper colour,
     * e.g. dark-theme `#161616`); without it the page would keep the PDF canvas's
     * default white, exporting dark-mode notes on a white background.
     *
     * Rendering a large imported PDF is slow, so the caller drives it off the main
     * thread: [onProgress] reports `(pagesDone, totalPages)` — emitted once as
     * `(0, total)` before any work and again after each finished page — so a dialog
     * can show real 0→100% progress, and [isCancelled] is polled per page so a
     * dismissed dialog aborts promptly. On cancel we return before [out] is written,
     * so the caller's half-built temp file is simply discarded.
     */
    fun export(
        doc: Document,
        source: PdfSource?,
        out: OutputStream,
        paperColor: (Page) -> Rgba,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        val pdf = PdfDocument()
        val scale = (72.0 / doc.dpi).toFloat()
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        val total = doc.pages.size
        try {
            onProgress(0, total)
            doc.pages.forEachIndexed { index, page ->
                if (isCancelled()) return
                val wPts = (page.width / doc.dpi * 72).roundToInt().coerceAtLeast(1)
                val hPts = (page.height / doc.dpi * 72).roundToInt().coerceAtLeast(1)
                val info = PdfDocument.PageInfo.Builder(wPts, hPts, index + 1).create()
                val pdfPage = pdf.startPage(info)
                val canvas = pdfPage.canvas
                // Paint the paper colour over the whole page first (drawColor ignores the
                // matrix); an embedded PDF page then covers it, but a plain note keeps it
                // instead of falling through to the canvas's default white.
                canvas.drawColor(paperColor(page).toArgb())
                // Draw in page-pixel coordinates; the canvas maps them to points.
                canvas.scale(scale, scale)

                val src = page.pdfPage
                if (src != null && source != null) {
                    val bg = source.renderPage(src, page.width.toInt(), page.height.toInt(), invert = false)
                    if (bg != null) {
                        canvas.drawBitmap(bg.bitmap, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), bitmapPaint)
                        bg.recycle()
                    }
                }
                val renderer = AndroidRenderer(canvas)
                for (item in page.items) item.paint(renderer)
                pdf.finishPage(pdfPage)
                onProgress(index + 1, total)
            }
            if (isCancelled()) return
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }
}
