package io.layertrace.core.idml

import io.layertrace.core.geom.Rect
import io.layertrace.core.geom.SubPath
import io.layertrace.core.geom.fmt
import io.layertrace.core.model.CharFormat
import io.layertrace.core.model.CharacterStyle
import io.layertrace.core.model.ColorSpace
import io.layertrace.core.model.Document
import io.layertrace.core.model.Guide
import io.layertrace.core.model.GuideOrientation
import io.layertrace.core.model.Ids
import io.layertrace.core.model.Insets
import io.layertrace.core.model.ItemKind
import io.layertrace.core.model.Layer
import io.layertrace.core.model.Margins
import io.layertrace.core.model.Page
import io.layertrace.core.model.PageItem
import io.layertrace.core.model.Paragraph
import io.layertrace.core.model.ParagraphStyle
import io.layertrace.core.model.PlacedImage
import io.layertrace.core.model.Spread
import io.layertrace.core.model.Story
import io.layertrace.core.model.Swatch
import io.layertrace.core.model.SwatchKind
import io.layertrace.core.model.TextAttrs
import io.layertrace.core.xml.XmlContent
import io.layertrace.core.xml.XmlDocument
import io.layertrace.core.xml.XmlNode
import io.layertrace.core.xml.XmlParser
import io.layertrace.core.xml.XmlProcessingInstruction
import io.layertrace.core.xml.XmlText
import io.layertrace.core.xml.XmlWriter
import java.util.Base64

/**
 * Writes a [Document] as an IDML package. Modelled content is merged into the original package
 * (or [IdmlTemplate] for new documents) so unmodelled features survive a round trip.
 */
