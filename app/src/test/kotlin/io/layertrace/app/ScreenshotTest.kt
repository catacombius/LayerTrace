package io.layertrace.app

import android.graphics.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.layertrace.app.editor.EditorViewModel
import io.layertrace.app.editor.KeyHandler
import io.layertrace.app.editor.PanelTab
import io.layertrace.app.editor.Tool
import io.layertrace.app.editor.WorkspaceMode
import io.layertrace.app.ui.theme.LayerTraceTheme
import io.layertrace.app.workspace.EditorScreen
import io.layertrace.app.workspace.FoldState
import io.layertrace.app.workspace.Posture
import io.layertrace.app.workspace.WelcomeScreen
import io.layertrace.core.edit.DocumentFactory
import io.layertrace.core.geom.Point
import io.layertrace.core.model.Ids
import io.layertrace.core.model.TextAttrs
import io.layertrace.core.text.Placeholder
import io.layertrace.core.text.StoryEditing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the editor at the screen sizes of the target devices so layouts can be reviewed
 * without hardware. Images are written to app/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun sampleVm(): EditorViewModel {
        val vm = EditorViewModel(RuntimeEnvironment.getApplication())
        vm.newDocument(DocumentFactory.Params(name = "Brochure", pages = 3))
        val spread = vm.doc!!.spreads[1]
        val left = spread.pages[0].boundsInSpread
        // Heading + body text frame on the left page, coloured block and ellipse on the right page.
        val tf = vm.createItem(spread.id, Tool.Type, Point(left.left + 36, left.top + 36), Point(left.right - 36, left.top + 400))!!
        val d0 = vm.doc!!
        val sid = vm.textEdit!!.storyId
        var st = StoryEditing.replace(d0.stories[sid]!!, 0, 0, "Spring Collection\n" + Placeholder.text(3))
        st = StoryEditing.overrideCharAttrs(st, 0, 17, TextAttrs(pointSize = 30.0, fontStyle = "Bold"))
        vm.commit(d0.copy(stories = d0.stories + (sid to st)), "text")
        vm.endTextEdit()
        val right = spread.pages[1].boundsInSpread
        val r = vm.createItem(spread.id, Tool.Rectangle, Point(right.left + 36, right.top + 36), Point(right.right - 36, right.top + 300))!!
        vm.select(listOf(r))
        vm.colorTarget = io.layertrace.app.editor.ColorTarget.Fill
        vm.applySwatch("Color/C=100 M=90 Y=10 K=0")
        vm.createItem(spread.id, Tool.Ellipse, Point(right.left + 100, right.top + 360), Point(right.left + 300, right.top + 560))
        vm.select(listOf(tf))
        vm.goToPage(1)
        return vm
    }

    private fun shoot(name: String, vm: EditorViewModel, fold: FoldState = FoldState()) {
        compose.setContent { LayerTraceTheme { EditorScreen(vm, KeyHandler(vm), fold) } }
        compose.waitForIdle()
        vm.fitSpread()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun tabS7FeLandscape() = shoot("tab_s7fe_landscape", sampleVm())

    @Test @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun tabS7FePortrait() = shoot("tab_s7fe_portrait", sampleVm().also { it.panelTab = PanelTab.Pages })

    @Test @Config(qualifiers = "w832dp-h750dp-420dpi")
    fun foldInnerFlat() = shoot("fold_inner_flat", sampleVm().also { it.drawerOpen = true })

    @Test @Config(qualifiers = "w411dp-h960dp-420dpi")
    fun foldCover() = shoot("fold_cover", sampleVm().also { it.changeWorkspace(WorkspaceMode.Touch) })

    @Test @Config(qualifiers = "w750dp-h832dp-420dpi")
    fun foldTabletop() {
        val px = (832 * 2.625).toInt(); val w = (750 * 2.625).toInt()
        shoot("fold_tabletop", sampleVm(), FoldState(Posture.Tabletop, Rect(0, px / 2 - 10, w, px / 2 + 10)))
    }

    @Test @Config(qualifiers = "w832dp-h750dp-420dpi")
    fun foldBook() {
        val w = (832 * 2.625).toInt(); val h = (750 * 2.625).toInt()
        shoot("fold_book", sampleVm().also { it.panelTab = PanelTab.Swatches }, FoldState(Posture.Book, Rect(w / 2 - 10, 0, w / 2 + 10, h)))
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun textEditing() {
        val vm = sampleVm()
        val f = vm.selection.first()
        vm.beginTextEdit(f, 5)
        vm.setCaret(30, 18)
        shoot("text_editing", vm)
    }

    /**
     * The Fold's inner screen in portrait, Touch workspace, zoomed in with a frame selected: the
     * configuration from the first on-device report, where the canvas drew over the menu and tool
     * bars and the rulers and handles were drawn in page space.
     */
    @Test @Config(qualifiers = "w718dp-h762dp-420dpi")
    fun foldInnerPortraitTouch() {
        val vm = sampleVm().also { it.changeWorkspace(WorkspaceMode.Touch) }
        val spread = vm.doc!!.spreads[1]
        val r = spread.pages[1].boundsInSpread
        val frame = vm.createItem(spread.id, Tool.Frame, Point(r.left + 60, r.top + 60), Point(r.left + 200, r.top + 300))!!
        vm.select(listOf(frame))
        shoot("fold_inner_portrait_touch", vm)
        vm.setZoomPercent(35f)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/fold_inner_portrait_touch_35.png")

        // Rulers hug the canvas edges at any zoom, and the canvas leaves the menu bar visible.
        val shot = android.graphics.BitmapFactory.decodeFile("build/outputs/roborazzi/fold_inner_portrait_touch_35.png")
        val b = compose.onNodeWithTag("documentCanvas").fetchSemanticsNode().boundsInRoot
        val ruler = io.layertrace.app.ui.theme.EditorColors().chromeLow.toArgb()
        val rs = 20 * 2.625f
        assertEquals(ruler, shot.getPixel((b.left + rs / 2).toInt(), b.center.y.toInt()))
        assertEquals(ruler, shot.getPixel(b.center.x.toInt(), (b.top + rs / 2).toInt()))
        assertTrue("menu bar must not be covered", b.top > 20 * 2.625f)
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun welcome() {
        val vm = EditorViewModel(RuntimeEnvironment.getApplication())
        compose.setContent { LayerTraceTheme { WelcomeScreen(vm) {} } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/welcome.png")
    }

    @Suppress("unused")
    private val unused = Ids.NONE
}
