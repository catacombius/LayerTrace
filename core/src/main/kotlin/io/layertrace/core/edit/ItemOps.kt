package io.layertrace.core.edit

import io.layertrace.core.geom.Matrix
import io.layertrace.core.geom.Point
import io.layertrace.core.geom.Rect
import io.layertrace.core.geom.decomposeOrthogonal
import io.layertrace.core.geom.transformed
import io.layertrace.core.model.Document
import io.layertrace.core.model.ItemKind
import io.layertrace.core.model.PageItem
import io.layertrace.core.model.Spread
import io.layertrace.core.model.Story
import kotlin.math.abs

/** Structural edits on page items. All functions are pure. */
object ItemOps {

    // ---- tree helpers ---------------------------------------------------------------------------

    /** Apply [f] to every spread and master spread. */
    fun Document.mapSpreads(f: (Spread) -> Spread): Document =
        copy(spreads = spreads.map(f), masters = masters.map(f))

    fun Document.updateSpread(id: String, f: (Spread) -> Spread): Document =
        copy(spreads = spreads.map { if (it.id == id) f(it) else it }, masters = masters.map { if (it.id == id) f(it) else it })

    private fun mapTree(items: List<PageItem>, f: (PageItem) -> PageItem?): List<PageItem> = items.mapNotNull { item ->
        val updated = if (item.isGroup) item.copy(children = mapTree(item.children, f)) else item
        f(updated)
    }

    /** Map every item (bottom-up). Returning null removes the item. Empty groups are removed. */
    fun Document.mapItems(f: (PageItem) -> PageItem?): Document = mapSpreads { s ->
        s.copy(items = mapTree(s.items, f).filterNot { it.isGroup && it.children.isEmpty() })
    }

    fun Document.updateItem(id: String, f: (PageItem) -> PageItem): Document = mapItems { if (it.id == id) f(it) else it }

    fun Document.updateItems(ids: Set<String>, f: (PageItem) -> PageItem): Document = mapItems { if (it.id in ids) f(it) else it }

    // ---- transforms -----------------------------------------------------------------------------

    /**
     * Apply world-space affine [op] to an item whose parent maps to the spread by [parentWorld].
     * Scale is baked into geometry so frames keep unscaled transforms (text keeps its size).
     */
    fun applyWorld(item: PageItem, parentWorld: Matrix, op: Matrix, scaleContent: Boolean): PageItem {
        val inv = parentWorld.inverse()
        val t = inv * op * parentWorld * item.transform
        return bake(item.copy(transform = t), scaleContent)
    }

    /** Move any non-rigid scale of [item]'s transform into its geometry. */
    fun bake(item: PageItem, scaleContent: Boolean): PageItem {
        val d = item.transform.decomposeOrthogonal() ?: return item
        if (abs(d.sx - 1) < 1e-9 && abs(d.sy - 1) < 1e-9) return item
        val s = Matrix.scale(d.sx, d.sy)
        return when (item.kind) {
            ItemKind.Group -> item.copy(
                transform = d.rotation,
                children = item.children.map { bake(it.copy(transform = s * it.transform), true) },
            )
            ItemKind.Unknown -> item
            else -> item.copy(
                transform = d.rotation,
                paths = item.paths.transformed(s),
                image = item.image?.let { img -> if (scaleContent) img.copy(transform = s * img.transform) else img },
                strokeWeight = if (scaleContent && item.kind != ItemKind.TextFrame) item.strokeWeight * (abs(d.sx) + abs(d.sy)) / 2 else item.strokeWeight,
            )
        }
    }

    fun transformItems(doc: Document, ids: Set<String>, op: Matrix, scaleContent: Boolean = false): Document {
        val idx = DocIndex.of(doc)
        val parents = ids.mapNotNull { id -> idx[id]?.let { id to it.parentWorld } }.toMap()
        return doc.mapItems { item ->
            val pw = parents[item.id]
            if (pw != null) applyWorld(item, pw, op, scaleContent) else item
        }
    }

