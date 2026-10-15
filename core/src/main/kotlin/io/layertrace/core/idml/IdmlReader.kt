package io.layertrace.core.idml

import io.layertrace.core.geom.Matrix
import io.layertrace.core.geom.PathPoint
import io.layertrace.core.geom.Point
import io.layertrace.core.geom.Rect
import io.layertrace.core.geom.SubPath
import io.layertrace.core.model.Capitalization
import io.layertrace.core.model.CharFormat
import io.layertrace.core.model.CharacterStyle
import io.layertrace.core.model.ColorSpace
import io.layertrace.core.model.Document
import io.layertrace.core.model.DocumentSetup
import io.layertrace.core.model.GradientStop
import io.layertrace.core.model.Guide
import io.layertrace.core.model.GuideOrientation
import io.layertrace.core.model.Ids
import io.layertrace.core.model.Insets
import io.layertrace.core.model.ItemKind
import io.layertrace.core.model.Justification
import io.layertrace.core.model.Layer
import io.layertrace.core.model.LayerColor
import io.layertrace.core.model.Margins
import io.layertrace.core.model.Page
import io.layertrace.core.model.PageItem
import io.layertrace.core.model.Paragraph
import io.layertrace.core.model.ParagraphStyle
import io.layertrace.core.model.PlacedImage
import io.layertrace.core.model.Run
import io.layertrace.core.model.SourcePackage
import io.layertrace.core.model.Spread
import io.layertrace.core.model.Story
import io.layertrace.core.model.Swatch
import io.layertrace.core.model.SwatchKind
import io.layertrace.core.model.TextAttrs
import io.layertrace.core.text.OBJECT_CHAR
import io.layertrace.core.text.StoryEditing
import io.layertrace.core.xml.XmlContent
import io.layertrace.core.xml.XmlNode
import io.layertrace.core.xml.XmlParser
import io.layertrace.core.xml.XmlProcessingInstruction
import io.layertrace.core.xml.XmlText
import java.util.Base64

class IdmlFormatException(message: String) : Exception(message)

/** Reads an IDML package into a [Document]. */
object IdmlReader {

    fun read(bytes: ByteArray, name: String = "Untitled"): Document {
        val kind = InddDetector.sniff(bytes.copyOf(minOf(16, bytes.size)))
        if (kind == InddDetector.Kind.INDD) throw IdmlFormatException("This is a native InDesign (.indd) file. Save it as IDML in InDesign first.")
        if (kind != InddDetector.Kind.IDML) throw IdmlFormatException("Not an IDML package")
        return read(IdmlPackage.unzip(bytes), name)
    }

    fun read(entries: Map<String, ByteArray>, name: String): Document {
        val dmBytes = entries["designmap.xml"] ?: throw IdmlFormatException("designmap.xml missing — not an IDML package")
        val designmap = XmlParser.parse(dmBytes)
        val root = designmap.root
        val ctx = Ctx()

        fun file(src: String?): XmlNode? = src?.let { entries[it] }?.let { XmlParser.parse(it).root }
        fun includes(tag: String) = root.childrenNamed(tag).mapNotNull { it["src"] }

        // Swatches
        val graphic = includes("idPkg:Graphic").firstOrNull()?.let { file(it) }
        val swatches = graphic?.let { readSwatches(it) } ?: emptyList()

        // Styles
        val styles = includes("idPkg:Styles").firstOrNull()?.let { file(it) }
        val paraStyles = styles?.let { s -> s.findAll("ParagraphStyle").map { readParagraphStyle(it) } } ?: emptyList()
        val charStyles = styles?.let { s -> s.findAll("CharacterStyle").map { readCharacterStyle(it) } } ?: emptyList()

        // Preferences
        val prefs = includes("idPkg:Preferences").firstOrNull()?.let { file(it) }
        val setup = readSetup(prefs)

        // Layers
        val layers = root.childrenNamed("Layer").map { l ->
            Layer(
                id = l["Self"] ?: "",
                name = l["Name"] ?: "Layer",
                visible = l["Visible"] != "false",
                locked = l["Locked"] == "true",
                printable = l["Printable"] != "false",
                color = LayerColor.parse(l.child("Properties")?.child("LayerColor")?.textContent?.trim()),
                passthrough = l,
            )
        }.ifEmpty { listOf(Layer("ub3", "Layer 1")) }
        ctx.defaultLayer = layers.last().id

        val masters = includes("idPkg:MasterSpread").mapNotNull { file(it)?.child("MasterSpread") }.map { readSpread(it, true, ctx, setup) }
        val spreads = includes("idPkg:Spread").mapNotNull { file(it)?.child("Spread") }.map { readSpread(it, false, ctx, setup) }

        val stories = LinkedHashMap<String, Story>()
        for (src in includes("idPkg:Story")) {
            val bytes = entries[src] ?: continue
            val storyEl = XmlParser.parse(bytes).root.child("Story") ?: continue
            val story = readStory(storyEl, bytes)
            stories[story.id] = story
        }

        var maxId = 0x1000L
        fun scan(n: XmlNode) {
            n["Self"]?.let { s ->
                if (s.length in 2..14 && s[0] == 'u') s.substring(1).toLongOrNull(16)?.let { if (it >= maxId) maxId = it + 1 }
            }
            for (c in n.children) if (c is XmlNode) scan(c)
        }
        for ((path, data) in entries) if (path.endsWith(".xml")) runCatching { scan(XmlParser.parse(data).root) }

        return Document(
            name = name,
            setup = setup,
            layers = layers,
            spreads = spreads,
            masters = masters,
            stories = stories,
            swatches = swatches,
            paragraphStyles = paraStyles,
            characterStyles = charStyles,
            source = SourcePackage(entries, designmap),
            nextIdSeed = maxId + 0x10,
        )
    }

