package io.layertrace.app.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import io.layertrace.core.geom.Rect
import io.layertrace.core.model.Capitalization
import io.layertrace.core.model.Colors
import io.layertrace.core.model.Document
import io.layertrace.core.model.Justification
import io.layertrace.core.model.PageItem
import io.layertrace.core.model.Paragraph
import io.layertrace.core.model.Story
import io.layertrace.core.model.TextAttrs
import io.layertrace.core.text.OBJECT_CHAR
import io.layertrace.core.text.StyleResolver
import kotlin.math.max
import kotlin.math.roundToInt

/** Text is laid out at this many units per point for sub-point precision. */
const val TEXT_SCALE = 4f

/** A run of consecutive lines of one paragraph layout placed in one column of one frame. */
class Slice(
    val layout: StaticLayout,
    val firstLine: Int,
    val lastLine: Int,
    /** Frame-inner coordinates (points) where the layout's origin is drawn. */
    val x: Float,
    val dy: Float,
    /** Story offset of the layout's character 0. */
    val storyStart: Int,
    val columnRight: Float,
) {
    val startOffset get() = storyStart + layout.getLineStart(firstLine)
    val endOffset get() = storyStart + layout.getLineEnd(lastLine)
    val top get() = dy + layout.getLineTop(firstLine) / TEXT_SCALE
    val bottom get() = dy + layout.getLineBottom(lastLine) / TEXT_SCALE
}

class FrameText(val frameId: String, val slices: List<Slice>, val columns: List<Rect>)

class StoryLayout(
    val storyId: String,
    val frames: Map<String, FrameText>,
    /** True if text did not fit in the frame chain. */
    val overset: Boolean,
    val lastFrameId: String?,
    val storyLength: Int,
) {
    val allSlices: List<Slice> get() = frames.values.flatMap { it.slices }

    /** Caret position for a story offset: frame id and (x, top, bottom) in frame-inner points. */
    fun caret(offset: Int): Pair<String, RectF>? {
        for ((fid, ft) in frames) for (s in ft.slices) {
            val local = offset - s.storyStart
            if (local < 0 || local > s.layout.text.length) continue
            val line = s.layout.getLineForOffset(local)
            if (line < s.firstLine || line > s.lastLine) continue
            val x = s.x + s.layout.getPrimaryHorizontal(local) / TEXT_SCALE
            val top = s.dy + s.layout.getLineTop(line) / TEXT_SCALE
            val bottom = s.dy + s.layout.getLineBottom(line) / TEXT_SCALE
            return fid to RectF(x, top, x, bottom)
        }
        return null
    }

    /** Story offset nearest to a frame-inner point. */
    fun offsetAt(frameId: String, x: Float, y: Float): Int? {
        val ft = frames[frameId] ?: return null
        if (ft.slices.isEmpty()) return null
        val s = ft.slices.minBy { sl ->
            when {
                y < sl.top -> (sl.top - y) + if (x < sl.x || x > sl.columnRight) 10000 else 0
                y > sl.bottom -> (y - sl.bottom) + if (x < sl.x || x > sl.columnRight) 10000 else 0
                else -> if (x < sl.x - 4 || x > sl.columnRight + 4) 5000f else 0f
            }
        }
        val ly = ((y - s.dy) * TEXT_SCALE).coerceIn(s.layout.getLineTop(s.firstLine).toFloat(), s.layout.getLineBottom(s.lastLine) - 1f)
        val line = s.layout.getLineForVertical(ly.roundToInt()).coerceIn(s.firstLine, s.lastLine)
        var off = s.layout.getOffsetForHorizontal(line, (x - s.x) * TEXT_SCALE)
        // Don't place the caret after a line's trailing break.
        val end = s.layout.getLineEnd(line)
        if (off == end && line < s.layout.lineCount - 1 && end > 0) off = end - 1
        return s.storyStart + off
    }

    /** Selection highlight path per frame, in frame-inner points. */
    fun selectionPath(frameId: String, start: Int, end: Int): Path? {
        val ft = frames[frameId] ?: return null
        val out = Path()
        var any = false
        for (s in ft.slices) {
            val a = (start - s.storyStart).coerceAtLeast(s.layout.getLineStart(s.firstLine))
            val b = (end - s.storyStart).coerceAtMost(s.layout.getLineEnd(s.lastLine))
            if (a >= b) continue
            val p = Path()
            s.layout.getSelectionPath(a, b, p)
            val m = android.graphics.Matrix()
            m.setScale(1 / TEXT_SCALE, 1 / TEXT_SCALE)
            m.postTranslate(s.x, s.dy)
            p.transform(m)
            out.addPath(p)
            any = true
        }
        return if (any) out else null
    }

    /** Offsets at the start/end of the visual line containing [offset]. */
    fun lineBounds(offset: Int): IntRange? {
        for (s in allSlices) {
            val local = offset - s.storyStart
            if (local < 0 || local > s.layout.text.length) continue
            val line = s.layout.getLineForOffset(local)
            if (line < s.firstLine || line > s.lastLine) continue
            var e = s.layout.getLineEnd(line)
            if (e > s.layout.getLineStart(line) && e <= s.layout.text.length && e > 0 && line < s.layout.lineCount - 1) e -= 1
            return (s.storyStart + s.layout.getLineStart(line))..(s.storyStart + e)
        }
        return null
    }

    /** Offset on the line above/below [offset] at the same x, or null at the ends of the story. */
    fun verticalMove(offset: Int, down: Boolean): Int? {
        val c = caret(offset) ?: return null
        val (fid, r) = c
        val ft = frames[fid] ?: return null
        val y = if (down) r.bottom + 1f else r.top - 1f
        val inSameFrame = ft.slices.any { y >= it.top && y <= it.bottom }
        if (inSameFrame) return offsetAt(fid, r.left, y)
        // Move to the adjacent slice in story order.
        val slices = allSlices.sortedBy { it.startOffset }
        val idx = slices.indexOfFirst { offset >= it.startOffset && offset <= it.endOffset }
        val next = slices.getOrNull(if (down) idx + 1 else idx - 1) ?: return null
        val fid2 = frames.entries.first { it.value.slices.contains(next) }.key
        return offsetAt(fid2, r.left, if (down) next.top + 1 else next.bottom - 1)
    }
}

