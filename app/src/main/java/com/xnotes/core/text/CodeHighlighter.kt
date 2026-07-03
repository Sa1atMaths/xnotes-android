package com.xnotes.core.text

import com.xnotes.core.model.Rgba

/** One capture over a code block's text, char offsets, capture name from the .scm query. */
class HighlightSpan(val start: Int, val end: Int, val capture: String)

/** A resolved colour span the layout paints code segments with (derived, never persisted). */
class CodeSpan(val start: Int, val end: Int, val color: Rgba)

/**
 * The platform seam for syntax highlighting: the core asks for capture spans and
 * stays JVM-pure; the Android layer answers via the vendored tree-sitter.
 */
interface CodeHighlighter {
    fun supports(language: String): Boolean

    /** Capture spans over [text] for [language], or null when it can't highlight. */
    fun highlight(text: String, language: String): List<HighlightSpan>?
}

/**
 * Maps .scm capture names to colours by longest dotted prefix ("keyword.return"
 * falls back to "keyword"). Two presets keyed to the app's dark/light paper;
 * user-imported themes may also carry the code-block [background].
 */
class HighlightTheme(private val colors: Map<String, Rgba>, val background: Rgba? = null) {

    fun colorFor(capture: String): Rgba? {
        var name = capture
        while (true) {
            colors[name]?.let { return it }
            val dot = name.lastIndexOf('.')
            if (dot < 0) return null
            name = name.substring(0, dot)
        }
    }

    companion object {
        val DARK = HighlightTheme(
            mapOf(
                "keyword" to Rgba(198, 120, 221, 255),
                "function" to Rgba(97, 175, 239, 255),
                "type" to Rgba(229, 192, 123, 255),
                "string" to Rgba(152, 195, 121, 255),
                "escape" to Rgba(86, 182, 194, 255),
                "number" to Rgba(209, 154, 102, 255),
                "constant" to Rgba(209, 154, 102, 255),
                "comment" to Rgba(127, 132, 142, 255),
                "operator" to Rgba(86, 182, 194, 255),
                "property" to Rgba(224, 108, 117, 255),
                "variable" to Rgba(224, 108, 117, 255),
                "punctuation" to Rgba(140, 146, 155, 255),
            ),
        )

        val LIGHT = HighlightTheme(
            mapOf(
                "keyword" to Rgba(166, 38, 164, 255),
                "function" to Rgba(64, 120, 242, 255),
                "type" to Rgba(193, 132, 1, 255),
                "string" to Rgba(80, 161, 79, 255),
                "escape" to Rgba(1, 132, 188, 255),
                "number" to Rgba(152, 104, 1, 255),
                "constant" to Rgba(152, 104, 1, 255),
                "comment" to Rgba(160, 161, 167, 255),
                "operator" to Rgba(1, 132, 188, 255),
                "property" to Rgba(228, 86, 73, 255),
                "variable" to Rgba(228, 86, 73, 255),
                "punctuation" to Rgba(105, 112, 122, 255),
            ),
        )
    }
}