    private class Ctx {
        var defaultLayer: String = "ub3"
    }

    // ---- swatches -------------------------------------------------------------------------------

    fun readSwatches(g: XmlNode): List<Swatch> {
        val out = ArrayList<Swatch>()
        for (e in g.elements) {
            val id = e["Self"] ?: continue
            when (e.name) {
                "Color" -> {
                    val space = when (e["Space"]) { "RGB" -> ColorSpace.RGB; "LAB" -> ColorSpace.LAB; else -> ColorSpace.CMYK }
                    out += Swatch(
                        id = id,
                        name = e["Name"]?.takeUnless { it == "\$ID/" } ?: id.substringAfter('/'),
                        kind = SwatchKind.Color,
                        space = space,
                        values = numbers(e["ColorValue"]),
                        spot = e["Model"] == "Spot",
                        builtIn = id == Ids.COLOR_BLACK || id == Ids.COLOR_PAPER || id == Ids.COLOR_REGISTRATION,
                        passthrough = e,
                    )
                }
                "Tint" -> out += Swatch(
                    id = id, name = e["Name"] ?: id, kind = SwatchKind.Tint,
                    tint = e["TintValue"]?.toDoubleOrNull() ?: 100.0, baseId = e["BaseColor"], passthrough = e,
                )
                "Gradient" -> out += Swatch(
                    id = id, name = e["Name"] ?: id, kind = SwatchKind.Gradient,
                    stops = e.childrenNamed("GradientStop").map { GradientStop(it["StopColor"] ?: Ids.COLOR_BLACK, it["Location"]?.toDoubleOrNull() ?: 0.0) },
                    radial = e["Type"] == "Radial", passthrough = e,
                )
                "Swatch" -> if (id == Ids.SWATCH_NONE) out += Swatch(id, "None", SwatchKind.None, builtIn = true, passthrough = e)
            }
        }
        // Order like InDesign's panel: None, Registration, Paper, Black, then the rest.
        val order = listOf(Ids.SWATCH_NONE, Ids.COLOR_REGISTRATION, Ids.COLOR_PAPER, Ids.COLOR_BLACK)
        return out.sortedBy { s -> order.indexOf(s.id).let { if (it < 0) order.size else it } }
    }

    // ---- styles ---------------------------------------------------------------------------------

    private val STYLE_META = setOf("Self", "Name", "Imported", "NextParagraphStyle", "KeyboardShortcut", "SplitDocument", "EmitCss", "IncludeClass", "ExtendedKeyboardShortcut")

