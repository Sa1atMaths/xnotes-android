package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.settings.Preferences
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.ColorMath
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.delay

private val accentPresets = listOf(
    Rgba(0, 230, 118), Rgba(255, 138, 30), Rgba(255, 77, 77), Rgba(255, 210, 30),
)
internal val pageColorPresets = listOf(
    Rgba(22, 22, 22), Rgba(13, 13, 13), Rgba(255, 255, 255), Rgba(247, 243, 233), Rgba(232, 232, 232),
)
private val penButtonOptions = listOf("eraser" to "Eraser", "pan" to "Pan", "select" to "Select", "none" to "None")

/**
 * Preferences as a backstage pane (spec 10 §8). Edits apply live — each change is
 * pushed straight to the [Editor] (and persisted), so theme/page tweaks are seen
 * immediately, including in the surrounding backstage.
 */
@Composable
fun PreferencesPane(editor: Editor, sidebarOpen: Boolean, onShowSidebar: () -> Unit) {
    val palette = LocalPalette.current
    var prefs by remember { mutableStateOf(editor.preferences) }
    fun update(p: Preferences) {
        prefs = p
        editor.applyPreferences(p)
    }

    val scrollState = rememberScrollState()
    val layout = editor.toolbarLayout
    // Toolbar drag state, hoisted here so the floating "ghost" chip can be drawn at the pane
    // root (above and outside the scroll, so it is never clipped). Bounds are plain maps read
    // only at drag time; observable state is just the dragged item, finger, and drop slot.
    var dragItem by remember { mutableStateOf<ToolbarItem?>(null) }
    var dragFinger by remember { mutableStateOf(Offset.Zero) }
    var dragGrab by remember { mutableStateOf(Offset.Zero) }
    var dropTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val chipBounds = remember { mutableMapOf<ToolbarItem, Rect>() }
    val sectionBounds = remember { mutableMapOf<Int, Rect>() }
    val sectionGrabBounds = remember { mutableMapOf<Int, Rect>() }
    // Section-drag state (the whole card, by its grip handle); shares the finger/grab with the
    // chip drag since only one gesture runs at a time. The drop slot is an insertion index.
    var dragSection by remember { mutableStateOf<Int?>(null) }
    var sectionDropTarget by remember { mutableStateOf<Int?>(null) }
    var paneTopLeft by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    fun retarget() {
        dropTarget = toolbarDropTarget(editor.toolbarLayout, dragFinger, chipBounds, sectionBounds)
    }
    fun retargetSection() {
        sectionDropTarget = toolbarSectionDropTarget(editor.toolbarLayout, dragFinger, sectionBounds)
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { paneTopLeft = it.boundsInRoot().topLeft }) {
    Column(Modifier.fillMaxSize()) {
        // Hamburger sits inline with the title (shown only when the sidebar is hidden); the row keeps
        // a constant height either way so toggling the sidebar never shifts the settings below.
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!sidebarOpen) {
                IconButton(onClick = onShowSidebar) {
                    Icon(XnotesIcons.menu, "Show sidebar", tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(4.dp))
            }
            Text("Preferences", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { update(Preferences()) }) { Text("Reset to defaults", fontSize = 13.sp) }
        }
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState)
                .onGloballyPositioned { viewport = it.boundsInRoot() },
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle("General")
            FieldLabel("UI theme")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Dark", prefs.uiAppearance == "dark") { update(prefs.copy(uiAppearance = "dark")) }
                Chip("Light", prefs.uiAppearance == "light") { update(prefs.copy(uiAppearance = "light")) }
                Chip("OLED", prefs.uiAppearance == "oled") { update(prefs.copy(uiAppearance = "oled")) }
            }
            FieldLabel("Accent colour")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                accentPresets.forEach { c ->
                    ColorDot(c.toComposeColor(), prefs.accentColor == c) { update(prefs.copy(accentColor = c)) }
                }
                ColorPickerDot(
                    prefs.accentColor,
                    custom = prefs.accentColor !in accentPresets,
                    onPick = { update(prefs.copy(accentColor = it)) },
                ) { onDismiss, onPick -> AccentColorGridPopup(onDismiss, onPick) }
            }
            Column {
                CheckRow("Open PDFs in dark mode (invert pages)", prefs.pdfDarkMode) { update(prefs.copy(pdfDarkMode = it)) }
                if (prefs.pdfDarkMode) {
                    CheckRow("Don't invert images", prefs.pdfKeepImageColors) { update(prefs.copy(pdfKeepImageColors = it)) }
                }
            }

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle("Input")
            CheckRow("Draw with finger (off = finger pans)", prefs.fingerDraws) { update(prefs.copy(fingerDraws = it)) }
            CheckRow("Snap held strokes to shapes (hold the pen still)", prefs.detectShapes) { update(prefs.copy(detectShapes = it)) }
            FieldLabel("Stylus/Pen side button (hold)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                penButtonOptions.forEach { (id, label) ->
                    Chip(label, prefs.penButtonTool == id) { update(prefs.copy(penButtonTool = id)) }
                }
            }

            HorizontalDivider(color = palette.border.toComposeColor())
            SectionTitle("Page")
            FieldLabel("Default page size")
            SizeDropdown(prefs.defaultPageSize) { update(prefs.copy(defaultPageSize = it)) }
            FieldLabel("Orientation")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Portrait", prefs.defaultPageOrientation == Orientation.PORTRAIT) {
                    update(prefs.copy(defaultPageOrientation = Orientation.PORTRAIT))
                }
                Chip("Landscape", prefs.defaultPageOrientation == Orientation.LANDSCAPE) {
                    update(prefs.copy(defaultPageOrientation = Orientation.LANDSCAPE))
                }
            }
            FieldLabel("Side margin  ${prefs.sideMargin.toInt()} px")
            Slider(
                value = prefs.sideMargin.toFloat(),
                onValueChange = { update(prefs.copy(sideMargin = it.toDouble())) },
                valueRange = 0f..64f,
                modifier = Modifier.width(280.dp),
            )
            FieldLabel("Page colour")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pageColorPresets.forEach { c ->
                    ColorDot(c.toComposeColor(), prefs.pageColor == c) { update(prefs.copy(pageColor = c)) }
                }
                ColorPickerDot(
                    prefs.pageColor,
                    custom = prefs.pageColor != null && prefs.pageColor !in pageColorPresets,
                    onPick = { update(prefs.copy(pageColor = it)) },
                    dismissOnPick = false,
                ) { onDismiss, onPick -> PageColorGridPopup(prefs.pageColor, onDismiss, onPick) }
            }
            CheckRow("Page colour follows the theme", prefs.pageColor == null) {
                update(prefs.copy(pageColor = if (it) null else pageColorPresets.first()))
            }

            HorizontalDivider(color = palette.border.toComposeColor())
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Toolbar")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { editor.applyToolbarLayout(ToolbarLayout.DEFAULT) }) { Text("Reset", fontSize = 13.sp) }
            }
            Text(
                "Tap a tool to show or hide it. Long-press to drag it within or across sections. Long-press a section's top to move the whole section.",
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )
            ToolbarCustomizerBody(
                layout = layout,
                dragItem = dragItem,
                dropTarget = dropTarget,
                dragSection = dragSection,
                sectionDropTarget = sectionDropTarget,
                chipBounds = chipBounds,
                sectionBounds = sectionBounds,
                sectionGrabBounds = sectionGrabBounds,
                onToggle = { s, i -> editor.applyToolbarLayout(editor.toolbarLayout.toggleVisible(s, i)) },
                onDelete = { s -> editor.applyToolbarLayout(editor.toolbarLayout.removeSection(s)) },
                onAdd = { editor.applyToolbarLayout(editor.toolbarLayout.addSection()) },
                onDragStart = { item, local ->
                    dragItem = item
                    dragGrab = local
                    dragFinger = (chipBounds[item]?.topLeft ?: Offset.Zero) + local
                    retarget()
                },
                onDrag = { delta -> dragFinger += delta; retarget() },
                onDrop = {
                    val target = dropTarget
                    val moving = dragItem
                    if (target != null && moving != null) {
                        val cur = editor.toolbarLayout
                        val fSec = cur.sections.indexOfFirst { s -> s.entries.any { it.item == moving } }
                        val fIdx = if (fSec >= 0) cur.sections[fSec].entries.indexOfFirst { it.item == moving } else -1
                        if (fSec >= 0 && fIdx >= 0) {
                            editor.applyToolbarLayout(cur.moveItem(fSec, fIdx, target.first, target.second))
                        }
                    }
                    dragItem = null
                    dropTarget = null
                },
                onCancel = {
                    dragItem = null
                    dropTarget = null
                },
                onSectionDragStart = { sec, local ->
                    dragSection = sec
                    val grab = sectionGrabBounds[sec]?.topLeft ?: sectionBounds[sec]?.topLeft ?: Offset.Zero
                    val card = sectionBounds[sec]?.topLeft ?: Offset.Zero
                    dragFinger = grab + local
                    dragGrab = dragFinger - card
                    retargetSection()
                },
                onSectionDrag = { delta -> dragFinger += delta; retargetSection() },
                onSectionDrop = {
                    val from = dragSection
                    val insertAt = sectionDropTarget
                    if (from != null && insertAt != null) {
                        val cur = editor.toolbarLayout
                        val to = (if (insertAt > from) insertAt - 1 else insertAt).coerceIn(0, cur.sections.lastIndex)
                        if (to != from) editor.applyToolbarLayout(cur.moveSection(from, to))
                    }
                    dragSection = null
                    sectionDropTarget = null
                },
                onSectionCancel = {
                    dragSection = null
                    sectionDropTarget = null
                },
            )
            Spacer(Modifier.size(8.dp))
        }
    }
        // The dragged chip/section's floating copy: a pane-root sibling so the scroll never clips it.
        val gx = (dragFinger.x - dragGrab.x - paneTopLeft.x).toInt()
        val gy = (dragFinger.y - dragGrab.y - paneTopLeft.y).toInt()
        val ghostChip = dragItem
        val ghostSecIdx = dragSection
        if (ghostChip != null) {
            Box(Modifier.offset { IntOffset(gx, gy) }) { ToolbarDragGhost(ghostChip) }
        } else if (ghostSecIdx != null) {
            val sectionG = editor.toolbarLayout.sections.getOrNull(ghostSecIdx)
            val widthG = sectionBounds[ghostSecIdx]?.width
            if (sectionG != null && widthG != null) {
                Box(Modifier.offset { IntOffset(gx, gy) }) { SectionCardGhost(sectionG, widthG) }
            }
        }
    }

    // While dragging near a vertical edge of the scroll viewport, ease the list along so items
    // off-screen can be reached without lifting the finger.
    LaunchedEffect(dragItem != null || dragSection != null) {
        while (dragItem != null || dragSection != null) {
            val band = 90f
            val y = dragFinger.y
            val delta = when {
                y < viewport.top + band -> -((viewport.top + band - y) / band) * 14f
                y > viewport.bottom - band -> ((y - (viewport.bottom - band)) / band) * 14f
                else -> 0f
            }
            if (delta != 0f) {
                scrollState.scrollBy(delta)
                if (dragItem != null) retarget() else retargetSection()
            }
            delay(16L)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = LocalPalette.current.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 15.sp)
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = LocalPalette.current.accent.toComposeColor(), fontSize = 13.sp)
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) palette.accentAlpha(48).toComposeColor() else palette.surface.toComposeColor())
            .border(1.dp, if (selected) palette.accent.toComposeColor() else palette.border.toComposeColor(), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (selected) palette.accent.toComposeColor() else palette.text.toComposeColor(), fontSize = 14.sp)
    }
}

