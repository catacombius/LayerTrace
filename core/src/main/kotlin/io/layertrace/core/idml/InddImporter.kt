package io.layertrace.core.idml

import io.layertrace.core.edit.DocumentFactory
import io.layertrace.core.geom.Matrix
import io.layertrace.core.geom.Rect
import io.layertrace.core.geom.Shapes
import io.layertrace.core.model.ColorSpace
import io.layertrace.core.model.Document
import io.layertrace.core.model.ItemKind
import io.layertrace.core.model.Layer
import io.layertrace.core.model.LayerColor
import io.layertrace.core.model.PageItem
import io.layertrace.core.model.PlacedImage
import io.layertrace.core.model.Swatch
import io.layertrace.core.xml.XmlNode
import io.layertrace.core.xml.XmlParser
import java.io.InputStream
import java.util.Base64

/**
 * Best-effort import of a native InDesign (.indd) file.
 *
 * The .indd database format is proprietary and undocumented, so page content (frames, text)
 * cannot be read. InDesign does embed an XMP metadata packet, though, which carries the page
 * count and size, fonts, swatches, linked files and usually a JPEG preview of the first page.
 * This builds a new document from that: matching pages, the document's swatches, and the
 * preview on a locked, non-printing reference layer to rebuild the layout over.
 */
object InddImporter {

    data class Result(
        val document: Document,
        val pageCount: Int,
        val pageWidth: Double,
        val pageHeight: Double,
        val hasPreview: Boolean,
        val fonts: List<String>,
        val links: List<String>,
        val swatches: List<String>,
        /** False when the file had no readable XMP (defaults were used). */
        val hadMetadata: Boolean,
    )

    const val PREVIEW_LAYER = "INDD preview (reference)"
    private const val MAX_PACKET = 32 * 1024 * 1024

    fun import(input: InputStream, name: String): Result {
        val xmp = extractXmp(input)
        val root = xmp?.let { runCatching { XmlParser.parse(it).root }.getOrNull() }
        return build(root, name)
    }

    /** The last complete `x:xmpmeta` packet in the stream (the most recent save), or null. */
    fun extractXmp(input: InputStream): String? {
        val start = "<x:xmpmeta".toByteArray(Charsets.ISO_8859_1)
        val end = "</x:xmpmeta>".toByteArray(Charsets.ISO_8859_1)
        val buf = ByteArray(1 shl 16)
        var last: ByteArray? = null
        var capture: java.io.ByteArrayOutputStream? = null
        var si = 0 // matched prefix of [start]
        var ei = 0 // matched prefix of [end]
        val src = input.buffered()
        while (true) {
            val n = src.read(buf)
            if (n < 0) break
            for (i in 0 until n) {
                val b = buf[i]
                val cap = capture
                if (cap == null) {
                    si = if (b == start[si]) si + 1 else if (b == start[0]) 1 else 0
                    if (si == start.size) {
                        capture = java.io.ByteArrayOutputStream().apply { write(start) }
                        si = 0; ei = 0
                    }
                } else {
                    cap.write(b.toInt())
                    ei = if (b == end[ei]) ei + 1 else if (b == end[0]) 1 else 0
                    if (ei == end.size) {
                        last = cap.toByteArray(); capture = null; ei = 0
                    } else if (cap.size() > MAX_PACKET) {
                        capture = null; ei = 0
                    }
                }
            }
        }
        return last?.toString(Charsets.UTF_8)
    }

    private fun local(n: String) = n.substringAfter(':')

    private fun XmlNode.desc(localName: String): List<XmlNode> {
        val out = mutableListOf<XmlNode>()
        fun walk(n: XmlNode) {
            for (c in n.elements) {
                if (local(c.name) == localName) out += c
                walk(c)
            }
        }
        walk(this)
        return out
    }

    /** A simple property written either as an attribute or as a child element. */
    private fun XmlNode.prop(localName: String): String? {
        attrs.entries.firstOrNull { local(it.key) == localName }?.let { return it.value.trim() }
        return elements.firstOrNull { local(it.name) == localName }?.textContent?.trim()
    }

    /** Items of an rdf:Bag/Seq/Alt property, or the property itself when it is a single struct. */
    private fun items(prop: XmlNode): List<XmlNode> {
        val li = prop.desc("li")
        return li.ifEmpty { listOf(prop) }
    }

    private fun toPoints(v: Double, unit: String?): Double = when (unit?.lowercase()) {
        "inches", "inch", "in" -> v * 72
        "millimeters", "mm" -> v * 72 / 25.4
        "centimeters", "cm" -> v * 72 / 2.54
        "picas" -> v * 12
        else -> v // Points, Pixels
    }

