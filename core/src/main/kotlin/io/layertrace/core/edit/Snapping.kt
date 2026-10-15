package io.layertrace.core.edit

import io.layertrace.core.geom.Matrix
import io.layertrace.core.geom.Point
import io.layertrace.core.geom.Rect
import io.layertrace.core.model.Document
import io.layertrace.core.model.GuideOrientation
import io.layertrace.core.model.Spread
import kotlin.math.abs

data class SnapTargets(val xs: List<Double>, val ys: List<Double>)

data class SnapResult(val dx: Double, val dy: Double, val xLine: Double?, val yLine: Double?)

object Snapping {

    fun targets(doc: Document, spread: Spread, exclude: Set<String>, guides: Boolean = true, items: Boolean = true): SnapTargets {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (pg in spread.pages) {
            val b = pg.boundsInSpread
            xs += listOf(b.left, b.centerX, b.right); ys += listOf(b.top, b.centerY, b.bottom)
            val m = pg.transform.mapRect(pg.marginRect)
            xs += listOf(m.left, m.right); ys += listOf(m.top, m.bottom)
            for (c in pg.columnRects) {
                val cr = pg.transform.mapRect(c)
                xs += listOf(cr.left, cr.right)
            }
            if (guides) for (g in pg.guides) {
                val p = pg.transform.map(Point(g.location, g.location))
                if (g.orientation == GuideOrientation.Vertical) xs += p.x else ys += p.y
            }
        }
        if (items) for (item in spread.items) {
            if (item.id in exclude) continue
            val b = item.boundsIn(Matrix.IDENTITY)
            xs += listOf(b.left, b.centerX, b.right); ys += listOf(b.top, b.centerY, b.bottom)
        }
        return SnapTargets(xs.distinct(), ys.distinct())
    }

    private fun best(candidates: List<Double>, targets: List<Double>, threshold: Double): Pair<Double, Double>? {
        var bestD: Double? = null; var line = 0.0
        for (c in candidates) for (t in targets) {
            val d = t - c
            if (abs(d) <= threshold && (bestD == null || abs(d) < abs(bestD))) { bestD = d; line = t }
        }
        return bestD?.let { it to line }
    }

    /** Snap a moving rectangle: its edges and centre try to align with targets. */
    fun snapRect(r: Rect, t: SnapTargets, threshold: Double): SnapResult {
        val x = best(listOf(r.left, r.centerX, r.right), t.xs, threshold)
        val y = best(listOf(r.top, r.centerY, r.bottom), t.ys, threshold)
        return SnapResult(x?.first ?: 0.0, y?.first ?: 0.0, x?.second, y?.second)
    }

    fun snapPoint(p: Point, t: SnapTargets, threshold: Double): SnapResult {
        val x = best(listOf(p.x), t.xs, threshold)
        val y = best(listOf(p.y), t.ys, threshold)
        return SnapResult(x?.first ?: 0.0, y?.first ?: 0.0, x?.second, y?.second)
    }
}
