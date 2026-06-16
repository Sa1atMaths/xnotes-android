package com.xnotes.core.tools

/**
 * User-customisable toolbar layout: ordered [sections], each an ordered list of [ToolbarEntry].
 * Pure model (no Android, no JSON) so the add/remove/move/migration logic is unit-testable on
 * the JVM; the settings layer persists it and the UI renders from it.
 */

/** Every atomic, movable toolbar element. The three block ids each render a fixed multi-control
 *  cluster but move and hide as one unit. [id] is the persistence key (never rename without a
 *  migration); the 12 tool ids match the matching [Tool.id]. */
enum class ToolbarItem(val id: String, val label: String) {
    HOME("home", "Home"),
    TITLE("title", "Title"),
    SIDEBAR("sidebar", "Sidebar"),
    PEN("pen", "Pen"),
    DASHED("dashed", "Dashed"),
    CALLIGRAPHY("calligraphy", "Calligraphy"),
    SPEED("speed", "Speed"),
    TAPER("taper", "Taper"),
    HIGHLIGHTER("highlighter", "Highlighter"),
    ERASER("eraser", "Eraser"),
    PAN("pan", "Pan"),
    SELECT("select", "Select"),
    LASSO("lasso", "Lasso"),
    WAND("wand", "Magic wand"),
    SHAPE("shape", "Shape"),
    RULER("ruler", "Ruler"),
    TEXT("text", "Text"),
    IMAGE("image", "Image"),
    UNDO("undo", "Undo"),
    REDO("redo", "Redo"),
    PAGE_NAV("page_nav", "Page nav"),
    PAGE_MENU("page_menu", "Page menu"),
    STYLES("styles", "Styles"),
    ZOOM("zoom", "Zoom"),
    FIT("fit", "Fit"),
    ZOOM_LOCK("zoom_lock", "Zoom lock"),
    FULLSCREEN("fullscreen", "Full screen"),
    PRESENT("present", "Present"),
    COLORS("colors", "Colours");

    companion object {
        fun fromId(id: String?): ToolbarItem? = entries.firstOrNull { it.id == id }
    }
}

data class ToolbarEntry(val item: ToolbarItem, val visible: Boolean = true)

data class ToolbarSection(val entries: List<ToolbarEntry>) {
    val visibleEntries: List<ToolbarEntry> get() = entries.filter { it.visible }
    val hasVisible: Boolean get() = entries.any { it.visible }
}

data class ToolbarLayout(val sections: List<ToolbarSection>) {

    /** Sections that paint on the real bar; a separator goes between consecutive ones. */
    val visibleSections: List<ToolbarSection> get() = sections.filter { it.hasVisible }

    fun addSection(): ToolbarLayout = copy(sections = sections + ToolbarSection(emptyList()))

    /**
     * Remove section [i], merging its entries into the previous section, or the next one when [i]
     * is the first. Order and visibility are preserved. No-op when only one section remains.
     */
    fun removeSection(i: Int): ToolbarLayout {
        if (sections.size <= 1 || i !in sections.indices) return this
        val target = if (i == 0) i + 1 else i - 1
        val moved = sections[i].entries
        val rebuilt = sections.mapIndexedNotNull { idx, sec ->
            when (idx) {
                i -> null
                target -> sec.copy(entries = if (target < i) sec.entries + moved else moved + sec.entries)
                else -> sec
            }
        }
        return copy(sections = rebuilt)
    }

    fun moveSection(from: Int, to: Int): ToolbarLayout {
        if (from !in sections.indices || to !in sections.indices || from == to) return this
        val list = sections.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(sections = list)
    }

    fun toggleVisible(sectionIndex: Int, entryIndex: Int): ToolbarLayout {
        val sec = sections.getOrNull(sectionIndex) ?: return this
        val e = sec.entries.getOrNull(entryIndex) ?: return this
        val entries = sec.entries.toMutableList().also { it[entryIndex] = e.copy(visible = !e.visible) }
        return copy(sections = sections.toMutableList().also { it[sectionIndex] = sec.copy(entries = entries) })
    }

