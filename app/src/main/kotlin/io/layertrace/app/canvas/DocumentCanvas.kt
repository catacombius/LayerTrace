package io.layertrace.app.canvas

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import io.layertrace.app.editor.ContextMenuRequest
import io.layertrace.app.editor.EditorViewModel
import io.layertrace.app.editor.KeyHandler
import io.layertrace.app.editor.StoryImeSink
import io.layertrace.app.editor.Tool
import io.layertrace.app.editor.WorkspaceMode
import io.layertrace.app.render.RenderOptions
import io.layertrace.app.render.toAndroid
import io.layertrace.app.render.toRectF
import io.layertrace.app.text.hasHardwareKeyboard
import io.layertrace.app.text.storyInput
import io.layertrace.app.ui.theme.EditorColors
import io.layertrace.app.ui.theme.LocalEditorColors
import io.layertrace.core.edit.DocIndex
import io.layertrace.core.edit.HitTest
import io.layertrace.core.edit.ItemOps
import io.layertrace.core.edit.ItemOps.updateItem
import io.layertrace.core.edit.Located
import io.layertrace.core.edit.Pagination
import io.layertrace.core.edit.Snapping
import io.layertrace.core.edit.Threading
import io.layertrace.core.geom.Point
import io.layertrace.core.geom.Rect
import io.layertrace.core.model.Document
import io.layertrace.core.model.Guide
import io.layertrace.core.model.GuideOrientation
import io.layertrace.core.text.StoryEditing
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

private sealed class Drag {
    data class Pan(val start: Offset, val startPanX: Float, val startPanY: Float) : Drag()
    data class Marquee(val pl: Placement, val a: Point, val b: Point, val additive: Boolean) : Drag()
    data class Move(val pl: Placement, val a: Point, val base: Document, val ids: Set<String>, val startBounds: Rect) : Drag()
    data class Resize(val pl: Placement, val handle: Int, val base: Document, val ids: List<String>, val single: Located?, val startBounds: Rect) : Drag()
    data class Rotate(val pl: Placement, val center: Point, val startAngle: Double, val base: Document, val ids: Set<String>) : Drag()
    data class Create(val pl: Placement, val tool: Tool, val a: Point, val b: Point) : Drag()
    data class TextSel(val frameId: String, val anchor: Int) : Drag()
    data class GuideDrag(val vertical: Boolean, val pos: Offset) : Drag()
    data class ZoomRect(val a: Offset, val b: Offset) : Drag()
    data class Anchor(val pl: Placement, val itemId: String, val sub: Int, val pt: Int, val base: Document) : Drag()
}

