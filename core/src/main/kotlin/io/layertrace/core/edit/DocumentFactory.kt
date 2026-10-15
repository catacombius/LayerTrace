package io.layertrace.core.edit

import io.layertrace.core.geom.Matrix
import io.layertrace.core.geom.Rect
import io.layertrace.core.idml.IdmlReader
import io.layertrace.core.idml.IdmlTemplate
import io.layertrace.core.model.Document
import io.layertrace.core.model.DocumentSetup
import io.layertrace.core.model.Layer
import io.layertrace.core.model.Margins
import io.layertrace.core.model.Page
import io.layertrace.core.model.Spread
import io.layertrace.core.xml.XmlParser

data class PagePreset(val name: String, val widthPt: Double, val heightPt: Double)

object DocumentFactory {
    private const val MM = 72.0 / 25.4

    val presets = listOf(
        PagePreset("A4", 210 * MM, 297 * MM),
        PagePreset("A5", 148 * MM, 210 * MM),
        PagePreset("A3", 297 * MM, 420 * MM),
        PagePreset("Letter", 612.0, 792.0),
        PagePreset("Legal", 612.0, 1008.0),
        PagePreset("Tabloid", 792.0, 1224.0),
        PagePreset("Business card (85×55 mm)", 85 * MM, 55 * MM),
        PagePreset("Instagram post (1080 px)", 1080.0, 1080.0),
        PagePreset("Slide 16:9 (1920 px)", 1920.0, 1080.0),
    )

    data class Params(
        val name: String = "Untitled",
        val width: Double = presets[0].widthPt,
        val height: Double = presets[0].heightPt,
        val pages: Int = 1,
        val facing: Boolean = true,
        val margins: Margins = Margins(36.0, 36.0, 36.0, 36.0, 1, 12.0),
        val bleed: Double = 0.0,
    )

    fun create(p: Params): Document {
        val src = IdmlTemplate.sourcePackage()
        val swatches = IdmlReader.readSwatches(XmlParser.parse(IdmlTemplate.graphic).root)
        val styles = XmlParser.parse(IdmlTemplate.styles).root
        val setup = DocumentSetup(
            pageWidth = p.width, pageHeight = p.height, facingPages = p.facing,
            bleedTop = p.bleed, bleedBottom = p.bleed, bleedInside = p.bleed, bleedOutside = p.bleed,
            margins = p.margins,
        )
        var doc = Document(
            name = p.name,
            setup = setup,
            layers = listOf(Layer("ub3", "Layer 1")),
            swatches = swatches,
            paragraphStyles = styles.findAll("ParagraphStyle").map { IdmlReader.readParagraphStyle(it) },
            characterStyles = styles.findAll("CharacterStyle").map { IdmlReader.readCharacterStyle(it) },
            source = src,
            nextIdSeed = 0x1000,
        )
        val bounds = Rect(0.0, 0.0, p.width, p.height)
        // Master spread
        val masterPageCount = if (p.facing) 2 else 1
        val (mids, d1) = doc.newIds(masterPageCount + 1)
        doc = d1
        val mt = Pagination.pageTransforms(masterPageCount, p.facing, p.width, p.height, true)
        val master = Spread(
            id = mids[0],
            pages = (0 until masterPageCount).map { i -> Page(mids[i + 1], "A", bounds, mt[i], null, p.margins) },
            isMaster = true, masterPrefix = "A", masterBaseName = "Master",
        )
        doc = doc.copy(masters = listOf(master))
        val (pids, d2) = doc.newIds(p.pages.coerceAtLeast(1))
        doc = d2
        val pages = pids.map { Page(it, "", bounds, Matrix.IDENTITY, master.id, p.margins) }
        return Pagination.repaginate(doc, pages)
    }
}
