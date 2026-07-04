package com.xnotes.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.xnotes.canvas.EditingField
import com.xnotes.core.pal.FontFace
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.abs

/** Maps an abstract [FontFace] to the matching Compose generic family (shared with the style bar). */
internal fun FontFace.toComposeFamily(): FontFamily = when (this) {
    FontFace.SANS -> FontFamily.SansSerif
    FontFace.SERIF -> FontFamily.Serif
    FontFace.MONO -> FontFamily.Monospace
    FontFace.HAND -> FontFamily.Cursive
}

/**
 * The in-place text-box editor (PAL §13): a native field overlaid on the canvas at the box's
 * on-screen position and size, with a zoom-scaled font matching the baked text. It does **not**
 * commit itself — the canvas is the single authority for ending an edit (tap outside / tool switch
 * / Back). It only mirrors keystrokes back to the model and grows with content.
 *
 * The field never scrolls its own text. It is measured with unbounded height so it sizes to its
 * content, and a one-finger drag on the box is rerouted to a document pan ([Editor.panWhileEditing])
 * with the edit kept live and the box following the page. The page never scrolls on its own — a
 * box must stay exactly where it is for the whole edit; reaching an off-screen caret is the
 * user's pan. The pan delta is taken in root coordinates (the field itself moves with the page,
 * so its own local frame would self-cancel). Touches off the box reach the canvas as before
 * (tap to commit, drag to scroll).
 */
@Composable
fun TextEditorOverlay(editor: Editor, field: EditingField) {
    val density = LocalDensity.current
    val palette = LocalPalette.current
    var value by remember { mutableStateOf(TextFieldValue(field.text, TextRange(field.text.length))) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val xDp = with(density) { field.x.toFloat().toDp() }
    val yDp = with(density) { field.y.toFloat().toDp() }
    val widthDp = with(density) { field.width.toFloat().toDp() }
    val heightDp = with(density) { field.heightPx.toFloat().toDp() }
    val fontSp = with(density) { field.fontPx.toFloat().toSp() }

    BasicTextField(
        value = value,
        onValueChange = {
            value = it
            editor.updateEditingText(it.text)
        },
        modifier = Modifier
            .offset(xDp, yDp)
            .widthIn(min = widthDp, max = widthDp)
            .heightIn(min = heightDp)
            // Unbounded height: the field sizes to its content and never scrolls its own text. The
            // box grows; the page scrolls to reach the rest; the host Box clips the overflow.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            // No fill: the edited box is lifted out of the ink cache, so a transparent field lets the
            // page/PDF underneath show through while typing (true WYSIWYG). The border marks the bounds.
            .border(1.dp, palette.accent.toComposeColor())
            .focusRequester(focusRequester)
            .onGloballyPositioned { coords = it }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                val longPress = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (down.type != PointerType.Touch) return@awaitEachGesture
                    val lc = coords ?: return@awaitEachGesture
                    var lastRoot = lc.localToRoot(down.position)
                    var dragging = false
                    var yielded = false
                    var totalX = 0f
                    var totalY = 0f
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { if (dragging) change.consume(); break }
                        if (yielded) continue
                        // Resolve to root coordinates each event so the field's own movement (it follows
                        // the page as we scroll) does not cancel out the finger delta.
                        val cur = (coords ?: lc).localToRoot(change.position)
                        val dx = cur.x - lastRoot.x
                        val dy = cur.y - lastRoot.y
                        lastRoot = cur
                        totalX += dx
                        totalY += dy
                        if (!dragging) {
                            if (abs(totalX) <= slop && abs(totalY) <= slop) continue
                            // A drag that only starts after a long hold is a text selection, not a
                            // scroll: yield so the field's own selection handling runs.
                            if (change.uptimeMillis - down.uptimeMillis >= longPress) { yielded = true; continue }
                            dragging = true
                        }
                        editor.panWhileEditing(dx, dy)
                        change.consume()
                    }
                }
            },
        textStyle = TextStyle(
            color = field.rgba.toComposeColor(),
            fontFamily = field.face.toComposeFamily(),
            fontSize = fontSp,
        ),
        cursorBrush = SolidColor(palette.accent.toComposeColor()),
    )
}
