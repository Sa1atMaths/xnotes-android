package com.xnotes.settings

import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import org.json.JSONObject

/**
 * Global, document-independent preferences (spec 09 §4). Forgiving load: every
 * field falls back to its default if missing or malformed.
 */
data class Preferences(
    val pdfDarkMode: Boolean = false,
    /** When dark mode is on, keep embedded PDF images in their original colours instead of inverting them. */
    val pdfKeepImageColors: Boolean = false,
    val uiAppearance: String = "dark", // "dark" | "light" | "oled"
    val accentColor: Rgba = DEFAULT_ACCENT,
    val hideWindowDecoration: Boolean = false,
    val pageColor: Rgba? = null, // null ⇒ follow theme paper
    val pageTemplatePdf: String? = null,
    val defaultTemplate: String = "color", // "color" | "pdf"
    val defaultPageSize: PageSize = PageSize.A4,
    val defaultPageOrientation: Orientation = Orientation.PORTRAIT,
    /** Whether a finger draws (true) or pans (false, default). The stylus always draws. */
    val fingerDraws: Boolean = false,
    /** Panning allowed while zoom is locked: "single" (default) | "double" | "none". */
    val zoomLockPan: String = "single",
    /** Whether holding a freehand ink stroke still snaps it to a recognized shape. */
    val detectShapes: Boolean = false,
    /** Tool the stylus side button activates while held; "none" disables it. */
    val penButtonTool: String = "eraser",
    /** Whether the side-button tool also activates during hover (no contact needed); eraser/pan only. */
    val penButtonHover: Boolean = false,
    /** Action mapped to a clean two-finger tap; "none" (default) disables it. */
    val twoFingerTap: String = "none",
    /** Action mapped to a clean three-finger tap; "none" (default) disables it. */
    val threeFingerTap: String = "none",
    /** Horizontal margin (px) on each side of the page column; 0 ⇒ fit-width fills the screen. */
    val sideMargin: Double = 16.0,
    /** Long-edge cap (px) for the on-screen page cache; higher holds more of the page ready at deep zoom. */
    val maxCacheResolution: Int = 2048,
    /** Open in fullscreen; null ⇒ auto (on unless the display has a camera cutout). */
    val startFullscreen: Boolean? = null,
) {
    val isDark: Boolean get() = uiAppearance != "light"

    fun toJson(): JSONObject = JSONObject()
        .put("pdf_dark_mode", pdfDarkMode)
        .put("pdf_keep_image_colors", pdfKeepImageColors)
        .put("ui_appearance", uiAppearance)
        .put("accent_color", Rgba.toHex(accentColor))
        .put("hide_window_decoration", hideWindowDecoration)
        .put("page_color", pageColor?.let { Rgba.toHex(it) } ?: JSONObject.NULL)
        .put("page_template_pdf", pageTemplatePdf ?: JSONObject.NULL)
        .put("default_template", defaultTemplate)
        .put("default_page_size", defaultPageSize.displayName)
        .put("default_page_orientation", defaultPageOrientation.toName())
        .put("finger_draws", fingerDraws)
        .put("zoom_lock_pan", zoomLockPan)
        .put("detect_shapes", detectShapes)
        .put("pen_button_tool", penButtonTool)
        .put("pen_button_hover", penButtonHover)
        .put("two_finger_tap", twoFingerTap)
        .put("three_finger_tap", threeFingerTap)
        .put("side_margin", sideMargin)
        .put("max_cache_resolution", maxCacheResolution)
        .apply { startFullscreen?.let { put("start_fullscreen", it) } }

    companion object {
        val DEFAULT_ACCENT = Rgba(0, 230, 118, 255)

        fun fromJson(o: JSONObject?): Preferences {
            if (o == null) return Preferences()
            val appearance = o.optString("ui_appearance", "dark").let { if (it == "light" || it == "oled") it else "dark" }
            val template = o.optString("default_template", "color").let { if (it == "pdf") "pdf" else "color" }
            val zoomLockPan = o.optString("zoom_lock_pan", "single").let { if (it == "double" || it == "none") it else "single" }
            val tapActions = setOf("none", "undo", "redo", "toggle_pan", "toggle_eraser", "toggle_previous")
            fun tapAction(key: String) = o.optString(key, "none").let { if (it in tapActions) it else "none" }
            return Preferences(
                pdfDarkMode = o.optBoolean("pdf_dark_mode", false),
                pdfKeepImageColors = o.optBoolean("pdf_keep_image_colors", false),
                uiAppearance = appearance,
                accentColor = Rgba.fromHex(o.optString("accent_color")) ?: DEFAULT_ACCENT,
                hideWindowDecoration = o.optBoolean("hide_window_decoration", false),
                pageColor = if (o.isNull("page_color")) null else Rgba.fromHex(o.optString("page_color")),
                pageTemplatePdf = if (o.isNull("page_template_pdf")) null else o.optString("page_template_pdf").ifEmpty { null },
                defaultTemplate = template,
                defaultPageSize = PageSize.fromName(o.optString("default_page_size", "A4")),
                defaultPageOrientation = Orientation.fromName(o.optString("default_page_orientation", "portrait")),
                fingerDraws = o.optBoolean("finger_draws", false),
                zoomLockPan = zoomLockPan,
                detectShapes = o.optBoolean("detect_shapes", false),
                penButtonTool = o.optString("pen_button_tool", "eraser").ifEmpty { "eraser" },
                penButtonHover = o.optBoolean("pen_button_hover", false),
                twoFingerTap = tapAction("two_finger_tap"),
                threeFingerTap = tapAction("three_finger_tap"),
                sideMargin = o.optDouble("side_margin", 16.0).coerceIn(0.0, 80.0),
                maxCacheResolution = o.optInt("max_cache_resolution", 2048).coerceIn(1024, 4096),
                startFullscreen = if (o.has("start_fullscreen")) o.getBoolean("start_fullscreen") else null,
            )
        }
    }
}
