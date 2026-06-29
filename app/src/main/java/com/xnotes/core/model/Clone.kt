package com.xnotes.core.model

import com.xnotes.core.pal.TextMeasurer

/**
 * A deep, independent copy of a canvas item. Mutable geometry (stroke samples) is duplicated so the
 * copy and original can be edited apart; image rasters are *shared* because their pixels are
 * immutable. [measurer] is needed to lay out a copied text box. Used for copy/paste/duplicate of
 * both items and whole pages (see [Page.deepCopy]).
 */
fun CanvasItem.deepCopy(measurer: TextMeasurer): CanvasItem = when (this) {
    is Stroke -> Stroke(tool, config, samples.toMutableList(), speedScale, straight)
    is ImageItem -> ImageItem(image, rect, orientation)
    is TextItem -> TextItem(pos, width, height, text, rgba, pointSize, face, measurer)
    is ShapeItem -> ShapeItem(shape, start, end, strokeRgba, strokeWidth, fillRgba, neon, neonStrength, points?.toList())
    else -> this
}

/** A deep copy of a page — its items cloned ([deepCopy]) — keeping the size, PDF link and style. */
fun Page.deepCopy(measurer: TextMeasurer): Page =
    Page(width, height, items.mapTo(mutableListOf()) { it.deepCopy(measurer) }, pdfPage, style)

/**
 * A deep copy of a document: pages cloned, bookmarks copied, the source PDF file and styles shared
 * (immutable for the copy's lifetime). Cheap now that image bytes are shared, so it can snapshot the
 * live document on the main thread before an off-thread save, keeping the writer off the mutating
 * model (no [ConcurrentModificationException]).
 */
fun Document.deepCopy(measurer: TextMeasurer): Document = Document(
    pages = pages.mapTo(mutableListOf()) { it.deepCopy(measurer) },
    dpi = dpi,
    path = path,
    displayName = displayName,
    dirty = dirty,
    pdfFile = pdfFile,
    bookmarks = bookmarks.mapTo(mutableListOf()) { Bookmark(it.page, it.label) },
    style = style,
)
