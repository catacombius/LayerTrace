package io.layertrace.app.render

import android.content.Context
import android.graphics.Typeface
import io.layertrace.core.text.FontInfo
import io.layertrace.core.text.FontNameParser
import java.io.File

/**
 * Maps IDML font names (family + style) to Android typefaces. User-imported fonts are matched
 * exactly; everything else falls back to a system family chosen by name heuristics.
 */
class FontRegistry(private val dir: File?) {

    data class Entry(val info: FontInfo, val file: File)

    private val imported = LinkedHashMap<String, MutableList<Entry>>()
    private val cache = HashMap<Pair<String?, String?>, Typeface>()

    /** Incremented whenever the set of fonts changes; part of text layout cache keys. */
    var generation = 0
        private set

    init {
        reload()
    }

    constructor(context: Context) : this(File(context.filesDir, "fonts"))

    fun reload() {
        imported.clear()
        cache.clear()
        dir?.listFiles()?.sortedBy { it.name }?.forEach { f ->
            val info = runCatching { FontNameParser.parse(f.readBytes()) }.getOrNull() ?: return@forEach
            imported.getOrPut(info.family.lowercase()) { ArrayList() } += Entry(info, f)
        }
        generation++
    }

    /** Copy a font file into app storage. Returns its names, or null if it is not a readable font. */
    fun import(bytes: ByteArray, suggestedName: String): FontInfo? {
        val info = FontNameParser.parse(bytes) ?: return null
        val d = dir ?: return null
        d.mkdirs()
        val safe = (info.postScriptName ?: "${info.family}-${info.style}").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val ext = suggestedName.substringAfterLast('.', "ttf").lowercase().takeIf { it == "otf" || it == "ttf" || it == "ttc" } ?: "ttf"
        File(d, "$safe.$ext").writeBytes(bytes)
        reload()
        return info
    }

    val importedFamilies: List<String> get() = imported.values.map { it.first().info.family }.sorted()

    fun stylesFor(family: String?): List<String> {
        val list = imported[family?.lowercase()]
        return list?.map { it.info.style }?.distinct()
            ?: listOf("Regular", "Italic", "Bold", "Bold Italic", "Light", "Medium", "Semibold", "Black")
    }

    /** Families offered in the font menu: imported first, then Android's built-in families. */
    fun menuFamilies(docFamilies: Collection<String>): List<String> =
        (importedFamilies + SYSTEM_FAMILIES.keys + docFamilies).distinct()

    fun isAvailable(family: String?): Boolean =
        family == null || imported.containsKey(family.lowercase()) || SYSTEM_FAMILIES.containsKey(family)

    fun typeface(family: String?, style: String?): Typeface = cache.getOrPut(family to style) { resolve(family, style) }

    private fun resolve(family: String?, style: String?): Typeface {
        val s = (style ?: "Regular").lowercase()
        val weight = weightOf(s)
        val italic = s.contains("italic") || s.contains("oblique")
        imported[family?.lowercase()]?.let { entries ->
            val exact = entries.firstOrNull { it.info.style.equals(style, true) }
                ?: entries.minByOrNull { e ->
                    val es = e.info.style.lowercase()
                    Math.abs(weightOf(es) - weight) + if ((es.contains("italic") || es.contains("oblique")) != italic) 1000 else 0
                }
            if (exact != null) runCatching { return Typeface.createFromFile(exact.file) }
        }
        val generic = SYSTEM_FAMILIES[family] ?: classify(family)
        val base = Typeface.create(generic, Typeface.NORMAL)
        return Typeface.create(base, weight, italic)
    }

    companion object {
        /** Display names of fonts that ship with Android, mapped to their family aliases. */
        val SYSTEM_FAMILIES = linkedMapOf(
            "Roboto" to "sans-serif",
            "Roboto Condensed" to "sans-serif-condensed",
            "Noto Serif" to "serif",
            "Roboto Mono" to "monospace",
            "Cutive Mono" to "serif-monospace",
            "Coming Soon" to "casual",
            "Dancing Script" to "cursive",
            "Carrois Gothic SC" to "sans-serif-smallcaps",
        )

        private val SANS = Regex("sans|helvet|arial|myriad|futura|gill|frutiger|univers|inter|roboto|lato|montserrat|avenir|verdana|tahoma|segoe|gotham|proxima|din|grotesk|grotesque|calibri|source sans|nunito|poppins|raleway|ubuntu|noto sans|franklin|akzidenz|neue haas", RegexOption.IGNORE_CASE)
        private val MONO = Regex("mono|courier|consol|menlo|code|typewriter", RegexOption.IGNORE_CASE)
        private val SCRIPT = Regex("script|brush|hand|cursive|comic", RegexOption.IGNORE_CASE)

        fun classify(family: String?): String = when {
            family == null -> "serif"
            MONO.containsMatchIn(family) -> "monospace"
            SCRIPT.containsMatchIn(family) -> "cursive"
            SANS.containsMatchIn(family) -> "sans-serif"
            else -> "serif"
        }

        fun weightOf(style: String): Int = when {
            style.contains("thin") || style.contains("hairline") -> 100
            style.contains("extralight") || style.contains("extra light") || style.contains("ultralight") -> 200
            style.contains("semibold") || style.contains("semi bold") || style.contains("demibold") || style.contains("demi") -> 600
            style.contains("extrabold") || style.contains("extra bold") || style.contains("ultrabold") || style.contains("heavy") -> 800
            style.contains("black") -> 900
            style.contains("bold") -> 700
            style.contains("medium") -> 500
            style.contains("light") -> 300
            style.contains("book") -> 400
            else -> 400
        }

        fun boldVariant(style: String?): String {
            val s = style ?: "Regular"
            val italic = s.contains("Italic", true)
            val bold = weightOf(s.lowercase()) >= 700
            return when {
                bold && italic -> "Italic"
                bold -> "Regular"
                italic -> "Bold Italic"
                else -> "Bold"
            }
        }

        fun italicVariant(style: String?): String {
            val s = style ?: "Regular"
            val italic = s.contains("Italic", true) || s.contains("Oblique", true)
            return if (italic) s.replace("Italic", "", true).replace("Oblique", "", true).trim().ifEmpty { "Regular" }
            else if (s.equals("Regular", true) || s.equals("Roman", true) || s.equals("Book", true)) "Italic" else "$s Italic"
        }
    }
}
