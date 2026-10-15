package io.layertrace.core.text

import io.layertrace.core.model.CharFormat
import io.layertrace.core.model.Paragraph
import io.layertrace.core.model.Run
import io.layertrace.core.model.Story
import io.layertrace.core.model.TextAttrs

const val OBJECT_CHAR = '￼'

/** Position of a story offset: paragraph index and offset within that paragraph. */
data class ParaPos(val paragraph: Int, val offset: Int)

/** Pure text editing operations on [Story]. Paragraph breaks are '\n' in story offsets. */
object StoryEditing {

    fun paragraphStart(story: Story, index: Int): Int {
        var o = 0
        for (i in 0 until index) o += story.paragraphs[i].length + 1
        return o
    }

    fun locate(story: Story, offset: Int): ParaPos {
        var o = offset.coerceIn(0, story.length)
        for ((i, p) in story.paragraphs.withIndex()) {
            if (o <= p.length) return ParaPos(i, o)
            o -= p.length + 1
        }
        val last = story.paragraphs.lastIndex
        return ParaPos(last, story.paragraphs[last].length)
    }

    /** Paragraph indices touched by the range [start, end]. */
    fun paragraphRange(story: Story, start: Int, end: Int): IntRange {
        val a = locate(story, minOf(start, end)).paragraph
        val b = locate(story, maxOf(start, end)).paragraph
        return a..b
    }

    /** Character format in effect at [offset] (format of the preceding character, as when typing). */
    fun formatAt(story: Story, offset: Int): CharFormat {
        val pos = locate(story, offset)
        val para = story.paragraphs[pos.paragraph]
        val target = if (pos.offset > 0) pos.offset - 1 else 0
        var o = 0
        var lastText: CharFormat? = null
        for (r in para.runs) {
            val e = o + r.text.length
            if (r.inline == null) lastText = r.format
            if (target < e) {
                if (r.inline == null) return r.format
                return lastText ?: para.runs.firstOrNull { it.inline == null }?.format ?: CharFormat()
            }
            o = e
        }
        return lastText ?: CharFormat()
    }

    /** Slice [from, to) of a paragraph's runs, splitting runs as needed. Inline object runs are never split. */
    fun sliceRuns(runs: List<Run>, from: Int, to: Int): List<Run> {
        val out = ArrayList<Run>()
        var o = 0
        for (r in runs) {
            val s = o; val e = o + r.text.length
            o = e
            if (e <= from || s >= to) continue
            val a = maxOf(from, s) - s; val b = minOf(to, e) - s
            if (r.inline != null) out += r
            else out += r.copy(text = r.text.substring(a, b))
        }
        return out
    }

    fun normalize(runs: List<Run>, fallback: CharFormat): List<Run> {
        val out = ArrayList<Run>()
        for (r in runs) {
            if (r.text.isEmpty() && r.inline == null) continue
            val last = out.lastOrNull()
            if (last != null && last.inline == null && r.inline == null && last.format == r.format) {
                out[out.size - 1] = last.copy(text = last.text + r.text)
            } else out += r
        }
        if (out.isEmpty()) {
            val f = runs.firstOrNull { it.inline == null }?.format ?: fallback
            out += Run("", f)
        }
        return out
    }

    private fun paraWithRuns(p: Paragraph, runs: List<Run>, fallback: CharFormat) =
        p.copy(runs = normalize(runs, fallback))

    /**
     * Replace the range [start, end) with [text]. Inserted text takes [format] or, by default,
     * the format in effect at [start]. Newlines create new paragraphs that inherit the paragraph
     * formatting of the paragraph at [start].
     */
    fun replace(story: Story, start: Int, end: Int, text: String, format: CharFormat? = null): Story {
        val s = minOf(start, end).coerceIn(0, story.length)
        val e = maxOf(start, end).coerceIn(0, story.length)
        val fmt = format ?: formatAt(story, s)
        val ps = locate(story, s)
        val pe = locate(story, e)
        val first = story.paragraphs[ps.paragraph]
        val last = story.paragraphs[pe.paragraph]
        val head = sliceRuns(first.runs, 0, ps.offset)
        val tail = sliceRuns(last.runs, pe.offset, last.length)
        val pieces = text.split('\n')
        val newParas = ArrayList<Paragraph>()
        if (pieces.size == 1) {
            newParas += paraWithRuns(first, head + Run(pieces[0], fmt) + tail, fmt)
        } else {
            newParas += paraWithRuns(first, head + Run(pieces[0], fmt), fmt)
            for (i in 1 until pieces.size - 1) newParas += paraWithRuns(first, listOf(Run(pieces[i], fmt)), fmt)
            newParas += paraWithRuns(first, listOf(Run(pieces.last(), fmt)) + tail, fmt)
        }
        val paragraphs = story.paragraphs.subList(0, ps.paragraph) + newParas +
            story.paragraphs.subList(pe.paragraph + 1, story.paragraphs.size)
        return story.copy(paragraphs = paragraphs, edited = true)
    }

