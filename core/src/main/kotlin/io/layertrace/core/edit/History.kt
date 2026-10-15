package io.layertrace.core.edit

import io.layertrace.core.model.Document

/** Undo/redo over immutable document snapshots. */
data class History(
    val past: List<Entry> = emptyList(),
    val future: List<Entry> = emptyList(),
    val limit: Int = 200,
) {
    data class Entry(val doc: Document, val label: String, val time: Long)

    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    val undoLabel get() = past.lastOrNull()?.label
    val redoLabel get() = future.lastOrNull()?.label

    /**
     * Record that [before] was replaced. Consecutive edits with the same [coalesceKey] within
     * [coalesceMs] merge into one undo step (used for typing).
     */
    fun record(before: Document, label: String, now: Long = System.currentTimeMillis(), coalesceKey: String? = null, coalesceMs: Long = 1500): History {
        val last = past.lastOrNull()
        if (coalesceKey != null && last != null && last.label == coalesceKey && now - last.time < coalesceMs) {
            return copy(past = past.dropLast(1) + last.copy(time = now), future = emptyList())
        }
        val entry = Entry(before, coalesceKey ?: label, now)
        return copy(past = (past + entry).takeLast(limit), future = emptyList())
    }

    fun undo(current: Document): Pair<Document, History>? {
        val e = past.lastOrNull() ?: return null
        return e.doc to copy(past = past.dropLast(1), future = future + e.copy(doc = current))
    }

    fun redo(current: Document): Pair<Document, History>? {
        val e = future.lastOrNull() ?: return null
        return e.doc to copy(past = past + e.copy(doc = current), future = future.dropLast(1))
    }
}
