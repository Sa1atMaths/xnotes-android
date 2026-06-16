package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolbarCustomizer(editor: Editor, sidebarOpen: Boolean, onShowSidebar: () -> Unit) {
    val palette = LocalPalette.current
    val layout = editor.toolbarLayout
    val listState = rememberLazyListState()

    var draggedItem by remember { mutableStateOf<ToolbarItem?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragAnchor by remember { mutableStateOf(Offset.Zero) }
    var dropTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val chipBounds = remember { mutableMapOf<ToolbarItem, Rect>() }
    val sectionBounds = remember { mutableMapOf<Int, Rect>() }
    var boxTopLeft by remember { mutableStateOf(Offset.Zero) }
    var boxHeight by remember { mutableStateOf(0) }

    fun findDropTarget(pointerRoot: Offset): Pair<Int, Int> {
        val cur = editor.toolbarLayout
        // Find the section whose top is at or above the pointer.
        val sIdx = sectionBounds.entries
            .filter { (_, b) -> pointerRoot.y >= b.top }
            .maxByOrNull { it.value.top }?.key ?: 0
        val section = cur.sections.getOrNull(sIdx) ?: return sIdx to 0
        val entries = section.entries
        if (entries.isEmpty()) return sIdx to 0
        // Walk chips sorted by visual order (top then left) and find the insertion slot.
        val ordered = entries.mapIndexedNotNull { i, e -> chipBounds[e.item]?.let { i to it } }
            .sortedWith(compareBy({ it.second.top }, { it.second.left }))
        for ((idx, b) in ordered) {
            if (pointerRoot.y < b.top) return sIdx to idx          // pointer is above this row
            if (pointerRoot.y <= b.bottom && pointerRoot.x < b.center.x) return sIdx to idx
        }
        return sIdx to entries.size
    }

    // Auto-scroll the LazyColumn when the ghost approaches the top or bottom edge.
    LaunchedEffect(draggedItem != null) {
        if (draggedItem == null) return@LaunchedEffect
        while (draggedItem != null) {
            val edgeBand = 80f
            val ry = dragOffset.y - boxTopLeft.y
            val h = boxHeight.toFloat()
            val delta = when {
                ry < edgeBand -> -(edgeBand - ry) / edgeBand * 12f
                ry > h - edgeBand -> (ry - (h - edgeBand)) / edgeBand * 12f
                else -> 0f
            }
            if (delta != 0f) listState.scrollBy(delta)
            delay(16L)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!sidebarOpen) {
                IconButton(onClick = onShowSidebar) {
                    Icon(XnotesIcons.menu, "Show sidebar", tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(4.dp))
            }
            Text("Toolbar", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { editor.applyToolbarLayout(ToolbarLayout.DEFAULT) }) {
                Text("Reset", fontSize = 13.sp)
            }
        }
        Text(
            "Tap to show/hide. Long-press to drag.",
            color = palette.textDim.toComposeColor(),
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        // Box is the drag root: ghost chip overlays the LazyColumn as its last child.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    boxTopLeft = coords.boundsInRoot().topLeft
                    boxHeight = coords.size.height
                },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(layout.sections) { sectionIdx, section ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(8.dp))
                            .onGloballyPositioned { sectionBounds[sectionIdx] = it.boundsInRoot() },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                XnotesIcons.menu, null,
                                tint = palette.textDim.toComposeColor(),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Section ${sectionIdx + 1}",
                                color = palette.text.toComposeColor(),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = { editor.applyToolbarLayout(layout.removeSection(sectionIdx)) },
                                enabled = layout.sections.size > 1,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    XnotesIcons.close, "Remove section",
                                    tint = (if (layout.sections.size > 1) palette.textDim else palette.border).toComposeColor(),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        HorizontalDivider(color = palette.border.toComposeColor())
                        FlowRow(
                            Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            section.entries.forEachIndexed { entryIdx, entry ->
                                if (draggedItem != null && dropTarget == sectionIdx to entryIdx) {
                                    InsertionCaret()
                                }
                                ToolbarItemChip(
                                    item = entry.item,
                                    visible = entry.visible,
                                    dragging = draggedItem == entry.item,
                                    onTap = {
                                        editor.applyToolbarLayout(layout.toggleVisible(sectionIdx, entryIdx))
                                    },
                                    onDragStart = { localOffset ->
                                        draggedItem = entry.item
                                        dragAnchor = localOffset
                                        dragOffset = (chipBounds[entry.item]?.topLeft ?: Offset.Zero) + localOffset
                                    },
                                    onDrag = { delta ->
                                        dragOffset += delta
                                        dropTarget = findDropTarget(dragOffset)
                                    },
                                    onDragEnd = {
                                        val target = dropTarget
                                        val dragged = draggedItem
                                        if (target != null && dragged != null) {
                                            val cur = editor.toolbarLayout
                                            val fSec = cur.sections.indexOfFirst { s -> s.entries.any { it.item == dragged } }
                                            val fIdx = if (fSec >= 0) cur.sections[fSec].entries.indexOfFirst { it.item == dragged } else -1
                                            if (fSec >= 0 && fIdx >= 0) {
                                                editor.applyToolbarLayout(cur.moveItem(fSec, fIdx, target.first, target.second))
                                            }
                                        }
                                        draggedItem = null
                                        dragOffset = Offset.Zero
                                        dropTarget = null
                                    },
                                    onDragCancel = {
                                        draggedItem = null
                                        dragOffset = Offset.Zero
                                        dropTarget = null
                                    },
                                    onBoundsChanged = { chipBounds[entry.item] = it },
                                )
                            }
                            // Caret when the pointer is past all chips in this section.
                            if (draggedItem != null && dropTarget == sectionIdx to section.entries.size) {
                                InsertionCaret()
                            }
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = { editor.applyToolbarLayout(layout.addSection()) },
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        Icon(XnotesIcons.plus, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add section")
                    }
                }
            }

            // Ghost chip: drawn above the LazyColumn, anchored to the original touch offset.
            val dragged = draggedItem
            if (dragged != null) {
                val ghostX = (dragOffset.x - dragAnchor.x - boxTopLeft.x).toInt()
                val ghostY = (dragOffset.y - dragAnchor.y - boxTopLeft.y).toInt()
                Box(
                    Modifier
                        .offset { IntOffset(ghostX, ghostY) }
                        .alpha(0.85f),
                ) {
                    GhostChip(dragged)
                }
            }
        }
    }
}

