package io.layertrace.core.text

import io.layertrace.core.model.CharFormat
import io.layertrace.core.model.Document
import io.layertrace.core.model.Paragraph
import io.layertrace.core.model.TextAttrs

/** Resolves the effective formatting of text by cascading styles and overrides. */
class StyleResolver(private val doc: Document) {
    private val paraCache = HashMap<String, TextAttrs>()
    private val charCache = HashMap<String, TextAttrs>()

    fun paragraphStyleAttrs(id: String?): TextAttrs {
        if (id == null) return TextAttrs.DEFAULTS
        return paraCache.getOrPut(id) {
            val chain = ArrayList<TextAttrs>()
            var cur = doc.paragraphStyle(id)
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur.id)) {
                chain += cur.attrs
                cur = doc.paragraphStyle(cur.basedOn)
            }
            chain.foldRight(TextAttrs.DEFAULTS) { a, acc -> acc.overlay(a) }
        }
    }

    fun characterStyleAttrs(id: String?): TextAttrs {
        if (id == null) return TextAttrs.EMPTY
        return charCache.getOrPut(id) {
            val chain = ArrayList<TextAttrs>()
            var cur = doc.characterStyle(id)
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur.id)) {
                chain += cur.attrs
                cur = doc.characterStyle(cur.basedOn)
            }
            chain.foldRight(TextAttrs.EMPTY) { a, acc -> acc.overlay(a) }
        }
    }

    fun paragraph(p: Paragraph): TextAttrs = paragraphStyleAttrs(p.styleId).overlay(p.overrides)

    fun run(p: Paragraph, f: CharFormat): TextAttrs =
        paragraph(p).overlay(characterStyleAttrs(f.styleId)).overlay(f.overrides)

    companion object {
        fun effectiveLeading(a: TextAttrs): Double {
            val size = a.pointSize ?: 12.0
            val l = a.leading ?: TextAttrs.AUTO_LEADING
            return if (l < 0) size * 1.2 else l
        }
    }
}
