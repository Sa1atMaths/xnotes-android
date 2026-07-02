package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.model.Rgba
import com.xnotes.core.text.ListKind
import com.xnotes.core.text.ParaAlign
import com.xnotes.core.tools.Tool
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/**
 * The bottom text-format strip, shown while the inline text tool is armed (and no
 * legacy box edit is open). Sits as the last child of the editor column, so the
 * adjustResize window floats it directly above the soft keyboard. Controls, in
 * order: checkbox item, font colour, highlight, size, bold/italic/underline/
 * strikethrough, ordered and unordered lists, alignment (cycles), indent, outdent.
 */
@Composable
fun TextFormatBar(editor: Editor) {
    if (editor.tool != Tool.TEXT || editor.editingField != null) return
    val palette = LocalPalette.current
    editor.flowSelTick // recompose whenever the caret, selection or pending style moves
    editor.contentVersion
    val style = editor.flowCaretStyle()
    val para = editor.flowCaretParagraph()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(palette.panel.toComposeColor())
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIcon(XnotesIcons.checkboxItem, "Checkbox item", active = para?.list == ListKind.CHECK) {
            editor.flowToggleList(ListKind.CHECK)
        }
        BarDivider()
        FontColorDot(editor, style.color)
        HighlightDot(editor, style.highlight)
        BarDivider()
        SizeStepper(style.sizePt ?: editor.flowDefaultSizePt()) { editor.flowAdjustSize(it) }
        BarDivider()
        BarIcon(XnotesIcons.bold, "Bold", active = style.bold) { editor.flowToggleBold() }
        BarIcon(XnotesIcons.italic, "Italic", active = style.italic) { editor.flowToggleItalic() }
        BarIcon(XnotesIcons.underline, "Underline", active = style.underline) { editor.flowToggleUnderline() }
        BarIcon(XnotesIcons.strikethrough, "Strikethrough", active = style.strike) { editor.flowToggleStrike() }
        BarDivider()
        BarIcon(XnotesIcons.listOrdered, "Ordered list", active = para?.list == ListKind.ORDERED) {
            editor.flowToggleList(ListKind.ORDERED)
        }
        BarIcon(XnotesIcons.listBullet, "Bullet list", active = para?.list == ListKind.BULLET) {
            editor.flowToggleList(ListKind.BULLET)
        }
        BarDivider()
        BarIcon(alignIcon(para?.align ?: ParaAlign.LEFT), "Alignment", active = para != null && para.align != ParaAlign.LEFT) {
            editor.flowCycleAlign()
        }
        BarIcon(XnotesIcons.indentIncrease, "Indent") { editor.flowIndent(1) }
        BarIcon(XnotesIcons.indentDecrease, "Outdent", enabled = (para?.indent ?: 0) > 0) {
            editor.flowIndent(-1)
        }
    }
}

private fun alignIcon(align: ParaAlign): ImageVector = when (align) {
    ParaAlign.LEFT -> XnotesIcons.alignLeft
    ParaAlign.CENTER -> XnotesIcons.alignCenter
    ParaAlign.RIGHT -> XnotesIcons.alignRight
    ParaAlign.JUSTIFY -> XnotesIcons.alignJustify
}

@Composable
private fun BarIcon(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val tint = when {
        !enabled -> com.xnotes.ui.theme.Palette.DISABLED_ICON.toComposeColor()
        active -> palette.accent.toComposeColor()
        else -> palette.textDim.toComposeColor()
    }
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun BarDivider() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .padding(vertical = 12.dp)
            .background(LocalPalette.current.border.toComposeColor()),
    )
}

/** Font colour: a dot filled with the caret's colour, opening the shared picker. */
@Composable
private fun FontColorDot(editor: Editor, current: Rgba?) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val shown = current ?: editor.flowDefaultColor()
    Box {
        Box(
            Modifier
                .padding(horizontal = 6.dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(shown.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), CircleShape)
                .clickable { open = true },
        )
        if (open) {
            ColorPickerPopup(
                initial = shown,
                recents = editor.recentColors,
                onDismiss = { open = false },
                onPick = { editor.flowSetCharColor(it); open = false },
            )
        }
    }
}

/** Highlight: tap clears an active highlight, or opens the picker when there is none. */
@Composable
private fun HighlightDot(editor: Editor, current: Rgba?) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .size(26.dp)
                .clip(CircleShape)
                .then(
                    if (current != null) Modifier.background(current.toComposeColor())
                    else Modifier.background(palette.panel.toComposeColor()),
                )
                .border(1.dp, palette.border.toComposeColor(), CircleShape)
                .clickable { if (current != null) editor.flowSetCharHighlight(null) else open = true },
            contentAlignment = Alignment.Center,
        ) {
            if (current == null) {
                Text("ab", color = palette.textDim.toComposeColor(), fontSize = 10.sp)
            }
        }
        if (open) {
            ColorPickerPopup(
                initial = Rgba(255, 235, 59),
                recents = editor.recentColors,
                onDismiss = { open = false },
                onPick = { editor.flowSetCharHighlight(it); open = false },
            )
        }
    }
}

@Composable
private fun SizeStepper(size: Double, onDelta: (Double) -> Unit) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clickable { onDelta(-1.0) }, contentAlignment = Alignment.Center) {
            Text("−", color = palette.text.toComposeColor(), fontSize = 20.sp)
        }
        Text(
            size.roundToInt().toString(),
            color = palette.text.toComposeColor(),
            fontSize = 14.sp,
            modifier = Modifier.width(24.dp),
            style = TextStyle(fontFamily = FontFamily.Monospace),
        )
        Box(Modifier.size(36.dp).clickable { onDelta(1.0) }, contentAlignment = Alignment.Center) {
            Text("+", color = palette.text.toComposeColor(), fontSize = 20.sp)
        }
    }
}
