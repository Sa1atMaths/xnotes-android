package com.xnotes.canvas

/** How pages are grouped into rows on the canvas (the View menu's viewing mode). */
enum class ViewingMode(val id: String) {
    /** One page per row (the classic column). */
    SINGLE("single"),

    /** Two pages side by side: 1-2, 3-4, ... */
    DOUBLE("double"),

    /** Page 1 alone (the cover), then pairs: 2-3, 4-5, ... */
    COVER("cover");

    companion object {
        fun fromId(id: String?): ViewingMode = entries.firstOrNull { it.id == id } ?: SINGLE
    }
}

/**
 * Per-note View-menu settings. Scoped to the open file but stored app-side — in
 * [com.xnotes.platform.ViewStateStore] for folder notes and the session sidecar for
 * the open note, exactly like zoom and scroll — never in the .xnote format.
 * The colour filters follow CSS filter semantics and are applied to the PDF page
 * raster only, in the fixed order contrast, invert, brightness, sepia.
 */
data class ViewSettings(
    val mode: ViewingMode = ViewingMode.SINGLE,
    /** True = continuous vertical scroll; false = paginated horizontal page-flip. */
    val verticalScroll: Boolean = true,
    val contrast: Int = 100, // 0..200, 100 = untouched
    /** Null = follow the global "open PDFs in dark mode" preference; set = this note's override. */
    val invert: Int? = null, // 0..100
    val brightness: Int = 100, // 0..200, 100 = untouched
    val sepia: Int = 0, // 0..100
    /** Whole-file page rotation, clockwise degrees: 0, 90, 180 or 270. */
    val rotation: Int = 0,
    /** Whether the canvas-area scrollbar is shown (off by default). */
    val scrollbar: Boolean = false,
) {
    /** The invert percentage to render with, resolving a null override against the global default. */
    fun effectiveInvert(globalPdfDarkMode: Boolean): Int = invert ?: if (globalPdfDarkMode) 100 else 0

    companion object {
        val ROTATIONS = listOf(0, 90, 180, 270)

        /** Snap any degree value onto the supported quarter turns. */
        fun normalizeRotation(deg: Int): Int = (((deg % 360) + 360) % 360) / 90 * 90
    }
}