/** Draws a run with InDesign-like character attributes. */
private class RunSpan(
    val typeface: Typeface,
    val sizePx: Float,
    val letterSpacing: Float,
    val color: Int,
    val underline: Boolean,
    val strike: Boolean,
    val baselineShiftPx: Float,
    val smallCaps: Boolean,
) : MetricAffectingSpan() {
    override fun updateMeasureState(p: TextPaint) = apply(p)
    override fun updateDrawState(p: TextPaint) {
        apply(p)
        p.color = color
        p.isUnderlineText = underline
        p.isStrikeThruText = strike
    }

    private fun apply(p: TextPaint) {
        p.typeface = typeface
        p.textSize = sizePx
        p.letterSpacing = letterSpacing
        p.baselineShift = -baselineShiftPx.roundToInt()
        p.fontFeatureSettings = if (smallCaps) "'smcp'" else null
    }
}

/** Fixed leading: baseline-to-baseline distance equals [leadingPx]. */
private class LeadingSpan(val leadingPx: Int) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        val h = fm.descent - fm.ascent
        if (h <= 0) return
        val diff = leadingPx - h
        fm.descent += diff
        fm.bottom = max(fm.bottom, fm.descent)
    }
}

/** Invisible stand-in for inline objects (anchored frames, tables, special characters). */
private class HiddenSpan : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) = 0
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {}
}

/**
 * Lays out stories across threaded frames using [StaticLayout], one layout per paragraph,
 * splitting paragraphs line by line across columns and frames.
 */
class TextLayoutEngine(private val fonts: FontRegistry) {

    private data class Key(
        val story: Story,
        val geometry: List<Any>,
        val paraStyles: Any,
        val charStyles: Any,
        val swatches: Any,
        val fontGen: Int,
    )

    private val cache = HashMap<String, Pair<Key, StoryLayout>>()

    fun clear() = cache.clear()

    fun layout(doc: Document, storyId: String, frames: List<PageItem>): StoryLayout? {
        val story = doc.stories[storyId] ?: return null
        val key = Key(
            story,
            frames.map { listOf(it.id, it.innerBounds, it.inset, it.columns, it.columnGutter) },
            doc.paragraphStyles, doc.characterStyles, doc.swatches, fonts.generation,
        )
        cache[storyId]?.let { (k, l) -> if (k.story === key.story && k == key) return l }
        val l = compute(doc, story, frames)
        cache[storyId] = key to l
        return l
    }

