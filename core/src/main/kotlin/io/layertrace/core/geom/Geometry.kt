package io.layertrace.core.geom

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val EPS = 1e-9

data class Point(val x: Double, val y: Double) {
    operator fun plus(o: Point) = Point(x + o.x, y + o.y)
    operator fun minus(o: Point) = Point(x - o.x, y - o.y)
    operator fun times(f: Double) = Point(x * f, y * f)
    fun distanceTo(o: Point) = hypot(x - o.x, y - o.y)

    companion object {
        val ZERO = Point(0.0, 0.0)
    }
}

data class Rect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val center get() = Point(centerX, centerY)
    val isEmpty get() = width <= 0 || height <= 0

    fun contains(p: Point, slop: Double = 0.0) =
        p.x >= left - slop && p.x <= right + slop && p.y >= top - slop && p.y <= bottom + slop

    fun contains(r: Rect) = r.left >= left && r.right <= right && r.top >= top && r.bottom <= bottom

    fun intersects(r: Rect) = r.left < right && r.right > left && r.top < bottom && r.bottom > top

    fun union(r: Rect) = Rect(min(left, r.left), min(top, r.top), max(right, r.right), max(bottom, r.bottom))

    fun inset(dx: Double, dy: Double = dx) = Rect(left + dx, top + dy, right - dx, bottom - dy)

    fun offset(dx: Double, dy: Double) = Rect(left + dx, top + dy, right + dx, bottom + dy)

    /** Point at a 3x3 reference grid position (0..2, 0..2). */
    fun refPoint(ref: RefPoint) = Point(left + width * ref.fx, top + height * ref.fy)

    val corners get() = listOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom))

    companion object {
        fun ofPoints(a: Point, b: Point) = Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
        fun bounding(points: Iterable<Point>): Rect? {
            var l = Double.POSITIVE_INFINITY; var t = Double.POSITIVE_INFINITY
            var r = Double.NEGATIVE_INFINITY; var b = Double.NEGATIVE_INFINITY
            var any = false
            for (p in points) {
                any = true
                l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
            }
            return if (any) Rect(l, t, r, b) else null
        }
        fun unionAll(rects: Iterable<Rect>): Rect? = rects.reduceOrNull { a, b -> a.union(b) }
    }
}

/** InDesign-style 9-point reference proxy. */
enum class RefPoint(val fx: Double, val fy: Double) {
    TOP_LEFT(0.0, 0.0), TOP(0.5, 0.0), TOP_RIGHT(1.0, 0.0),
    LEFT(0.0, 0.5), CENTER(0.5, 0.5), RIGHT(1.0, 0.5),
    BOTTOM_LEFT(0.0, 1.0), BOTTOM(0.5, 1.0), BOTTOM_RIGHT(1.0, 1.0),
}

/**
 * 2D affine transform in IDML order `a b c d tx ty`:
 * x' = a·x + c·y + tx, y' = b·x + d·y + ty.
 */
