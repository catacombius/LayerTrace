package io.layertrace.core.model

import io.layertrace.core.geom.Matrix
import io.layertrace.core.geom.Point
import io.layertrace.core.geom.Rect
import io.layertrace.core.geom.SubPath
import io.layertrace.core.geom.anchorBounds
import io.layertrace.core.xml.XmlContent
import io.layertrace.core.xml.XmlNode

/**
 * In-memory model of an InDesign document. Everything is immutable; edits produce a new [Document].
 * Coordinates are PostScript points. Item coordinates follow IDML: path anchors live in the item's
 * inner space and [PageItem.transform] maps them to the parent (spread or group) space.
 */
data class Document(
    val name: String = "Untitled",
    val setup: DocumentSetup = DocumentSetup(),
    val layers: List<Layer> = emptyList(),
    val spreads: List<Spread> = emptyList(),
    val masters: List<Spread> = emptyList(),
    val stories: Map<String, Story> = emptyMap(),
    val swatches: List<Swatch> = emptyList(),
    val paragraphStyles: List<ParagraphStyle> = emptyList(),
    val characterStyles: List<CharacterStyle> = emptyList(),
    /** Original package for round-tripping unmodelled content; null for documents never saved as IDML. */
    val source: SourcePackage? = null,
    val nextIdSeed: Long = 0x1000,
) {
    val pages: List<Page> get() = spreads.flatMap { it.pages }
    val pageCount: Int get() = spreads.sumOf { it.pages.size }

    fun layer(id: String?) = layers.firstOrNull { it.id == id }
    fun swatch(id: String?) = swatches.firstOrNull { it.id == id }
    fun paragraphStyle(id: String?) = paragraphStyles.firstOrNull { it.id == id }
    fun characterStyle(id: String?) = characterStyles.firstOrNull { it.id == id }
    fun master(id: String?) = masters.firstOrNull { it.id == id }

    fun spread(id: String): Spread? = spreads.firstOrNull { it.id == id } ?: masters.firstOrNull { it.id == id }

    /** Allocate [count] fresh unique Self ids. Returns the ids and the updated document. */
    fun newIds(count: Int): Pair<List<String>, Document> {
        val ids = (0 until count).map { "u" + java.lang.Long.toHexString(nextIdSeed + it) }
        return ids to copy(nextIdSeed = nextIdSeed + count)
    }

    fun newId(): Pair<String, Document> = newIds(1).let { it.first[0] to it.second }
}

data class DocumentSetup(
    val pageWidth: Double = 595.275590551181,
    val pageHeight: Double = 841.889763779528,
    val facingPages: Boolean = true,
    val bleedTop: Double = 0.0,
    val bleedBottom: Double = 0.0,
    val bleedInside: Double = 0.0,
    val bleedOutside: Double = 0.0,
    val margins: Margins = Margins(),
)

data class Margins(
    val top: Double = 36.0,
    val bottom: Double = 36.0,
    val left: Double = 36.0,
    val right: Double = 36.0,
    val columns: Int = 1,
    val gutter: Double = 12.0,
)

data class Layer(
    val id: String,
    val name: String,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val printable: Boolean = true,
    val color: LayerColor = LayerColor.LightBlue,
    val passthrough: XmlNode? = null,
)

/** InDesign UI colours (a subset of the UIColors enumeration) with sRGB approximations. */
enum class LayerColor(val argb: Long) {
    LightBlue(0xFF4F99FF), Red(0xFFFF0000), Green(0xFF4FFF4F), Blue(0xFF0000FF), Yellow(0xFFFFFF4F),
    Magenta(0xFFFF4FFF), Cyan(0xFF00FFFF), Gray(0xFF808080), Black(0xFF000000), Orange(0xFFFF6600),
    DarkGreen(0xFF005400), Teal(0xFF008080), Tan(0xFFCCAA77), Brown(0xFF993300), Violet(0xFF9933FF),
    Gold(0xFFFFCC00), DarkBlue(0xFF000088), Pink(0xFFFF99CC), Lavender(0xFF9999FF), BrickRed(0xFF990000),
    OliveGreen(0xFF666600), Peach(0xFFFF9999), Burgundy(0xFF990033), GrassGreen(0xFF99CC00),
    Ochre(0xFF996600), Purple(0xFF660066), LightGray(0xFFBABABA), Charcoal(0xFF333333), GridBlue(0xFF0066CC),
    GridOrange(0xFFFF9933), Fiesta(0xFFF65C44), LightOlive(0xFF8C9E3C), Lipstick(0xFFCF3C6D),
    Cute_Teal(0xFF40C0C0), Sulphur(0xFFCCCC33), GridGreen(0xFF66CC33), White(0xFFFFFFFF);

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: LightBlue
        val rotation = listOf(LightBlue, Red, Green, Blue, Yellow, Magenta, Cyan, Gray, Orange, DarkGreen, Teal, Violet, Gold)
    }
}