/** The document view: renders spreads and handles mouse, pen and touch input. */
@Composable
fun DocumentCanvas(vm: EditorViewModel, keys: KeyHandler, modifier: Modifier = Modifier) {
    val colors = LocalEditorColors.current
    val density = LocalDensity.current.density
    val config = LocalConfiguration.current
    var drag by remember { mutableStateOf<Drag?>(null) }
    var icon by remember { mutableStateOf(PointerIcon.Default) }
    var threadFrom by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val sink = remember(vm) { StoryImeSink(vm) }
    val frame = remember { FrameBuffer() }
    val touchMode = vm.workspace == WorkspaceMode.Touch
    val showKeyboard = touchMode || !hasHardwareKeyboard(config)

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(vm.textEdit != null) { runCatching { focus.requestFocus() } }

    // ---- coordinate helpers ----------------------------------------------------------------
    fun locate(pos: Offset): Pair<Placement, Point>? {
        val v = vm.viewport.toView(pos.x, pos.y)
        val pl = SpreadLayout.at(vm.placements, v) ?: return null
        return pl to pl.toSpread(v)
    }

    fun toScreen(pl: Placement, p: Point) = vm.viewport.toScreen(pl.toView(p))
    fun handleRadius() = (if (vm.workspace == WorkspaceMode.Touch) 14f else 7f) * density
    fun rulerSize() = if (vm.view.showRulers && !vm.view.preview) 20f * density else 0f
    fun tol() = ((if (vm.workspace == WorkspaceMode.Touch) 10f else 4f) * density / vm.viewport.scale).toDouble()

    fun selectionFrame(d: Document, pl: Placement): SelectionFrame? =
        Overlays.selectionFrame(d, vm.selection) { toScreen(pl, it) }

    fun selectionPlacement(d: Document): Placement? {
        val loc = DocIndex.of(d)[vm.selection.firstOrNull()] ?: return null
        return vm.placementOf(loc.spread.id)
    }

    /** Hit handle index 0..7, 8 = rotate, or null. */
    fun hitHandle(pos: Offset, frame: SelectionFrame): Int? {
        val r = handleRadius() * 1.4f
        frame.handles.forEachIndexed { i, h -> if (hypot(h.x - pos.x, h.y - pos.y) <= r) return i }
        val rh = frame.rotateHandle(24f * density)
        if (hypot(rh.x - pos.x, rh.y - pos.y) <= r * 1.2) return 8
        // Mouse: just outside a corner rotates, like InDesign.
        if (vm.workspace == WorkspaceMode.KeyboardMouse) {
            for (i in listOf(0, 2, 4, 6)) {
                val h = frame.handles[i]
                val dist = hypot(h.x - pos.x, h.y - pos.y)
                if (dist > r && dist <= r + 14f * density) {
                    val c = frame.center
                    // outside the frame
                    if (hypot(pos.x - c.x, pos.y - c.y) > hypot(h.x - c.x, h.y - c.y)) return 8
                }
            }
        }
        return null
    }

    fun textOffsetAt(frameId: String, sp: Point): Int? {
        val d = vm.doc ?: return null
        val loc = DocIndex.of(d)[frameId] ?: return null
        val sid = loc.item.storyId ?: return null
        val layout = vm.storyLayoutFor(sid) ?: return d.stories[sid]?.length
        val lp = loc.world.inverse().map(sp)
        return layout.offsetAt(frameId, lp.x.toFloat(), lp.y.toFloat()) ?: d.stories[sid]?.length
    }

    fun openContextMenu(pos: Offset, deepHit: Boolean) {
        val d = vm.doc ?: return
        val (pl, sp) = locate(pos) ?: return
        val hit = HitTest.hit(d, pl.spread, sp, tol(), deepHit)
        if (hit != null && hit.id !in vm.selection && vm.textEdit == null) vm.select(listOf(hit.id))
        vm.contextMenu = ContextMenuRequest(pos.x, pos.y, hit != null || vm.textEdit != null)
    }

    // ---- drag start ------------------------------------------------------------------------
    var clickCount = 0
    var lastClickTime = 0L
    var lastClickPos = Offset.Zero

    fun beginDrag(pos: Offset, shift: Boolean, alt: Boolean, ctrl: Boolean, finger: Boolean, clicks: Int): Drag? {
        val d = vm.doc ?: return null
        val rs = rulerSize()
        if (rs > 0 && (pos.y < rs || pos.x < rs)) {
            if (pos.y < rs && pos.x >= rs) return Drag.GuideDrag(vertical = false, pos)
            if (pos.x < rs && pos.y >= rs) return Drag.GuideDrag(vertical = true, pos)
            return null
        }
        val (pl, sp) = locate(pos) ?: return null
        val tool = if (keys.spaceDown) Tool.Hand else vm.tool
        val vp = vm.viewport

        // Threading mode: next click picks the frame to flow into.
        threadFrom?.let { from ->
            threadFrom = null
            val hit = HitTest.hit(d, pl.spread, sp, tol(), deep = true)
            if (hit != null && hit.isText) {
                when (val r = Threading.link(d, from, hit.id)) {
                    is Threading.Result.Ok -> { vm.commit(r.doc, "Thread Frames"); vm.select(listOf(hit.id)) }
                    is Threading.Result.Error -> vm.message(r.message)
                }
            }
            return null
        }

        when (tool) {
            Tool.Hand -> return Drag.Pan(pos, vp.panX, vp.panY)
            Tool.Zoom -> return Drag.ZoomRect(pos, pos)
            else -> {}
        }

        // Text editing: clicks in the story's frames move the caret.
        vm.textEdit?.let { te ->
            val hit = HitTest.hit(d, pl.spread, sp, tol(), deep = true)
            if (hit != null && hit.isText && hit.storyId == te.storyId) {
                val off = textOffsetAt(hit.id, sp) ?: return null
                val st = d.stories[te.storyId] ?: return null
                when {
                    clicks == 2 -> { val w = StoryEditing.wordAt(st.text, off); vm.setCaret(w.last + 1, w.first, hit.id); return null }
                    clicks >= 3 -> {
                        val pi = StoryEditing.locate(st, off).paragraph
                        val s = StoryEditing.paragraphStart(st, pi)
                        vm.setCaret(s + st.paragraphs[pi].length, s, hit.id); return null
                    }
                    shift -> vm.setCaret(off, te.anchor, hit.id)
                    else -> vm.setCaret(off, off, hit.id)
                }
                return Drag.TextSel(hit.id, if (shift) te.anchor else off)
            }
            vm.endTextEdit()
            if (tool != Tool.Type) vm.clearSelection()
        }

        if (tool.isDrawing) return Drag.Create(pl, tool, sp, sp)

        if (tool == Tool.Type) {
            val hit = HitTest.hit(d, pl.spread, sp, tol(), deep = true)
            if (hit != null && hit.isText) {
                val off = textOffsetAt(hit.id, sp) ?: 0
                vm.beginTextEdit(hit.id, off)
                return Drag.TextSel(hit.id, off)
            }
            return Drag.Create(pl, Tool.Type, sp, sp)
        }

        // Selection / Direct selection
        val frame = if (vm.selection.isNotEmpty()) selectionPlacement(d)?.let { spl -> selectionFrame(d, spl)?.let { spl to it } } else null
        if (frame != null && !shift) {
            val (spl, f) = frame
            val h = hitHandle(pos, f)
            if (h != null) {
                val idx = DocIndex.of(d)
                val ub = Rect.unionAll(vm.selection.mapNotNull { idx[it]?.worldBounds }) ?: return null
                return if (h == 8) {
                    val c = f.single?.let { l -> l.world.map(l.item.innerBounds.center) } ?: ub.center
                    val sp2 = spl.toSpread(vp.toView(pos.x, pos.y))
                    Drag.Rotate(spl, c, Math.toDegrees(atan2(sp2.y - c.y, sp2.x - c.x)), d, vm.selection.toSet())
                } else Drag.Resize(spl, h, d, vm.selection, f.single, ub)
            }
        }
        // Direct selection: anchor points of the selected item.
        if (tool == Tool.Direct && vm.selection.size == 1) {
            val loc = DocIndex.of(d)[vm.selection[0]]
            if (loc != null && !loc.item.isGroup && loc.spread.id == pl.spread.id) {
                val w = loc.world
                loc.item.paths.forEachIndexed { si, spth ->
                    spth.points.forEachIndexed { pi, pp ->
                        val s = toScreen(pl, w.map(pp.anchor))
                        if (hypot(s.x - pos.x, s.y - pos.y) <= handleRadius() * 1.3) return Drag.Anchor(pl, loc.item.id, si, pi, d)
                    }
                }
            }
        }
        val deep = tool == Tool.Direct || ctrl
        val hit = HitTest.hit(d, pl.spread, sp, tol(), deep)
        if (hit != null) {
            if (clicks >= 2 && hit.isText) {
                val off = textOffsetAt(hit.id, sp) ?: 0
                vm.beginTextEdit(hit.id, off)
                return null
            }
            if (clicks >= 2 && hit.isGroup) {
                HitTest.hit(d, pl.spread, sp, tol(), deep = true)?.let { vm.select(listOf(it.id)) }
                return null
            }
            if (shift) { vm.toggleSelect(hit.id); return null }
            if (hit.id !in vm.selection) vm.select(listOf(hit.id))
            var base = d
            var ids = vm.selection.toSet()
            if (alt) {
                val (nd, newIds) = ItemOps.duplicate(d, vm.selection, 0.0, 0.0)
                base = nd; ids = newIds.toSet()
            }
            val idx = DocIndex.of(base)
            val b = Rect.unionAll(ids.mapNotNull { idx[it]?.worldBounds }) ?: return null
            return Drag.Move(pl, sp, base, ids, b)
        }
        if (!shift) vm.clearSelection()
        return if (finger) Drag.Pan(pos, vp.panX, vp.panY) else Drag.Marquee(pl, sp, sp, shift)
    }

    // ---- drag update ----------------------------------------------------------------------
    fun updateDrag(dr: Drag, pos: Offset, shift: Boolean, alt: Boolean): Drag {
        val vp = vm.viewport
        val threshold = 6.0 * density / vp.scale
        when (dr) {
            is Drag.Pan -> vm.moveViewport(vp.copy(panX = dr.startPanX + pos.x - dr.start.x, panY = dr.startPanY + pos.y - dr.start.y))
            is Drag.ZoomRect -> return dr.copy(b = pos)
            is Drag.GuideDrag -> return dr.copy(pos = pos)
            is Drag.Marquee -> return dr.copy(b = dr.pl.toSpread(vp.toView(pos.x, pos.y)))
            is Drag.TextSel -> {
                val (pl, sp) = locate(pos) ?: return dr
                val d = vm.doc ?: return dr
                val te = vm.textEdit ?: return dr
                // Find the story frame under the pointer, else stay in the current one.
                val idx = DocIndex.of(d)
                val frames = idx.chain(te.storyId).filter { idx[it.id]?.spread?.id == pl.spread.id }
                val f = frames.firstOrNull { HitTest.contains(it, idx[it.id]!!.world, sp, tol()) }?.id ?: dr.frameId
                textOffsetAt(f, sp)?.let { vm.setCaret(it, dr.anchor, f) }
            }
            is Drag.Create -> {
                var b = dr.pl.toSpread(vp.toView(pos.x, pos.y))
                if (vm.view.snap) {
                    val t = Snapping.targets(vm.doc!!, dr.pl.spread, emptySet())
                    val s = Snapping.snapPoint(b, t, threshold)
                    b = Point(b.x + s.dx, b.y + s.dy)
                    vm.snapLines = s.xLine to s.yLine
                }
                if (shift) {
                    val dx = b.x - dr.a.x; val dy = b.y - dr.a.y
                    b = if (dr.tool == Tool.Line) {
                        val ang = Math.round(atan2(dy, dx) / (Math.PI / 4)) * (Math.PI / 4)
                        val len = hypot(dx, dy)
                        Point(dr.a.x + Math.cos(ang) * len, dr.a.y + Math.sin(ang) * len)
                    } else {
                        val m = max(abs(dx), abs(dy))
                        Point(dr.a.x + Math.signum(dx) * m, dr.a.y + Math.signum(dy) * m)
                    }
                }
                return dr.copy(b = b)
            }
            is Drag.Move -> {
                val sp = dr.pl.toSpread(vp.toView(pos.x, pos.y))
                var dx = sp.x - dr.a.x; var dy = sp.y - dr.a.y
                if (shift) { if (abs(dx) > abs(dy)) dy = 0.0 else dx = 0.0 }
                vm.snapLines = null to null
                if (vm.view.snap) {
                    val t = Snapping.targets(dr.base, dr.pl.spread, dr.ids, vm.view.showGuides)
                    val s = Snapping.snapRect(dr.startBounds.offset(dx, dy), t, threshold)
                    dx += s.dx; dy += s.dy
                    vm.snapLines = s.xLine to s.yLine
                }
                vm.showPreview(ItemOps.move(dr.base, dr.ids, dx, dy))
            }
            is Drag.Rotate -> {
                val sp = dr.pl.toSpread(vp.toView(pos.x, pos.y))
                var ang = Math.toDegrees(atan2(sp.y - dr.center.y, sp.x - dr.center.x)) - dr.startAngle
                if (shift) ang = (ang / 15.0).roundToInt() * 15.0
                vm.showPreview(ItemOps.rotate(dr.base, dr.ids, ang, dr.center))
            }
            is Drag.Resize -> {
                val sp = dr.pl.toSpread(vp.toView(pos.x, pos.y))
                val single = dr.single
                if (single != null && single.world.isRigid && !single.item.isGroup && single.item.kind != io.layertrace.core.model.ItemKind.Unknown) {
                    val lp = single.world.inverse().map(sp)
                    val nr = resizeRect(single.item.innerBounds, dr.handle, lp, shift, alt)
                    vm.showPreview(ItemOps.resizeLocal(dr.base, single.item.id, nr))
                } else {
                    val nr = resizeRect(dr.startBounds, dr.handle, sp, shift, alt)
                    vm.showPreview(ItemOps.scale(dr.base, dr.ids.toSet(), dr.startBounds, nr, scaleContent = true))
                }
            }
            is Drag.Anchor -> {
                val sp = dr.pl.toSpread(vp.toView(pos.x, pos.y))
                val loc = DocIndex.of(dr.base)[dr.itemId] ?: return dr
                val lp = loc.world.inverse().map(sp)
                vm.showPreview(dr.base.updateItem(dr.itemId) { item ->
                    item.copy(paths = item.paths.mapIndexed { si, spth ->
                        if (si != dr.sub) spth else spth.copy(points = spth.points.mapIndexed { pi, pp ->
                            if (pi != dr.pt) pp else {
                                val ddx = lp.x - pp.anchor.x; val ddy = lp.y - pp.anchor.y
                                io.layertrace.core.geom.PathPoint(lp, Point(pp.left.x + ddx, pp.left.y + ddy), Point(pp.right.x + ddx, pp.right.y + ddy))
                            }
                        })
                    })
                })
            }
        }
        return dr
    }

    fun endDrag(dr: Drag, moved: Boolean, pos: Offset, alt: Boolean) {
        vm.snapLines = null to null
        when (dr) {
            is Drag.Move -> vm.preview?.let { vm.commit(it, if (dr.base !== vm.doc) "Duplicate" else "Move"); vm.select(dr.ids.toList()) } ?: vm.showPreview(null)
            is Drag.Rotate -> vm.preview?.let { vm.commit(it, "Rotate") }
            is Drag.Resize -> vm.preview?.let { vm.commit(it, "Resize") }
            is Drag.Anchor -> vm.preview?.let { vm.commit(it, "Edit Path") }
            is Drag.Marquee -> if (moved) {
                val d = vm.doc ?: return
                val r = Rect.ofPoints(dr.a, dr.b)
                val ids = HitTest.inRect(d, dr.pl.spread, r).map { it.id }
                vm.select(if (dr.additive) (vm.selection + ids) else ids)
            }
            is Drag.Create -> {
                var a = dr.a; var b = dr.b
                if (!moved || (abs(b.x - a.x) < 2 && abs(b.y - a.y) < 2)) {
                    val (w, h) = when (dr.tool) { Tool.Type -> 240.0 to 72.0; Tool.Line -> 144.0 to 0.0; else -> 144.0 to 144.0 }
                    b = Point(a.x + w, a.y + h)
                }
                if (alt && dr.tool != Tool.Line) a = Point(2 * a.x - b.x, 2 * a.y - b.y)
                vm.createItem(dr.pl.spread.id, dr.tool, a, b)
                if (dr.tool != Tool.Type) vm.tool = Tool.Selection
            }
            is Drag.ZoomRect -> {
                if (!moved || hypot(dr.b.x - dr.a.x, dr.b.y - dr.a.y) < 10) vm.zoomBy(if (alt) 0.5f else 2f, dr.a.x, dr.a.y)
                else {
                    val (w, h) = vm.canvasSize
                    val a = vm.viewport.toView(dr.a.x, dr.a.y); val b = vm.viewport.toView(dr.b.x, dr.b.y)
                    vm.moveViewport(Viewport.fit(Rect.ofPoints(a, b), w, h, 0f))
                }
            }
            is Drag.GuideDrag -> {
                val d = vm.doc ?: return
                val (pl, sp) = locate(pos) ?: return
                if (pos.x < rulerSize() || pos.y < rulerSize()) return
                val page = Pagination.owningPage(pl.spread, sp) ?: return
                val lp = page.transform.inverse().map(sp)
                val (gid, d2) = d.newId()
                val g = Guide(gid, if (dr.vertical) GuideOrientation.Vertical else GuideOrientation.Horizontal, if (dr.vertical) lp.x else lp.y)
                val nd = d2.copy(
                    spreads = d2.spreads.map { s -> if (s.id != pl.spread.id) s else s.copy(pages = s.pages.map { if (it.id == page.id) it.copy(guides = it.guides + g) else it }) },
                    masters = d2.masters.map { s -> if (s.id != pl.spread.id) s else s.copy(pages = s.pages.map { if (it.id == page.id) it.copy(guides = it.guides + g) else it }) },
                )
                vm.commit(nd, "Add Guide")
            }
            else -> {}
        }
    }

    // ---- composable ------------------------------------------------------------------------
    Box(
        modifier
            .clipToBounds()
            .testTag("documentCanvas")
            .onSizeChanged { vm.onCanvasSize(it.width.toFloat(), it.height.toFloat()) }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { keys.onKey(it) }
            .storyInput(sink, vm.textEdit != null, showKeyboard)
            .pointerHoverIcon(icon)
            .pointerInput(vm) {
                // Hover and wheel
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: continue
                        when (ev.type) {
                            PointerEventType.Scroll -> {
                                val delta = ch.scrollDelta
                                val mods = ev.keyboardModifiers
                                if (mods.isCtrlPressed || mods.isMetaPressed || mods.isAltPressed) {
                                    vm.zoomBy(1.15f.pow(-delta.y), ch.position.x, ch.position.y)
                                } else if (mods.isShiftPressed) {
                                    vm.panBy(-delta.y * 60f * density / 2f, 0f)
                                } else {
                                    vm.panBy(-delta.x * 60f * density / 2f, -delta.y * 60f * density / 2f)
                                }
                                ch.consume()
                            }
                            PointerEventType.Move, PointerEventType.Enter -> if (!ev.buttons.isPrimaryPressed && ch.type != PointerType.Touch) {
                                val d = vm.doc
                                val loc = locate(ch.position)
                                val tool = if (keys.spaceDown) Tool.Hand else vm.tool
                                var hov: String? = null
                                if (d != null && loc != null && (tool == Tool.Selection || tool == Tool.Direct || tool == Tool.Type)) {
                                    hov = HitTest.hit(d, loc.first.spread, loc.second, tol(), tool == Tool.Direct)?.id
                                }
                                if (hov != vm.hoverId) vm.hoverId = hov
                                icon = when {
                                    threadFrom != null -> PointerIcon.Hand
                                    tool == Tool.Hand -> PointerIcon.Hand
                                    tool.isDrawing || tool == Tool.Zoom -> PointerIcon.Crosshair
                                    tool == Tool.Type -> PointerIcon.Text
                                    vm.textEdit != null && hov != null -> PointerIcon.Text
                                    else -> PointerIcon.Default
                                }
                            }
                            PointerEventType.Exit -> vm.hoverId = null
                        }
                    }
                }
            }
            .pointerInput(vm) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val firstEvent = currentEvent
                    val downPos = down.position
                    runCatching { focus.requestFocus() }
                    vm.contextMenu = null
                    val finger = down.type == PointerType.Touch
                    val buttons = firstEvent.buttons
                    val mods = firstEvent.keyboardModifiers
                    val slop = viewConfiguration.touchSlop / (if (finger) 1f else 3f)

                    suspend fun AwaitPointerEventScope.pinch() {
                        vm.showPreview(null); drag = null
                        var prevC: Offset? = null; var prevSpan = 0f
                        while (true) {
                            val e = awaitPointerEvent()
                            val pts = e.changes.filter { it.pressed }
                            if (pts.size < 2) break
                            val cx = pts.sumOf { it.position.x.toDouble() }.toFloat() / pts.size
                            val cy = pts.sumOf { it.position.y.toDouble() }.toFloat() / pts.size
                            val span = pts.sumOf { hypot((it.position.x - cx).toDouble(), (it.position.y - cy).toDouble()) }.toFloat() / pts.size
                            val pc = prevC
                            if (pc != null && prevSpan > 0f && span > 0f) {
                                vm.moveViewport(vm.viewport.zoomAt(span / prevSpan, cx, cy, vm.actualSizeScale * 0.02f, vm.actualSizeScale * 40f))
                                vm.panBy(cx - pc.x, cy - pc.y)
                            }
                            prevC = Offset(cx, cy); prevSpan = span
                            e.changes.forEach { it.consume() }
                        }
                        // Wait for remaining fingers to lift.
                        while (currentEvent.changes.any { it.pressed }) awaitPointerEvent().changes.forEach { it.consume() }
                    }

                    suspend fun AwaitPointerEventScope.waitUp() {
                        while (currentEvent.changes.any { it.pressed }) awaitPointerEvent().changes.forEach { it.consume() }
                    }

                    if (buttons.isSecondaryPressed) {
                        openContextMenu(downPos, deepHit = false)
                        waitUp()
                        return@awaitEachGesture
                    }
                    val now = System.currentTimeMillis()
                    clickCount = if (now - lastClickTime < 400 && (downPos - lastClickPos).getDistance() < 8 * density) clickCount + 1 else 1
                    lastClickTime = now; lastClickPos = downPos
                    val shift = mods.isShiftPressed; val alt = mods.isAltPressed; val ctrl = mods.isCtrlPressed || mods.isMetaPressed

                    var released = false
                    var lastPos = downPos
                    // Touch: wait for movement, release, a second finger, or a long press.
                    if (finger) {
                        var multi = false
                        val r = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.changes.count { it.pressed } > 1) { multi = true; break }
                                val c = e.changes.firstOrNull { it.id == down.id } ?: run { released = true; null } ?: break
                                lastPos = c.position
                                if (!c.pressed) { released = true; break }
                                if ((c.position - downPos).getDistance() > slop) break
                            }
                            true
                        }
                        if (multi) { pinch(); return@awaitEachGesture }
                        if (r == null) {
                            openContextMenu(downPos, deepHit = false)
                            waitUp()
                            return@awaitEachGesture
                        }
                    }

                    var dr: Drag? = if (buttons.isTertiaryPressed) Drag.Pan(downPos, vm.viewport.panX, vm.viewport.panY)
                    else beginDrag(downPos, shift, alt, ctrl, finger, clickCount)
                    drag = dr
                    var moved = (lastPos - downPos).getDistance() > slop
                    if (moved && dr != null) { dr = updateDrag(dr, lastPos, shift, alt); drag = dr }
                    var lastMods = mods
                    while (!released) {
                        val e = awaitPointerEvent()
                        if (e.changes.count { it.pressed } > 1) {
                            dr = null
                            pinch()
                            return@awaitEachGesture
                        }
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        lastPos = c.position
                        lastMods = e.keyboardModifiers
                        if (!c.pressed) break
                        if (!moved && (c.position - downPos).getDistance() > slop) moved = true
                        val cur = dr
                        if (moved && cur != null) { val nd = updateDrag(cur, c.position, lastMods.isShiftPressed, lastMods.isAltPressed); dr = nd; drag = nd }
                        c.consume()
                    }
                    dr?.let { endDrag(it, moved, lastPos, lastMods.isAltPressed) }
                    val fin = dr
                    if (!moved && fin is Drag.Move && !shift && clickCount == 1 && vm.selection.size > 1) {
                        // A plain click inside a multi-selection selects just the clicked item.
                        locate(downPos)?.let { (pl, sp) -> HitTest.hit(vm.doc!!, pl.spread, sp, tol(), false)?.let { vm.select(listOf(it.id)) } }
                    }
                    if (!moved) vm.showPreview(null)
                    drag = null
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val gen = vm.renderGeneration
            val bw = size.width.toInt(); val bh = size.height.toInt()
            if (bw <= 0 || bh <= 0) return@Canvas
            // Render into our own software bitmap: document transforms and clips can never leak
            // into Compose's (hardware) canvas, and output matches the JVM screenshot tests.
            val bmp = frame.obtain(bw, bh)
            drawDocument(android.graphics.Canvas(bmp), vm, colors, bw.toFloat(), bh.toFloat(), density, drag, threadFrom, ::selectionFrame, rulerSize())
            drawImage(bmp.asImageBitmap())
        }
    }

    // Expose threading-mode entry to the context menu via the view model.
    LaunchedEffect(vm.threadRequest) {
        vm.threadRequest?.let { threadFrom = it; vm.threadRequest = null; vm.message("Click a text frame to continue the story") }
    }
}