data class Matrix(
    val a: Double = 1.0, val b: Double = 0.0,
    val c: Double = 0.0, val d: Double = 1.0,
    val tx: Double = 0.0, val ty: Double = 0.0,
) {
    fun map(p: Point) = Point(a * p.x + c * p.y + tx, b * p.x + d * p.y + ty)
    fun map(x: Double, y: Double) = Point(a * x + c * y + tx, b * x + d * y + ty)
    fun mapVector(p: Point) = Point(a * p.x + c * p.y, b * p.x + d * p.y)

    /** this ∘ o: first apply [o], then this. */
    operator fun times(o: Matrix) = Matrix(
        a = a * o.a + c * o.b,
        b = b * o.a + d * o.b,
        c = a * o.c + c * o.d,
        d = b * o.c + d * o.d,
        tx = a * o.tx + c * o.ty + tx,
        ty = b * o.tx + d * o.ty + ty,
    )

    val determinant get() = a * d - b * c

    fun inverse(): Matrix {
        val det = determinant
        if (abs(det) < EPS) return IDENTITY
        val ia = d / det; val ib = -b / det; val ic = -c / det; val id = a / det
        return Matrix(ia, ib, ic, id, -(ia * tx + ic * ty), -(ib * tx + id * ty))
    }

    val isIdentity get() = this == IDENTITY

    /** Rotation in degrees (counter-clockwise in y-down screen space is negative, like InDesign). */
    val rotationDegrees get() = Math.toDegrees(atan2(b, a))

    val scaleX get() = hypot(a, b)
    val scaleY get() = hypot(c, d)

    /** True if the linear part is a rotation (plus optional reflection) with uniform unit scale and no shear. */
    val isRigid get() = abs(scaleX - 1) < 1e-6 && abs(scaleY - 1) < 1e-6 && abs(a * c + b * d) < 1e-6

    val isOrthogonal get() = abs(a * c + b * d) < 1e-6 * max(1.0, scaleX * scaleY)

    fun mapRect(r: Rect): Rect = Rect.bounding(r.corners.map { map(it) })!!

    fun toIdml(): String = listOf(a, b, c, d, tx, ty).joinToString(" ") { fmt(it) }

    companion object {
        val IDENTITY = Matrix()
        fun translate(dx: Double, dy: Double) = Matrix(tx = dx, ty = dy)
        fun scale(sx: Double, sy: Double) = Matrix(a = sx, d = sy)
        fun rotate(degrees: Double): Matrix {
            val r = Math.toRadians(degrees)
            val cs = cos(r); val sn = sin(r)
            return Matrix(cs, sn, -sn, cs)
        }
        fun rotateAround(degrees: Double, p: Point) = translate(p.x, p.y) * rotate(degrees) * translate(-p.x, -p.y)
        fun scaleAround(sx: Double, sy: Double, p: Point) = translate(p.x, p.y) * scale(sx, sy) * translate(-p.x, -p.y)

        /** Maps rectangle [from] onto [to]. */
        fun rectToRect(from: Rect, to: Rect): Matrix {
            val sx = if (abs(from.width) < EPS) 1.0 else to.width / from.width
            val sy = if (abs(from.height) < EPS) 1.0 else to.height / from.height
            return translate(to.left, to.top) * scale(sx, sy) * translate(-from.left, -from.top)
        }

        fun parse(s: String?): Matrix? {
            if (s.isNullOrBlank()) return null
            val p = s.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
            return if (p.size == 6) Matrix(p[0], p[1], p[2], p[3], p[4], p[5]) else null
        }
    }
}

/** A Bézier anchor point with its two direction handles, in the owning item's inner coordinates. */
data class PathPoint(val anchor: Point, val left: Point = anchor, val right: Point = anchor) {
    fun transformed(m: Matrix) = PathPoint(m.map(anchor), m.map(left), m.map(right))
    val isCorner get() = left == anchor && right == anchor
}

data class SubPath(val points: List<PathPoint>, val open: Boolean = false) {
    fun transformed(m: Matrix) = SubPath(points.map { it.transformed(m) }, open)
}

fun List<SubPath>.anchorBounds(): Rect? = Rect.bounding(flatMap { sp -> sp.points.map { it.anchor } })

fun List<SubPath>.transformed(m: Matrix): List<SubPath> = map { it.transformed(m) }

object Shapes {
    private const val KAPPA = 0.5522847498307936

    fun rect(r: Rect) = listOf(SubPath(r.corners.map { PathPoint(it) }))

    fun ellipse(r: Rect): List<SubPath> {
        val cx = r.centerX; val cy = r.centerY
        val rx = r.width / 2; val ry = r.height / 2
        val kx = rx * KAPPA; val ky = ry * KAPPA
        return listOf(
            SubPath(
                listOf(
                    PathPoint(Point(cx, r.top), Point(cx - kx, r.top), Point(cx + kx, r.top)),
                    PathPoint(Point(r.right, cy), Point(r.right, cy - ky), Point(r.right, cy + ky)),
                    PathPoint(Point(cx, r.bottom), Point(cx + kx, r.bottom), Point(cx - kx, r.bottom)),
                    PathPoint(Point(r.left, cy), Point(r.left, cy + ky), Point(r.left, cy - ky)),
                )
            )
        )
    }

    fun polygon(r: Rect, sides: Int, starInset: Double = 0.0): List<SubPath> {
        val n = sides.coerceIn(3, 100)
        val pts = ArrayList<PathPoint>()
        val count = if (starInset > 0) n * 2 else n
        for (i in 0 until count) {
            val ang = -Math.PI / 2 + i * 2 * Math.PI / count
            val f = if (starInset > 0 && i % 2 == 1) 1 - starInset else 1.0
            pts += PathPoint(Point(r.centerX + cos(ang) * r.width / 2 * f, r.centerY + sin(ang) * r.height / 2 * f))
        }
        return listOf(SubPath(pts))
    }