    /**
     * Move the entry at ([fromSection], [fromIndex]) to ([toSection], [toIndex]), keeping its
     * visibility. The target index is interpreted against the layout after the source is removed.
     */
    fun moveItem(fromSection: Int, fromIndex: Int, toSection: Int, toIndex: Int): ToolbarLayout {
        val src = sections.getOrNull(fromSection) ?: return this
        val entry = src.entries.getOrNull(fromIndex) ?: return this
        if (toSection !in sections.indices) return this
        val mutable = sections.map { it.entries.toMutableList() }.toMutableList()
        mutable[fromSection].removeAt(fromIndex)
        val adjusted = if (toSection == fromSection && toIndex > fromIndex) toIndex - 1 else toIndex
        mutable[toSection].add(adjusted.coerceIn(0, mutable[toSection].size), entry)
        return copy(sections = mutable.mapIndexed { idx, e -> sections[idx].copy(entries = e) })
    }

    /** Append any canonical item missing from this layout to the last section, visible. */
    fun withMissingItemsAppended(): ToolbarLayout {
        val present = sections.flatMap { it.entries }.map { it.item }.toSet()
        val missing = ToolbarItem.entries.filter { it !in present }
        if (missing.isEmpty()) return this
        val last = sections.last()
        val merged = last.copy(entries = last.entries + missing.map { ToolbarEntry(it) })
        return copy(sections = sections.dropLast(1) + merged)
    }

    companion object {
        /** Mirrors the hardcoded bar exactly (see Toolbar.kt) so existing users see no change. */
        val DEFAULT: ToolbarLayout = of(
            listOf(ToolbarItem.HOME, ToolbarItem.TITLE),
            listOf(ToolbarItem.SIDEBAR),
            listOf(
                ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.CALLIGRAPHY, ToolbarItem.SPEED,
                ToolbarItem.TAPER, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER,
            ),
            listOf(ToolbarItem.PAN, ToolbarItem.SELECT, ToolbarItem.LASSO),
            listOf(ToolbarItem.WAND, ToolbarItem.SHAPE, ToolbarItem.RULER, ToolbarItem.TEXT),
            listOf(ToolbarItem.IMAGE),
            listOf(ToolbarItem.COLORS),
            listOf(ToolbarItem.UNDO, ToolbarItem.REDO),
            listOf(ToolbarItem.PAGE_NAV, ToolbarItem.PAGE_MENU, ToolbarItem.STYLES),
            listOf(ToolbarItem.ZOOM, ToolbarItem.FIT, ToolbarItem.ZOOM_LOCK),
            listOf(ToolbarItem.FULLSCREEN, ToolbarItem.PRESENT),
        )

        private fun of(vararg groups: List<ToolbarItem>): ToolbarLayout =
            ToolbarLayout(groups.map { g -> ToolbarSection(g.map { ToolbarEntry(it) }) })

        /**
         * Build from raw (id, visible) pairs per section as read from storage: unknown ids are
         * dropped, duplicates keep their first occurrence, empty input falls back to [DEFAULT], and
         * any canonical item not present is appended so the bar never goes stale across versions.
         */
        fun fromRaw(rawSections: List<List<Pair<String, Boolean>>>): ToolbarLayout {
            if (rawSections.isEmpty()) return DEFAULT
            val seen = LinkedHashSet<ToolbarItem>()
            val sections = rawSections.map { raw ->
                ToolbarSection(
                    raw.mapNotNull { (id, visible) ->
                        val item = ToolbarItem.fromId(id) ?: return@mapNotNull null
                        if (!seen.add(item)) return@mapNotNull null
                        ToolbarEntry(item, visible)
                    },
                )
            }
            if (sections.all { it.entries.isEmpty() }) return DEFAULT
            return ToolbarLayout(sections).withMissingItemsAppended()
        }
    }
}
