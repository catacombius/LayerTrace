package io.layertrace.app.render

import android.graphics.pdf.PdfDocument
import io.layertrace.core.model.Document
import java.io.OutputStream

/** Exports pages as vector PDF (RGB) using Android's PdfDocument. */
object PdfExporter {

    /** Parse a page range like "1-3, 5" (1-based). Empty/"all" means every page. */
    fun parseRange(spec: String, pageCount: Int): List<Int> {
        val s = spec.trim()
        if (s.isEmpty() || s.equals("all", true)) return (0 until pageCount).toList()
        val out = LinkedHashSet<Int>()
        for (part in s.split(',', ';')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val m = Regex("^(\\d+)\\s*-\\s*(\\d+)$").matchEntire(p)
            if (m != null) {
                val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
                for (i in minOf(a, b)..maxOf(a, b)) if (i in 1..pageCount) out += i - 1
            } else p.toIntOrNull()?.let { if (it in 1..pageCount) out += it - 1 }
        }
        return out.toList()
    }

    fun export(doc: Document, renderer: PageRenderer, pages: List<Int>, bleed: Boolean, out: OutputStream) {
        val pdf = PdfDocument()
        try {
            val all = doc.spreads.flatMap { s -> s.pages.map { s to it } }
            val b = if (bleed) maxOf(doc.setup.bleedTop, doc.setup.bleedBottom, doc.setup.bleedInside, doc.setup.bleedOutside) else 0.0
            for ((n, i) in pages.withIndex()) {
                val (spread, page) = all.getOrNull(i) ?: continue
                val w = (page.bounds.width + 2 * b).toInt().coerceAtLeast(1)
                val h = (page.bounds.height + 2 * b).toInt().coerceAtLeast(1)
                val p = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, n + 1).create())
                renderer.drawPage(p.canvas, doc, spread, page, b)
                pdf.finishPage(p)
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }
}
