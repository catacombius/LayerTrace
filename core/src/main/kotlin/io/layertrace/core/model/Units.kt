package io.layertrace.core.model

import io.layertrace.core.geom.fmt
import kotlin.math.roundToLong

enum class MeasureUnit(val label: String, val points: Double) {
    Millimeters("mm", 72.0 / 25.4),
    Centimeters("cm", 72.0 / 2.54),
    Inches("in", 72.0),
    Points("pt", 1.0),
    Picas("p", 12.0),
    Pixels("px", 1.0);

    fun fromPoints(pt: Double) = pt / points
    fun toPoints(v: Double) = v * points

    /** Format a point value in this unit with sensible precision. */
    fun format(pt: Double, withUnit: Boolean = true): String {
        val v = fromPoints(pt)
        val decimals = when (this) { Points, Pixels -> 2; Picas -> 2; Millimeters -> 2; Centimeters -> 3; Inches -> 4 }
        val p = Math.pow(10.0, decimals.toDouble())
        val rounded = (v * p).roundToLong() / p
        return fmt(rounded) + if (withUnit) " $label" else ""
    }

    /**
     * Parse user input like `12`, `12 mm`, `1in`, `3p6` (picas) or `10+2`. Values without a unit are
     * interpreted in this unit. Returns points, or null if unparseable.
     */
    fun parse(input: String): Double? {
        val s = input.trim().lowercase().replace(',', '.')
        if (s.isEmpty()) return null
        // Simple sums/differences: 10mm+2
        val parts = Regex("(?=[+-])").split(s).filter { it.isNotBlank() }
        if (parts.size > 1 && parts.drop(1).all { it.startsWith("+") || it.startsWith("-") }) {
            var total = 0.0
            for (p in parts) total += parseSingle(p) ?: return null
            return total
        }
        return parseSingle(s)
    }

    private fun parseSingle(raw: String): Double? {
        val s = raw.trim()
        Regex("^([+-]?\\d*)p(\\d*\\.?\\d*)$").matchEntire(s)?.let { m ->
            val picas = m.groupValues[1].let { if (it.isEmpty() || it == "+" || it == "-") 0.0 else it.toDouble() }
            val pts = m.groupValues[2].toDoubleOrNull() ?: 0.0
            val sign = if (s.startsWith("-")) -1 else 1
            return picas * 12 + sign * pts
        }
        val m = Regex("^([+-]?\\d*\\.?\\d+)\\s*([a-z]*)$").matchEntire(s) ?: return null
        val v = m.groupValues[1].toDouble()
        val unit = when (m.groupValues[2]) {
            "" -> this
            "mm" -> Millimeters
            "cm" -> Centimeters
            "in", "inch", "\"" -> Inches
            "pt" -> Points
            "pc", "p" -> Picas
            "px" -> Pixels
            else -> return null
        }
        return unit.toPoints(v)
    }
}
