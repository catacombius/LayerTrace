package io.layertrace.core

import io.layertrace.core.edit.DocIndex
import io.layertrace.core.edit.DocumentFactory
import io.layertrace.core.edit.HitTest
import io.layertrace.core.edit.History
import io.layertrace.core.edit.ItemOps
import io.layertrace.core.edit.Pagination
import io.layertrace.core.edit.Snapping
import io.layertrace.core.edit.Threading
import io.layertrace.core.geom.Matrix
import io.layertrace.core.geom.Point
import io.layertrace.core.geom.Rect
import io.layertrace.core.geom.Shapes
import io.layertrace.core.idml.IdmlReader
import io.layertrace.core.model.Document
import io.layertrace.core.model.ItemKind
import io.layertrace.core.model.MeasureUnit
import io.layertrace.core.model.PageItem
import io.layertrace.core.model.Story
import io.layertrace.core.text.StoryEditing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditingTest {

    private fun doc() = IdmlReader.read(Fixtures.idml(), "Fixture")

    private fun withRect(d: Document, r: Rect, kind: ItemKind = ItemKind.Rectangle): Pair<Document, String> {
        val (id, d1) = d.newId()
        val item = PageItem(id, kind, d.layers.first().id, paths = Shapes.rect(r), fill = "Color/Black")
        return d1.copy(spreads = d1.spreads.mapIndexed { i, s -> if (i == 0) s.copy(items = s.items + item) else s }) to id
    }

    @Test
    fun matrixMath() {
        val m = Matrix.translate(10.0, 5.0) * Matrix.rotate(90.0)
        val p = m.map(Point(1.0, 0.0))
        assertEquals(10.0, p.x, 1e-9); assertEquals(6.0, p.y, 1e-9)
        val back = m.inverse().map(p)
        assertEquals(1.0, back.x, 1e-9); assertEquals(0.0, back.y, 1e-9)
        assertEquals(Matrix(1.0, 0.0, 0.0, 1.0, 3.5, -2.0), Matrix.parse("1 0 0 1 3.5 -2"))
        assertEquals("1 0 0 1 3.5 -2", Matrix(tx = 3.5, ty = -2.0).toIdml())
    }

    @Test
    fun resizeIsBakedIntoGeometry() {
        val (d0, id) = withRect(DocumentFactory.create(DocumentFactory.Params()), Rect(0.0, 0.0, 100.0, 50.0))
        val d = ItemOps.scale(d0, setOf(id), Rect(0.0, 0.0, 100.0, 50.0), Rect(0.0, 0.0, 200.0, 100.0), scaleContent = false)
        val item = DocIndex(d)[id]!!.item
        assertTrue(item.transform.isRigid)
        assertEquals(Rect(0.0, 0.0, 200.0, 100.0), item.innerBounds)
    }

    @Test
    fun rotateAndFlip() {
        val (d0, id) = withRect(DocumentFactory.create(DocumentFactory.Params()), Rect(0.0, 0.0, 100.0, 50.0))
        val d = ItemOps.rotate(d0, setOf(id), 30.0, Point(50.0, 25.0))
        assertEquals(30.0, DocIndex(d)[id]!!.world.rotationDegrees, 1e-9)
        val c = DocIndex(d)[id]!!.worldBounds.center
        assertEquals(50.0, c.x, 1e-6); assertEquals(25.0, c.y, 1e-6)
        val f = ItemOps.flip(d0, setOf(id), true, Point(0.0, 0.0))
        assertEquals(Rect(-100.0, 0.0, 0.0, 50.0), DocIndex(f)[id]!!.worldBounds)
    }

    @Test
    fun groupAndUngroupPreserveWorldGeometry() {
        var (d, a) = withRect(DocumentFactory.create(DocumentFactory.Params()), Rect(0.0, 0.0, 10.0, 10.0))
        val r2 = withRect(d, Rect(20.0, 20.0, 40.0, 40.0)); d = r2.first; val b = r2.second
        val (g, gid) = ItemOps.group(d, listOf(a, b))
        assertNotNull(gid)
        assertEquals(gid, DocIndex(g)[a]!!.parentGroupId)
        val moved = ItemOps.move(g, setOf(gid!!), 5.0, 5.0)
        val (u, released) = ItemOps.ungroup(moved, setOf(gid))
        assertEquals(setOf(a, b), released.toSet())
        assertEquals(Rect(5.0, 5.0, 15.0, 15.0), DocIndex(u)[a]!!.worldBounds)
        assertNull(DocIndex(u)[gid])
    }

    @Test
    fun arrange() {
        var (d, a) = withRect(DocumentFactory.create(DocumentFactory.Params()), Rect(0.0, 0.0, 10.0, 10.0))
        val r2 = withRect(d, Rect(0.0, 0.0, 10.0, 10.0)); d = r2.first; val b = r2.second
        val front = ItemOps.arrange(d, setOf(a), ItemOps.Arrange.BringToFront)
        assertEquals(listOf(b, a), front.spreads[0].items.map { it.id })
        val back = ItemOps.arrange(front, setOf(a), ItemOps.Arrange.SendBackward)
        assertEquals(listOf(a, b), back.spreads[0].items.map { it.id })
    }

    @Test
    fun alignAndDistribute() {
        var (d, a) = withRect(DocumentFactory.create(DocumentFactory.Params()), Rect(0.0, 0.0, 10.0, 10.0))
        var r = withRect(d, Rect(50.0, 30.0, 60.0, 40.0)); d = r.first; val b = r.second
        r = withRect(d, Rect(70.0, 0.0, 100.0, 10.0)); d = r.first; val c = r.second
        val left = ItemOps.align(d, listOf(a, b, c), ItemOps.Align.Left)
        assertEquals(0.0, DocIndex(left)[c]!!.worldBounds.left, 1e-9)
        val dist = ItemOps.distribute(d, listOf(a, b, c), horizontal = true)
        val idx = DocIndex(dist)
        assertEquals((idx[a]!!.worldBounds.centerX + idx[c]!!.worldBounds.centerX) / 2, idx[b]!!.worldBounds.centerX, 1e-9)
    }

    @Test
    fun deleteThreadedFrameRelinks() {
        val d = ItemOps.delete(doc(), setOf("u210"))
        val idx = DocIndex(d)
        assertNull(idx["u211"]!!.item.previousFrameId)
        assertNotNull(d.stories["u200"])
        val d2 = ItemOps.delete(d, setOf("u211"))
        assertNull(d2.stories["u200"])
    }

    @Test
    fun duplicateClonesStory() {
        val (d, ids) = ItemOps.duplicate(doc(), listOf("u310"), 10.0, 10.0)
        val copy = DocIndex(d)[ids.single()]!!.item
        assertTrue(copy.storyId != "u300")
        assertEquals("Rotated", d.stories[copy.storyId]!!.text)
        assertEquals(310.0, copy.transform.tx, 1e-9)
    }

    @Test
    fun threading() {
        var d = DocumentFactory.create(DocumentFactory.Params())
        val ids = d.newIds(4); d = ids.second
        val (f1, f2, s1, s2) = ids.first
        fun frame(id: String, sid: String, r: Rect) = PageItem(id, ItemKind.TextFrame, "ub3", paths = Shapes.rect(r), storyId = sid)
        d = d.copy(
            stories = mapOf(s1 to StoryEditing.ofText(s1, "one", "P"), s2 to StoryEditing.ofText(s2, "two", "P")),
            spreads = d.spreads.mapIndexed { i, s -> if (i == 0) s.copy(items = listOf(frame(f1, s1, Rect(0.0, 0.0, 10.0, 10.0)), frame(f2, s2, Rect(20.0, 0.0, 30.0, 10.0)))) else s },
        )
        val linked = (Threading.link(d, f1, f2) as Threading.Result.Ok).doc
        assertEquals("one\ntwo", linked.stories[s1]!!.text)
        assertFalse(s2 in linked.stories)
        assertEquals(listOf(f1, f2), DocIndex(linked).chain(s1).map { it.id })
        assertTrue(Threading.link(linked, f1, f2) is Threading.Result.Error)
        val un = Threading.unlinkAfter(linked, f1)
        val idx = DocIndex(un)
        assertNull(idx[f1]!!.item.nextFrameId)
        assertTrue(idx[f2]!!.item.storyId != s1)
        assertEquals("", un.stories[idx[f2]!!.item.storyId]!!.text)
    }

    @Test
    fun paginationFacing() {
        assertEquals(listOf(1, 2, 2, 1), Pagination.spreadSizes(6, true))
        assertEquals(listOf(1, 1, 1), Pagination.spreadSizes(3, false))
        var d = DocumentFactory.create(DocumentFactory.Params(pages = 2))
        assertTrue(Pagination.isLeftPage(d.spreads[1].pages[0]))
        val (d1, id) = withRect(d, Rect(10.0, -100.0, 50.0, -50.0))
        d = Pagination.addPages(d1, 0, 1, null)
        assertEquals(3, d.pageCount)
        // Item stays on page 1 at same page-relative position.
        val loc = DocIndex(d)[id]!!
        assertEquals(d.spreads[0].id, loc.spread.id)
        d = Pagination.deletePages(d, setOf(d.pages[0].id))
        assertEquals(2, d.pageCount)
        assertNull(DocIndex(d)[id])
        assertEquals(listOf("1", "2"), d.pages.map { it.name })
    }

    @Test
    fun movePageCarriesItems() {
        var d = DocumentFactory.create(DocumentFactory.Params(pages = 3, facing = false))
        val p0 = d.pages[0]
        val r = p0.boundsInSpread
        val (d1, id) = withRect(d, Rect(r.left + 10, r.top + 10, r.left + 20, r.top + 20))
        d = Pagination.movePage(d1, p0.id, 2)
        assertEquals(p0.id, d.pages[2].id)
        val loc = DocIndex(d)[id]!!
        assertEquals(d.spreads[2].id, loc.spread.id)
        val pb = d.pages[2].boundsInSpread
        assertEquals(pb.left + 10, loc.worldBounds.left, 1e-9)
    }

    @Test
    fun hitTesting() {
        val d = doc()
        val s = d.spreads[0]
        assertEquals("u230", HitTest.hit(d, s, Point(30.0, 40.0), 1.0, deep = false)?.id)
        assertEquals("u231", HitTest.hit(d, s, Point(30.0, 40.0), 1.0, deep = true)?.id)
        assertEquals("u232", HitTest.hit(d, s, Point(30.0, 80.5), 1.0, deep = true)?.id)
        // Rotated text frame on spread 2
        val s2 = d.spreads[1]
        assertEquals("u310", HitTest.hit(d, s2, Point(300.0, 50.0), 1.0, false)?.id)
    }

    @Test
    fun snapping() {
        val d = DocumentFactory.create(DocumentFactory.Params(facing = false))
        val s = d.spreads[0]
        val t = Snapping.targets(d, s, emptySet())
        val page = s.pages[0].boundsInSpread
        val r = Snapping.snapRect(Rect(page.left + 3, page.top + 100, page.left + 53, page.top + 150), t, 5.0)
        assertEquals(-3.0, r.dx, 1e-9)
        assertEquals(page.left, r.xLine!!, 1e-9)
    }

    @Test
    fun history() {
        val a = DocumentFactory.create(DocumentFactory.Params(name = "a"))
        val b = a.copy(name = "b"); val c = b.copy(name = "c")
        var h = History().record(a, "x", 0).record(b, "y", 10)
        val (u1, h1) = h.undo(c)!!
        assertEquals("b", u1.name)
        val (r1, _) = h1.redo(u1)!!
        assertEquals("c", r1.name)
        h = History().record(a, "t", 0, "Typing").record(b, "t", 500, "Typing")
        assertEquals(1, h.past.size)
        assertEquals("a", h.past[0].doc.name)
    }

    @Test
    fun units() {
        assertEquals(72.0, MeasureUnit.Millimeters.parse("25.4")!!, 1e-9)
        assertEquals(72.0, MeasureUnit.Millimeters.parse("1in")!!, 1e-9)
        assertEquals(42.0, MeasureUnit.Points.parse("3p6")!!, 1e-9)
        assertEquals(12.0, MeasureUnit.Points.parse("10+2")!!, 1e-9)
        assertEquals(72.0, MeasureUnit.Points.parse("2,54cm")!!, 1e-9)
        assertNull(MeasureUnit.Points.parse("abc"))
        assertEquals("25.4 mm", MeasureUnit.Millimeters.format(72.0))
    }

    @Test
    fun emptyStoryEdits() {
        val s = Story("x")
        val s2 = StoryEditing.insert(s, 0, "abc\ndef")
        assertEquals(2, s2.paragraphs.size)
        assertEquals("abc\ndef", s2.text)
    }
}
