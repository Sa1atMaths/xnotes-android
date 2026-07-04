package com.xnotes.platform

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import com.xnotes.core.pal.FontFace
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves [FontFace] ids to concrete typefaces. The four generic tokens map to
 * system fonts; family names map to the fonts bundled under `assets/fonts/`
 * (an upright and a true-italic file per family; bold comes from the `wght`
 * axis of variable families or the bold instances of static ones); unknown ids
 * fall back to the sans face so notes referencing missing fonts still render.
 * Call [init] once before any canvas work. Thread-safe: paints are built on
 * background cache threads.
 */
object FontCatalog {

    /** A bundled family: [face].id is the display name, [slug] the asset folder. */
    class Family(
        val face: FontFace,
        val slug: String,
        val mono: Boolean,
        val variable: Boolean,
        val hasItalic: Boolean = true,
    )

    /** What a face resolves to; fake flags are paint-level bold/slant synthesis. */
    class Resolved(val typeface: Typeface, val fakeBold: Boolean, val fakeItalic: Boolean)

    /** One pickable font for the UI dropdowns, generic tokens included. */
    class Choice(val face: FontFace, val label: String, val mono: Boolean)

    private val BUNDLED = listOf(
        Family(FontFace("Fira Sans"), "fira-sans", mono = false, variable = false),
        Family(FontFace("Inter"), "inter", mono = false, variable = true),
        Family(FontFace("Lato"), "lato", mono = false, variable = false),
        Family(FontFace("Montserrat"), "montserrat", mono = false, variable = true),
        Family(FontFace("Noto Sans"), "noto-sans", mono = false, variable = true),
        Family(FontFace("Nunito"), "nunito", mono = false, variable = true),
        Family(FontFace("Open Sans"), "open-sans", mono = false, variable = true),
        Family(FontFace("Poppins"), "poppins", mono = false, variable = false),
        Family(FontFace("Raleway"), "raleway", mono = false, variable = true),
        Family(FontFace("Roboto"), "roboto", mono = false, variable = true),
        Family(FontFace("Source Sans 3"), "source-sans-3", mono = false, variable = true),
        Family(FontFace("Ubuntu"), "ubuntu", mono = false, variable = false),
        Family(FontFace("Lora"), "lora", mono = false, variable = true),
        Family(FontFace("Merriweather"), "merriweather", mono = false, variable = true),
        Family(FontFace("Playfair Display"), "playfair-display", mono = false, variable = true),
        Family(FontFace("Fira Code"), "fira-code", mono = true, variable = true, hasItalic = false),
        Family(FontFace("IBM Plex Mono"), "ibm-plex-mono", mono = true, variable = false),
        Family(FontFace("JetBrains Mono"), "jetbrains-mono", mono = true, variable = true),
        Family(FontFace("Roboto Mono"), "roboto-mono", mono = true, variable = true),
        Family(FontFace("Source Code Pro"), "source-code-pro", mono = true, variable = true),
        Family(FontFace("Ubuntu Mono"), "ubuntu-mono", mono = true, variable = false),
    )

    private val byId = BUNDLED.associateBy { it.face.id }
    private val hand: Typeface = Typeface.create("cursive", Typeface.NORMAL)

    @Volatile
    private var assets: AssetManager? = null
    private val cache = ConcurrentHashMap<String, Resolved>()

    fun init(context: Context) {
        assets = context.applicationContext.assets
    }

    /** The pickable fonts, generic tokens first, in display order. */
    fun choices(): List<Choice> = buildList {
        add(Choice(FontFace.SANS, "Sans", mono = false))
        add(Choice(FontFace.SERIF, "Serif", mono = false))
        add(Choice(FontFace.MONO, "Mono", mono = true))
        add(Choice(FontFace.HAND, "Hand", mono = false))
        for (f in BUNDLED) add(Choice(f.face, f.face.id, f.mono))
    }

    fun label(face: FontFace): String = when (face) {
        FontFace.SANS -> "Sans"
        FontFace.SERIF -> "Serif"
        FontFace.MONO -> "Mono"
        FontFace.HAND -> "Hand"
        else -> face.id
    }

    fun resolve(face: FontFace, bold: Boolean, italic: Boolean): Resolved {
        generic(face)?.let { return Resolved(Typeface.create(it, style(bold, italic)), false, false) }
        return cache.getOrPut("${face.id}/$bold/$italic") {
            val family = byId[face.id]
            val am = assets
            if (family == null || am == null) {
                Resolved(Typeface.create(Typeface.SANS_SERIF, style(bold, italic)), false, false)
            } else {
                runCatching { loadBundled(family, bold, italic, am) }.getOrElse {
                    Resolved(Typeface.create(Typeface.SANS_SERIF, style(bold, italic)), false, false)
                }
            }
        }
    }

    private fun generic(face: FontFace): Typeface? = when (face) {
        FontFace.SANS -> Typeface.SANS_SERIF
        FontFace.SERIF -> Typeface.SERIF
        FontFace.MONO -> Typeface.MONOSPACE
        FontFace.HAND -> hand
        else -> null
    }

    private fun style(bold: Boolean, italic: Boolean): Int = when {
        bold && italic -> Typeface.BOLD_ITALIC
        bold -> Typeface.BOLD
        italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }

    private fun loadBundled(f: Family, bold: Boolean, italic: Boolean, am: AssetManager): Resolved {
        val dir = "fonts/${f.slug}"
        val slantFake = italic && !f.hasItalic
        if (f.variable) {
            val path = "$dir/${if (italic && f.hasItalic) "italic" else "regular"}.ttf"
            val tf = if (bold) {
                Typeface.Builder(am, path).setFontVariationSettings("'wght' 700").build()
            } else {
                Typeface.Builder(am, path).build()
            }
            return if (tf != null) {
                Resolved(tf, fakeBold = false, fakeItalic = slantFake)
            } else {
                Resolved(Typeface.createFromAsset(am, path), fakeBold = bold, fakeItalic = slantFake)
            }
        }
        val name = when {
            bold && italic -> "bolditalic"
            bold -> "bold"
            italic -> "italic"
            else -> "regular"
        }
        return Resolved(Typeface.createFromAsset(am, "$dir/$name.ttf"), false, fakeItalic = slantFake)
    }
}