data class Spread(
    val id: String,
    val pages: List<Page>,
    val items: List<PageItem> = emptyList(),
    val transform: Matrix = Matrix.IDENTITY,
    /** Master spreads only. */
    val masterPrefix: String = "A",
    val masterBaseName: String = "Master",
    val isMaster: Boolean = false,
    val passthrough: XmlNode? = null,
) {
    val masterName get() = "$masterPrefix-$masterBaseName"

    /** Union of page rectangles in spread coordinates. */
    val pageBounds: Rect get() = Rect.unionAll(pages.map { it.boundsInSpread }) ?: Rect(0.0, 0.0, 1.0, 1.0)
}

data class Page(
    val id: String,
    val name: String = "",
    /** Page rectangle in its own coordinates (normally 0,0,w,h). */
    val bounds: Rect,
    /** Page coordinates -> spread coordinates. */
    val transform: Matrix,
    val appliedMasterId: String? = null,
    val margins: Margins = Margins(),
    val guides: List<Guide> = emptyList(),
    val passthrough: XmlNode? = null,
) {
    val boundsInSpread: Rect get() = transform.mapRect(bounds)

    val marginRect: Rect
        get() = Rect(bounds.left + margins.left, bounds.top + margins.top, bounds.right - margins.right, bounds.bottom - margins.bottom)

    /** Column rectangles in page coordinates. */
    val columnRects: List<Rect>
        get() {
            val m = marginRect
            val n = margins.columns.coerceAtLeast(1)
            val w = (m.width - margins.gutter * (n - 1)) / n
            return (0 until n).map { i ->
                val l = m.left + i * (w + margins.gutter)
                Rect(l, m.top, l + w, m.bottom)
            }
        }
}

enum class GuideOrientation { Horizontal, Vertical }

/** Ruler guide. [location] is in page coordinates (x for vertical, y for horizontal). */
data class Guide(
    val id: String,
    val orientation: GuideOrientation,
    val location: Double,
    val passthrough: XmlNode? = null,
)

enum class ItemKind(val element: String) {
    Rectangle("Rectangle"), Oval("Oval"), Polygon("Polygon"), GraphicLine("GraphicLine"),
    TextFrame("TextFrame"), Group("Group"), Unknown("");

    companion object {
        fun forElement(name: String) = entries.firstOrNull { it.element == name && it != Unknown }
    }
}

data class PageItem(
    val id: String,
    val kind: ItemKind,
    val layerId: String,
    val transform: Matrix = Matrix.IDENTITY,
    val paths: List<SubPath> = emptyList(),
    val fill: String? = null,
    val stroke: String? = null,
    val strokeWeight: Double = 0.0,
    val opacity: Double = 100.0,
    val cornerRadius: Double = 0.0,
    val name: String? = null,
    val locked: Boolean = false,
    val visible: Boolean = true,
    // Text frames
    val storyId: String? = null,
    val previousFrameId: String? = null,
    val nextFrameId: String? = null,
    val columns: Int = 1,
    val columnGutter: Double = 12.0,
    val inset: Insets = Insets.ZERO,
    // Graphic frames
    val image: PlacedImage? = null,
    // Groups
    val children: List<PageItem> = emptyList(),
    /** Original element for round-tripping unmodelled attributes and children. */
    val passthrough: XmlNode? = null,
) {
    /** Bounds of the path anchors in inner coordinates. */
    val innerBounds: Rect
        get() = when (kind) {
            ItemKind.Group -> Rect.unionAll(children.map { it.boundsIn(Matrix.IDENTITY) }) ?: Rect(0.0, 0.0, 0.0, 0.0)
            else -> paths.anchorBounds() ?: Rect(0.0, 0.0, 0.0, 0.0)
        }

    /** Axis-aligned bounds after applying [parent] ∘ [transform]. */
    fun boundsIn(parent: Matrix): Rect {
        val m = parent * transform
        return if (kind == ItemKind.Group) {
            Rect.unionAll(children.map { it.boundsIn(m) }) ?: m.mapRect(Rect(0.0, 0.0, 0.0, 0.0))
        } else {
            Rect.bounding(paths.flatMap { sp -> sp.points.map { m.map(it.anchor) } }) ?: m.mapRect(Rect(0.0, 0.0, 0.0, 0.0))
        }
    }

    val isText get() = kind == ItemKind.TextFrame
    val isGroup get() = kind == ItemKind.Group

    /** Visit this item and all descendants, depth first, giving each item's parent-to-spread matrix. */
    fun walk(parent: Matrix = Matrix.IDENTITY, f: (PageItem, Matrix) -> Unit) {
        f(this, parent)
        if (isGroup) {
            val m = parent * transform
            for (c in children) c.walk(m, f)
        }
    }
}