    fun move(doc: Document, ids: Set<String>, dx: Double, dy: Double) =
        if (dx == 0.0 && dy == 0.0) doc else transformItems(doc, ids, Matrix.translate(dx, dy))

    fun rotate(doc: Document, ids: Set<String>, degrees: Double, around: Point) =
        transformItems(doc, ids, Matrix.rotateAround(degrees, around))

    fun scale(doc: Document, ids: Set<String>, from: Rect, to: Rect, scaleContent: Boolean) =
        transformItems(doc, ids, Matrix.rectToRect(from, to), scaleContent)

    fun flip(doc: Document, ids: Set<String>, horizontal: Boolean, around: Point) =
        transformItems(doc, ids, Matrix.scaleAround(if (horizontal) -1.0 else 1.0, if (horizontal) 1.0 else -1.0, around), scaleContent = true)

    /** Resize a single item's geometry in its own (local) coordinate space. Content (images) stays put. */
    fun resizeLocal(doc: Document, id: String, newInner: Rect): Document = doc.updateItem(id) { item ->
        val old = item.innerBounds
        val m = Matrix.rectToRect(old, newInner)
        when (item.kind) {
            ItemKind.Group -> item.copy(children = item.children.map { bake(it.copy(transform = m * it.transform), true) })
            else -> item.copy(paths = item.paths.transformed(m))
        }
    }

    /** Set the rotation angle of an item (around [around], in spread coords). */
    fun setRotation(doc: Document, id: String, degrees: Double, around: Point): Document {
        val loc = DocIndex.of(doc)[id] ?: return doc
        val current = loc.world.rotationDegrees
        return rotate(doc, setOf(id), degrees - current, around)
    }

    // ---- alignment ------------------------------------------------------------------------------

    enum class Align { Left, CenterH, Right, Top, CenterV, Bottom }

    fun align(doc: Document, ids: List<String>, how: Align, to: Rect? = null): Document {
        val idx = DocIndex.of(doc)
        val locs = ids.mapNotNull { idx[it] }
        if (locs.isEmpty()) return doc
        val target = to ?: Rect.unionAll(locs.map { it.worldBounds }) ?: return doc
        var d = doc
        for (l in locs) {
            val b = l.worldBounds
            val (dx, dy) = when (how) {
                Align.Left -> target.left - b.left to 0.0
                Align.CenterH -> target.centerX - b.centerX to 0.0
                Align.Right -> target.right - b.right to 0.0
                Align.Top -> 0.0 to target.top - b.top
                Align.CenterV -> 0.0 to target.centerY - b.centerY
                Align.Bottom -> 0.0 to target.bottom - b.bottom
            }
            d = move(d, setOf(l.item.id), dx, dy)
        }
        return d
    }

    fun distribute(doc: Document, ids: List<String>, horizontal: Boolean): Document {
        val idx = DocIndex.of(doc)
        val locs = ids.mapNotNull { idx[it] }.sortedBy { if (horizontal) it.worldBounds.centerX else it.worldBounds.centerY }
        if (locs.size < 3) return doc
        val first = locs.first().worldBounds; val last = locs.last().worldBounds
        val start = if (horizontal) first.centerX else first.centerY
        val end = if (horizontal) last.centerX else last.centerY
        val step = (end - start) / (locs.size - 1)
        var d = doc
        locs.forEachIndexed { i, l ->
            val c = if (horizontal) l.worldBounds.centerX else l.worldBounds.centerY
            val target = start + step * i
            d = if (horizontal) move(d, setOf(l.item.id), target - c, 0.0) else move(d, setOf(l.item.id), 0.0, target - c)
        }
        return d
    }

    // ---- stacking order -------------------------------------------------------------------------

    enum class Arrange { BringToFront, BringForward, SendBackward, SendToBack }

