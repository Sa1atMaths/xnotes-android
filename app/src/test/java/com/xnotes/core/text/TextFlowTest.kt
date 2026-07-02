package com.xnotes.core.text

import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFlowTest {

    private fun flowOf(vararg lines: String): TextFlow = TextFlow().apply {
        lines.forEach { paragraphs.add(Paragraph(mutableListOf(Run(it)))) }
    }

    @Test
    fun plainTextJoinsParagraphsWithNewlines() {
        assertEquals("one\ntwo\n", flowOf("one", "two", "").plainText())
        assertEquals("", TextFlow().plainText())
    }

    @Test
    fun globalOffsetRoundTripsThroughEveryPosition() {
        val flow = flowOf("ab", "", "cde")
        val text = flow.plainText()
        for (g in 0..text.length) {
            val pos = flow.posAtGlobal(g)
            assertEquals("global $g", g, flow.globalOffset(pos))
        }
        assertEquals(FlowPos(0, 0), flow.posAtGlobal(0))
        assertEquals(FlowPos(1, 0), flow.posAtGlobal(3))
        assertEquals(FlowPos(2, 3), flow.posAtGlobal(999))
        assertEquals(FlowPos(2, 3), flow.endPos())
    }

    @Test
    fun emptinessIgnoresASingleDefaultBlankParagraph() {
        assertTrue(TextFlow().isEmpty)
        assertTrue(flowOf("").isEmpty)
        assertFalse(flowOf("x").isEmpty)
        assertFalse(flowOf("", "").isEmpty)
        val styledBlank = TextFlow().apply {
            paragraphs.add(Paragraph(list = ListKind.BULLET))
        }
        assertFalse(styledBlank.isEmpty)
    }

    @Test
    fun deepCopyIsIndependent() {
        val flow = flowOf("hello")
        flow.paragraphs[0].runs[0].style = CharStyle(bold = true, color = Rgba(1, 2, 3, 255))
        flow.paragraphs[0].align = ParaAlign.CENTER
        val copy = flow.deepCopy()
        assertNotSame(flow.paragraphs[0], copy.paragraphs[0])
        assertNotSame(flow.paragraphs[0].runs[0], copy.paragraphs[0].runs[0])
        copy.paragraphs[0].runs[0].text = "changed"
        copy.paragraphs.add(Paragraph())
        assertEquals("hello", flow.paragraphs[0].plainText())
        assertEquals(1, flow.paragraphs.size)
        assertEquals(ParaAlign.CENTER, copy.paragraphs[0].align)
        assertEquals(CharStyle(bold = true, color = Rgba(1, 2, 3, 255)), copy.paragraphs[0].runs[0].style)
    }

    @Test
    fun rangeNormalizationOrdersEndpoints() {
        val a = FlowPos(0, 2)
        val b = FlowPos(1, 0)
        assertEquals(FlowRange(a, b), FlowRange(b, a).normalized())
        assertTrue(FlowRange.caret(a).collapsed)
        assertTrue(a < b)
    }
}