/** Compute a resized rectangle for handle [h] (TL,T,TR,R,BR,B,BL,L) dragged to [p]. */
fun resizeRect(r: Rect, h: Int, p: Point, proportional: Boolean, fromCenter: Boolean): Rect {
    var l = r.left; var t = r.top; var rt = r.right; var b = r.bottom
    val left = h == 0 || h == 6 || h == 7
    val right = h == 2 || h == 3 || h == 4
    val top = h == 0 || h == 1 || h == 2
    val bottom = h == 4 || h == 5 || h == 6
    if (left) l = p.x
    if (right) rt = p.x
    if (top) t = p.y
    if (bottom) b = p.y
    if (fromCenter) {
        if (left) rt = 2 * r.centerX - l
        if (right) l = 2 * r.centerX - rt
        if (top) b = 2 * r.centerY - t
        if (bottom) t = 2 * r.centerY - b
    }
    if (proportional && r.width > 0 && r.height > 0) {
        val sx = (rt - l) / r.width; val sy = (b - t) / r.height
        val s = if ((left || right) && (top || bottom)) (if (abs(sx) > abs(sy)) sx else sy) else if (left || right) sx else sy
        val nw = r.width * s; val nh = r.height * s
        if (fromCenter) {
            l = r.centerX - nw / 2; rt = r.centerX + nw / 2; t = r.centerY - nh / 2; b = r.centerY + nh / 2
        } else {
            if (left) l = rt - nw else if (right) rt = l + nw else { l = r.centerX - nw / 2; rt = r.centerX + nw / 2 }
            if (top) t = b - nh else if (bottom) b = t + nh else { t = r.centerY - nh / 2; b = r.centerY + nh / 2 }
        }
    }
    val nr = Rect(minOf(l, rt), minOf(t, b), maxOf(l, rt), maxOf(t, b))
    return if (nr.width < 1 || nr.height < 1) Rect(nr.left, nr.top, nr.left + max(1.0, nr.width), nr.top + max(1.0, nr.height)) else nr
}

