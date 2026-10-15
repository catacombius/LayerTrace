package io.layertrace.core.edit

import io.layertrace.core.geom.Matrix
import io.layertrace.core.geom.Rect
import io.layertrace.core.model.Document
import io.layertrace.core.model.PageItem
import io.layertrace.core.model.Spread

/** Where an item lives in the document tree. */
data class Located(
    val item: PageItem,
    val spread: Spread,
    /** Parent space -> spread space. */
    val parentWorld: Matrix,
    /** Id of the top-level ancestor on the spread (the item itself if top level). */
    val topLevelId: String,
    val parentGroupId: String?,
) {
    val world: Matrix get() = parentWorld * item.transform
    val worldBounds: Rect get() = item.boundsIn(parentWorld)
    val isTopLevel get() = parentGroupId == null
}

/** Id lookup over all spreads and masters. Build once per document instance. */
class DocIndex(val doc: Document) {
    private val map = HashMap<String, Located>()

    init {
        for (s in doc.spreads + doc.masters) {
            for (top in s.items) {
                fun visit(item: PageItem, parent: Matrix, parentGroup: String?) {
                    map[item.id] = Located(item, s, parent, top.id, parentGroup)
                    if (item.isGroup) {
                        val m = parent * item.transform
                        item.children.forEach { visit(it, m, item.id) }
                    }
                }
                visit(top, Matrix.IDENTITY, null)
            }
        }
    }

    operator fun get(id: String?): Located? = id?.let { map[it] }

    val all: Collection<Located> get() = map.values

    /** Text frames of a story in thread order. */
    fun chain(storyId: String): List<PageItem> {
        val frames = map.values.filter { it.item.isText && it.item.storyId == storyId }.map { it.item }
        if (frames.isEmpty()) return emptyList()
        val byId = frames.associateBy { it.id }
        val head = frames.firstOrNull { it.previousFrameId == null || it.previousFrameId !in byId } ?: frames.first()
        val out = ArrayList<PageItem>()
        val seen = HashSet<String>()
        var cur: PageItem? = head
        while (cur != null && seen.add(cur.id)) {
            out += cur
            cur = byId[cur.nextFrameId]
        }
        // Frames that are not reachable (broken threads) are appended so their text still shows.
        frames.filter { it.id !in seen }.forEach { out += it }
        return out
    }

    companion object {
        private var lastDoc: Document? = null
        private var lastIndex: DocIndex? = null

        /** Cached index for the most recently indexed document instance. */
        @Synchronized
        fun of(doc: Document): DocIndex {
            val li = lastIndex
            if (li != null && lastDoc === doc) return li
            return DocIndex(doc).also { lastDoc = doc; lastIndex = it }
        }
    }
}