@Composable
internal fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .size(30.dp)
            .then(if (selected) Modifier.border(2.dp, palette.accent.toComposeColor(), CircleShape) else Modifier)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, palette.border.toComposeColor(), CircleShape)
            .clickable(onClick = onClick),
    )
}

/** A spectrum wheel signals that this dot opens the full picker. */
private val spectrumBrush = Brush.sweepGradient(
    listOf(
        Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
        Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
    ),
)

/**
 * A spectrum dot that opens [grid], a popup of colour swatches — shared by the accent and
 * page-colour rows. Until a colour outside the row's presets is chosen the dot shows the
 * spectrum wheel; once one is, it fills with that colour and reads as selected.
 */
@Composable
internal fun ColorPickerDot(
    current: Rgba?,
    custom: Boolean,
    onPick: (Rgba) -> Unit,
    dismissOnPick: Boolean = true,
    grid: @Composable (onDismiss: () -> Unit, onPick: (Rgba) -> Unit) -> Unit,
) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(30.dp)
                .then(if (custom) Modifier.border(2.dp, palette.accent.toComposeColor(), CircleShape) else Modifier)
                .padding(4.dp)
                .clip(CircleShape)
                .then(if (custom && current != null) Modifier.background(current.toComposeColor()) else Modifier.background(spectrumBrush))
                .border(1.dp, palette.border.toComposeColor(), CircleShape)
                .clickable { open = true },
        )
        // A live picker (e.g. the page/ink popup) edits across several taps, so it stays open until a
        // tap outside; a one-shot grid (the accent swatches) closes the moment a colour is chosen.
        if (open) grid({ open = false }, { onPick(it); if (dismissOnPick) open = false })
    }
}

