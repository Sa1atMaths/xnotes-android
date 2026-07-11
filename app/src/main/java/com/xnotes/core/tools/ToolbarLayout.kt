package com.xnotes.core.tools

/**
 * User-customisable toolbar layout: ordered [sections], each an ordered list of [ToolbarEntry].
 * Pure model (no Android, no JSON) so the add/remove/move/migration logic is unit-testable on
 * the JVM; the settings layer persists it and the UI renders from it.
 */

/** Every atomic, movable toolbar element. The three block ids each render a fixed multi-control
 *  cluster but move and hide as one unit. [id] is the persistence key (never rename without a
 *  migration); the tool ids match the matching [Tool.id]. */
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
    SCREENSHOT("screenshot", "Screenshot"),
    WAND("wand", "Disappearing ink"),
    SHAPE("shape", "Shape"),
    RULER("ruler", "Ruler"),
    TEXT("text", "Text"),
    TEXT_BOX("text_box", "Text box"),
    IMAGE("image", "Image"),
    UNDO("undo", "Undo"),
    REDO("redo", "Redo"),
    PAGE_NAV("page_nav", "Page nav"),
    PAGE_MENU("page_menu", "Page menu"),
    STYLES("styles", "Styles"),
    VIEW("view", "View"),
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

    /** Add any canonical item missing from this layout, visible, so the bar never goes stale
     *  across versions: an item with a designated neighbour ([INSERT_AFTER]) slots in right
     *  after it; anything else is appended to the last section. */
    fun withMissingItemsAppended(): ToolbarLayout {
        var layout = this
        for (item in ToolbarItem.entries) {
            if (layout.sections.any { s -> s.entries.any { it.item == item } }) continue
            layout = layout.insertMissing(item)
        }
        return layout
    }

    private fun insertMissing(item: ToolbarItem): ToolbarLayout {
        val anchor = INSERT_AFTER[item]
        if (anchor != null) {
            sections.forEachIndexed { si, sec ->
                val i = sec.entries.indexOfFirst { it.item == anchor }
                if (i >= 0) {
                    val entries = sec.entries.toMutableList().also { it.add(i + 1, ToolbarEntry(item)) }
                    return copy(sections = sections.toMutableList().also { it[si] = sec.copy(entries = entries) })
                }
            }
        }
        val last = sections.last()
        return copy(sections = sections.dropLast(1) + last.copy(entries = last.entries + ToolbarEntry(item)))
    }

    companion object {
        /** Later-added items that belong beside an existing one in stored layouts. */
        private val INSERT_AFTER = mapOf(
            ToolbarItem.TEXT_BOX to ToolbarItem.TEXT,
            ToolbarItem.VIEW to ToolbarItem.STYLES,
        )

        /** Mirrors the hardcoded bar exactly (see Toolbar.kt) so existing users see no change. */
        val DEFAULT: ToolbarLayout = of(
            listOf(ToolbarItem.HOME, ToolbarItem.TITLE),
            listOf(ToolbarItem.SIDEBAR),
            listOf(
                ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.CALLIGRAPHY, ToolbarItem.SPEED,
                ToolbarItem.TAPER, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER,
            ),
            listOf(ToolbarItem.PAN, ToolbarItem.SELECT, ToolbarItem.LASSO, ToolbarItem.SCREENSHOT),
            listOf(ToolbarItem.WAND, ToolbarItem.SHAPE, ToolbarItem.RULER, ToolbarItem.TEXT, ToolbarItem.TEXT_BOX),
            listOf(ToolbarItem.IMAGE),
            listOf(ToolbarItem.COLORS),
            listOf(ToolbarItem.UNDO, ToolbarItem.REDO),
            listOf(ToolbarItem.PAGE_NAV, ToolbarItem.PAGE_MENU, ToolbarItem.STYLES, ToolbarItem.VIEW),
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
