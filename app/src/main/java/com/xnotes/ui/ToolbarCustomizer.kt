package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * The toolbar customizer, rendered inline inside Preferences. It mirrors the real bar: a single
 * wrapping [FlowRow] where each section's chips flow left-to-right and a tappable divider sits
 * between sections (tap = merge). Chips tap to show/hide and long-press to drag (within or across
 * sections). The drag ghost and the edge auto-scroll live in the caller (PreferencesPane) so the
 * ghost can float above the scroll; this body only paints chips, captures bounds, and forwards the
 * raw gesture events back up.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolbarCustomizerBody(
    layout: ToolbarLayout,
    dragItem: ToolbarItem?,
    dropTarget: Pair<Int, Int>?,
    chipBounds: MutableMap<ToolbarItem, Rect>,
    sepBounds: MutableMap<Int, Rect>,
    emptyBounds: MutableMap<Int, Rect>,
    onToggle: (Int, Int) -> Unit,
    onMerge: (Int) -> Unit,
    onAdd: () -> Unit,
    onDragStart: (ToolbarItem, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
) {
    val dragging = dragItem != null
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        layout.sections.forEachIndexed { sec, section ->
            if (section.entries.isEmpty()) {
                EmptySectionSlot(
                    active = dragging && dropTarget == sec to 0,
                    onBounds = { emptyBounds[sec] = it },
                )
            } else {
                section.entries.forEachIndexed { idx, entry ->
                    if (dragging && dropTarget == sec to idx) InsertionCaret()
                    ChipCell(
                        item = entry.item,
                        visible = entry.visible,
                        dragging = dragItem == entry.item,
                        onTap = { onToggle(sec, idx) },
                        onDragStart = { local -> onDragStart(entry.item, local) },
                        onDrag = onDrag,
                        onDrop = onDrop,
                        onCancel = onCancel,
                        onBounds = { chipBounds[entry.item] = it },
                    )
                }
                if (dragging && dropTarget == sec to section.entries.size) InsertionCaret()
            }
            if (sec < layout.sections.lastIndex) {
                SectionDivider(onClick = { onMerge(sec) }, onBounds = { sepBounds[sec] = it })
            }
        }
        AddSectionChip(onClick = onAdd)
    }
}

/**
 * Resolve the drop slot (sectionIndex, entryIndex) for a finger position in root coordinates by
 * walking sections in render order: the first chip the finger sits "before" (an earlier row, or
 * left of its centre on the same row) wins; a divider claims the end of its left section; an empty
 * section claims its own slot 0. Falls through to the end of the last section.
 */
internal fun toolbarDropTarget(
    layout: ToolbarLayout,
    finger: Offset,
    chipBounds: Map<ToolbarItem, Rect>,
    sepBounds: Map<Int, Rect>,
    emptyBounds: Map<Int, Rect>,
): Pair<Int, Int> {
    fun before(r: Rect): Boolean = finger.y < r.top || (finger.y <= r.bottom && finger.x < r.center.x)
    for (sec in layout.sections.indices) {
        val entries = layout.sections[sec].entries
        if (entries.isEmpty()) {
            val r = emptyBounds[sec]
            if (r != null && (before(r) || r.contains(finger))) return sec to 0
        } else {
            for (idx in entries.indices) {
                val r = chipBounds[entries[idx].item] ?: continue
                if (before(r)) return sec to idx
            }
        }
        if (sec < layout.sections.lastIndex) {
            val sr = sepBounds[sec]
            if (sr != null && before(sr)) return sec to entries.size
        }
    }
    val last = layout.sections.lastIndex
    return last to layout.sections[last].entries.size
}

@Composable
private fun ChipCell(
    item: ToolbarItem,
    visible: Boolean,
    dragging: Boolean,
    onTap: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    val palette = LocalPalette.current
    // The pointerInput blocks key on the stable item, so they capture the first-composition
    // lambdas; rememberUpdatedState keeps the gestures calling the latest callbacks instead.
    val tap by rememberUpdatedState(onTap)
    val dStart by rememberUpdatedState(onDragStart)
    val dMove by rememberUpdatedState(onDrag)
    val dEnd by rememberUpdatedState(onDrop)
    val dCancel by rememberUpdatedState(onCancel)
    Row(
        Modifier
            .alpha(if (dragging) 0.15f else if (!visible) 0.4f else 1f)
            .clip(RoundedCornerShape(6.dp))
            .background(palette.surface.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(6.dp))
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .pointerInput(item) { detectTapGestures(onTap = { tap() }) }
            .pointerInput(item) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dStart(it) },
                    onDrag = { _, delta -> dMove(delta) },
                    onDragEnd = { dEnd() },
                    onDragCancel = { dCancel() },
                )
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(itemIcon(item), null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(item.label, color = palette.text.toComposeColor(), fontSize = 12.sp)
    }
}

