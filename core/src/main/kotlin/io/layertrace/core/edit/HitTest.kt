package io.layertrace.core.edit

import io.layertrace.core.geom.Matrix
import io.layertrace.core.geom.Point
import io.layertrace.core.geom.Rect
import io.layertrace.core.geom.Shapes
import io.layertrace.core.model.Document
import io.layertrace.core.model.ItemKind
import io.layertrace.core.model.PageItem
import io.layertrace.core.model.Spread

object HitTest {

    /**
     * Topmost item under [p] (spread coordinates). With [deep], items inside groups are returned
     * instead of the group. Hidden and locked layers/items are skipped.
     */
    fun hit(doc: Document, spread: Spread, p: Point, tolerance: Double, deep: Boolean): PageItem? {
        for (layer in doc.layers) {
            if (!layer.visible || layer.locked) continue
            for (item in spread.items.asReversed()) {
                if (item.layerId != layer.id || !item.visible || item.locked) continue
                val r = hitItem(item, Matrix.IDENTITY, p, tolerance, deep)
                if (r != null) return r
            }
        }
        // Items on unknown layers
        val known = doc.layers.map { it.id }.toSet()
        for (item in spread.items.asReversed()) {
            if (item.layerId in known || !item.visible || item.locked) continue
            hitItem(item, Matrix.IDENTITY, p, tolerance, deep)?.let { return it }
        }
        return null
    }

    private fun hitItem(item: PageItem, parent: Matrix, p: Point, tol: Double, deep: Boolean): PageItem? {
        val world = parent * item.transform
        if (item.isGroup) {
            for (c in item.children.asReversed()) {
                val r = hitItem(c, world, p, tol, deep)
                if (r != null) return if (deep) r else item
            }
            return null
        }
        return if (contains(item, world, p, tol)) item else null
    }

    fun contains(item: PageItem, world: Matrix, p: Point, tol: Double): Boolean {
        val local = world.inverse().map(p)
        if (item.paths.isEmpty()) return item.innerBounds.contains(local, tol)
        val stroke = item.strokeWeight / 2
        for (sp in item.paths) {
            val poly = Shapes.flatten(sp)
            if (poly.size < 2) continue
            val closed = !sp.open
            if (closed && Shapes.containsPoint(poly, local)) return true
            if (Shapes.distanceToPolyline(local, poly, closed) <= tol + stroke) return true
        }
        return false
    }

    /** Top-level items whose bounds intersect [r] (marquee selection). */
    fun inRect(doc: Document, spread: Spread, r: Rect): List<PageItem> {
        val visible = doc.layers.filter { it.visible && !it.locked }.map { it.id }.toSet()
        return spread.items.filter { (it.layerId in visible || doc.layer(it.layerId) == null) && it.visible && !it.locked && it.boundsIn(Matrix.IDENTITY).intersects(r) }
    }

    @Suppress("unused")
    fun isFrame(item: PageItem) = item.kind == ItemKind.Rectangle || item.kind == ItemKind.Oval || item.kind == ItemKind.Polygon
}