private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

/** Reusable offscreen bitmap for the document view. */
private class FrameBuffer {
    private var bmp: android.graphics.Bitmap? = null
    fun obtain(w: Int, h: Int): android.graphics.Bitmap {
        val b = bmp
        if (b != null && b.width == w && b.height == h) return b
        b?.recycle()
        return android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { bmp = it }
    }
}

private fun drawDocument(
    c: android.graphics.Canvas,
    vm: EditorViewModel,
    colors: EditorColors,
    w: Float,
    h: Float,
    density: Float,
    drag: Any?,
    threadFrom: String?,
    selectionFrameOf: (Document, Placement) -> SelectionFrame?,
    rulerSize: Float,
) {
    c.drawRect(0f, 0f, w, h, Paint().apply { color = colors.pasteboard.toArgb() })
    val d = vm.shown ?: return
    val baseCount = c.saveCount
    val vp = vm.viewport
    val px = 1f / vp.scale
    val renderer = vm.engine.renderer
    val idx = DocIndex.of(d)
    val preview = vm.view.preview
    val shadow = Paint().apply { color = 0x66000000 }
    val accent = colors.selection.toArgb()

    for (pl in vm.placements) {
        val pb = pl.pageBounds
        val sl = vp.toScreenX(pb.left); val st = vp.toScreenY(pb.top); val sr = vp.toScreenX(pb.right); val sb = vp.toScreenY(pb.bottom)
        if (sr < -200 || sl > w + 200 || sb < -200 || st > h + 200) continue
        c.save()
        c.translate(vp.panX, vp.panY)
        c.scale(vp.scale, vp.scale)
        c.translate(pl.dx.toFloat(), pl.dy.toFloat())
        for (pg in pl.spread.pages) c.drawRect(pg.boundsInSpread.offset(3.0 * px, 3.0 * px).toRectF(), shadow)
        if (preview) c.clipRect(pl.spread.pageBounds.toRectF())
        renderer.drawPaper(c, d, pl.spread)
        renderer.drawSpread(c, d, pl.spread, RenderOptions(printOnly = preview, placeholders = !preview))
        if (!preview) {
            Overlays.drawBleed(c, d, pl.spread, px)
            if (vm.view.showGuides) Overlays.drawLayoutGuides(c, pl.spread, px, colors.margin.toArgb(), colors.column.toArgb(), colors.guide.toArgb())
            if (vm.view.showFrameEdges) Overlays.drawFrameEdges(c, d, pl.spread, px, colors.frameEdge.copy(alpha = 0.7f).toArgb())
            // Overset markers on the last frame of each overflowing story.
            for (item in pl.spread.items) item.walk { it, parent ->
                if (it.isText && it.nextFrameId == null && it.storyId != null) {
                    val layout = vm.storyLayoutFor(it.storyId!!)
                    if (layout?.overset == true) {
                        val m = parent * it.transform
                        val b = it.innerBounds
                        val p = m.map(Point(b.right, b.bottom))
                        val s = 10f * density * px
                        Overlays.fill.color = colors.overset.toArgb()
                        c.drawRect(p.x.toFloat() - s, p.y.toFloat() - s, p.x.toFloat(), p.y.toFloat(), Overlays.fill)
                        Overlays.stroke.color = -1; Overlays.stroke.strokeWidth = 1.5f * density * px
                        c.drawLine(p.x.toFloat() - s / 2, p.y.toFloat() - s * 0.8f, p.x.toFloat() - s / 2, p.y.toFloat() - s * 0.2f, Overlays.stroke)
                        c.drawLine(p.x.toFloat() - s * 0.8f, p.y.toFloat() - s / 2, p.x.toFloat() - s * 0.2f, p.y.toFloat() - s / 2, Overlays.stroke)
                    }
                }
            }
        }
        // Hover
        val hov = idx[vm.hoverId]
        if (hov != null && hov.spread.id == pl.spread.id && vm.hoverId !in vm.selection) {
            Overlays.outline(c, hov, px, (d.layer(hov.item.layerId)?.color?.argb ?: 0xFF4F99FF).toInt(), 1.5f)
        }
        // Selection outlines
        for (id in vm.selection) {
            val loc = idx[id] ?: continue
            if (loc.spread.id != pl.spread.id) continue
            val col = (d.layer(loc.item.layerId)?.color?.argb ?: 0xFF4F99FF).toInt()
            Overlays.outline(c, loc, px, col, 1f)
            // Text threads: draw connection lines between frames of the selected story.
            if (loc.item.isText && loc.item.storyId != null && !preview) {
                val chain = idx.chain(loc.item.storyId!!)
                Overlays.stroke.color = col; Overlays.stroke.strokeWidth = px
                for (i in 0 until chain.size - 1) {
                    val a = idx[chain[i].id] ?: continue; val b = idx[chain[i + 1].id] ?: continue
                    if (a.spread.id != pl.spread.id || b.spread.id != pl.spread.id) continue
                    val pa = a.world.map(Point(a.item.innerBounds.right, a.item.innerBounds.bottom))
                    val pb2 = b.world.map(Point(b.item.innerBounds.left, b.item.innerBounds.top))
                    c.drawLine(pa.x.toFloat(), pa.y.toFloat(), pb2.x.toFloat(), pb2.y.toFloat(), Overlays.stroke)
                }
            }
        }
        // Text selection and caret
        vm.textEdit?.let { te ->
            val layout = vm.storyLayoutFor(te.storyId) ?: return@let
            for (f in idx.chain(te.storyId)) {
                val loc = idx[f.id] ?: continue
                if (loc.spread.id != pl.spread.id) continue
                c.save()
                c.concat(loc.world.toAndroid())
                if (te.hasSelection) {
                    layout.selectionPath(f.id, te.start, te.end)?.let { p ->
                        Overlays.fill.color = (accent and 0x00FFFFFF) or 0x66000000
                        c.drawPath(p, Overlays.fill)
                    }
                }
                val car = layout.caret(te.caret)
                if (car != null && car.first == f.id && !te.hasSelection) {
                    Overlays.stroke.color = 0xFF000000.toInt(); Overlays.stroke.strokeWidth = 1.5f * density * px
                    c.drawLine(car.second.left, car.second.top, car.second.left, car.second.bottom, Overlays.stroke)
                }
                if (layout.frames.isEmpty() || (car == null && te.caret == 0 && f.id == te.frameId)) {
                    val b = f.innerBounds
                    Overlays.stroke.color = 0xFF000000.toInt(); Overlays.stroke.strokeWidth = 1.5f * density * px
                    c.drawLine(b.left.toFloat() + 1, b.top.toFloat(), b.left.toFloat() + 1, b.top.toFloat() + 12, Overlays.stroke)
                }
                c.restore()
            }
        }
        // Live overlays in spread space
        when (drag) {
            is Drag.Marquee -> if (drag.pl.spread.id == pl.spread.id) {
                val r = Rect.ofPoints(drag.a, drag.b)
                Overlays.fill.color = (accent and 0x00FFFFFF) or 0x22000000
                c.drawRect(r.toRectF(), Overlays.fill)
                Overlays.dashedRect(c, r, px, accent)
            }
            is Drag.Create -> if (drag.pl.spread.id == pl.spread.id) {
                Overlays.stroke.color = accent; Overlays.stroke.strokeWidth = px
                if (drag.tool == Tool.Line) c.drawLine(drag.a.x.toFloat(), drag.a.y.toFloat(), drag.b.x.toFloat(), drag.b.y.toFloat(), Overlays.stroke)
                else if (drag.tool == Tool.Ellipse) c.drawOval(Rect.ofPoints(drag.a, drag.b).toRectF(), Overlays.stroke)
                else c.drawRect(Rect.ofPoints(drag.a, drag.b).toRectF(), Overlays.stroke)
                val r = Rect.ofPoints(drag.a, drag.b)
                textPaint.color = accent; textPaint.textSize = 11f * density * px
                val u = vm.view.unit
                c.drawText("${u.format(r.width, false)} × ${u.format(r.height)}", drag.b.x.toFloat() + 6 * px, drag.b.y.toFloat() + 14 * density * px, textPaint)
            }
            else -> {}
        }
        // Snap lines
        val (sx, sy) = vm.snapLines
        Overlays.stroke.color = colors.snap.toArgb(); Overlays.stroke.strokeWidth = px
        if (sx != null) c.drawLine(sx.toFloat(), (pl.spread.pageBounds.top - 1000).toFloat(), sx.toFloat(), (pl.spread.pageBounds.bottom + 1000).toFloat(), Overlays.stroke)
        if (sy != null) c.drawLine((pl.spread.pageBounds.left - 1000).toFloat(), sy.toFloat(), (pl.spread.pageBounds.right + 1000).toFloat(), sy.toFloat(), Overlays.stroke)
        c.restore()
    }

    // Guard against any unbalanced save in content rendering.
    c.restoreToCount(baseCount)
    // Screen-space selection handles
    val selPl = DocIndex.of(d)[vm.selection.firstOrNull()]?.let { vm.placementOf(it.spread.id) }
    if (selPl != null && vm.textEdit == null && !preview) {
        val f = selectionFrameOf(d, selPl)
        if (f != null) {
            val r = (if (vm.workspace == WorkspaceMode.Touch) 7f else 4f) * density
            Overlays.stroke.color = accent; Overlays.stroke.strokeWidth = 1f * density
            c.drawPath(Overlays.polygon(f.corners), Overlays.stroke)
            val rh = f.rotateHandle(24f * density)
            val top = Point((f.corners[0].x + f.corners[1].x) / 2, (f.corners[0].y + f.corners[1].y) / 2)
            c.drawLine(top.x.toFloat(), top.y.toFloat(), rh.x.toFloat(), rh.y.toFloat(), Overlays.stroke)
            Overlays.fill.color = -1
            c.drawCircle(rh.x.toFloat(), rh.y.toFloat(), r, Overlays.fill)
            c.drawCircle(rh.x.toFloat(), rh.y.toFloat(), r, Overlays.stroke)
            for (hp in f.handles) {
                c.drawRect(hp.x.toFloat() - r, hp.y.toFloat() - r, hp.x.toFloat() + r, hp.y.toFloat() + r, Overlays.fill)
                c.drawRect(hp.x.toFloat() - r, hp.y.toFloat() - r, hp.x.toFloat() + r, hp.y.toFloat() + r, Overlays.stroke)
            }
            // Direct selection: anchor points
            if (vm.tool == Tool.Direct && vm.selection.size == 1) {
                val loc = DocIndex.of(d)[vm.selection[0]]
                if (loc != null && !loc.item.isGroup) {
                    val wm = loc.world
                    Overlays.fill.color = accent
                    for (sp in loc.item.paths) for (pp in sp.points) {
                        val s = vp.toScreen(selPl.toView(wm.map(pp.anchor)))
                        c.drawRect(s.x.toFloat() - r * 0.8f, s.y.toFloat() - r * 0.8f, s.x.toFloat() + r * 0.8f, s.y.toFloat() + r * 0.8f, Overlays.fill)
                    }
                }
            }
        }
    }
    if (drag is Drag.ZoomRect) {
        Overlays.dashedRect(c, Rect.ofPoints(Point(drag.a.x.toDouble(), drag.a.y.toDouble()), Point(drag.b.x.toDouble(), drag.b.y.toDouble())), density, accent)
    }
    if (threadFrom != null) {
        textPaint.color = accent; textPaint.textSize = 13f * density
        c.drawText("Threading: click a text frame (Esc to cancel)", 16f * density + rulerSize, h - 16f * density, textPaint)
    }
    if (rulerSize > 0) drawRulers(c, vm, colors, w, h, density, rulerSize, drag)
}