    fun readParagraphStyle(e: XmlNode): ParagraphStyle {
        val id = e["Self"] ?: ""
        return ParagraphStyle(
            id = id,
            name = styleName(e["Name"], id),
            basedOn = e.child("Properties")?.child("BasedOn")?.textContent?.trim()?.takeIf { it.isNotEmpty() && it != "\$ID/[No paragraph style]" },
            nextStyle = e["NextParagraphStyle"],
            attrs = readAttrs(e, STYLE_META, keepExtras = false),
            passthrough = e,
        )
    }

    fun readCharacterStyle(e: XmlNode): CharacterStyle {
        val id = e["Self"] ?: ""
        return CharacterStyle(
            id = id,
            name = styleName(e["Name"], id),
            basedOn = e.child("Properties")?.child("BasedOn")?.textContent?.trim()?.takeIf { it.isNotEmpty() && it != "\$ID/[No character style]" },
            attrs = readAttrs(e, STYLE_META, keepExtras = false),
            passthrough = e,
        )
    }

    private fun styleName(n: String?, id: String): String {
        val raw = n ?: id.substringAfter('/')
        return raw.removePrefix("\$ID/").let { if (it == "NormalParagraphStyle") "Basic Paragraph" else it }
    }

    /** Attribute names modelled by [TextAttrs]. */
    val TEXT_ATTRS = setOf(
        "FontStyle", "PointSize", "Tracking", "FillColor", "Underline", "StrikeThru", "Capitalization",
        "BaselineShift", "Justification", "LeftIndent", "RightIndent", "FirstLineIndent", "SpaceBefore", "SpaceAfter",
    )
    val TEXT_PROPS = setOf("AppliedFont", "Leading", "BasedOn")

    fun readAttrs(e: XmlNode, ignore: Set<String>, keepExtras: Boolean = true): TextAttrs {
        val props = e.child("Properties")
        val leadingNode = props?.child("Leading")
        val leading = leadingNode?.let { n ->
            val t = n.textContent.trim()
            if (t == "Auto" || n["type"] == "enumeration") TextAttrs.AUTO_LEADING else t.toDoubleOrNull()
        }
        val extras = if (keepExtras) e.attrs.filterKeys { it !in TEXT_ATTRS && it !in ignore } else emptyMap()
        val extraProps = if (keepExtras) props?.elements?.filter { it.name !in TEXT_PROPS } ?: emptyList() else emptyList()
        return TextAttrs(
            fontFamily = props?.child("AppliedFont")?.textContent?.trim()?.takeIf { it.isNotEmpty() },
            fontStyle = e["FontStyle"],
            pointSize = e["PointSize"]?.toDoubleOrNull(),
            leading = leading,
            tracking = e["Tracking"]?.toDoubleOrNull(),
            fillColor = e["FillColor"],
            underline = e["Underline"]?.toBooleanStrictOrNull(),
            strikeThrough = e["StrikeThru"]?.toBooleanStrictOrNull(),
            capitalization = Capitalization.parse(e["Capitalization"]),
            baselineShift = e["BaselineShift"]?.toDoubleOrNull(),
            justification = Justification.parse(e["Justification"]),
            leftIndent = e["LeftIndent"]?.toDoubleOrNull(),
            rightIndent = e["RightIndent"]?.toDoubleOrNull(),
            firstLineIndent = e["FirstLineIndent"]?.toDoubleOrNull(),
            spaceBefore = e["SpaceBefore"]?.toDoubleOrNull(),
            spaceAfter = e["SpaceAfter"]?.toDoubleOrNull(),
            extraAttrs = extras,
            extraProps = extraProps,
        )
    }

    // ---- preferences ----------------------------------------------------------------------------

    private fun readSetup(prefs: XmlNode?): DocumentSetup {
        val dp = prefs?.child("DocumentPreference")
        val mp = prefs?.child("MarginPreference")
        val d = DocumentSetup()
        return DocumentSetup(
            pageWidth = dp?.get("PageWidth")?.toDoubleOrNull() ?: d.pageWidth,
            pageHeight = dp?.get("PageHeight")?.toDoubleOrNull() ?: d.pageHeight,
            facingPages = dp?.get("FacingPages")?.let { it == "true" } ?: d.facingPages,
            bleedTop = dp?.get("DocumentBleedTopOffset")?.toDoubleOrNull() ?: 0.0,
            bleedBottom = dp?.get("DocumentBleedBottomOffset")?.toDoubleOrNull() ?: 0.0,
            bleedInside = dp?.get("DocumentBleedInsideOrLeftOffset")?.toDoubleOrNull() ?: 0.0,
            bleedOutside = dp?.get("DocumentBleedOutsideOrRightOffset")?.toDoubleOrNull() ?: 0.0,
            margins = mp?.let { readMargins(it) } ?: d.margins,
        )
    }