    private fun arrangeList(list: List<PageItem>, ids: Set<String>, how: Arrange): List<PageItem> {
        val sel = list.filter { it.id in ids }
        if (sel.isEmpty()) return list.map { if (it.isGroup) it.copy(children = arrangeList(it.children, ids, how)) else it }
        val rest = list.filter { it.id !in ids }
        return when (how) {
            Arrange.BringToFront -> rest + sel
            Arrange.SendToBack -> sel + rest
            Arrange.BringForward -> {
                val m = list.toMutableList()
                for (i in m.indices.reversed()) if (m[i].id in ids && i + 1 < m.size && m[i + 1].id !in ids) {
                    val t = m[i]; m[i] = m[i + 1]; m[i + 1] = t
                }
                m
            }
            Arrange.SendBackward -> {
                val m = list.toMutableList()
                for (i in m.indices) if (m[i].id in ids && i > 0 && m[i - 1].id !in ids) {
                    val t = m[i]; m[i] = m[i - 1]; m[i - 1] = t
                }
                m
            }
        }
    }

    fun arrange(doc: Document, ids: Set<String>, how: Arrange): Document = doc.mapSpreads { s -> s.copy(items = arrangeList(s.items, ids, how)) }

    // ---- grouping -------------------------------------------------------------------------------

    fun group(doc: Document, ids: List<String>): Pair<Document, String?> {
        val idx = DocIndex.of(doc)
        val locs = ids.mapNotNull { idx[it] }.filter { it.isTopLevel }
        if (locs.size < 2) return doc to null
        val spread = locs.first().spread
        if (locs.any { it.spread.id != spread.id }) return doc to null
        val selIds = locs.map { it.item.id }.toSet()
        val members = spread.items.filter { it.id in selIds }
        val (gid, d1) = doc.newId()
        val topIndex = spread.items.indexOfLast { it.id in selIds }
        val group = PageItem(id = gid, kind = ItemKind.Group, layerId = members.last().layerId, children = members)
        val newItems = spread.items.toMutableList()
        newItems.add(topIndex + 1, group)
        newItems.removeAll { it.id in selIds }
        return d1.updateSpread(spread.id) { it.copy(items = newItems) } to gid
    }

    fun ungroup(doc: Document, ids: Set<String>): Pair<Document, List<String>> {
        val released = ArrayList<String>()
        fun expand(list: List<PageItem>): List<PageItem> = list.flatMap { item ->
            if (item.isGroup && item.id in ids) {
                item.children.map { c -> bake(c.copy(transform = item.transform * c.transform), true) }.also { cs -> released += cs.map { it.id } }
            } else if (item.isGroup) listOf(item.copy(children = expand(item.children)))
            else listOf(item)
        }
        return doc.mapSpreads { s -> s.copy(items = expand(s.items)) } to released
    }

    // ---- delete / duplicate ---------------------------------------------------------------------

    fun delete(doc: Document, ids: Set<String>): Document {
        val idx = DocIndex.of(doc)
        // Collect every removed item including group descendants.
        val removed = HashSet<String>()
        for (id in ids) idx[id]?.item?.walk { it, _ -> removed += it.id }
        val removedFrames = removed.mapNotNull { idx[it]?.item }.filter { it.isText }
        var d = doc
        // Re-link threads around removed frames.
        for (f in removedFrames) {
            var prev = f.previousFrameId
            while (prev != null && prev in removed) prev = idx[prev]?.item?.previousFrameId
            var next = f.nextFrameId
            while (next != null && next in removed) next = idx[next]?.item?.nextFrameId
            if (prev != null) d = d.updateItem(prev) { it.copy(nextFrameId = next) }
            if (next != null) d = d.updateItem(next) { it.copy(previousFrameId = prev) }
        }
        d = d.mapItems { if (it.id in removed) null else it }
        // Drop stories that no longer have frames.
        val remainingStories = HashSet<String>()
        DocIndex(d).all.forEach { l -> l.item.storyId?.let { remainingStories += it } }
        val orphaned = removedFrames.mapNotNull { it.storyId }.filter { it !in remainingStories }
        return if (orphaned.isEmpty()) d else d.copy(stories = d.stories - orphaned.toSet())
    }

