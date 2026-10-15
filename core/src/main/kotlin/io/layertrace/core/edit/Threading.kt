package io.layertrace.core.edit

import io.layertrace.core.edit.ItemOps.updateItem
import io.layertrace.core.model.Document
import io.layertrace.core.model.Paragraph
import io.layertrace.core.model.Story

/** Text frame threading (linking frames so a story flows between them). */
object Threading {

    sealed class Result {
        data class Ok(val doc: Document) : Result()
        data class Error(val message: String) : Result()
    }

    /** Thread [toId] after [fromId]. The target's text (if any) is appended to the source story. */
    fun link(doc: Document, fromId: String, toId: String): Result {
        val idx = DocIndex.of(doc)
        val from = idx[fromId]?.item ?: return Result.Error("Frame not found")
        val to = idx[toId]?.item ?: return Result.Error("Frame not found")
        if (!from.isText || !to.isText) return Result.Error("Only text frames can be threaded")
        if (from.id == to.id) return Result.Error("Cannot thread a frame to itself")
        if (from.storyId == to.storyId) return Result.Error("Frames are already in the same thread")
        if (to.previousFrameId != null || to.nextFrameId != null) return Result.Error("Target frame is already threaded. Unthread it first.")
        val storyId = from.storyId ?: return Result.Error("Source frame has no story")
        var d = doc
        val src = d.stories[storyId] ?: Story(storyId)
        val tgt = to.storyId?.let { d.stories[it] }
        if (tgt != null && tgt.text.isNotEmpty()) {
            d = d.copy(stories = d.stories + (storyId to src.copy(paragraphs = src.paragraphs + tgt.paragraphs, edited = true)))
        }
        if (to.storyId != null) d = d.copy(stories = d.stories - to.storyId)
        val oldNext = from.nextFrameId
        d = d.updateItem(to.id) { it.copy(storyId = storyId, previousFrameId = from.id, nextFrameId = oldNext) }
        d = d.updateItem(from.id) { it.copy(nextFrameId = to.id) }
        if (oldNext != null) d = d.updateItem(oldNext) { it.copy(previousFrameId = to.id) }
        return Result.Ok(d)
    }

    /** Break the thread after [frameId]. Following frames become a new, empty thread. */
    fun unlinkAfter(doc: Document, frameId: String): Document {
        val idx = DocIndex.of(doc)
        val f = idx[frameId]?.item ?: return doc
        val nextId = f.nextFrameId ?: return doc
        val chain = idx.chain(f.storyId ?: return doc)
        val after = chain.dropWhile { it.id != nextId }
        val (sid, d0) = doc.newId()
        var d = d0.copy(stories = d0.stories + (sid to Story(sid, listOf(Paragraph()))))
        d = d.updateItem(frameId) { it.copy(nextFrameId = null) }
        d = d.updateItem(nextId) { it.copy(previousFrameId = null) }
        for (a in after) d = d.updateItem(a.id) { it.copy(storyId = sid) }
        return d
    }

    /** Remove a frame from its thread without deleting it; it keeps an empty story. */
    fun detach(doc: Document, frameId: String): Document {
        val idx = DocIndex.of(doc)
        val f = idx[frameId]?.item ?: return doc
        if (f.previousFrameId == null && f.nextFrameId == null) return doc
        val (sid, d0) = doc.newId()
        var d = d0.copy(stories = d0.stories + (sid to Story(sid, listOf(Paragraph()))))
        f.previousFrameId?.let { p -> d = d.updateItem(p) { it.copy(nextFrameId = f.nextFrameId) } }
        f.nextFrameId?.let { n -> d = d.updateItem(n) { it.copy(previousFrameId = f.previousFrameId) } }
        d = d.updateItem(frameId) { it.copy(previousFrameId = null, nextFrameId = null, storyId = sid) }
        return d
    }
}
