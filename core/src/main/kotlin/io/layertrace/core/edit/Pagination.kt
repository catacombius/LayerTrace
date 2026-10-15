package io.layertrace.core.edit

import io.layertrace.core.geom.Matrix
import io.layertrace.core.geom.Point
import io.layertrace.core.geom.Rect
import io.layertrace.core.model.Document
import io.layertrace.core.model.Page
import io.layertrace.core.model.PageItem
import io.layertrace.core.model.Spread
import kotlin.math.abs

/** Page layout: assigning pages to spreads and keeping items attached to their pages. */
object Pagination {
    const val SPREAD_GAP = 36.0

    /** Group page indices into spreads. Facing documents start with a single right-hand page. */
    fun spreadSizes(pageCount: Int, facing: Boolean): List<Int> {
        if (pageCount <= 0) return emptyList()
        if (!facing) return List(pageCount) { 1 }
        val out = ArrayList<Int>()
        out += 1
        var remaining = pageCount - 1
        while (remaining > 0) {
            val n = minOf(2, remaining); out += n; remaining -= n
        }
        return out
    }

    /** Page -> spread transforms for a spread of [count] pages. [firstIsRight] for a lone first page. */
    fun pageTransforms(count: Int, facing: Boolean, w: Double, h: Double, singleIsRight: Boolean): List<Matrix> = when {
        !facing -> List(count) { i -> Matrix.translate(-w / 2 + i * w, -h / 2) }
        count == 1 -> listOf(Matrix.translate(if (singleIsRight) 0.0 else -w, -h / 2))
        else -> List(count) { i -> Matrix.translate(-w + i * w, -h / 2) }
    }

    /** True if [page] is a left-hand page (binding on its right). */
    fun isLeftPage(page: Page): Boolean = page.boundsInSpread.right <= 1e-3

    /** Spread-relative point -> page that owns it (nearest page if outside all pages). */
    fun owningPage(spread: Spread, p: Point): Page? {
        if (spread.pages.isEmpty()) return null
        spread.pages.firstOrNull { it.boundsInSpread.contains(p) }?.let { return it }
        return spread.pages.minBy { pg ->
            val b = pg.boundsInSpread
            val dx = maxOf(b.left - p.x, 0.0, p.x - b.right)
            val dy = maxOf(b.top - p.y, 0.0, p.y - b.bottom)
            dx * dx + dy * dy
        }
    }

    /**
     * Rebuild spreads from an ordered list of pages. Items stay attached to the page under their
     * centre and move with it. Pages not in [order] are deleted together with their items.
     */
    fun repaginate(doc: Document, order: List<Page>, master: Boolean = false): Document {
        val facing = doc.setup.facingPages
        val keptIds = order.map { it.id }.toSet()
        var d = doc
        // Delete items on removed pages first (handles thread relinking and story cleanup).
        val removedItemIds = (if (master) doc.masters else doc.spreads).flatMap { s ->
            s.items.filter { item -> owningPage(s, item.boundsIn(Matrix.IDENTITY).center)?.let { it.id !in keptIds } == true }.map { it.id }
        }.toSet()
        if (removedItemIds.isNotEmpty()) d = ItemOps.delete(d, removedItemIds)
        val source = if (master) d.masters else d.spreads
        // page id -> items with transforms relative to that page
        val attached = HashMap<String, MutableList<PageItem>>()
        val orphanedItems = ArrayList<PageItem>()
        for (s in source) {
            for (item in s.items) {
                val pg = owningPage(s, item.boundsIn(Matrix.IDENTITY).center)
                if (pg == null) { orphanedItems += item; continue }
                attached.getOrPut(pg.id) { ArrayList() } += item.copy(transform = pg.transform.inverse() * item.transform)
            }
        }

        val sizes = if (master) listOf(order.size) else spreadSizes(order.size, facing)
        val spreadIds = source.map { it.id }.toMutableList()
        val spreads = ArrayList<Spread>()
        var pi = 0
        var y = 0.0
        val h = doc.setup.pageHeight
        for ((si, n) in sizes.withIndex()) {
            val pages = order.subList(pi, pi + n)
            val singleIsRight = !(facing && n == 1 && si > 0 && si == sizes.lastIndex)
            val transforms = pageTransforms(n, facing, pages.first().bounds.width, pages.first().bounds.height, singleIsRight)
            val newPages = pages.mapIndexed { i, p -> p.copy(transform = transforms[i], name = if (master) p.name else (pi + i + 1).toString()) }
            val items = ArrayList<PageItem>()
            newPages.forEach { np ->
                attached[np.id]?.forEach { rel -> items += rel.copy(transform = np.transform * rel.transform) }
            }
            val base = source.getOrNull(si)
            val id = if (si < spreadIds.size) spreadIds[si] else { val (nid, nd) = d.newId(); d = nd; nid }
            spreads += (base ?: Spread(id, emptyList(), isMaster = master)).copy(
                id = id, pages = newPages, items = items, transform = Matrix.translate(0.0, y),
            )
            y += h + SPREAD_GAP
            pi += n
        }
        // Pasteboard items that belonged to no page stay on the first spread.
        if (orphanedItems.isNotEmpty() && spreads.isNotEmpty()) spreads[0] = spreads[0].copy(items = spreads[0].items + orphanedItems)
        return if (master) d.copy(masters = spreads) else d.copy(spreads = spreads)
    }

