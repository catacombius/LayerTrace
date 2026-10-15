package io.layertrace.app.canvas

import io.layertrace.core.geom.Point
import io.layertrace.core.geom.Rect
import io.layertrace.core.model.Spread

/** A spread positioned in the continuous "view" space (points) where spreads are stacked vertically. */
class Placement(val spread: Spread, val dx: Double, val dy: Double) {
    /** Page area in view space. */
    val pageBounds: Rect get() = spread.pageBounds.offset(dx, dy)
    fun toSpread(view: Point) = Point(view.x - dx, view.y - dy)
    fun toView(sp: Point) = Point(sp.x + dx, sp.y + dy)
}

object SpreadLayout {
    const val GAP = 48.0

    private var lastInput: List<Spread>? = null
    private var lastOutput: List<Placement> = emptyList()

    @Synchronized
    fun place(spreads: List<Spread>): List<Placement> {
        if (spreads === lastInput) return lastOutput
        var y = 0.0
        val out = spreads.map { s ->
            val b = s.pageBounds
            val p = Placement(s, -b.centerX, y - b.top)
            y += b.height + GAP
            p
        }
        lastInput = spreads; lastOutput = out
        return out
    }

    /** Spread whose band (including half the gap around it) contains view-space y. */
    fun at(placements: List<Placement>, view: Point): Placement? {
        if (placements.isEmpty()) return null
        return placements.firstOrNull { view.y <= it.pageBounds.bottom + GAP / 2 } ?: placements.last()
    }
}

/** Mapping between view space (points) and screen pixels. */
data class Viewport(val scale: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    fun toScreenX(x: Double) = (x * scale + panX).toFloat()
    fun toScreenY(y: Double) = (y * scale + panY).toFloat()
    fun toScreen(p: Point) = Point(p.x * scale + panX, p.y * scale + panY)
    fun toView(sx: Float, sy: Float) = Point(((sx - panX) / scale).toDouble(), ((sy - panY) / scale).toDouble())

    fun zoomAt(factor: Float, sx: Float, sy: Float, min: Float, max: Float): Viewport {
        val ns = (scale * factor).coerceIn(min, max)
        val f = ns / scale
        return Viewport(ns, sx - (sx - panX) * f, sy - (sy - panY) * f)
    }

    /** Fit [r] (view space) into a screen area of [w]×[h] pixels with [margin]. */
    companion object {
        fun fit(r: Rect, w: Float, h: Float, margin: Float): Viewport {
            val s = minOf((w - 2 * margin) / r.width.toFloat(), (h - 2 * margin) / r.height.toFloat()).coerceAtLeast(0.01f)
            return Viewport(s, (w / 2 - r.centerX * s).toFloat(), (h / 2 - r.centerY * s).toFloat())
        }
    }
}