    fun insert(story: Story, offset: Int, text: String, format: CharFormat? = null) = replace(story, offset, offset, text, format)

    fun delete(story: Story, start: Int, end: Int) = replace(story, start, end, "")

    /** Extract plain text of a range. */
    fun substring(story: Story, start: Int, end: Int): String {
        val t = story.text
        return t.substring(minOf(start, end).coerceIn(0, t.length), maxOf(start, end).coerceIn(0, t.length))
    }

    /** Apply [f] to the character formats in [start, end). */
    fun mapCharFormats(story: Story, start: Int, end: Int, f: (CharFormat) -> CharFormat): Story {
        val s = minOf(start, end); val e = maxOf(start, end)
        if (s == e) return story
        var paraStart = 0
        val paragraphs = story.paragraphs.map { p ->
            val ps = paraStart; val pe = ps + p.length
            paraStart = pe + 1
            val a = (s - ps).coerceIn(0, p.length); val b = (e - ps).coerceIn(0, p.length)
            if (a >= b) p
            else {
                val runs = sliceRuns(p.runs, 0, a) +
                    sliceRuns(p.runs, a, b).map { if (it.inline == null) it.copy(format = f(it.format)) else it } +
                    sliceRuns(p.runs, b, p.length)
                p.copy(runs = normalize(runs, p.runs.first().format))
            }
        }
        return story.copy(paragraphs = paragraphs, edited = true)
    }

    fun mapParagraphs(story: Story, start: Int, end: Int, f: (Paragraph) -> Paragraph): Story {
        val range = paragraphRange(story, start, end)
        return story.copy(
            paragraphs = story.paragraphs.mapIndexed { i, p -> if (i in range) f(p) else p },
            edited = true,
        )
    }

    fun applyParagraphStyle(story: Story, start: Int, end: Int, styleId: String, clearOverrides: Boolean = true) =
        mapParagraphs(story, start, end) { p -> p.copy(styleId = styleId, overrides = if (clearOverrides) TextAttrs.EMPTY else p.overrides) }

    fun applyCharacterStyle(story: Story, start: Int, end: Int, styleId: String) =
        mapCharFormats(story, start, end) { it.copy(styleId = styleId) }

    fun overrideCharAttrs(story: Story, start: Int, end: Int, attrs: TextAttrs) =
        mapCharFormats(story, start, end) { it.copy(overrides = it.overrides.overlay(attrs)) }

    fun overrideParagraphAttrs(story: Story, start: Int, end: Int, attrs: TextAttrs) =
        mapParagraphs(story, start, end) { it.copy(overrides = it.overrides.overlay(attrs)) }

    fun ofText(id: String, text: String, paraStyle: String, format: CharFormat = CharFormat()): Story =
        Story(id, text.split('\n').map { Paragraph(paraStyle, TextAttrs.EMPTY, listOf(Run(it, format))) })

    // --- caret navigation helpers -------------------------------------------------------------

    fun wordBoundaryLeft(text: String, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i > 0 && !text[i - 1].isLetterOrDigit()) i--
        while (i > 0 && text[i - 1].isLetterOrDigit()) i--
        return i
    }

    fun wordBoundaryRight(text: String, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i < text.length && !text[i].isLetterOrDigit()) i++
        while (i < text.length && text[i].isLetterOrDigit()) i++
        return i
    }

    fun wordAt(text: String, offset: Int): IntRange {
        if (text.isEmpty()) return 0 until 0
        var s = offset.coerceIn(0, text.length)
        var e = s
        while (s > 0 && text[s - 1].isLetterOrDigit()) s--
        while (e < text.length && text[e].isLetterOrDigit()) e++
        return s until e
    }
}