@Composable
private fun ToolbarItemChip(
    item: ToolbarItem,
    visible: Boolean,
    dragging: Boolean,
    onTap: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onBoundsChanged: (Rect) -> Unit,
) {
    val palette = LocalPalette.current
    // The pointerInput blocks key on the stable [item], so they never restart across
    // recomposition; rememberUpdatedState keeps the gestures calling the latest callbacks
    // (which close over the current layout) instead of the stale first-composition ones.
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    Row(
        Modifier
            .alpha(
                when {
                    dragging -> 0.15f
                    !visible -> 0.4f
                    else -> 1f
                },
            )
            .clip(RoundedCornerShape(6.dp))
            .background(palette.surface.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(6.dp))
            .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) }
            .pointerInput(item) { detectTapGestures(onTap = { currentOnTap() }) }
            .pointerInput(item) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { currentOnDragStart(it) },
                    onDrag = { _, delta -> currentOnDrag(delta) },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragCancel() },
                )
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            itemIcon(item), null,
            tint = palette.textDim.toComposeColor(),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(item.label, color = palette.text.toComposeColor(), fontSize = 12.sp)
    }
}

@Composable
private fun GhostChip(item: ToolbarItem) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(palette.surface.toComposeColor())
            .border(1.dp, palette.accent.toComposeColor(), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            itemIcon(item), null,
            tint = palette.accent.toComposeColor(),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(item.label, color = palette.accent.toComposeColor(), fontSize = 12.sp)
    }
}

@Composable
private fun InsertionCaret() {
    Box(
        Modifier
            .width(2.dp)
            .height(28.dp)
            .background(LocalPalette.current.accent.toComposeColor()),
    )
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
