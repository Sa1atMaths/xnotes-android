package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xnotes.canvas.EditingField
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * The in-place text-box editor (PAL §13): a native field overlaid on the canvas
 * at the box's on-screen position and size, with a zoom-scaled monospace font so
 * it matches the baked text. Commits on focus loss; auto-grows with content.
 */
@Composable
fun TextEditorOverlay(editor: Editor, field: EditingField) {
    val density = LocalDensity.current
    val palette = LocalPalette.current
    var text by remember { mutableStateOf(field.text) }
    val focusRequester = remember { FocusRequester() }
    var gainedFocus by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val xDp = with(density) { field.x.toFloat().toDp() }
    val yDp = with(density) { field.y.toFloat().toDp() }
    val widthDp = with(density) { field.width.toFloat().toDp() }
    val fontSp = with(density) { field.fontPx.toFloat().toSp() }

    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            editor.updateEditingText(it)
        },
        modifier = Modifier
            .offset(xDp, yDp)
            .widthIn(min = widthDp, max = widthDp)
            .border(1.dp, palette.accent.toComposeColor())
            .background(palette.menuBg.toComposeColor().copy(alpha = 0.85f))
            .focusRequester(focusRequester)
            .onFocusChanged {
                if (it.isFocused) {
                    gainedFocus = true
                } else if (gainedFocus) {
                    editor.commitText(text)
                }
            },
        textStyle = TextStyle(
            color = field.rgba.toComposeColor(),
            fontFamily = FontFamily.Monospace,
            fontSize = fontSp,
        ),
        cursorBrush = SolidColor(palette.accent.toComposeColor()),
    )
}
