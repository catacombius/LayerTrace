package io.layertrace.app

import io.layertrace.app.canvas.Viewport
import io.layertrace.app.canvas.resizeRect
import io.layertrace.app.editor.Act
import io.layertrace.app.editor.EditorViewModel
import io.layertrace.app.editor.ShortcutMap
import io.layertrace.app.editor.Tool
import io.layertrace.app.render.FontRegistry
import io.layertrace.app.render.PdfExporter
import io.layertrace.app.workspace.SizeClass
import io.layertrace.app.workspace.sizeClassFor
import io.layertrace.core.edit.DocIndex
import io.layertrace.core.edit.DocumentFactory
import io.layertrace.core.geom.Point
import io.layertrace.core.geom.Rect
import io.layertrace.core.idml.IdmlReader
import io.layertrace.core.idml.IdmlWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LogicTest {

    @Test
    fun noShortcutConflicts() {
        assertEquals(emptyList<Any>(), ShortcutMap.conflicts())
        assertEquals(Act.Save, ShortcutMap.find(androidx.compose.ui.input.key.Key.S, ctrl = true, shift = false, alt = false))
    }

    @Test
    fun pageRanges() {
        assertEquals(listOf(0, 1, 2, 3), PdfExporter.parseRange("all", 4))
        assertEquals(listOf(0, 1, 2, 4), PdfExporter.parseRange("1-3, 5", 6))
        assertEquals(listOf(1), PdfExporter.parseRange("2, 99", 3))
    }

    @Test
    fun resizeHandles() {
        val r = Rect(0.0, 0.0, 100.0, 50.0)
        assertEquals(Rect(0.0, 0.0, 200.0, 50.0), resizeRect(r, 3, Point(200.0, 25.0), false, false))
        assertEquals(Rect(0.0, 0.0, 200.0, 100.0), resizeRect(r, 4, Point(200.0, 60.0), true, false))
        assertEquals(Rect(-50.0, 0.0, 150.0, 50.0), resizeRect(r, 3, Point(150.0, 0.0), false, true))
    }

    @Test
    fun sizeClasses() {
        assertEquals(SizeClass.Expanded, sizeClassFor(1280f, 800f))
        assertEquals(SizeClass.Medium, sizeClassFor(832f, 750f))
        assertEquals(SizeClass.Compact, sizeClassFor(411f, 960f))
    }

    @Test
    fun viewportZoomKeepsPointFixed() {
        val v = Viewport(2f, 10f, 20f)
        val before = v.toView(100f, 100f)
        val z = v.zoomAt(1.5f, 100f, 100f, 0.1f, 100f)
        val after = z.toView(100f, 100f)
        assertEquals(before.x, after.x, 1e-4)
        assertEquals(before.y, after.y, 1e-4)
    }

    @Test
    fun fontClassification() {
        assertEquals("sans-serif", FontRegistry.classify("Helvetica Neue"))
        assertEquals("serif", FontRegistry.classify("Minion Pro"))
        assertEquals("monospace", FontRegistry.classify("Courier New"))
        assertEquals(700, FontRegistry.weightOf("bold"))
        assertEquals("Bold Italic", FontRegistry.boldVariant("Italic"))
        assertEquals("Italic", FontRegistry.italicVariant("Regular"))
    }

    @Test
    fun editingFlowThroughViewModel() {
        val vm = EditorViewModel(RuntimeEnvironment.getApplication())
        vm.onCanvasSize(1200f, 800f)
        vm.newDocument(DocumentFactory.Params(pages = 2, facing = false))
        val spread = vm.doc!!.spreads[0]
        val p = spread.pages[0].boundsInSpread
        val id = vm.createItem(spread.id, Tool.Type, Point(p.left + 10, p.top + 10), Point(p.left + 200, p.top + 100))!!
        vm.textReplace("Hello")
        vm.textReplace(" world")
        assertEquals("Hello world", vm.doc!!.stories[vm.textEdit!!.storyId]!!.text)
        // Typing coalesces into one undo step.
        vm.undo()
        assertEquals("", vm.doc!!.stories.values.first().text)
        vm.redo()
        vm.moveCaret(EditorViewModel.CaretMove.StoryEnd, extend = false)
        vm.moveCaret(EditorViewModel.CaretMove.WordLeft, extend = true)
        assertTrue(vm.textEdit!!.hasSelection)
        vm.perform(Act.Bold)
        val st = vm.doc!!.stories[vm.textEdit!!.storyId]!!
        assertEquals("Bold", st.paragraphs[0].runs.last().format.overrides.fontStyle)
        vm.endTextEdit()
        assertEquals(listOf(id), vm.selection)
        vm.perform(Act.Duplicate)
        assertEquals(2, vm.doc!!.spreads[0].items.size)
        vm.perform(Act.SelectAll)
        vm.perform(Act.Group)
        assertTrue(DocIndex(vm.doc!!)[vm.selection.single()]!!.item.isGroup)
        vm.perform(Act.Delete)
        assertEquals(0, vm.doc!!.spreads[0].items.size)
        vm.perform(Act.AddPage)
        assertEquals(3, vm.doc!!.pageCount)
        // Saved IDML reopens.
        val again = IdmlReader.read(IdmlWriter.write(vm.doc!!), "x")
        assertEquals(3, again.pageCount)
    }

    @Test
    fun textLayoutFlowsAcrossThreadedFrames() {
        val vm = EditorViewModel(RuntimeEnvironment.getApplication())
        vm.newDocument(DocumentFactory.Params(pages = 1, facing = false))
        val s = vm.doc!!.spreads[0]
        val p = s.pages[0].boundsInSpread
        val a = vm.createItem(s.id, Tool.Type, Point(p.left + 10, p.top + 10), Point(p.left + 150, p.top + 60))!!
        vm.textReplace(io.layertrace.core.text.Placeholder.text(4))
        vm.endTextEdit()
        val sid = DocIndex(vm.doc!!)[a]!!.item.storyId!!
        assertTrue(vm.storyLayoutFor(sid)!!.overset)
        val b = vm.createItem(s.id, Tool.Rectangle, Point(0.0, 0.0), Point(1.0, 1.0)) // unrelated item
        assertNotNull(b)
        val c = vm.createItem(s.id, Tool.Type, Point(p.left + 10, p.top + 200), Point(p.left + 400, p.top + 700))!!
        vm.endTextEdit()
        vm.select(listOf(a, c))
        vm.perform(Act.ThreadFrames)
        val layout = vm.storyLayoutFor(sid)!!
        assertFalse(layout.overset)
        assertTrue(layout.frames[a]!!.slices.isNotEmpty())
        assertTrue(layout.frames[c]!!.slices.isNotEmpty())
        // Caret positions map back to offsets.
        val caret = layout.caret(10)
        assertNotNull(caret)
    }

    @Test
    fun shortcutLabelsUseKeyNames() {
        assertEquals("Ctrl+V", Act.Paste.shortcut!!.display)
        assertEquals("Ctrl+Alt+Shift+V", Act.PasteInPlace.shortcut!!.display)
        assertEquals("Ctrl+A", Act.SelectAll.shortcut!!.display)
        for (a in Act.entries) a.shortcut?.let { assertFalse(a.name, it.display.contains("code", ignoreCase = true)) }
    }

    @Test
    fun refitsWhenCanvasResizesUntilUserZooms() {
        val vm = EditorViewModel(RuntimeEnvironment.getApplication())
        vm.onCanvasSize(300f, 400f) // e.g. the Fold cover screen
        vm.newDocument(DocumentFactory.Params())
        vm.fitPage(0)
        val small = vm.viewport.scale
        vm.onCanvasSize(1800f, 1900f) // unfolded
        assertTrue("refit after resize", vm.viewport.scale > small * 3)
        vm.zoomBy(1.5f)
        val zoomed = vm.viewport.scale
        vm.onCanvasSize(1700f, 1900f)
        assertEquals("user zoom is kept", zoomed, vm.viewport.scale, 1e-4f)
    }
}