    fun columnsOf(frame: PageItem): List<Rect> {
        val b = frame.innerBounds
        val i = frame.inset
        val inner = Rect(b.left + i.left, b.top + i.top, b.right - i.right, b.bottom - i.bottom)
        val n = frame.columns.coerceAtLeast(1)
        val w = (inner.width - frame.columnGutter * (n - 1)) / n
        return (0 until n).map { k ->
            val l = inner.left + k * (w + frame.columnGutter)
            Rect(l, inner.top, l + w.coerceAtLeast(1.0), inner.bottom)
        }
    }

    private class Col(val frameId: String, val rect: Rect)

    private fun compute(doc: Document, story: Story, frames: List<PageItem>): StoryLayout {
        val resolver = StyleResolver(doc)
        val cols = frames.flatMap { f -> columnsOf(f).map { Col(f.id, it) } }
        val slicesByFrame = LinkedHashMap<String, MutableList<Slice>>()
        frames.forEach { slicesByFrame[it.id] = ArrayList() }
        var overset = false
        var ci = 0
        var y = cols.firstOrNull()?.rect?.top?.toFloat() ?: 0f
        var colEmpty = true
        var storyOffset = 0

        fun nextCol(): Boolean {
            ci++
            if (ci >= cols.size) return false
            y = cols[ci].rect.top.toFloat()
            colEmpty = true
            return true
        }

        outer@ for (para in story.paragraphs) {
            val pStart = storyOffset
            storyOffset += para.length + 1
            if (ci >= cols.size) { overset = true; break }
            val pa = resolver.paragraph(para)
            val spaceBefore = (pa.spaceBefore ?: 0.0).toFloat()
            val spaceAfter = (pa.spaceAfter ?: 0.0).toFloat()
            if (!colEmpty) y += spaceBefore
            var textStart = 0 // offset within paragraph where the current layout begins
            var layout = buildParagraph(doc, resolver, para, pa, textStart, cols[ci].rect.width.toFloat(), firstLine = true)
            var line = 0
            while (line < layout.lineCount) {
                val col = cols[ci].rect
                val top0 = layout.getLineTop(line) / TEXT_SCALE
                var fit = 0
                while (line + fit < layout.lineCount) {
                    val bottom = y + (layout.getLineBottom(line + fit) / TEXT_SCALE - top0)
                    if (bottom > col.bottom + 0.01) break
                    fit++
                }
                if (fit == 0) {
                    if (!nextCol()) { overset = true; break@outer }
                    // Re-layout the remainder if the new column has a different width.
                    if (Math.abs(cols[ci].rect.width - col.width) > 0.01) {
                        val consumed = layout.getLineStart(line)
                        textStart += consumed
                        layout = buildParagraph(doc, resolver, para, pa, textStart, cols[ci].rect.width.toFloat(), firstLine = textStart == 0)
                        line = 0
                    }
                    continue
                }
                slicesByFrame[cols[ci].frameId]!! += Slice(
                    layout, line, line + fit - 1, col.left.toFloat(), y - top0, pStart + textStart, col.right.toFloat(),
                )
                y += layout.getLineBottom(line + fit - 1) / TEXT_SCALE - top0
                colEmpty = false
                line += fit
                if (line < layout.lineCount) {
                    if (!nextCol()) { overset = true; break@outer }
                    if (Math.abs(cols[ci].rect.width - col.width) > 0.01) {
                        val consumed = layout.getLineStart(line)
                        textStart += consumed
                        layout = buildParagraph(doc, resolver, para, pa, textStart, cols[ci].rect.width.toFloat(), firstLine = false)
                        line = 0
                    }
                }
            }
            y += spaceAfter
        }
        return StoryLayout(
            story.id,
            slicesByFrame.mapValues { (fid, sl) -> FrameText(fid, sl, cols.filter { it.frameId == fid }.map { it.rect }) },
            overset,
            frames.lastOrNull()?.id,
            story.length,
        )
    }

