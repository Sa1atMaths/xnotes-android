package com.xnotes.platform

import com.xnotes.canvas.ViewSettings
import com.xnotes.canvas.ViewingMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ViewStateStoreTest {

    private fun store(): JsonStore =
        JsonStore(File(Files.createTempDirectory("xnotes-views").toFile(), "view_states.json"))

    @Test fun roundTripsViewAndSettings() {
        val json = store()
        val vs = ViewSettings(
            mode = ViewingMode.DOUBLE,
            verticalScroll = false,
            contrast = 150,
            invert = 100,
            brightness = 60,
            sepia = 10,
            rotation = 90,
            scrollbar = true,
        )
        ViewStateStore(json).put("auth|doc1", 1.5, 12.0, 34.0, vs)

        val v = ViewStateStore(json).get("auth|doc1")!!
        assertEquals(1.5, v.zoom, 1e-9)
        assertEquals(12.0, v.scrollX, 1e-9)
        assertEquals(34.0, v.scrollY, 1e-9)
        assertEquals(vs, v.settings)
    }

    @Test fun legacyEntryWithoutSettingsLoadsDefaults() {
        val json = store()
        // An entry written before the View-menu settings existed: zoom/scroll only.
        json.write(JSONObject().put("auth|old", JSONObject().put("zoom", 2.0).put("scrollX", 5.0).put("scrollY", 7.0)))

        val v = ViewStateStore(json).get("auth|old")!!
        assertEquals(2.0, v.zoom, 1e-9)
        assertEquals(ViewSettings(), v.settings)
        assertNull(v.settings.invert) // follows the global default until overridden
    }

    @Test fun defaultsAreOmittedFromJson() {
        val json = store()
        ViewStateStore(json).put("auth|doc", 1.0, 0.0, 0.0, ViewSettings())
        val entry = json.read().getJSONObject("auth|doc")
        assertEquals(setOf("zoom", "scrollX", "scrollY"), entry.keys().asSequence().toSet())
    }
}