    /**
     * Deep-copy [items] with fresh ids. Text frames get copies of their stories; threads between
     * copied frames are preserved, threads to frames outside the copy are cut.
     */
    fun cloneItems(doc: Document, items: List<PageItem>, stories: Map<String, Story>): Triple<Document, List<PageItem>, Map<String, Story>> {
        var d = doc
        val idMap = HashMap<String, String>()
        fun allocate(item: PageItem) {
            val (nid, nd) = d.newId(); d = nd; idMap[item.id] = nid
            item.image?.let { img -> val (iid, d2) = d.newId(); d = d2; idMap[img.id] = iid }
            item.children.forEach { allocate(it) }
        }
        items.forEach { allocate(it) }
        val storyMap = HashMap<String, String>()
        val newStories = LinkedHashMap<String, Story>()
        fun copyItem(item: PageItem): PageItem {
            val sid = item.storyId?.let { old ->
                storyMap.getOrPut(old) {
                    val (nid, nd) = d.newId(); d = nd
                    stories[old]?.let { st -> newStories[nid] = st.copy(id = nid, passthrough = st.passthrough?.withAttr("Self", nid), edited = true, originalBytes = null) }
                    nid
                }
            }
            return item.copy(
                id = idMap[item.id]!!,
                storyId = sid,
                previousFrameId = item.previousFrameId?.let { idMap[it] },
                nextFrameId = item.nextFrameId?.let { idMap[it] },
                image = item.image?.let { it.copy(id = idMap[it.id]!!, passthrough = it.passthrough?.let { p -> p.withAttr("Self", idMap[it.id]).withoutChildren("Link") }) },
                children = item.children.map { copyItem(it) },
                passthrough = item.passthrough?.let { p -> p.withAttr("Self", idMap[item.id]) },
            )
        }
        val copies = items.map { copyItem(it) }
        return Triple(d.copy(stories = d.stories + newStories), copies, newStories)
    }

    /** Duplicate items on their spreads, placed just above the originals, offset by (dx, dy). */
    fun duplicate(doc: Document, ids: List<String>, dx: Double, dy: Double): Pair<Document, List<String>> {
        val idx = DocIndex.of(doc)
        val locs = ids.mapNotNull { idx[it] }.filter { it.isTopLevel }
        var d = doc
        val newIds = ArrayList<String>()
        for ((spreadId, group) in locs.groupBy { it.spread.id }) {
            val spread = d.spread(spreadId) ?: continue
            val ordered = spread.items.filter { item -> group.any { it.item.id == item.id } }
            val (d2, copies, _) = cloneItems(d, ordered, d.stories)
            d = d2
            val moved = copies.map { applyWorld(it, Matrix.IDENTITY, Matrix.translate(dx, dy), false) }
            newIds += moved.map { it.id }
            d = d.updateSpread(spreadId) { it.copy(items = it.items + moved) }
        }
        return d to newIds
    }

    /** Insert already-cloned items onto a spread (top of z order), optionally translating them. */
    fun insertItems(doc: Document, spreadId: String, items: List<PageItem>, dx: Double = 0.0, dy: Double = 0.0): Document {
        val moved = items.map { if (dx == 0.0 && dy == 0.0) it else applyWorld(it, Matrix.IDENTITY, Matrix.translate(dx, dy), false) }
        return doc.updateSpread(spreadId) { it.copy(items = it.items + moved) }
    }

    fun moveToLayer(doc: Document, ids: Set<String>, layerId: String) = doc.updateItems(ids) { it.copy(layerId = layerId) }
}
