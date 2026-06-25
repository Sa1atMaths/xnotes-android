package com.xnotes.platform

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.File

/**
 * One entry of a PDF's built-in outline (its "table of contents"). [destPage] is the 0-based source
 * PDF page index the entry points at (matching [com.xnotes.core.model.Page.pdfPage]), or -1 when the
 * entry has no resolvable page. [level] is the nesting depth (0 = top level).
 */
data class PdfOutlineEntry(val title: String, val destPage: Int, val level: Int)

/**
 * Extracts a PDF's document outline with PdfBox. The framework [android.graphics.pdf.PdfRenderer]
 * exposes only per-page goto links (API 35+), never the document-level outline tree, so PdfBox is the
 * only source of a real TOC. Reads the catalog's /Outlines (cheap: no page content streams are
 * decoded) in a short-lived, scratch-capped [PDDocument] that is closed immediately, so it never
 * holds heap the way the live image sweep in [PdfSource] does. Forgiving: any failure (no outline,
 * broken destinations) yields an empty list.
 */
object PdfOutline {
    private const val SCRATCH_MEM_BYTES = 16L * 1024 * 1024
    private const val MAX_ENTRIES = 2000

    fun extract(context: Context, file: File): List<PdfOutlineEntry> = runCatching {
        PDFBoxResourceLoader.init(context.applicationContext)
        val mem = MemoryUsageSetting.setupMixed(SCRATCH_MEM_BYTES).setTempDir(context.cacheDir)
        PDDocument.load(file, mem).use { doc ->
            val root = doc.documentCatalog?.documentOutline ?: return emptyList()
            val pages = doc.pages
            val out = ArrayList<PdfOutlineEntry>()
            fun walk(node: PDOutlineNode, level: Int) {
                for (item in node.children()) {
                    if (out.size >= MAX_ENTRIES) return
                    val title = item.title?.trim().orEmpty()
                    val dest = runCatching { item.findDestinationPage(doc) }.getOrNull()
                    val idx = if (dest != null) runCatching { pages.indexOf(dest) }.getOrDefault(-1) else -1
                    if (title.isNotEmpty()) out.add(PdfOutlineEntry(title, idx, level))
                    if (item.hasChildren()) walk(item, level + 1)
                }
            }
            walk(root, 0)
            out
        }
    }.getOrDefault(emptyList())
}