/** The floating chip that tracks the finger during a drag (drawn at the pane root by the caller). */
@Composable
fun ToolbarDragGhost(item: ToolbarItem) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .alpha(0.9f)
            .clip(RoundedCornerShape(6.dp))
            .background(palette.surface.toComposeColor())
            .border(1.dp, palette.accent.toComposeColor(), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(itemIcon(item), null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(item.label, color = palette.accent.toComposeColor(), fontSize = 12.sp)
    }
}

/** A tappable section boundary; tapping merges the two sections it divides. */
@Composable
private fun SectionDivider(onClick: () -> Unit, onBounds: (Rect) -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .clip(RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(1.dp).height(26.dp).background(palette.border.toComposeColor()))
    }
}

/** Placeholder for a section with no items: a visible drop target so an empty section is reachable. */
@Composable
private fun EmptySectionSlot(active: Boolean, onBounds: (Rect) -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, (if (active) palette.accent else palette.border).toComposeColor(), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("drop here", color = palette.textDim.toComposeColor(), fontSize = 11.sp)
    }
}

@Composable
private fun AddSectionChip(onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(XnotesIcons.plus, "Add section", tint = palette.accent.toComposeColor(), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("Add section", color = palette.accent.toComposeColor(), fontSize = 12.sp)
    }
}

@Composable
private fun InsertionCaret() {
    Box(Modifier.width(2.dp).height(28.dp).background(LocalPalette.current.accent.toComposeColor()))
}

@Composable
private fun itemIcon(item: ToolbarItem): ImageVector = when (item) {
    ToolbarItem.HOME -> XnotesIcons.home
    ToolbarItem.TITLE -> XnotesIcons.edit
    ToolbarItem.SIDEBAR -> XnotesIcons.sidebar
    ToolbarItem.PEN -> ImageVector.vectorResource(R.drawable.ic_stroke_regular)
    ToolbarItem.DASHED -> ImageVector.vectorResource(R.drawable.ic_stroke_dashed)
    ToolbarItem.CALLIGRAPHY -> ImageVector.vectorResource(R.drawable.ic_stroke_calligraphy)
    ToolbarItem.SPEED -> ImageVector.vectorResource(R.drawable.ic_stroke_speed)
    ToolbarItem.TAPER -> ImageVector.vectorResource(R.drawable.ic_stroke_taper)
    ToolbarItem.HIGHLIGHTER -> ImageVector.vectorResource(R.drawable.ic_stroke_highlighter)
    ToolbarItem.ERASER -> XnotesIcons.eraser
    ToolbarItem.PAN -> XnotesIcons.pan
    ToolbarItem.SELECT -> XnotesIcons.select
    ToolbarItem.LASSO -> XnotesIcons.lasso
    ToolbarItem.SCREENSHOT -> XnotesIcons.scissors
    ToolbarItem.WAND -> XnotesIcons.magicWand
    ToolbarItem.SHAPE -> XnotesIcons.shape
    ToolbarItem.RULER -> XnotesIcons.ruler
    ToolbarItem.TEXT -> XnotesIcons.text
    ToolbarItem.IMAGE -> XnotesIcons.image
    ToolbarItem.UNDO -> XnotesIcons.undo
    ToolbarItem.REDO -> XnotesIcons.redo
    ToolbarItem.PAGE_NAV -> XnotesIcons.prev
    ToolbarItem.PAGE_MENU -> XnotesIcons.page
    ToolbarItem.STYLES -> XnotesIcons.sliders
    ToolbarItem.ZOOM -> XnotesIcons.zoomIn
    ToolbarItem.FIT -> XnotesIcons.fit
    ToolbarItem.ZOOM_LOCK -> XnotesIcons.lock
    ToolbarItem.FULLSCREEN -> XnotesIcons.fullscreen
    ToolbarItem.PRESENT -> XnotesIcons.present
    ToolbarItem.COLORS -> XnotesIcons.more
}
