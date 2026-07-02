package com.xnotes.core.text

import com.xnotes.core.model.Rgba

/**
 * A deliberately small, line-based markdown reader for paste (no AST, no tables,
 * no indented code blocks). Headings map to size multipliers over the flow's
 * default size, fenced blocks become code paragraphs (one per line, language
 * from the fence info), lists/tasks/blockquotes map to paragraph properties,
 * and the inline pass handles ** __ * _ ~~ `code` plus links (styled text, URL
 * dropped) and images (alt text). Unclosed markers fall out as literal text.
 */
object MarkdownParser {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val FENCE = Regex("^\\s{0,3}```\\s*([A-Za-z0-9+#-]*)\\s*$")
    private val TASK = Regex("^(\\s*)[-*+]\\s+\\[([ xX])\\]\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)\\d{1,9}[.)]\\s+(.*)$")
    private val BLOCKQUOTE = Regex("^(>+)\\s?(.*)$")
    private val LINK = Regex("^\\[([^\\]]*)\\]\\(([^)]*)\\)")
    private val IMAGE = Regex("^!\\[([^\\]]*)\\]\\(([^)]*)\\)")

    /** Inert link styling: the text keeps a link look, the URL is dropped. */
    val LINK_COLOR = Rgba(100, 160, 255, 255)

    private val HEADING_SCALE = doubleArrayOf(2.0, 1.5, 1.25, 1.1, 1.0, 1.0)

    /**
     * The cheap paste heuristic: one structural line (heading, fence, list, task,
     * ordered item, blockquote) or two inline emphasis pairs within the first 200
     * lines reads as markdown; plain prose does not.
     */
    fun looksLikeMarkdown(text: String): Boolean {
        var inlinePairs = 0
        for ((i, line) in text.lineSequence().withIndex()) {
            if (i >= 200) break
            if (HEADING.matches(line) || FENCE.matches(line) || TASK.matches(line) ||
                BULLET.matches(line) || ORDERED.matches(line) || BLOCKQUOTE.matches(line)
            ) {
                return true
            }
            inlinePairs += pairCount(line, "**") + pairCount(line, "~~") + pairCount(line, "`")
            if (inlinePairs >= 2) return true
        }
        return false
    }

    private fun pairCount(line: String, marker: String): Int {
        var count = 0
        var i = line.indexOf(marker)
        while (i >= 0) {
            count++
            i = line.indexOf(marker, i + marker.length)
        }
        return count / 2
    }

    /** Parse [text] into flow paragraphs; [baseSizePt] anchors the heading sizes. */
    fun parse(text: String, baseSizePt: Double): List<Paragraph> {
        val out = mutableListOf<Paragraph>()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                val lang = fence.groupValues[1].lowercase()
                i++
                while (i < lines.size && !FENCE.matches(lines[i])) {
                    out.add(codeLine(lines[i], lang))
                    i++
                }
                if (i < lines.size) i++ // swallow the closing fence
                continue
            }
            out.add(parseLine(line, baseSizePt))
            i++
        }
        return out
    }

    private fun codeLine(line: String, lang: String): Paragraph = Paragraph(
        if (line.isEmpty()) mutableListOf() else mutableListOf(Run(line)),
        codeLang = lang,
    )

    private fun parseLine(line: String, baseSizePt: Double): Paragraph {
        HEADING.matchEntire(line)?.let { m ->
            val level = m.groupValues[1].length
            val style = CharStyle(bold = true, sizePt = baseSizePt * HEADING_SCALE[level - 1])
            return Paragraph(inline(m.groupValues[2], style))
        }
        TASK.matchEntire(line)?.let { m ->
            return Paragraph(
                inline(m.groupValues[3], CharStyle.DEFAULT),
                indent = indentOf(m.groupValues[1]),
                list = ListKind.CHECK,
                checked = m.groupValues[2].isNotBlank(),
            )
        }
        BULLET.matchEntire(line)?.let { m ->
            return Paragraph(
                inline(m.groupValues[2], CharStyle.DEFAULT),
                indent = indentOf(m.groupValues[1]),
                list = ListKind.BULLET,
            )
        }
        ORDERED.matchEntire(line)?.let { m ->
            return Paragraph(
                inline(m.groupValues[2], CharStyle.DEFAULT),
                indent = indentOf(m.groupValues[1]),
                list = ListKind.ORDERED,
            )
        }
        BLOCKQUOTE.matchEntire(line)?.let { m ->
            return Paragraph(
                inline(m.groupValues[2], CharStyle.DEFAULT),
                indent = m.groupValues[1].length.coerceAtMost(Paragraph.MAX_INDENT),
            )
        }
        return Paragraph(inline(line, CharStyle.DEFAULT))
    }

    private fun indentOf(leading: String): Int =
        (leading.replace("\t", "  ").length / 2).coerceAtMost(Paragraph.MAX_INDENT)

    // --- inline emphasis ---

    private fun inline(text: String, base: CharStyle): MutableList<Run> {
        val out = mutableListOf<Run>()
        inlineInto(text, base, out)
        return out
    }

    private fun inlineInto(text: String, base: CharStyle, out: MutableList<Run>) {
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) {
                appendRun(out, literal.toString(), base)
                literal.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close > i + 1) {
                        flush()
                        appendRun(out, text.substring(i + 1, close), base.copy(code = true))
                        i = close + 1
                    } else {
                        literal.append(c)
                        i++
                    }
                }
                text.startsWith("**", i) -> i = emphasis(text, i, "**", base.copy(bold = true), base, out, literal) { flush() }
                text.startsWith("__", i) -> i = emphasis(text, i, "__", base.copy(bold = true), base, out, literal) { flush() }
                text.startsWith("~~", i) -> i = emphasis(text, i, "~~", base.copy(strike = true), base, out, literal) { flush() }
                c == '*' -> i = emphasis(text, i, "*", base.copy(italic = true), base, out, literal) { flush() }
                // Intraword underscores (snake_case) are not emphasis.
                c == '_' && (i == 0 || !text[i - 1].isLetterOrDigit()) ->
                    i = emphasis(text, i, "_", base.copy(italic = true), base, out, literal) { flush() }
                c == '!' && IMAGE.containsMatchIn(text.substring(i)) -> {
                    val m = IMAGE.find(text.substring(i))!!
                    flush()
                    inlineInto(m.groupValues[1], base, out)
                    i += m.value.length
                }
                c == '[' && LINK.containsMatchIn(text.substring(i)) -> {
                    val m = LINK.find(text.substring(i))!!
                    flush()
                    inlineInto(m.groupValues[1], base.copy(underline = true, color = LINK_COLOR), out)
                    i += m.value.length
                }
                else -> {
                    literal.append(c)
                    i++
                }
            }
        }
        flush()
    }

    /** Consume a [marker]-delimited span (recursing with [styled]); unmatched emits literally. */
    private inline fun emphasis(
        text: String,
        at: Int,
        marker: String,
        styled: CharStyle,
        base: CharStyle,
        out: MutableList<Run>,
        literal: StringBuilder,
        flush: () -> Unit,
    ): Int {
        val close = text.indexOf(marker, at + marker.length)
        if (close <= at + marker.length - 1 || close == at + marker.length) {
            literal.append(marker)
            return at + marker.length
        }
        flush()
        inlineInto(text.substring(at + marker.length, close), styled, out)
        return close + marker.length
    }

    private fun appendRun(out: MutableList<Run>, text: String, style: CharStyle) {
        if (text.isEmpty()) return
        val last = out.lastOrNull()
        if (last != null && last.style == style) last.text += text else out.add(Run(text, style))
    }
}