data class Insets(val top: Double, val left: Double, val bottom: Double, val right: Double) {
    companion object {
        val ZERO = Insets(0.0, 0.0, 0.0, 0.0)
        fun all(v: Double) = Insets(v, v, v, v)
    }
}

/** A raster placed in a graphic frame. */
data class PlacedImage(
    val id: String,
    /** Image inner coordinates -> frame inner coordinates. */
    val transform: Matrix = Matrix.IDENTITY,
    /** Image size in points (GraphicBounds). */
    val bounds: Rect,
    /** Embedded bytes (PNG/JPEG...). Null if only linked. */
    val data: ByteArray? = null,
    val linkUri: String? = null,
    val passthrough: XmlNode? = null,
) {
    override fun equals(other: Any?): Boolean = other is PlacedImage && other.id == id && other.transform == transform &&
        other.bounds == bounds && other.linkUri == linkUri && (other.data === data || other.data.contentEquals(data)) && other.passthrough == passthrough

    override fun hashCode(): Int = id.hashCode() * 31 + transform.hashCode()
}

enum class ColorSpace { CMYK, RGB, LAB }

enum class SwatchKind { None, Color, Tint, Gradient }

data class GradientStop(val swatchId: String, val location: Double)

data class Swatch(
    val id: String,
    val name: String,
    val kind: SwatchKind = SwatchKind.Color,
    val space: ColorSpace = ColorSpace.CMYK,
    val values: List<Double> = listOf(0.0, 0.0, 0.0, 100.0),
    val spot: Boolean = false,
    val tint: Double = 100.0,
    val baseId: String? = null,
    val stops: List<GradientStop> = emptyList(),
    val radial: Boolean = false,
    /** Built-in swatches (None, Paper, Black, Registration) cannot be deleted. */
    val builtIn: Boolean = false,
    val passthrough: XmlNode? = null,
) {
    val isNone get() = kind == SwatchKind.None
}

object Ids {
    const val SWATCH_NONE = "Swatch/None"
    const val COLOR_BLACK = "Color/Black"
    const val COLOR_PAPER = "Color/Paper"
    const val COLOR_REGISTRATION = "Color/Registration"
    const val PARA_NONE = "ParagraphStyle/\$ID/[No paragraph style]"
    const val PARA_BASIC = "ParagraphStyle/\$ID/NormalParagraphStyle"
    const val CHAR_NONE = "CharacterStyle/\$ID/[No character style]"
    const val NONE = "n"
}

/**
 * Text formatting attributes. Every field is optional so the same type is used for style definitions,
 * paragraph overrides and character overrides; resolution merges them in cascade order.
 */
data class TextAttrs(
    val fontFamily: String? = null,
    val fontStyle: String? = null,
    val pointSize: Double? = null,
    /** Leading in points; [AUTO_LEADING] means auto (120%). */
    val leading: Double? = null,
    val tracking: Double? = null,
    val fillColor: String? = null,
    val underline: Boolean? = null,
    val strikeThrough: Boolean? = null,
    val capitalization: Capitalization? = null,
    val baselineShift: Double? = null,
    val justification: Justification? = null,
    val leftIndent: Double? = null,
    val rightIndent: Double? = null,
    val firstLineIndent: Double? = null,
    val spaceBefore: Double? = null,
    val spaceAfter: Double? = null,
    /** Unmodelled IDML attributes, preserved verbatim. */
    val extraAttrs: Map<String, String> = emptyMap(),
    /** Unmodelled `<Properties>` children, preserved verbatim. */
    val extraProps: List<XmlNode> = emptyList(),
) {
    /** Values in [o] win over values in this. */
    fun overlay(o: TextAttrs?): TextAttrs {
        if (o == null) return this
        return TextAttrs(
            fontFamily = o.fontFamily ?: fontFamily,
            fontStyle = o.fontStyle ?: fontStyle,
            pointSize = o.pointSize ?: pointSize,
            leading = o.leading ?: leading,
            tracking = o.tracking ?: tracking,
            fillColor = o.fillColor ?: fillColor,
            underline = o.underline ?: underline,
            strikeThrough = o.strikeThrough ?: strikeThrough,
            capitalization = o.capitalization ?: capitalization,
            baselineShift = o.baselineShift ?: baselineShift,
            justification = o.justification ?: justification,
            leftIndent = o.leftIndent ?: leftIndent,
            rightIndent = o.rightIndent ?: rightIndent,
            firstLineIndent = o.firstLineIndent ?: firstLineIndent,
            spaceBefore = o.spaceBefore ?: spaceBefore,
            spaceAfter = o.spaceAfter ?: spaceAfter,
            extraAttrs = if (o.extraAttrs.isEmpty()) extraAttrs else extraAttrs + o.extraAttrs,
            extraProps = if (o.extraProps.isEmpty()) extraProps else (extraProps.filter { e -> o.extraProps.none { it.name == e.name } } + o.extraProps),
        )
    }

    val isEmpty get() = this == EMPTY

    /** Keep only character-level attributes. */
    fun characterOnly() = copy(
        justification = null, leftIndent = null, rightIndent = null, firstLineIndent = null,
        spaceBefore = null, spaceAfter = null,
    )

    fun paragraphOnly() = TextAttrs(
        justification = justification, leftIndent = leftIndent, rightIndent = rightIndent,
        firstLineIndent = firstLineIndent, spaceBefore = spaceBefore, spaceAfter = spaceAfter,
    )

    companion object {
        val EMPTY = TextAttrs()
        const val AUTO_LEADING = -1.0
        val DEFAULTS = TextAttrs(
            fontFamily = "Minion Pro", fontStyle = "Regular", pointSize = 12.0, leading = AUTO_LEADING,
            tracking = 0.0, fillColor = Ids.COLOR_BLACK, underline = false, strikeThrough = false,
            capitalization = Capitalization.Normal, baselineShift = 0.0, justification = Justification.LeftAlign,
            leftIndent = 0.0, rightIndent = 0.0, firstLineIndent = 0.0, spaceBefore = 0.0, spaceAfter = 0.0,
        )
    }
}