    fun line(a: Point, b: Point) = listOf(SubPath(listOf(PathPoint(a), PathPoint(b)), open = true))

    /** True if [paths] is a single closed 4-point axis-aligned rectangle with no curves. */
    fun isAxisRect(paths: List<SubPath>): Boolean {
        if (paths.size != 1) return false
        val sp = paths[0]
        if (sp.open || sp.points.size != 4 || sp.points.any { !it.isCorner }) return false
        val p = sp.points.map { it.anchor }
        val xs = p.map { it.x }.distinctBy { (it * 1e4).toLong() }
        val ys = p.map { it.y }.distinctBy { (it * 1e4).toLong() }
        if (xs.size != 2 || ys.size != 2) return false
        for (i in 0 until 4) {
            val q = p[i]; val r = p[(i + 1) % 4]
            if (abs(q.x - r.x) > 1e-6 && abs(q.y - r.y) > 1e-6) return false
        }
        return true
    }

    /** Flattened polyline approximation of a sub path (for hit testing). */
    fun flatten(sp: SubPath, steps: Int = 12): List<Point> {
        val pts = sp.points
        if (pts.isEmpty()) return emptyList()
        val out = ArrayList<Point>()
        out += pts[0].anchor
        val segs = if (sp.open) pts.size - 1 else pts.size
        for (i in 0 until segs) {
            val p0 = pts[i]; val p1 = pts[(i + 1) % pts.size]
            if (p0.right == p0.anchor && p1.left == p1.anchor) {
                out += p1.anchor
            } else {
                for (s in 1..steps) {
                    val t = s.toDouble() / steps
                    out += cubic(p0.anchor, p0.right, p1.left, p1.anchor, t)
                }
            }
        }
        return out
    }

    fun cubic(p0: Point, c1: Point, c2: Point, p1: Point, t: Double): Point {
        val u = 1 - t
        val a = u * u * u; val b = 3 * u * u * t; val c = 3 * u * t * t; val d = t * t * t
        return Point(a * p0.x + b * c1.x + c * c2.x + d * p1.x, a * p0.y + b * c1.y + c * c2.y + d * p1.y)
    }

    /** Even-odd point-in-polygon test. */
    fun containsPoint(poly: List<Point>, p: Point): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val pi = poly[i]; val pj = poly[j]
            if ((pi.y > p.y) != (pj.y > p.y) && p.x < (pj.x - pi.x) * (p.y - pi.y) / (pj.y - pi.y) + pi.x) inside = !inside
            j = i
        }
        return inside
    }

    fun distanceToSegment(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 < EPS) return p.distanceTo(a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return p.distanceTo(Point(a.x + t * dx, a.y + t * dy))
    }

    fun distanceToPolyline(p: Point, poly: List<Point>, closed: Boolean): Double {
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until poly.size - 1) best = min(best, distanceToSegment(p, poly[i], poly[i + 1]))
        if (closed && poly.size > 2) best = min(best, distanceToSegment(p, poly.last(), poly.first()))
        return best
    }
}

/** Shortest round-tripping decimal, without scientific notation (IDML does not accept exponents). */
fun fmt(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return "0"
    if (v == Math.rint(v) && abs(v) < 1e15) return v.toLong().toString()
    if (abs(v) < 1e-12) return "0"
    val s = java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
    return if (s == "-0") "0" else s
}

/** Decompose a matrix whose linear part is orthogonal into rotation (with optional flip) and scale. */
data class Decomposed(val rotation: Matrix, val sx: Double, val sy: Double)

fun Matrix.decomposeOrthogonal(): Decomposed? {
    if (!isOrthogonal) return null
    val sx = scaleX
    val sy0 = scaleY
    if (sx < EPS || sy0 < EPS) return null
    val flip = determinant < 0
    val sy = if (flip) -sy0 else sy0
    // rotation R = M * diag(1/sx, 1/sy)
    val r = Matrix(a / sx, b / sx, c / sy, d / sy, tx, ty)
    return Decomposed(r, sx, sy)
}

fun sqr(v: Double) = v * v
fun dist(x: Double, y: Double) = sqrt(x * x + y * y)
