package com.xnotes.platform

import com.xnotes.canvas.ViewSettings
import com.xnotes.canvas.ViewingMode
import org.json.JSONObject

/**
 * JSON (de)serialization for [ViewSettings], shared by the per-note view store and the
 * session sidecar. Defaults are omitted on write and every field is forgiving on read,
 * so entries written before a field existed load with that field's default.
 */
internal object ViewSettingsJson {

    fun write(o: JSONObject, s: ViewSettings): JSONObject = o.apply {
        if (s.mode != ViewingMode.SINGLE) put("mode", s.mode.id)
        if (!s.verticalScroll) put("verticalScroll", false)
        if (s.contrast != 100) put("contrast", s.contrast)
        s.invert?.let { put("invert", it) }
        if (s.brightness != 100) put("brightness", s.brightness)
        if (s.sepia != 0) put("sepia", s.sepia)
        if (s.rotation != 0) put("rotation", s.rotation)
        if (s.scrollbar) put("scrollbar", true)
    }

    fun read(o: JSONObject): ViewSettings = ViewSettings(
        mode = ViewingMode.fromId(o.optString("mode", ViewingMode.SINGLE.id)),
        verticalScroll = o.optBoolean("verticalScroll", true),
        contrast = o.optInt("contrast", 100).coerceIn(0, 200),
        invert = if (o.has("invert")) o.optInt("invert", 0).coerceIn(0, 100) else null,
        brightness = o.optInt("brightness", 100).coerceIn(0, 200),
        sepia = o.optInt("sepia", 0).coerceIn(0, 100),
        rotation = ViewSettings.normalizeRotation(o.optInt("rotation", 0)),
        scrollbar = o.optBoolean("scrollbar", false),
    )
}