enum class Justification(val idml: String) {
    LeftAlign("LeftAlign"), CenterAlign("CenterAlign"), RightAlign("RightAlign"),
    LeftJustified("LeftJustified"), CenterJustified("CenterJustified"), RightJustified("RightJustified"),
    FullyJustified("FullyJustified"), ToBindingSide("ToBindingSide"), AwayFromBindingSide("AwayFromBindingSide");

    val isJustified get() = this == LeftJustified || this == CenterJustified || this == RightJustified || this == FullyJustified

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.idml == s }
    }
}

enum class Capitalization(val idml: String) {
    Normal("Normal"), SmallCaps("SmallCaps"), AllCaps("AllCaps"), CapToSmallCap("CapToSmallCap");

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.idml == s }
    }
}

data class ParagraphStyle(
    val id: String,
    val name: String,
    val basedOn: String? = null,
    val nextStyle: String? = null,
    val attrs: TextAttrs = TextAttrs.EMPTY,
    val passthrough: XmlNode? = null,
)

data class CharacterStyle(
    val id: String,
    val name: String,
    val basedOn: String? = null,
    val attrs: TextAttrs = TextAttrs.EMPTY,
    val passthrough: XmlNode? = null,
)

data class CharFormat(val styleId: String = Ids.CHAR_NONE, val overrides: TextAttrs = TextAttrs.EMPTY)

/**
 * A run of identically formatted text. If [inline] is set the run is a single object replacement
 * character (U+FFFC) standing for unmodelled inline content (anchored objects, tables, special
 * character processing instructions) which is written back verbatim.
 */
data class Run(val text: String, val format: CharFormat = CharFormat(), val inline: XmlContent? = null)

data class Paragraph(
    val styleId: String = Ids.PARA_BASIC,
    val overrides: TextAttrs = TextAttrs.EMPTY,
    val runs: List<Run> = listOf(Run("")),
) {
    val text: String get() = runs.joinToString("") { it.text }
    val length: Int get() = runs.sumOf { it.text.length }
}

data class Story(
    val id: String,
    val paragraphs: List<Paragraph> = listOf(Paragraph()),
    /** Original story element for unmodelled attributes and children (StoryPreference etc.). */
    val passthrough: XmlNode? = null,
    /** Original file bytes; written verbatim when the story has not been edited. */
    val originalBytes: ByteArray? = null,
    val edited: Boolean = true,
) {
    val text: String get() = paragraphs.joinToString("\n") { it.text }
    val length: Int get() = paragraphs.sumOf { it.length } + (paragraphs.size - 1).coerceAtLeast(0)

    override fun equals(other: Any?) = other is Story && other.id == id && other.paragraphs == paragraphs &&
        other.passthrough == passthrough && other.edited == edited

    override fun hashCode() = id.hashCode() * 31 + paragraphs.hashCode()
}

/** Unmodelled files of an opened IDML package, kept for verbatim round-tripping. */
data class SourcePackage(
    val entries: Map<String, ByteArray>,
    val designmap: io.layertrace.core.xml.XmlDocument,
) {
    override fun equals(other: Any?) = other === this
    override fun hashCode() = System.identityHashCode(this)
}

/** Convenience to locate a spread-space point inside a page. */
fun Spread.pageAt(p: Point): Page? = pages.firstOrNull { it.boundsInSpread.contains(p) }