    private fun buildParagraph(
        doc: Document,
        resolver: StyleResolver,
        para: Paragraph,
        pa: TextAttrs,
        from: Int,
        widthPt: Float,
        firstLine: Boolean,
    ): StaticLayout {
        val full = para.text
        val raw = full.substring(from.coerceAtMost(full.length))
        // Per-character capitalisation keeps offsets stable.
        var o = 0
        val runAttrs = para.runs.map { r -> resolver.run(para, r.format) }
        val sb = StringBuilder(raw)
        for ((ri, r) in para.runs.withIndex()) {
            val a = runAttrs[ri]
            val upper = a.capitalization == Capitalization.AllCaps
            for (k in r.text.indices) {
                val idx = o + k - from
                if (idx in sb.indices && upper) sb.setCharAt(idx, Character.toUpperCase(sb[idx]))
            }
            o += r.text.length
        }
        val text = SpannableString(sb.toString())
        o = 0
        var maxLeading = 0f
        for ((ri, r) in para.runs.withIndex()) {
            val a = runAttrs[ri]
            val s = (o - from).coerceAtLeast(0)
            val e = (o + r.text.length - from).coerceAtMost(text.length)
            o += r.text.length
            maxLeading = max(maxLeading, StyleResolver.effectiveLeading(a).toFloat())
            if (s >= e) continue
            if (r.inline != null) {
                text.setSpan(HiddenSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }
            text.setSpan(runSpan(doc, a), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // OBJECT_CHAR inside plain text (shouldn't happen) is hidden too.
            var idx = text.indexOf(OBJECT_CHAR, s)
            while (idx in s until e) {
                text.setSpan(HiddenSpan(), idx, idx + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                idx = text.indexOf(OBJECT_CHAR, idx + 1)
            }
        }
        if (maxLeading <= 0f) maxLeading = StyleResolver.effectiveLeading(pa).toFloat()
        if (text.isNotEmpty()) text.setSpan(LeadingSpan((maxLeading * TEXT_SCALE).roundToInt()), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

        val basePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
        val baseAttrs = runAttrs.firstOrNull() ?: pa
        basePaint.typeface = fonts.typeface(baseAttrs.fontFamily, baseAttrs.fontStyle)
        basePaint.textSize = ((baseAttrs.pointSize ?: 12.0) * TEXT_SCALE).toFloat()
        basePaint.color = Colors.argb(doc, baseAttrs.fillColor).let { if (it == 0) 0xFF000000.toInt() else it }

        val widthPx = (widthPt * TEXT_SCALE).roundToInt().coerceAtLeast(1)
        val just = pa.justification ?: Justification.LeftAlign
        val align = when (just) {
            Justification.CenterAlign, Justification.CenterJustified -> Layout.Alignment.ALIGN_CENTER
            Justification.RightAlign, Justification.RightJustified, Justification.AwayFromBindingSide -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val li = ((pa.leftIndent ?: 0.0) * TEXT_SCALE).roundToInt()
        val ri = ((pa.rightIndent ?: 0.0) * TEXT_SCALE).roundToInt()
        val fi = if (firstLine) ((pa.firstLineIndent ?: 0.0) * TEXT_SCALE).roundToInt() else 0
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, basePaint, widthPx)
            .setAlignment(align)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .setIndents(intArrayOf((li + fi).coerceAtLeast(0), li.coerceAtLeast(0)), intArrayOf(ri.coerceAtLeast(0)))
        if (just.isJustified) builder.setJustificationMode(android.graphics.text.LineBreaker.JUSTIFICATION_MODE_INTER_WORD)
        return builder.build()
    }

    private fun runSpan(doc: Document, a: TextAttrs): RunSpan {
        val size = ((a.pointSize ?: 12.0) * TEXT_SCALE).toFloat()
        val color = Colors.argb(doc, a.fillColor).let { if (it == 0 && a.fillColor != null && a.fillColor != io.layertrace.core.model.Ids.SWATCH_NONE) 0xFF000000.toInt() else it }
        return RunSpan(
            typeface = fonts.typeface(a.fontFamily, a.fontStyle),
            sizePx = size,
            letterSpacing = ((a.tracking ?: 0.0) / 1000.0).toFloat(),
            color = color,
            underline = a.underline == true,
            strike = a.strikeThrough == true,
            baselineShiftPx = ((a.baselineShift ?: 0.0) * TEXT_SCALE).toFloat(),
            smallCaps = a.capitalization == Capitalization.SmallCaps || a.capitalization == Capitalization.CapToSmallCap,
        )
    }
}