/** One tappable colour cell in a picker grid. */
@Composable
private fun Swatch(c: Rgba, onPick: (Rgba) -> Unit) {
    Box(
        Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(c.toComposeColor())
            .border(0.5.dp, LocalPalette.current.border.toComposeColor(), RoundedCornerShape(2.dp))
            .clickable { onPick(c) },
    )
}

/** Picker grid restricted to bright, saturated hues — no greys, no washed-out tints. */
@Composable
private fun AccentColorGridPopup(onDismiss: () -> Unit, onPick: (Rgba) -> Unit) {
    val hues = (0 until 12).map { it * 360.0 / 12.0 }
    val shades = listOf(1.0 to 1.0, 1.0 to 0.82, 0.78 to 1.0)
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            shades.forEach { (s, v) ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    hues.forEach { h -> Swatch(ColorMath.hsvToRgb(h, s, v), onPick) }
                }
            }
        }
    }
}

/**
 * Page/pattern colour picker: the shared [ColorPickerPopup] (Swatches + Spectrum tabs, full
 * 13×13 range from pale tint to near-black plus a greyscale row, and HEX/RGB fields), so paper-like
 * and muted page backgrounds are reachable, not just the saturated ones. It keeps no recents list.
 */
@Composable
internal fun PageColorGridPopup(initial: Rgba?, onDismiss: () -> Unit, onPick: (Rgba) -> Unit) {
    ColorPickerPopup(initial = initial, recents = emptyList(), onDismiss = onDismiss, onPick = onPick)
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onChange(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(2.dp))
        Text(label, color = LocalPalette.current.text.toComposeColor(), fontSize = 14.sp)
    }
}

@Composable
private fun SizeDropdown(size: PageSize, onSelect: (PageSize) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    Box {
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(size.displayName, color = palette.text.toComposeColor(), fontSize = 14.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PageSize.entries.forEach { s ->
                DropdownMenuItem(text = { Text(s.displayName) }, onClick = { onSelect(s); expanded = false })
            }
        }
    }
}
