package io.layertrace.core.model

import kotlin.math.pow
import kotlin.math.roundToInt

/** Converts swatches to display sRGB (ARGB ints). Colour management is approximate (no ICC profiles). */
object Colors {
    const val NONE = 0x00000000

    fun argb(doc: Document, swatchId: String?, tintPercent: Double = 100.0): Int {
        val sw = doc.swatch(swatchId) ?: return if (swatchId == null || swatchId == Ids.SWATCH_NONE) NONE else 0xFF000000.toInt()
        return argb(doc, sw, tintPercent, 0)
    }

    private fun argb(doc: Document, sw: Swatch, tintPercent: Double, depth: Int): Int {
        if (depth > 8) return 0xFF000000.toInt()
        return when (sw.kind) {
            SwatchKind.None -> NONE
            SwatchKind.Tint -> {
                val base = doc.swatch(sw.baseId) ?: return 0xFF000000.toInt()
                argb(doc, base, sw.tint * tintPercent / 100.0, depth + 1)
            }
            SwatchKind.Gradient -> {
                val first = sw.stops.firstOrNull()?.let { doc.swatch(it.swatchId) }
                if (first == null) 0xFF000000.toInt() else argb(doc, first, tintPercent, depth + 1)
            }
            SwatchKind.Color -> {
                val rgb = toRgb(sw.space, sw.values)
                applyTint(rgb, tintPercent)
            }
        }
    }

    fun applyTint(rgb: Int, tintPercent: Double): Int {
        if (tintPercent >= 100.0) return rgb
        val t = (tintPercent / 100.0).coerceIn(0.0, 1.0)
        fun ch(shift: Int): Int {
            val v = (rgb shr shift) and 0xFF
            return (255 - (255 - v) * t).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun toRgb(space: ColorSpace, v: List<Double>): Int = when (space) {
        ColorSpace.RGB -> pack(v.getOrElse(0) { 0.0 }, v.getOrElse(1) { 0.0 }, v.getOrElse(2) { 0.0 })
        ColorSpace.CMYK -> {
            val c = v.getOrElse(0) { 0.0 } / 100; val m = v.getOrElse(1) { 0.0 } / 100
            val y = v.getOrElse(2) { 0.0 } / 100; val k = v.getOrElse(3) { 0.0 } / 100
            pack(255 * (1 - c) * (1 - k), 255 * (1 - m) * (1 - k), 255 * (1 - y) * (1 - k))
        }
        ColorSpace.LAB -> labToRgb(v.getOrElse(0) { 0.0 }, v.getOrElse(1) { 0.0 }, v.getOrElse(2) { 0.0 })
    }

    fun pack(r: Double, g: Double, b: Double): Int =
        (0xFF shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)

    fun rgbComponents(argb: Int) = listOf(((argb shr 16) and 0xFF).toDouble(), ((argb shr 8) and 0xFF).toDouble(), (argb and 0xFF).toDouble())

    fun rgbToCmyk(r: Double, g: Double, b: Double): List<Double> {
        val rr = r / 255; val gg = g / 255; val bb = b / 255
        val k = 1 - maxOf(rr, gg, bb)
        if (k >= 1.0) return listOf(0.0, 0.0, 0.0, 100.0)
        return listOf((1 - rr - k) / (1 - k), (1 - gg - k) / (1 - k), (1 - bb - k) / (1 - k), k).map { (it * 1000).roundToInt() / 10.0 }
    }

    private fun labToRgb(l: Double, a: Double, b: Double): Int {
        var y = (l + 16) / 116
        var x = a / 500 + y
        var z = y - b / 200
        fun f(t: Double) = if (t.pow(3) > 0.008856) t.pow(3) else (t - 16.0 / 116) / 7.787
        x = 0.95047 * f(x); y = 1.0 * f(y); z = 1.08883 * f(z)
        var r = x * 3.2406 + y * -1.5372 + z * -0.4986
        var g = x * -0.9689 + y * 1.8758 + z * 0.0415
        var bb = x * 0.0557 + y * -0.2040 + z * 1.0570
        fun gamma(c: Double) = if (c > 0.0031308) 1.055 * c.pow(1 / 2.4) - 0.055 else 12.92 * c
        r = gamma(r); g = gamma(g); bb = gamma(bb)
        return pack(r * 255, g * 255, bb * 255)
    }

    fun hex(argb: Int) = String.format("#%06X", argb and 0xFFFFFF)
}