private fun drawRulers(c: android.graphics.Canvas, vm: EditorViewModel, colors: EditorColors, w: Float, h: Float, density: Float, rs: Float, drag: Any?) {
    val bg = Paint().apply { color = colors.chromeLow.toArgb() }
    val line = Paint().apply { color = colors.textDim.toArgb(); strokeWidth = 1f }
    val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.textDim.toArgb(); textSize = 9f * density }
    c.drawRect(0f, 0f, w, rs, bg)
    c.drawRect(0f, 0f, rs, h, bg)
    val vp = vm.viewport
    val unit = vm.view.unit
    // Origin: top-left of the current spread's first page.
    val spread = vm.currentSpread() ?: return
    val pl = vm.placementOf(spread.id) ?: return
    val first = spread.pages.firstOrNull() ?: return
    val origin = vp.toScreen(pl.toView(first.boundsInSpread.let { Point(it.left, it.top) }))
    val pxPerUnit = vp.scale * unit.points.toFloat()
    val steps = listOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0)
    val step = steps.firstOrNull { it * pxPerUnit >= 60 * density } ?: 5000.0
    val stepPx = (step * pxPerUnit).toFloat()
    fun ticks(start: Float, len: Float, horizontal: Boolean) {
        var k = Math.floor(((rs - start) / stepPx).toDouble()).toInt()
        while (true) {
            val p = start + k * stepPx
            if (p > len) break
            if (p >= rs) {
                for (m in 0 until 10) {
                    val q = p + m * stepPx / 10
                    if (q > len) break
                    val tl = if (m == 0) rs else if (m == 5) rs * 0.5f else rs * 0.25f
                    if (horizontal) c.drawLine(q, rs, q, rs - tl, line) else c.drawLine(rs, q, rs - tl, q, line)
                }
                val label = io.layertrace.core.geom.fmt(k * step)
                if (horizontal) c.drawText(label, p + 2 * density, 9f * density, txt)
                else {
                    c.save(); c.rotate(-90f, 9f * density, p - 2 * density); c.drawText(label, 9f * density, p - 2 * density, txt); c.restore()
                }
            }
            k++
        }
    }
    ticks(origin.x.toFloat(), w, true)
    ticks(origin.y.toFloat(), h, false)
    c.drawRect(0f, 0f, rs, rs, bg)
    txt.textSize = 8f * density
    c.drawText(unit.label, 3f * density, rs - 5f * density, txt)
    if (drag is Drag.GuideDrag) {
        val gp = Paint().apply { color = colors.guide.toArgb(); strokeWidth = density }
        if (drag.vertical) c.drawLine(drag.pos.x, rs, drag.pos.x, h, gp) else c.drawLine(rs, drag.pos.y, w, drag.pos.y, gp)
    }
}