    fun build(xmp: XmlNode?, name: String): Result {
        val baseName = name.substringBeforeLast('.').ifEmpty { "Imported" }
        var pages = 1
        var w = DocumentFactory.presets[0].widthPt
        var h = DocumentFactory.presets[0].heightPt
        val fonts = mutableListOf<String>()
        val links = mutableListOf<String>()
        val colorants = mutableListOf<Swatch>()
        var thumb: ByteArray? = null

        if (xmp != null) {
            xmp.desc("NPages").firstOrNull()?.textContent?.trim()?.toIntOrNull()?.let { pages = it.coerceIn(1, 999) }
            xmp.desc("MaxPageSize").firstOrNull()?.let { m ->
                val pw = m.prop("w")?.toDoubleOrNull()
                val ph = m.prop("h")?.toDoubleOrNull()
                val unit = m.prop("unit")
                if (pw != null && ph != null && pw > 0 && ph > 0) { w = toPoints(pw, unit); h = toPoints(ph, unit) }
            }
            xmp.desc("Fonts").firstOrNull()?.let { f ->
                for (it in items(f)) (it.prop("fontName") ?: listOfNotNull(it.prop("fontFamily"), it.prop("fontFace")).joinToString(" ").ifEmpty { null })
                    ?.let { n -> if (n !in fonts) fonts += n }
            }
            xmp.desc("Manifest").firstOrNull()?.let { m ->
                for (it in items(m)) {
                    val ref = it.desc("reference").firstOrNull() ?: it
                    (ref.prop("filePath") ?: ref.prop("lastURL"))?.let { p -> if (p.isNotBlank() && p !in links) links += p }
                }
            }
            xmp.desc("Colorants").firstOrNull()?.let { c -> for (it in items(c)) colorantSwatch(it)?.let { s -> colorants += s } }
            xmp.desc("Thumbnails").firstOrNull()?.let { t ->
                val li = items(t).firstOrNull { (it.prop("format") ?: "JPEG").equals("JPEG", true) }
                val data = li?.prop("image")?.replace("&#xA;", "")?.filterNot { it.isWhitespace() }
                if (!data.isNullOrEmpty()) thumb = runCatching { Base64.getMimeDecoder().decode(data) }.getOrNull()
            }
        }

        var doc = DocumentFactory.create(
            DocumentFactory.Params(name = "$baseName.idml", width = w, height = h, pages = pages, facing = pages > 1),
        )
        // Swatches the document defines (built-ins like Black/Paper already exist).
        val known = doc.swatches.map { it.name.lowercase() }.toMutableSet()
        val added = mutableListOf<String>()
        for (s in colorants) if (known.add(s.name.lowercase())) { doc = doc.copy(swatches = doc.swatches + s); added += s.name }

        val image = thumb
        val size = image?.let { ImageSize.of(it) }
        if (image != null && size != null) {
            val (layerId, d1) = doc.newId(); doc = d1
            val layer = Layer(layerId, PREVIEW_LAYER, locked = true, printable = false, color = LayerColor.Red)
            doc = doc.copy(layers = listOf(layer) + doc.layers)
            val spread = doc.spreads.first()
            val page = spread.pages.first()
            val pr = page.boundsInSpread
            val (ids, d2) = doc.newIds(2); doc = d2
            val ib = Rect(0.0, 0.0, size.first.toDouble(), size.second.toDouble())
            // The preview shows the whole page, so stretch it to the page rather than fitting.
            val img = PlacedImage(ids[0], Matrix.rectToRect(ib, pr), ib, image, linkUri = "file:$baseName-preview.jpg")
            val frame = PageItem(ids[1], ItemKind.Rectangle, layerId, paths = Shapes.rect(pr), image = img)
            doc = doc.copy(spreads = doc.spreads.map { if (it.id == spread.id) it.copy(items = it.items + frame) else it })
        }
        return Result(doc, pages, w, h, image != null && size != null, fonts, links, added, xmp != null)
    }

    private fun colorantSwatch(n: XmlNode): Swatch? {
        val name = n.prop("swatchName")?.takeIf { it.isNotBlank() && !it.startsWith("[") } ?: return null
        val spot = n.prop("type").equals("SPOT", true)
        fun d(k: String) = n.prop(k)?.toDoubleOrNull()
        return when (n.prop("mode")?.uppercase()) {
            "CMYK" -> Swatch("Color/$name", name, space = ColorSpace.CMYK,
                values = listOf(d("cyan") ?: 0.0, d("magenta") ?: 0.0, d("yellow") ?: 0.0, d("black") ?: 0.0), spot = spot)
            "RGB" -> Swatch("Color/$name", name, space = ColorSpace.RGB,
                values = listOf(d("red") ?: 0.0, d("green") ?: 0.0, d("blue") ?: 0.0), spot = spot)
            "LAB" -> Swatch("Color/$name", name, space = ColorSpace.LAB,
                values = listOf(d("L") ?: 0.0, d("A") ?: 0.0, d("B") ?: 0.0), spot = spot)
            else -> null
        }
    }
}

/** Pixel size of a JPEG or PNG without decoding it (pure JVM, usable from core). */
object ImageSize {
    fun of(b: ByteArray): Pair<Int, Int>? {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (b.size > 24 && u8(0) == 0x89 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()) {
            val w = (u16(16) shl 16) or u16(18); val h = (u16(20) shl 16) or u16(22)
            return if (w > 0 && h > 0) w to h else null
        }
        if (b.size > 4 && u8(0) == 0xFF && u8(1) == 0xD8) {
            var i = 2
            while (i + 9 < b.size) {
                if (u8(i) != 0xFF) { i++; continue }
                val marker = u8(i + 1)
                if (marker == 0xFF) { i++; continue }
                if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
                val len = u16(i + 2)
                // SOF0..SOF15 except DHT (C4), JPG (C8), DAC (CC).
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    val h = u16(i + 5); val w = u16(i + 7)
                    return if (w > 0 && h > 0) w to h else null
                }
                i += 2 + len
            }
        }
        return null
    }
}
