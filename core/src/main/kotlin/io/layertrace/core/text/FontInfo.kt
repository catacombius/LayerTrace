package io.layertrace.core.text

/** Family/style names read from a TrueType/OpenType font's `name` table. */
data class FontInfo(val family: String, val style: String, val fullName: String?, val postScriptName: String?)

object FontNameParser {

    fun parse(bytes: ByteArray): FontInfo? = runCatching { parseUnsafe(bytes) }.getOrNull()

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()

    private fun parseUnsafe(b: ByteArray): FontInfo? {
        var base = 0
        if (b.size > 12 && String(b, 0, 4, Charsets.US_ASCII) == "ttcf") base = u32(b, 12).toInt()
        val numTables = u16(b, base + 4)
        var nameOff = -1
        for (i in 0 until numTables) {
            val rec = base + 12 + i * 16
            if (String(b, rec, 4, Charsets.US_ASCII) == "name") { nameOff = u32(b, rec + 8).toInt(); break }
        }
        if (nameOff < 0) return null
        val count = u16(b, nameOff + 2)
        val strOff = nameOff + u16(b, nameOff + 4)
        val names = HashMap<Int, String>()
        val prio = HashMap<Int, Int>()
        for (i in 0 until count) {
            val r = nameOff + 6 + i * 12
            val platform = u16(b, r); val enc = u16(b, r + 2); val lang = u16(b, r + 4)
            val nameId = u16(b, r + 6); val len = u16(b, r + 8); val off = u16(b, r + 10)
            if (nameId !in setOf(1, 2, 4, 6, 16, 17)) continue
            val start = strOff + off
            if (start + len > b.size) continue
            val (text, p) = when {
                platform == 3 && (enc == 1 || enc == 10) -> String(b, start, len, Charsets.UTF_16BE) to (if (lang == 0x409) 3 else 2)
                platform == 0 -> String(b, start, len, Charsets.UTF_16BE) to 2
                platform == 1 && enc == 0 -> String(b, start, len, Charsets.ISO_8859_1) to 1
                else -> continue
            }
            if (p > (prio[nameId] ?: 0)) { names[nameId] = text; prio[nameId] = p }
        }
        val family = names[16] ?: names[1] ?: return null
        val style = names[17] ?: names[2] ?: "Regular"
        return FontInfo(family.trim(), style.trim(), names[4], names[6])
    }
}

object Placeholder {
    const val LOREM = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. " +
        "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. " +
        "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. " +
        "Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum."

    fun text(paragraphs: Int = 3) = List(paragraphs) { LOREM }.joinToString("\n")
}