    fun readMargins(m: XmlNode) = Margins(
        top = m["Top"]?.toDoubleOrNull() ?: 36.0,
        bottom = m["Bottom"]?.toDoubleOrNull() ?: 36.0,
        left = m["Left"]?.toDoubleOrNull() ?: 36.0,
        right = m["Right"]?.toDoubleOrNull() ?: 36.0,
        columns = m["ColumnCount"]?.toIntOrNull() ?: 1,
        gutter = m["ColumnGutter"]?.toDoubleOrNull() ?: 12.0,
    )

    // ---- spreads --------------------------------------------------------------------------------

    private fun readSpread(e: XmlNode, master: Boolean, ctx: Ctx, setup: DocumentSetup): Spread {
        val pageEls = e.childrenNamed("Page")
        val pages = pageEls.map { p ->
            val gb = numbers(p["GeometricBounds"])
            val bounds = if (gb.size == 4) Rect(gb[1], gb[0], gb[3], gb[2]) else Rect(0.0, 0.0, setup.pageWidth, setup.pageHeight)
            Page(
                id = p["Self"] ?: "",
                name = p["Name"] ?: "",
                bounds = bounds,
                transform = Matrix.parse(p["ItemTransform"]) ?: Matrix.IDENTITY,
                appliedMasterId = p["AppliedMaster"]?.takeUnless { it == Ids.NONE },
                margins = p.child("MarginPreference")?.let { readMargins(it) } ?: setup.margins,
                guides = p.childrenNamed("Guide").mapNotNull { readGuide(it) },
                passthrough = p,
            )
        }.toMutableList()
        // Spread-level guides are attached to their page by PageIndex.
        for (g in e.childrenNamed("Guide")) {
            val guide = readGuide(g) ?: continue
            val idx = (g["PageIndex"]?.toIntOrNull() ?: 0).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            if (pages.isNotEmpty()) pages[idx] = pages[idx].copy(guides = pages[idx].guides + guide)
        }
        val items = e.elements.mapNotNull { readItem(it, ctx) }
        return Spread(
            id = e["Self"] ?: "",
            pages = pages,
            items = items,
            transform = Matrix.parse(e["ItemTransform"]) ?: Matrix.IDENTITY,
            masterPrefix = e["NamePrefix"] ?: "A",
            masterBaseName = e["BaseName"] ?: "Master",
            isMaster = master,
            passthrough = e,
        )
    }

    private fun readGuide(g: XmlNode): Guide? {
        val loc = g["Location"]?.toDoubleOrNull() ?: return null
        return Guide(
            id = g["Self"] ?: "",
            orientation = if (g["Orientation"] == "Vertical") GuideOrientation.Vertical else GuideOrientation.Horizontal,
            location = loc,
            passthrough = g,
        )
    }

    private val ITEM_ELEMENTS = setOf("Rectangle", "Oval", "Polygon", "GraphicLine", "TextFrame", "Group",
        "Button", "MultiStateObject", "TextBox", "CheckBox", "ComboBox", "ListBox", "RadioButton", "SignatureField",
        "EPSText", "FormField", "HtmlItem", "MediaItem")

    fun isPageItemElement(name: String) = name in ITEM_ELEMENTS

