package io.layertrace.app.canvas

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import io.layertrace.app.render.pathOf
import io.layertrace.app.render.toAndroid
import io.layertrace.app.render.toRectF
import io.layertrace.core.edit.DocIndex
import io.layertrace.core.edit.Located
import io.layertrace.core.geom.Matrix
import io.layertrace.core.geom.Point
import io.layertrace.core.geom.Rect
import io.layertrace.core.model.Document
import io.layertrace.core.model.GuideOrientation
import io.layertrace.core.model.Spread
import kotlin.math.atan2
import kotlin.math.hypot

/** Selection frame: four corners in screen space plus whether it is a single (possibly rotated) item. */
data class SelectionFrame(val corners: List<Point>, val single: Located?) {
    val center get() = Point(corners.sumOf { it.x } / 4, corners.sumOf { it.y } / 4)

    /** 8 handles: TL, T, TR, R, BR, B, BL, L. */
    val handles: List<Point>
        get() {
            val (tl, tr, br, bl) = corners
            fun mid(a: Point, b: Point) = Point((a.x + b.x) / 2, (a.y + b.y) / 2)
            return listOf(tl, mid(tl, tr), tr, mid(tr, br), br, mid(br, bl), bl, mid(bl, tl))
        }

    /** Rotation handle position above the top edge. */
    fun rotateHandle(offset: Float): Point {
        val (tl, tr) = corners
        val mid = Point((tl.x + tr.x) / 2, (tl.y + tr.y) / 2)
        val c = center
        val dx = mid.x - c.x; val dy = mid.y - c.y
        val len = hypot(dx, dy).takeIf { it > 1e-6 } ?: 1.0
        return Point(mid.x + dx / len * offset, mid.y + dy / len * offset)
    }

    val angle get() = Math.toDegrees(atan2(corners[1].y - corners[0].y, corners[1].x - corners[0].x))
}

object Overlays {
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dash = DashPathEffect(floatArrayOf(4f, 4f), 0f)

    /** Margins, columns and guides of a spread (canvas already in spread space; [px] = 1 screen pixel). */
    fun drawLayoutGuides(c: Canvas, spread: Spread, px: Float, marginColor: Int, columnColor: Int, guideColor: Int) {
        stroke.strokeWidth = px
        stroke.pathEffect = null
        for (pg in spread.pages) {
            c.save()
            c.concat(pg.transform.toAndroid())
            stroke.color = marginColor
            c.drawRect(pg.marginRect.toRectF(), stroke)
            stroke.color = columnColor
            val cols = pg.columnRects
            if (cols.size > 1) for ((i, cr) in cols.withIndex()) {
                if (i > 0) c.drawLine(cr.left.toFloat(), cr.top.toFloat(), cr.left.toFloat(), cr.bottom.toFloat(), stroke)
                if (i < cols.size - 1) c.drawLine(cr.right.toFloat(), cr.top.toFloat(), cr.right.toFloat(), cr.bottom.toFloat(), stroke)
            }
            stroke.color = guideColor
            for (g in pg.guides) {
                val l = g.location.toFloat()
                if (g.orientation == GuideOrientation.Vertical) c.drawLine(l, -2000f, l, (pg.bounds.bottom + 2000).toFloat(), stroke)
                else c.drawLine(-2000f, l, (pg.bounds.right + 2000).toFloat(), l, stroke)
            }
            c.restore()
        }
    }

    fun drawBleed(c: Canvas, doc: Document, spread: Spread, px: Float) {
        val s = doc.setup
        if (s.bleedTop <= 0 && s.bleedBottom <= 0 && s.bleedInside <= 0 && s.bleedOutside <= 0) return
        stroke.strokeWidth = px
        stroke.color = 0xFFFF4040.toInt()
        val b = spread.pageBounds
        val bl = maxOf(s.bleedInside, s.bleedOutside)
        c.drawRect(Rect(b.left - bl, b.top - s.bleedTop, b.right + bl, b.bottom + s.bleedBottom).toRectF(), stroke)
    }

    /** Thin edges of every frame so empty frames are visible. */
    fun drawFrameEdges(c: Canvas, doc: Document, spread: Spread, px: Float, color: Int) {
        stroke.strokeWidth = px
        stroke.color = color
        stroke.pathEffect = null
        val visible = doc.layers.filter { it.visible }.map { it.id }.toSet()
        for (item in spread.items) {
            if (item.layerId !in visible) continue
            item.walk { it, parent ->
                if (!it.isGroup && it.stroke == null) {
                    c.save()
                    c.concat((parent * it.transform).toAndroid())
                    c.drawPath(pathOf(it.paths), stroke)
                    c.restore()
                }
            }
        }
    }

    fun outline(c: Canvas, loc: Located, px: Float, color: Int, width: Float = 1f) {
        stroke.strokeWidth = px * width
        stroke.color = color
        stroke.pathEffect = null
        c.save()
        c.concat(loc.parentWorld.toAndroid())
        loc.item.walk { it, parent ->
            if (!it.isGroup) {
                c.save()
                c.concat((parent * it.transform).toAndroid())
                c.drawPath(pathOf(it.paths), stroke)
                c.restore()
            }
        }
        c.restore()
    }

    fun dashedRect(c: Canvas, r: Rect, px: Float, color: Int) {
        stroke.strokeWidth = px
        stroke.color = color
        stroke.pathEffect = dash
        c.drawRect(r.toRectF(), stroke)
        stroke.pathEffect = null
    }

    fun polygon(points: List<Point>): Path = Path().apply {
        points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x.toFloat(), p.y.toFloat()) else lineTo(p.x.toFloat(), p.y.toFloat()) }
        close()
    }

    /** Corners of an item's inner bounds in spread space. */
    fun itemCorners(loc: Located): List<Point> {
        val b = loc.item.innerBounds
        val m = loc.world
        return listOf(Point(b.left, b.top), Point(b.right, b.top), Point(b.right, b.bottom), Point(b.left, b.bottom)).map { m.map(it) }
    }

    fun selectionFrame(doc: Document, ids: List<String>, toScreen: (Point) -> Point): SelectionFrame? {
        val idx = DocIndex.of(doc)
        val locs = ids.mapNotNull { idx[it] }
        if (locs.isEmpty()) return null
        if (locs.size == 1 && locs[0].world.isOrthogonal && !locs[0].item.isGroup) {
            return SelectionFrame(itemCorners(locs[0]).map(toScreen), locs[0])
        }
        val r = Rect.unionAll(locs.map { it.worldBounds }) ?: return null
        return SelectionFrame(r.corners.map(toScreen), if (locs.size == 1) locs[0] else null)
    }

    @Suppress("unused")
    fun identity() = Matrix.IDENTITY
}
