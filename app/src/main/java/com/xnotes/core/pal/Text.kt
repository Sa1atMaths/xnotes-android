package com.xnotes.core.pal

/**
 * The selectable text typefaces. The core picks one of these abstract faces; the
 * host resolves each to a concrete platform font. [MONO] is the historical default
 * (and what files without a face stored fall back to), so older notes are unchanged.
 */
enum class FontFace(val id: String) {
    SANS("sans"),
    SERIF("serif"),
    MONO("mono"),
    HAND("hand");

    companion object {
        fun fromId(id: String?): FontFace = entries.firstOrNull { it.id == id } ?: MONO
    }
}

/**
 * A font request: a point size, an abstract [face] (resolved to a concrete family
 * by the host — spec 01 §12), and a weight. Defaults to monospace so existing
 * call sites and stored notes render exactly as before.
 */
data class FontSpec(val pointSize: Double, val face: FontFace = FontFace.MONO, val bold: Boolean = false)

/** Text layout flags (spec 01 §1 `draw_text`). Text boxes use the defaults. */
data class TextFlags(
    val wordWrap: Boolean = true,
    val alignLeft: Boolean = true,
    val alignTop: Boolean = true,
)