    private fun readItem(e: XmlNode, ctx: Ctx): PageItem? {
        if (e.name !in ITEM_ELEMENTS) return null
        val kind = ItemKind.forElement(e.name) ?: ItemKind.Unknown
        val paths = readPaths(e.child("Properties")?.child("PathGeometry"))
        val tfp = e.child("TextFramePreference")
        val inset = tfp?.child("Properties")?.child("InsetSpacing")?.let { ins ->
            val vals = ins.childrenNamed("ListItem").mapNotNull { it.textContent.trim().toDoubleOrNull() }
            when {
                vals.size == 4 -> Insets(vals[0], vals[1], vals[2], vals[3])
                else -> ins.textContent.trim().toDoubleOrNull()?.let { Insets.all(it) }
            }
        } ?: tfp?.get("InsetSpacing")?.let { s -> numbers(s).let { if (it.size == 4) Insets(it[0], it[1], it[2], it[3]) else it.firstOrNull()?.let { v -> Insets.all(v) } } }
        val corner = if ((e["TopLeftCornerOption"] ?: "None") != "None") e["TopLeftCornerRadius"]?.toDoubleOrNull() ?: 0.0 else 0.0
        val children = if (kind == ItemKind.Group) e.elements.mapNotNull { readItem(it, ctx) } else emptyList()
        val image = e.elements.firstOrNull { it.name in GRAPHIC_ELEMENTS }?.let { readImage(it) }
        return PageItem(
            id = e["Self"] ?: "",
            kind = kind,
            layerId = e["ItemLayer"] ?: ctx.defaultLayer,
            transform = Matrix.parse(e["ItemTransform"]) ?: Matrix.IDENTITY,
            paths = paths,
            fill = e["FillColor"]?.takeUnless { it == Ids.SWATCH_NONE },
            stroke = e["StrokeColor"]?.takeUnless { it == Ids.SWATCH_NONE },
            strokeWeight = e["StrokeWeight"]?.toDoubleOrNull() ?: if (e["StrokeColor"] != null && e["StrokeColor"] != Ids.SWATCH_NONE) 1.0 else 0.0,
            opacity = e.child("TransparencySetting")?.child("BlendingSetting")?.get("Opacity")?.toDoubleOrNull() ?: 100.0,
            cornerRadius = corner,
            name = e["Name"]?.takeUnless { it == "\$ID/" },
            locked = e["Locked"] == "true",
            visible = e["Visible"] != "false",
            storyId = e["ParentStory"],
            previousFrameId = e["PreviousTextFrame"]?.takeUnless { it == Ids.NONE },
            nextFrameId = e["NextTextFrame"]?.takeUnless { it == Ids.NONE },
            columns = tfp?.get("TextColumnCount")?.toIntOrNull() ?: 1,
            columnGutter = tfp?.get("TextColumnGutter")?.toDoubleOrNull() ?: 12.0,
            inset = inset ?: Insets.ZERO,
            image = image,
            children = children,
            passthrough = e,
        )
    }

    val GRAPHIC_ELEMENTS = setOf("Image", "EPS", "PDF", "PICT", "WMF", "ImportedPage", "SVG")

    private fun readImage(e: XmlNode): PlacedImage {
        val props = e.child("Properties")
        val gb = props?.child("GraphicBounds")
        val bounds = if (gb != null) Rect(
            gb["Left"]?.toDoubleOrNull() ?: 0.0, gb["Top"]?.toDoubleOrNull() ?: 0.0,
            gb["Right"]?.toDoubleOrNull() ?: 0.0, gb["Bottom"]?.toDoubleOrNull() ?: 0.0,
        ) else Rect(0.0, 0.0, 0.0, 0.0)
        val data = if (e.name == "Image") props?.child("Contents")?.textContent?.let { b64 ->
            runCatching { Base64.getMimeDecoder().decode(b64.trim()) }.getOrNull()
        } else null
        return PlacedImage(
            id = e["Self"] ?: "",
            transform = Matrix.parse(e["ItemTransform"]) ?: Matrix.IDENTITY,
            bounds = bounds,
            data = data,
            linkUri = e.child("Link")?.get("LinkResourceURI"),
            passthrough = e,
        )
    }

    fun readPaths(pg: XmlNode?): List<SubPath> {
        if (pg == null) return emptyList()
        return pg.childrenNamed("GeometryPathType").map { gpt ->
            val pts = gpt.child("PathPointArray")?.childrenNamed("PathPointType")?.map { pp ->
                val a = point(pp["Anchor"]) ?: Point.ZERO
                PathPoint(a, point(pp["LeftDirection"]) ?: a, point(pp["RightDirection"]) ?: a)
            } ?: emptyList()
            SubPath(pts, open = gpt["PathOpen"] == "true")
        }
    }