object IdmlWriter {
    private const val NS = IdmlTemplate.NS
    private val XML_DECL = XmlProcessingInstruction("xml", """version="1.0" encoding="UTF-8" standalone="yes"""")

    fun write(doc: Document): ByteArray = IdmlPackage.zip(entries(doc))

    fun entries(doc: Document): LinkedHashMap<String, ByteArray> {
        val src = doc.source ?: IdmlTemplate.sourcePackage()
        val srcEntries = src.entries
        val dmRoot = src.designmap.root
        val domVersion = dmRoot["DOMVersion"] ?: IdmlTemplate.DOM_VERSION

        val regenerated = HashSet<String>()
        for (tag in listOf("idPkg:MasterSpread", "idPkg:Spread", "idPkg:Story")) dmRoot.childrenNamed(tag).mapNotNull { it["src"] }.forEach { regenerated += it }

        val out = LinkedHashMap<String, ByteArray>()
        out["mimetype"] = IdmlPackage.MIMETYPE.toByteArray()
        out["META-INF/container.xml"] = srcEntries["META-INF/container.xml"] ?: IdmlTemplate.container.toByteArray()
        out["designmap.xml"] = ByteArray(0) // placeholder to keep order

        fun include(tag: String) = dmRoot.childrenNamed(tag).firstOrNull()?.get("src")

        val graphicPath = include("idPkg:Graphic") ?: "Resources/Graphic.xml"
        val stylesPath = include("idPkg:Styles") ?: "Resources/Styles.xml"
        val prefsPath = include("idPkg:Preferences") ?: "Resources/Preferences.xml"

        out[graphicPath] = mergeGraphic(srcEntries[graphicPath] ?: IdmlTemplate.graphic.toByteArray(), doc)
        out[stylesPath] = mergeStyles(srcEntries[stylesPath] ?: IdmlTemplate.styles.toByteArray(), doc)
        out[prefsPath] = mergePreferences(srcEntries[prefsPath] ?: IdmlTemplate.preferences.toByteArray(), doc)
        for (fixed in listOf("Resources/Fonts.xml", "XML/BackingStory.xml", "XML/Tags.xml")) {
            if (fixed !in srcEntries && dmRoot.children.none { it is XmlNode && it["src"] == fixed }) continue
            out[fixed] = srcEntries[fixed] ?: IdmlTemplate.entries()[fixed]!!
        }

        val masterPaths = doc.masters.map { m ->
            val path = "MasterSpreads/MasterSpread_${m.id}.xml"
            out[path] = pkgFile("idPkg:MasterSpread", domVersion, spreadXml(m, doc))
            path
        }
        val spreadPaths = doc.spreads.map { s ->
            val path = "Spreads/Spread_${s.id}.xml"
            out[path] = pkgFile("idPkg:Spread", domVersion, spreadXml(s, doc))
            path
        }
        val storyPaths = doc.stories.values.map { st ->
            val path = "Stories/Story_${st.id}.xml"
            out[path] = if (!st.edited && st.originalBytes != null) st.originalBytes else pkgFile("idPkg:Story", domVersion, storyXml(st))
            path
        }

        // Everything else is copied verbatim.
        for ((path, data) in srcEntries) {
            if (path in out || path in regenerated || path == "mimetype") continue
            out[path] = data
        }

        out["designmap.xml"] = XmlWriter.toBytes(
            XmlDocument(src.designmap.prolog, designmap(dmRoot, doc, masterPaths, spreadPaths, storyPaths))
        )
        return out
    }

    private fun pkgFile(tag: String, domVersion: String, content: XmlNode): ByteArray =
        XmlWriter.toBytes(
            XmlDocument(listOf(XML_DECL), XmlNode(tag, linkedMapOf("xmlns:idPkg" to NS, "DOMVersion" to domVersion), listOf(content)))
        )

    // ---- designmap ------------------------------------------------------------------------------

    private fun designmap(root: XmlNode, doc: Document, masters: List<String>, spreads: List<String>, stories: List<String>): XmlNode {
        val groups = setOf("Layer", "idPkg:MasterSpread", "idPkg:Spread", "idPkg:Story")
        val swatchIds = doc.swatches.map { it.id }.toSet()
        val list = ArrayList<XmlContent>()
        for (c in root.children) {
            if (c is XmlNode && c.name in groups) continue
            if (c is XmlNode && c.name == "ColorGroup") {
                list += c.copy(children = c.children.filterNot { it is XmlNode && it.name == "ColorGroupSwatch" && it["SwatchItemRef"] !in swatchIds })
                continue
            }
            list += c
        }

        fun insertBefore(nodes: List<XmlNode>, before: Set<String>, after: String? = null) {
            if (nodes.isEmpty()) return
            var idx = if (after != null) list.indexOfLast { it is XmlNode && it.name == after }.let { if (it >= 0) it + 1 else -1 } else -1
            if (idx < 0) idx = list.indexOfFirst { it is XmlNode && it.name in before }
            if (idx < 0) idx = list.size
            list.addAll(idx, nodes)
        }
        insertBefore(doc.layers.map { layerXml(it) }, setOf("idPkg:MasterSpread", "idPkg:Spread", "Section", "DocumentUser", "idPkg:BackingStory", "idPkg:Story", "idPkg:Tags"))
        insertBefore(masters.map { XmlNode("idPkg:MasterSpread", mapOf("src" to it)) }, setOf("idPkg:Spread", "Section", "DocumentUser", "idPkg:BackingStory", "idPkg:Story", "idPkg:Tags"), after = "Layer")
        insertBefore(spreads.map { XmlNode("idPkg:Spread", mapOf("src" to it)) }, setOf("Section", "DocumentUser", "idPkg:BackingStory", "idPkg:Story", "idPkg:Tags"), after = "idPkg:MasterSpread")
        insertBefore(stories.map { XmlNode("idPkg:Story", mapOf("src" to it)) }, setOf("idPkg:Tags", "ColorGroup"), after = "idPkg:BackingStory")

        fixSections(list, doc)

        // StoryList: keep ids of stories that are not story files (e.g. the backing story), then ours.
        val fileStoryIds = root.childrenNamed("idPkg:Story").mapNotNull { it["src"]?.substringAfterLast("Story_")?.removeSuffix(".xml") }.toSet()
        val keep = (root["StoryList"] ?: "").split(' ').filter { it.isNotBlank() && it !in fileStoryIds && it !in doc.stories }
        val storyList = (keep + doc.stories.keys).joinToString(" ")
        val activeLayer = root["ActiveLayer"]?.takeIf { id -> doc.layers.any { it.id == id } } ?: doc.layers.firstOrNull()?.id ?: "ub3"
        return root.withAttrs(mapOf("StoryList" to storyList, "ActiveLayer" to activeLayer, "Name" to "${doc.name}.indd"))
            .copy(children = list)
    }

    private fun fixSections(list: MutableList<XmlContent>, doc: Document) {
        val pageIds = doc.pages.map { it.id }
        if (pageIds.isEmpty()) return
        val indices = list.indices.filter { (list[it] as? XmlNode)?.name == "Section" }
        val valid = indices.map { it to list[it] as XmlNode }.filter { it.second["PageStart"] in pageIds }
            .sortedBy { pageIds.indexOf(it.second["PageStart"]) }.toMutableList()
        val firstIdx = indices.firstOrNull() ?: list.size
        if (valid.isEmpty() || valid[0].second["PageStart"] != pageIds[0]) {
            val template = (indices.firstOrNull()?.let { list[it] as XmlNode }) ?: XmlParser.parse(IdmlTemplate.designmap).root.child("Section")!!
            valid.add(0, -1 to template.withAttr("PageStart", pageIds[0]))
        }
        val rebuilt = valid.mapIndexed { i, (_, s) ->
            val start = pageIds.indexOf(s["PageStart"])
            val next = if (i + 1 < valid.size) pageIds.indexOf(valid[i + 1].second["PageStart"]) else pageIds.size
            s.withAttrs(mapOf("Length" to (next - start).toString(), "AlternateLayoutLength" to (next - start).toString()))
        }
        for (i in indices.sortedDescending()) list.removeAt(i)
        list.addAll(minOf(firstIdx, list.size), rebuilt)
    }

    private fun layerXml(l: Layer): XmlNode {
        val base = l.passthrough ?: XmlNode(
            "Layer",
            linkedMapOf("IgnoreWrap" to "false", "ShowGuides" to "true", "LockGuides" to "false", "UI" to "true", "Expendable" to "true"),
        )
        val props = (base.child("Properties") ?: XmlNode("Properties"))
            .withChild("LayerColor", XmlNode("LayerColor", mapOf("type" to "enumeration"), listOf(XmlText(l.color.name))))
        return base.withAttrs(
            linkedMapOf(
                "Self" to l.id, "Name" to l.name, "Visible" to l.visible.toString(),
                "Locked" to l.locked.toString(), "Printable" to l.printable.toString(),
            )
        ).withChild("Properties", props, 0)
    }

    // ---- resources ------------------------------------------------------------------------------

    private fun mergeGraphic(bytes: ByteArray, doc: Document): ByteArray {
        val x = XmlParser.parse(bytes)
        val swatchTags = setOf("Color", "Tint", "Gradient", "Swatch")
        val children = x.root.children
        val firstIdx = children.indexOfFirst { it is XmlNode && it.name in swatchTags }.let { if (it < 0) children.size else it }
        val kept = children.filterNot { it is XmlNode && it.name in swatchTags }.toMutableList()
        // Index in the filtered list where the first swatch used to be.
        val insertAt = children.subList(0, firstIdx).count { !(it is XmlNode && it.name in swatchTags) }
        val ordered = doc.swatches.filter { it.kind == SwatchKind.Color } + doc.swatches.filter { it.kind == SwatchKind.None } +
            doc.swatches.filter { it.kind == SwatchKind.Tint } + doc.swatches.filter { it.kind == SwatchKind.Gradient }
        kept.addAll(insertAt, ordered.map { swatchXml(it) })
        return XmlWriter.toBytes(x.copy(root = x.root.copy(children = kept)))
    }

    private fun swatchXml(s: Swatch): XmlNode = when (s.kind) {
        SwatchKind.None -> s.passthrough ?: XmlNode(
            "Swatch",
            linkedMapOf("Self" to Ids.SWATCH_NONE, "Name" to "None", "ColorEditable" to "false", "ColorRemovable" to "false", "Visible" to "true", "SwatchCreatorID" to "7937"),
        )
        SwatchKind.Color -> {
            val base = s.passthrough ?: XmlNode(
                "Color",
                linkedMapOf(
                    "Self" to s.id, "Model" to "Process", "Space" to "CMYK", "ColorValue" to "", "ColorOverride" to "Normal",
                    "AlternateSpace" to "NoAlternateColor", "AlternateColorValue" to "", "Name" to s.name,
                    "ColorEditable" to "true", "ColorRemovable" to "true", "Visible" to "true", "SwatchCreatorID" to "7937",
                ),
            )
            val model = when {
                base["Model"] == "Registration" -> "Registration"
                s.spot -> "Spot"
                else -> "Process"
            }
            base.withAttrs(
                linkedMapOf(
                    "Self" to s.id, "Model" to model,
                    "Space" to when (s.space) { ColorSpace.RGB -> "RGB"; ColorSpace.LAB -> "LAB"; ColorSpace.CMYK -> "CMYK" },
                    "ColorValue" to s.values.joinToString(" ") { fmt(it) },
                    "Name" to s.name,
                )
            )
        }
        SwatchKind.Tint -> (s.passthrough ?: XmlNode("Tint", linkedMapOf("Self" to s.id, "Model" to "Process", "Name" to "\$ID/", "ColorEditable" to "true", "ColorRemovable" to "true", "Visible" to "true", "SwatchCreatorID" to "7937")))
            .withAttrs(linkedMapOf("Self" to s.id, "BaseColor" to s.baseId, "TintValue" to fmt(s.tint)))
        SwatchKind.Gradient -> s.passthrough ?: XmlNode(
            "Gradient",
            linkedMapOf("Self" to s.id, "Type" to if (s.radial) "Radial" else "Linear", "Name" to s.name, "ColorEditable" to "true", "ColorRemovable" to "true", "Visible" to "true", "SwatchCreatorID" to "7937"),
            s.stops.mapIndexed { i, st ->
                XmlNode("GradientStop", linkedMapOf("Self" to "${s.id}GradientStop$i", "StopColor" to st.swatchId, "Location" to fmt(st.location), "Midpoint" to "50"))
            },
        )
    }

    private fun mergeStyles(bytes: ByteArray, doc: Document): ByteArray {
        val x = XmlParser.parse(bytes)
        val paras = doc.paragraphStyles.associateBy { it.id }
        val chars = doc.characterStyles.associateBy { it.id }
        val written = HashSet<String>()
        fun walk(n: XmlNode): XmlNode = n.copy(children = n.children.mapNotNull { c ->
            if (c !is XmlNode) c
            else when (c.name) {
                "ParagraphStyle" -> paras[c["Self"]]?.let { written += it.id; paragraphStyleXml(it) }
                "CharacterStyle" -> chars[c["Self"]]?.let { written += it.id; characterStyleXml(it) }
                else -> walk(c)
            }
        })
        var root = walk(x.root)
        fun appendTo(group: String, nodes: List<XmlNode>) {
            if (nodes.isEmpty()) return
            root = root.copy(children = root.children.map { c ->
                if (c is XmlNode && c.name == group) c.copy(children = c.children + nodes) else c
            })
            if (root.children.none { it is XmlNode && it.name == group }) {
                root = root.copy(children = listOf(XmlNode(group, mapOf("Self" to "u${group.hashCode().toUInt().toString(16)}"), nodes)) + root.children)
            }
        }
        appendTo("RootParagraphStyleGroup", doc.paragraphStyles.filter { it.id !in written }.map { paragraphStyleXml(it) })
        appendTo("RootCharacterStyleGroup", doc.characterStyles.filter { it.id !in written }.map { characterStyleXml(it) })
        return XmlWriter.toBytes(x.copy(root = root))
    }

    private fun styleIdName(id: String) = id.substringAfter('/')

    private fun paragraphStyleXml(s: ParagraphStyle): XmlNode {
        val base = s.passthrough ?: XmlNode("ParagraphStyle", linkedMapOf("Self" to s.id, "Name" to s.name, "Imported" to "false"))
        val isBuiltIn = s.id.startsWith("ParagraphStyle/\$ID/")
        var n = base.withAttrs(
            linkedMapOf(
                "Self" to s.id,
                "Name" to if (isBuiltIn) base["Name"] ?: styleIdName(s.id) else s.name,
                "NextParagraphStyle" to (s.nextStyle ?: base["NextParagraphStyle"] ?: s.id),
            )
        )
        n = applyTextAttrs(n, s.attrs, keepExtras = false)
        if (s.id != Ids.PARA_NONE) n = setBasedOn(n, s.basedOn, "\$ID/[No paragraph style]")
        return n
    }

    private fun characterStyleXml(s: CharacterStyle): XmlNode {
        val base = s.passthrough ?: XmlNode("CharacterStyle", linkedMapOf("Self" to s.id, "Imported" to "false", "Name" to s.name))
        val isBuiltIn = s.id.startsWith("CharacterStyle/\$ID/")
        var n = base.withAttrs(linkedMapOf("Self" to s.id, "Name" to if (isBuiltIn) base["Name"] ?: styleIdName(s.id) else s.name))
        n = applyTextAttrs(n, s.attrs, keepExtras = false)
        if (s.id != Ids.CHAR_NONE) n = setBasedOn(n, s.basedOn, "\$ID/[No character style]")
        return n
    }

    private fun setBasedOn(n: XmlNode, basedOn: String?, rootName: String): XmlNode {
        val props = n.child("Properties") ?: XmlNode("Properties")
        val node = if (basedOn == null) XmlNode("BasedOn", mapOf("type" to "string"), listOf(XmlText(rootName)))
        else XmlNode("BasedOn", mapOf("type" to "object"), listOf(XmlText(basedOn)))
        return n.withChild("Properties", props.withChild("BasedOn", node, 0), 0)
    }

    /** Writes modelled [TextAttrs] onto an element (style or range). */
    fun applyTextAttrs(node: XmlNode, a: TextAttrs, keepExtras: Boolean): XmlNode {
        val attrs = LinkedHashMap<String, String>()
        for ((k, v) in node.attrs) if (k !in IdmlReader.TEXT_ATTRS) attrs[k] = v
        if (keepExtras) attrs.putAll(a.extraAttrs)
        a.fontStyle?.let { attrs["FontStyle"] = it }
        a.pointSize?.let { attrs["PointSize"] = fmt(it) }
        a.tracking?.let { attrs["Tracking"] = fmt(it) }
        a.fillColor?.let { attrs["FillColor"] = it }
        a.underline?.let { attrs["Underline"] = it.toString() }
        a.strikeThrough?.let { attrs["StrikeThru"] = it.toString() }
        a.capitalization?.let { attrs["Capitalization"] = it.idml }
        a.baselineShift?.let { attrs["BaselineShift"] = fmt(it) }
        a.justification?.let { attrs["Justification"] = it.idml }
        a.leftIndent?.let { attrs["LeftIndent"] = fmt(it) }
        a.rightIndent?.let { attrs["RightIndent"] = fmt(it) }
        a.firstLineIndent?.let { attrs["FirstLineIndent"] = fmt(it) }
        a.spaceBefore?.let { attrs["SpaceBefore"] = fmt(it) }
        a.spaceAfter?.let { attrs["SpaceAfter"] = fmt(it) }

        val oldProps = node.child("Properties")
        val propChildren = ArrayList<XmlContent>()
        oldProps?.children?.forEach { c ->
            if (c is XmlNode && (c.name == "AppliedFont" || c.name == "Leading")) return@forEach
            if (keepExtras && c is XmlNode && a.extraProps.any { it.name == c.name }) return@forEach
            propChildren += c
        }
        if (keepExtras) propChildren += a.extraProps
        a.fontFamily?.let { propChildren += XmlNode("AppliedFont", mapOf("type" to "string"), listOf(XmlText(it))) }
        a.leading?.let { l ->
            propChildren += if (l < 0) XmlNode("Leading", mapOf("type" to "enumeration"), listOf(XmlText("Auto")))
            else XmlNode("Leading", mapOf("type" to "unit"), listOf(XmlText(fmt(l))))
        }
        val children = node.children.filterNot { it is XmlNode && it.name == "Properties" }.toMutableList()
        if (propChildren.isNotEmpty()) children.add(0, XmlNode("Properties", oldProps?.attrs ?: emptyMap(), propChildren))
        return node.copy(attrs = attrs, children = children)
    }

    private fun mergePreferences(bytes: ByteArray, doc: Document): ByteArray {
        val x = XmlParser.parse(bytes)
        val s = doc.setup
        val root = x.root.copy(children = x.root.children.map { c ->
            if (c !is XmlNode) c else when (c.name) {
                "DocumentPreference" -> c.withAttrs(
                    linkedMapOf(
                        "PageWidth" to fmt(s.pageWidth), "PageHeight" to fmt(s.pageHeight),
                        "PagesPerDocument" to doc.pageCount.coerceAtLeast(1).toString(),
                        "FacingPages" to s.facingPages.toString(),
                        "DocumentBleedTopOffset" to fmt(s.bleedTop), "DocumentBleedBottomOffset" to fmt(s.bleedBottom),
                        "DocumentBleedInsideOrLeftOffset" to fmt(s.bleedInside), "DocumentBleedOutsideOrRightOffset" to fmt(s.bleedOutside),
                    )
                )
                "MarginPreference" -> marginXml(c, s.margins, s.pageWidth)
                else -> c
            }
        })
        return XmlWriter.toBytes(x.copy(root = root))
    }

    private fun marginXml(base: XmlNode, m: Margins, pageWidth: Double): XmlNode {
        val inner = pageWidth - m.left - m.right
        val n = m.columns.coerceAtLeast(1)
        val w = (inner - m.gutter * (n - 1)) / n
        val positions = buildList {
            add(0.0)
            for (i in 0 until n) {
                if (i > 0) add(i * (w + m.gutter))
                add(i * (w + m.gutter) + w)
            }
        }
        return base.withAttrs(
            linkedMapOf(
                "ColumnCount" to n.toString(), "ColumnGutter" to fmt(m.gutter),
                "Top" to fmt(m.top), "Bottom" to fmt(m.bottom), "Left" to fmt(m.left), "Right" to fmt(m.right),
                "ColumnDirection" to (base["ColumnDirection"] ?: "Horizontal"),
                "ColumnsPositions" to positions.joinToString(" ") { fmt(it) },
            )
        )
    }

    // ---- spreads --------------------------------------------------------------------------------

    fun spreadXml(s: Spread, doc: Document): XmlNode {
        val tag = if (s.isMaster) "MasterSpread" else "Spread"
        val base = s.passthrough ?: XmlNode(
            tag,
            if (s.isMaster) linkedMapOf("OverriddenPageItemProps" to "", "PrimaryTextFrame" to "n", "ShowMasterItems" to "true", "PageColor" to "UseMasterColor")
            else linkedMapOf("FlattenerOverride" to "Default", "ShowMasterItems" to "true", "AllowPageShuffle" to "true", "PageTransitionType" to "None"),
        )
        val leftPages = s.pages.count { it.boundsInSpread.right <= 1e-3 }
        val attrs = linkedMapOf<String, String?>(
            "Self" to s.id,
            "ItemTransform" to s.transform.toIdml(),
            "PageCount" to s.pages.size.toString(),
            "BindingLocation" to leftPages.toString(),
        )
        if (s.isMaster) {
            attrs["NamePrefix"] = s.masterPrefix
            attrs["BaseName"] = s.masterBaseName
            attrs["Name"] = s.masterName
        }
        val keepers = base.children.filterNot { it is XmlNode && (it.name == "Page" || it.name == "Guide" || IdmlReader.isPageItemElement(it.name)) }
        val pages = s.pages.map { pageXml(it, doc) }
        val guides = s.pages.flatMapIndexed { i, p -> p.guides.map { guideXml(it, i, s) } }
        val items = s.items.map { itemXml(it) }
        return base.withAttrs(attrs).copy(children = keepers + pages + items + guides)
    }

    private fun pageXml(p: Page, doc: Document): XmlNode {
        val base = p.passthrough ?: XmlNode(
            "Page",
            linkedMapOf("OverrideList" to "", "TabOrder" to "", "GridStartingPoint" to "TopOutside", "UseMasterGrid" to "true"),
        )
        val margin = marginXml(base.child("MarginPreference") ?: XmlNode("MarginPreference"), p.margins, p.bounds.width)
        val b = p.bounds
        return base.withAttrs(
            linkedMapOf(
                "Self" to p.id,
                "Name" to p.name,
                "AppliedMaster" to (p.appliedMasterId ?: Ids.NONE),
                "ItemTransform" to p.transform.toIdml(),
                "GeometricBounds" to "${fmt(b.top)} ${fmt(b.left)} ${fmt(b.bottom)} ${fmt(b.right)}",
            )
        ).withoutChildren("Guide").withChild("MarginPreference", margin)
    }

    private fun guideXml(g: Guide, pageIndex: Int, s: Spread): XmlNode {
        val base = g.passthrough ?: XmlNode(
            "Guide",
            linkedMapOf(
                "OverriddenPageItemProps" to "", "FitToPage" to "true", "ViewThreshold" to "0", "Locked" to "false",
                "GuideType" to "Ruler", "GuideZone" to "0", "Name" to "\$ID/",
            ),
        )
        return base.withAttrs(
            linkedMapOf(
                "Self" to g.id,
                "Orientation" to if (g.orientation == GuideOrientation.Vertical) "Vertical" else "Horizontal",
                "Location" to fmt(g.location),
                "PageIndex" to pageIndex.toString(),
                "ItemLayer" to (base["ItemLayer"] ?: s.items.firstOrNull()?.layerId ?: "ub3"),
            )
        )
    }

    fun itemXml(item: PageItem): XmlNode {
        if (item.kind == ItemKind.Unknown && item.passthrough != null) {
            return item.passthrough.withAttrs(
                linkedMapOf("ItemTransform" to item.transform.toIdml(), "ItemLayer" to item.layerId, "Locked" to item.locked.toString(), "Visible" to item.visible.toString())
            )
        }
        val base = item.passthrough ?: XmlNode(item.kind.element, newItemDefaults(item))
        val attrs = linkedMapOf<String, String?>(
            "Self" to item.id,
            "ItemLayer" to item.layerId,
            "ItemTransform" to item.transform.toIdml(),
            "Locked" to item.locked.toString(),
            "Visible" to item.visible.toString(),
            "Name" to (item.name ?: "\$ID/"),
        )
        if (item.kind != ItemKind.Group) {
            attrs["FillColor"] = item.fill ?: Ids.SWATCH_NONE
            attrs["StrokeColor"] = item.stroke ?: Ids.SWATCH_NONE
            attrs["StrokeWeight"] = fmt(item.strokeWeight)
        }
        when (item.kind) {
            ItemKind.TextFrame -> {
                attrs["ParentStory"] = item.storyId
                attrs["PreviousTextFrame"] = item.previousFrameId ?: Ids.NONE
                attrs["NextTextFrame"] = item.nextFrameId ?: Ids.NONE
                attrs["ContentType"] = "TextType"
            }
            ItemKind.Rectangle, ItemKind.Oval, ItemKind.Polygon ->
                if (item.image != null) attrs["ContentType"] = "GraphicType"
            else -> {}
        }
        if (item.kind != ItemKind.Group && item.kind != ItemKind.GraphicLine) {
            val hadCorners = (base["TopLeftCornerOption"] ?: "None") != "None"
            if (item.cornerRadius > 0 || hadCorners) {
                val opt = if (item.cornerRadius > 0) "RoundedCorner" else "None"
                for (c in listOf("TopLeft", "TopRight", "BottomLeft", "BottomRight")) {
                    attrs["${c}CornerOption"] = opt
                    attrs["${c}CornerRadius"] = fmt(item.cornerRadius.coerceAtLeast(0.0))
                }
            }
        }

        val out = ArrayList<XmlContent>()
        var propsDone = false; var transDone = false; var tfpDone = false
        val props = (base.child("Properties") ?: XmlNode("Properties")).let {
            if (item.kind == ItemKind.Group) it else it.withChild("PathGeometry", pathGeometry(item.paths))
        }
        for (c in base.children) {
            if (c !is XmlNode) { out += c; continue }
            when {
                c.name == "Properties" -> { out += props; propsDone = true }
                c.name == "TransparencySetting" -> { out += transparency(c, item.opacity); transDone = true }
                c.name == "TextFramePreference" && item.kind == ItemKind.TextFrame -> { out += textFramePref(c, item); tfpDone = true }
                IdmlReader.isPageItemElement(c.name) -> {}
                c.name in IdmlReader.GRAPHIC_ELEMENTS -> {}
                else -> out += c
            }
        }
        if (!propsDone && (props.children.isNotEmpty())) out.add(0, props)
        if (!transDone && item.opacity < 100.0) out.add(if (out.firstOrNull() is XmlNode && (out[0] as XmlNode).name == "Properties") 1 else 0, transparency(null, item.opacity))
        if (!tfpDone && item.kind == ItemKind.TextFrame) out += textFramePref(null, item)
        item.image?.let { out += imageXml(it) }
        if (item.kind == ItemKind.Group) item.children.forEach { out += itemXml(it) }
        return base.withAttrs(attrs).copy(children = out)
    }

    private fun newItemDefaults(item: PageItem): LinkedHashMap<String, String> = when (item.kind) {
        ItemKind.TextFrame -> linkedMapOf("ContentType" to "TextType", "AppliedObjectStyle" to "ObjectStyle/\$ID/[Normal Text Frame]")
        ItemKind.Group -> linkedMapOf("AppliedObjectStyle" to "ObjectStyle/\$ID/[None]")
        else -> linkedMapOf("ContentType" to "Unassigned", "AppliedObjectStyle" to "ObjectStyle/\$ID/[Normal Graphics Frame]")
    }

    private fun transparency(base: XmlNode?, opacity: Double): XmlNode {
        val b = base ?: XmlNode("TransparencySetting")
        val bs = (b.child("BlendingSetting") ?: XmlNode("BlendingSetting")).withAttr("Opacity", fmt(opacity))
        return b.withChild("BlendingSetting", bs, 0)
    }

    private fun textFramePref(base: XmlNode?, item: PageItem): XmlNode {
        var b = (base ?: XmlNode("TextFramePreference")).withAttrs(
            linkedMapOf("TextColumnCount" to item.columns.toString(), "TextColumnGutter" to fmt(item.columnGutter))
        )
        val oldProps = b.child("Properties")
        if (item.inset != Insets.ZERO || oldProps?.child("InsetSpacing") != null) {
            val i = item.inset
            val ins = XmlNode(
                "InsetSpacing", mapOf("type" to "list"),
                listOf(i.top, i.left, i.bottom, i.right).map { XmlNode("ListItem", mapOf("type" to "unit"), listOf(XmlText(fmt(it)))) },
            )
            b = b.withChild("Properties", (oldProps ?: XmlNode("Properties")).withChild("InsetSpacing", ins), 0)
        }
        return b
    }

    private fun imageXml(img: PlacedImage): XmlNode {
        val base = img.passthrough ?: XmlNode("Image", linkedMapOf("Space" to "\$ID/#Links_RGB", "ActualPpi" to "72 72", "EffectivePpi" to "72 72", "ImageRenderingIntent" to "UseColorSettings", "LocalDisplaySetting" to "Default", "ImageTypeName" to "\$ID/PNG", "AppliedObjectStyle" to "ObjectStyle/\$ID/[None]", "Visible" to "true", "Name" to "\$ID/"))
        var props = base.child("Properties") ?: XmlNode("Properties")
        val b = img.bounds
        props = props.withChild(
            "GraphicBounds",
            XmlNode("GraphicBounds", linkedMapOf("Left" to fmt(b.left), "Top" to fmt(b.top), "Right" to fmt(b.right), "Bottom" to fmt(b.bottom))),
        )
        if (img.passthrough == null && img.data != null) {
            props = props.withChild("Contents", XmlNode("Contents", emptyMap(), listOf(XmlText(Base64.getEncoder().encodeToString(img.data)))))
        }
        var n = base.withAttrs(linkedMapOf("Self" to img.id, "ItemTransform" to img.transform.toIdml())).withChild("Properties", props, 0)
        if (n.child("Link") == null) {
            val isPng = img.data != null && img.data.size > 4 && img.data[1] == 'P'.code.toByte() && img.data[2] == 'N'.code.toByte()
            n = n.withChild(
                "Link",
                XmlNode(
                    "Link",
                    linkedMapOf(
                        "Self" to "${img.id}L", "AssetURL" to "\$ID/", "AssetID" to "\$ID/",
                        "LinkResourceURI" to (img.linkUri ?: "file:embedded.${if (isPng) "png" else "jpg"}"),
                        "LinkResourceFormat" to if (isPng) "\$ID/Portable Network Graphics (PNG)" else "\$ID/JPEG",
                        "StoredState" to "Embedded", "LinkClassID" to "35906", "LinkClientID" to "257",
                        "LinkResourceModified" to "false", "LinkObjectModified" to "false", "ShowInUI" to "true",
                        "CanEmbed" to "true", "CanUnembed" to "true", "CanPackage" to "true",
                        "ImportPolicy" to "NoAutoImport", "ExportPolicy" to "NoAutoExport",
                        "LinkImportStamp" to "", "LinkImportModificationTime" to "", "LinkImportTime" to "",
                        "LinkResourceSize" to "0~${Integer.toHexString(img.data?.size ?: 0)}",
                    ),
                ),
            )
        }
        return n
    }

    fun pathGeometry(paths: List<SubPath>): XmlNode = XmlNode(
        "PathGeometry", emptyMap(),
        paths.map { sp ->
            XmlNode(
                "GeometryPathType", mapOf("PathOpen" to sp.open.toString()),
                listOf(
                    XmlNode(
                        "PathPointArray", emptyMap(),
                        sp.points.map { p ->
                            XmlNode(
                                "PathPointType",
                                linkedMapOf(
                                    "Anchor" to "${fmt(p.anchor.x)} ${fmt(p.anchor.y)}",
                                    "LeftDirection" to "${fmt(p.left.x)} ${fmt(p.left.y)}",
                                    "RightDirection" to "${fmt(p.right.x)} ${fmt(p.right.y)}",
                                ),
                            )
                        },
                    )
                ),
            )
        },
    )

    // ---- stories --------------------------------------------------------------------------------

    fun storyXml(st: Story): XmlNode {
        val base = st.passthrough ?: XmlNode(
            "Story",
            linkedMapOf("AppliedTOCStyle" to "n", "TrackChanges" to "false", "StoryTitle" to "\$ID/", "AppliedNamedGrid" to "n"),
            listOf(
                XmlNode("StoryPreference", linkedMapOf("OpticalMarginAlignment" to "false", "OpticalMarginSize" to "12", "FrameType" to "TextFrameType", "StoryOrientation" to "Horizontal", "StoryDirection" to "LeftToRightDirection")),
                XmlNode("InCopyExportOption", linkedMapOf("IncludeGraphicProxies" to "true", "IncludeAllResources" to "false")),
            ),
        )
        val keep = base.children.filter { it is XmlNode && (it.name == "StoryPreference" || it.name == "InCopyExportOption") }
        val ranges = st.paragraphs.mapIndexed { i, p -> paragraphXml(p, isLast = i == st.paragraphs.lastIndex) }
        return base.withAttr("Self", st.id).copy(children = keep + ranges)
    }

    private fun paragraphXml(p: Paragraph, isLast: Boolean): XmlNode {
        val psr = applyTextAttrs(XmlNode("ParagraphStyleRange", linkedMapOf("AppliedParagraphStyle" to p.styleId)), p.overrides, keepExtras = true)
        // Group consecutive runs with the same format into one CharacterStyleRange.
        val groups = ArrayList<Pair<CharFormat, MutableList<io.layertrace.core.model.Run>>>()
        for (r in p.runs) {
            val last = groups.lastOrNull()
            if (last != null && last.first == r.format) last.second += r else groups += r.format to mutableListOf(r)
        }
        if (groups.isEmpty()) groups += CharFormat() to mutableListOf()
        val csrs = groups.mapIndexed { gi, (fmt, runs) ->
            val children = ArrayList<XmlContent>()
            var content = ArrayList<XmlContent>()
            fun flush() {
                if (content.isNotEmpty()) children += XmlNode("Content", emptyMap(), content)
                content = ArrayList()
            }
            for (r in runs) {
                when (val inl = r.inline) {
                    null -> if (r.text.isNotEmpty()) {
                        // Line separators and other text stay literal; Content cannot be empty.
                        val last = content.lastOrNull()
                        if (last is XmlText) content[content.size - 1] = XmlText(last.text + r.text) else content += XmlText(r.text)
                    }
                    is XmlProcessingInstruction -> content += inl
                    is XmlNode -> { flush(); children += inl }
                    else -> {}
                }
            }
            flush()
            if (gi == groups.lastIndex && !isLast) children += XmlNode("Br")
            applyTextAttrs(XmlNode("CharacterStyleRange", linkedMapOf("AppliedCharacterStyle" to fmt.styleId)), fmt.overrides, keepExtras = true)
                .let { it.copy(children = it.children + children) }
        }
        return psr.copy(children = psr.children + csrs)
    }

    @Suppress("unused")
    private fun Rect.idmlBounds() = "${fmt(top)} ${fmt(left)} ${fmt(bottom)} ${fmt(right)}"
}
