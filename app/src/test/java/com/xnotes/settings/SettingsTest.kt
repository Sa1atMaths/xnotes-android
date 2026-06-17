package com.xnotes.settings

import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolbarLayout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    @Test fun emptyJsonYieldsDefaults() {
        val s = Settings.fromJson(JSONObject())
        assertEquals(7, s.toolbarColors.size)
        assertEquals(5, s.toolbarColorCount)
        assertEquals(0, s.activeColor)
        assertEquals(1.0, s.renderScale, 1e-9)
        assertFalse(s.sidebarVisible)
        assertEquals(PageSize.A4, s.prefs.defaultPageSize)
        assertTrue(s.prefs.isDark)
    }

    @Test fun roundTripPreservesValues() {
        val original = Settings(
            tools = mapOf(Tool.PEN to com.xnotes.core.tools.ToolConfig(5.0, false, 0.2, 0.1, Rgba(1, 2, 3, 255))),
            toolbarColors = listOf(Rgba(0, 230, 118), Rgba(1, 1, 1), Rgba(2, 2, 2), Rgba(3, 3, 3), Rgba(4, 4, 4)),
            activeColor = 2,
            renderScale = 1.5,
            sidebarVisible = true,
            prefs = Preferences(
                uiAppearance = "light",
                accentColor = Rgba(255, 138, 30),
                defaultPageSize = PageSize.LETTER,
                defaultPageOrientation = Orientation.LANDSCAPE,
                pageColor = Rgba(20, 20, 20),
            ),
        )
        val back = Settings.fromJson(original.toJson())
        assertEquals(5.0, back.configFor(Tool.PEN).baseWidth, 1e-9)
        assertFalse(back.configFor(Tool.PEN).pressureEnabled)
        assertEquals(2, back.activeColor)
        assertEquals(1.5, back.renderScale, 1e-9)
        assertTrue(back.sidebarVisible)
        assertEquals("light", back.prefs.uiAppearance)
        assertEquals(PageSize.LETTER, back.prefs.defaultPageSize)
        assertEquals(Orientation.LANDSCAPE, back.prefs.defaultPageOrientation)
        assertEquals(Rgba(20, 20, 20, 255), back.prefs.pageColor)
    }

    @Test fun malformedAppearanceFallsBackToDark() {
        val o = JSONObject().put("prefs", JSONObject().put("ui_appearance", "rainbow"))
        assertEquals("dark", Settings.fromJson(o).prefs.uiAppearance)
    }

    @Test fun toolbarColorsPaddedToSeven() {
        val o = JSONObject().put(
            "toolbar_colors",
            org.json.JSONArray().put(org.json.JSONArray().put(0).put(0).put(0).put(255)),
        )
        assertEquals(7, Settings.fromJson(o).toolbarColors.size)
    }

    @Test fun toolbarColorCountDefaultsToFive() {
        assertEquals(5, Settings.fromJson(JSONObject()).toolbarColorCount)
    }

    @Test fun toolbarColorCountRoundTripsAndClamps() {
        assertEquals(7, Settings.fromJson(Settings(toolbarColorCount = 7).toJson()).toolbarColorCount)
        assertEquals(1, Settings.fromJson(Settings(toolbarColorCount = 0).toJson()).toolbarColorCount)
        assertEquals(7, Settings.fromJson(Settings(toolbarColorCount = 99).toJson()).toolbarColorCount)
    }

    @Test fun rememberColorDedupesAndCaps() {
        var s = Settings()
        repeat(30) { s = s.rememberColor(Rgba(it, it, it)) }
        assertEquals(24, s.recentColors.size)
        s = s.rememberColor(Rgba(5, 5, 5))
        assertEquals(Rgba(5, 5, 5, 255), s.recentColors.first())
        assertEquals(24, s.recentColors.size)
    }

    @Test fun pageColorNullByDefault() {
        assertNull(Settings.fromJson(JSONObject()).prefs.pageColor)
    }

    @Test fun fingerDrawAutoCheckedDefaultsFalse() {
        assertFalse(Settings.fromJson(JSONObject()).fingerDrawAutoChecked)
    }

    @Test fun fingerDrawAutoCheckedRoundTrips() {
        val back = Settings.fromJson(Settings(fingerDrawAutoChecked = true).toJson())
        assertTrue(back.fingerDrawAutoChecked)
    }

    @Test fun toolbarLayoutDefaultsWhenAbsent() {
        assertEquals(ToolbarLayout.DEFAULT, Settings.fromJson(JSONObject()).toolbarLayout)
    }

    @Test fun toolbarLayoutRoundTrips() {
        val custom = ToolbarLayout.DEFAULT.toggleVisible(2, 0).addSection()
        val back = Settings.fromJson(Settings(toolbarLayout = custom).toJson())
        assertEquals(custom, back.toolbarLayout)
    }
}