    // ---- stories --------------------------------------------------------------------------------

    private val TRANSPARENT_WRAPPERS = setOf("HyperlinkTextSource", "XMLElement", "Change", "HyperlinkTextDestination")

    fun readStory(storyEl: XmlNode, originalBytes: ByteArray?): Story {
        val paragraphs = ArrayList<Paragraph>()
        var curStyle = Ids.PARA_BASIC
        var curOverrides = TextAttrs.EMPTY
        var runs = ArrayList<Run>()
        var lastFormat = CharFormat()
        var fresh = true

        fun endParagraph() {
            paragraphs += Paragraph(curStyle, curOverrides, StoryEditing.normalize(runs, lastFormat))
            runs = ArrayList()
            fresh = true
        }

        fun content(children: List<XmlContent>, fmt: CharFormat) {
            for (c in children) when (c) {
                is XmlText -> if (c.text.isNotEmpty()) runs += Run(c.text, fmt)
                is XmlProcessingInstruction -> runs += Run(OBJECT_CHAR.toString(), fmt, c)
                else -> {}
            }
        }

        fun charChildren(children: List<XmlContent>, fmt: CharFormat) {
            for (c in children) {
                if (c !is XmlNode) continue
                when (c.name) {
                    "Properties" -> {}
                    "Content" -> { content(c.children, fmt); fresh = false }
                    "Br" -> endParagraph()
                    in TRANSPARENT_WRAPPERS -> charChildren(c.children, fmt)
                    else -> { runs += Run(OBJECT_CHAR.toString(), fmt, c); fresh = false }
                }
            }
        }

        fun paraRange(psr: XmlNode) {
            val style = psr["AppliedParagraphStyle"] ?: Ids.PARA_BASIC
            val ov = readAttrs(psr, setOf("AppliedParagraphStyle")).paragraphOnlyWithExtras()
            if (fresh || runs.isEmpty()) { curStyle = style; curOverrides = ov }
            for (c in psr.elements) {
                when (c.name) {
                    "CharacterStyleRange" -> {
                        val cattrs = readAttrs(c, setOf("AppliedCharacterStyle"))
                        val fmt = CharFormat(c["AppliedCharacterStyle"] ?: Ids.CHAR_NONE, cattrs)
                        lastFormat = fmt
                        if (fresh || runs.isEmpty()) { curStyle = style; curOverrides = ov }
                        charChildren(c.children, fmt)
                    }
                    "Properties" -> {}
                    "Br" -> endParagraph()
                    "Content" -> content(c.children, lastFormat)
                    in TRANSPARENT_WRAPPERS -> c.elements.filter { it.name == "ParagraphStyleRange" || it.name == "CharacterStyleRange" }.let { inner ->
                        if (inner.any { it.name == "CharacterStyleRange" }) charChildren(listOf(c), lastFormat) else inner.forEach { paraRange(it) }
                    }
                    else -> runs += Run(OBJECT_CHAR.toString(), lastFormat, c)
                }
            }
        }

        fun storyChildren(nodes: List<XmlNode>) {
            for (c in nodes) when (c.name) {
                "ParagraphStyleRange" -> paraRange(c)
                "CharacterStyleRange" -> paraRange(XmlNode("ParagraphStyleRange", mapOf("AppliedParagraphStyle" to Ids.PARA_BASIC), listOf(c)))
                in TRANSPARENT_WRAPPERS -> storyChildren(c.elements)
                else -> {}
            }
        }
        storyChildren(storyEl.elements)
        endParagraph()
        return Story(
            id = storyEl["Self"] ?: "",
            paragraphs = paragraphs,
            passthrough = storyEl,
            originalBytes = originalBytes,
            edited = false,
        )
    }

    /** Paragraph ranges carry paragraph-level overrides plus any other attributes verbatim. */
    private fun TextAttrs.paragraphOnlyWithExtras() = this

    // ---- helpers --------------------------------------------------------------------------------

    fun numbers(s: String?): List<Double> =
        s?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toDoubleOrNull() } ?: emptyList()

    private fun point(s: String?): Point? = numbers(s).let { if (it.size >= 2) Point(it[0], it[1]) else null }
}