    fun addPages(doc: Document, afterIndex: Int, count: Int, masterId: String?): Document {
        var d = doc
        val pages = d.pages.toMutableList()
        val tmpl = pages.getOrNull(afterIndex) ?: pages.lastOrNull()
        val newPages = (0 until count).map {
            val (id, nd) = d.newId(); d = nd
            Page(
                id = id,
                bounds = tmpl?.bounds ?: Rect(0.0, 0.0, d.setup.pageWidth, d.setup.pageHeight),
                transform = Matrix.IDENTITY,
                appliedMasterId = masterId ?: tmpl?.appliedMasterId,
                margins = tmpl?.margins ?: d.setup.margins,
            )
        }
        pages.addAll((afterIndex + 1).coerceIn(0, pages.size), newPages)
        return repaginate(d, pages)
    }

    fun deletePages(doc: Document, pageIds: Set<String>): Document {
        val remaining = doc.pages.filter { it.id !in pageIds }
        if (remaining.isEmpty()) return doc
        return repaginate(doc, remaining)
    }

    fun movePage(doc: Document, pageId: String, toIndex: Int): Document {
        val pages = doc.pages.toMutableList()
        val i = pages.indexOfFirst { it.id == pageId }
        if (i < 0) return doc
        val p = pages.removeAt(i)
        pages.add(toIndex.coerceIn(0, pages.size), p)
        return repaginate(doc, pages)
    }

    fun applyMaster(doc: Document, pageIds: Set<String>, masterId: String?): Document = doc.copy(
        spreads = doc.spreads.map { s -> s.copy(pages = s.pages.map { if (it.id in pageIds) it.copy(appliedMasterId = masterId) else it }) }
    )

    /** Master page used for [page]: matching side for facing masters. */
    fun masterPageFor(doc: Document, page: Page): Pair<Spread, Page>? {
        val m = doc.master(page.appliedMasterId) ?: return null
        if (m.pages.isEmpty()) return null
        val left = isLeftPage(page)
        val mp = if (m.pages.size >= 2) (if (left) m.pages.first() else m.pages.last()) else m.pages.first()
        return m to mp
    }

    /** Change page size for all pages, keeping items anchored to their page's top-left corner. */
    fun resizePages(doc: Document, w: Double, h: Double, facing: Boolean): Document {
        val size = Rect(0.0, 0.0, w, h)
        var d = doc.copy(setup = doc.setup.copy(pageWidth = w, pageHeight = h, facingPages = facing))
        d = repaginate(d, d.pages.map { it.copy(bounds = size) })
        val masterOrder = d.masters.map { m -> m.pages.map { it.copy(bounds = size) } }
        // Masters: one spread each; facing documents use two-page masters.
        val masters = d.masters.mapIndexed { i, m ->
            val order = masterOrder[i]
            val transforms = pageTransforms(order.size, facing && order.size > 1, w, h, true)
            val newPages = order.mapIndexed { pi, p -> p.copy(transform = transforms.getOrElse(pi) { Matrix.IDENTITY }) }
            val items = m.items.map { item ->
                val pg = owningPage(m, item.boundsIn(Matrix.IDENTITY).center)
                val np = newPages.firstOrNull { it.id == pg?.id }
                if (pg != null && np != null) item.copy(transform = np.transform * pg.transform.inverse() * item.transform) else item
            }
            m.copy(pages = newPages, items = items)
        }
        return d.copy(masters = masters)
    }

    fun almostEqual(a: Double, b: Double) = abs(a - b) < 1e-6
}
