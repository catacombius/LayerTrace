package io.layertrace.app.editor

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.layertrace.app.canvas.Placement
import io.layertrace.app.canvas.SpreadLayout
import io.layertrace.app.canvas.Viewport
import io.layertrace.app.io.Prefs
import io.layertrace.app.render.Engine
import io.layertrace.app.render.FontRegistry
import io.layertrace.app.render.ImageCache
import io.layertrace.app.render.PdfExporter
import io.layertrace.app.render.StoryLayout
import io.layertrace.core.edit.DocIndex
import io.layertrace.core.edit.DocumentFactory
import io.layertrace.core.edit.History
import io.layertrace.core.edit.ItemOps
import io.layertrace.core.edit.ItemOps.updateItem
import io.layertrace.core.edit.ItemOps.updateItems
import io.layertrace.core.edit.Pagination
import io.layertrace.core.edit.Threading
import io.layertrace.core.geom.Matrix
import io.layertrace.core.geom.Point
import io.layertrace.core.geom.Rect
import io.layertrace.core.geom.Shapes
import io.layertrace.core.idml.IdmlFormatException
import io.layertrace.core.idml.IdmlReader
import io.layertrace.core.idml.IdmlWriter
import io.layertrace.core.idml.InddDetector
import io.layertrace.core.idml.InddImporter
import io.layertrace.core.model.CharFormat
import io.layertrace.core.model.CharacterStyle
import io.layertrace.core.model.Document
import io.layertrace.core.model.Ids
import io.layertrace.core.model.ItemKind
import io.layertrace.core.model.Justification
import io.layertrace.core.model.Layer
import io.layertrace.core.model.LayerColor
import io.layertrace.core.model.PageItem
import io.layertrace.core.model.ParagraphStyle
import io.layertrace.core.model.PlacedImage
import io.layertrace.core.model.Spread
import io.layertrace.core.model.Story
import io.layertrace.core.model.Swatch
import io.layertrace.core.model.TextAttrs
import io.layertrace.core.text.Placeholder
import io.layertrace.core.text.StoryEditing
import io.layertrace.core.text.StyleResolver
import io.layertrace.core.xml.XmlNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class Dialog {
    data object NewDocument : Dialog()
    data object DocumentSetup : Dialog()
    data object ExportPdf : Dialog()
    data class EditSwatch(val swatch: Swatch?) : Dialog()
    data class EditParagraphStyle(val style: ParagraphStyle?) : Dialog()
    data class EditCharacterStyle(val style: CharacterStyle?) : Dialog()
    data class RenameLayer(val layer: Layer) : Dialog()
    data class InddInfo(val fileName: String, val uri: Uri) : Dialog()
    data class InddImported(val fileName: String, val result: InddImporter.Result) : Dialog()
    data object Shortcuts : Dialog()
    data object About : Dialog()
    data class ConfirmDiscard(val then: () -> Unit) : Dialog()
    data object Recover : Dialog()
}

sealed class FileRequest {
    data object OpenDocument : FileRequest()
    data class SaveDocument(val suggestedName: String) : FileRequest()
    data class ExportPdf(val suggestedName: String, val pages: List<Int>, val bleed: Boolean) : FileRequest()
    data class ExportPng(val suggestedName: String) : FileRequest()
    data object PlaceImage : FileRequest()
    data object ImportFont : FileRequest()
}

data class ContextMenuRequest(val x: Float, val y: Float, val onItem: Boolean)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    val engine = Engine(app)
    val prefs = Prefs(app)

    // ---- observable state --------------------------------------------------------------------
    var doc by mutableStateOf<Document?>(null); private set
    /** Live preview while dragging; drawn instead of [doc]. */
    var preview by mutableStateOf<Document?>(null); private set
    var history by mutableStateOf(History()); private set
    var fileUri by mutableStateOf<Uri?>(null); private set
    var dirty by mutableStateOf(false); private set

    var tool by mutableStateOf(Tool.Selection)
    var selection by mutableStateOf<List<String>>(emptyList()); private set
    var textEdit by mutableStateOf<TextEdit?>(null); private set
    var hoverId by mutableStateOf<String?>(null)
    var editingMasters by mutableStateOf(false); private set
    var activeLayerId by mutableStateOf<String?>(null)
    var colorTarget by mutableStateOf(ColorTarget.Fill)
    var panelTab by mutableStateOf(PanelTab.Properties)
    var view by mutableStateOf(ViewOptions())
    var workspace by mutableStateOf(WorkspaceMode.KeyboardMouse); private set
    var viewport by mutableStateOf(Viewport(1f, 0f, 0f))
    var canvasSize by mutableStateOf(0f to 0f)
    var dialog by mutableStateOf<Dialog?>(null)
    var fileRequest by mutableStateOf<FileRequest?>(null)
    var contextMenu by mutableStateOf<ContextMenuRequest?>(null)
    var snapLines by mutableStateOf<Pair<Double?, Double?>>(null to null)
    var busy by mutableStateOf<String?>(null); private set
    /** Set to a text frame id to enter "click the next frame" threading mode on the canvas. */
    var threadRequest by mutableStateOf<String?>(null)
    /** True when the current window uses the compact/medium layout (panels in a drawer or sheet). */
    var compactLayout by mutableStateOf(false)
    var drawerOpen by mutableStateOf(false)
    /** Suggested workspace after a keyboard/mouse was connected or disconnected. */
    var workspaceSuggestion by mutableStateOf<WorkspaceMode?>(null)
    var recentFiles by mutableStateOf<List<Prefs.Recent>>(emptyList()); private set

    /** Bumped when fonts change so the canvas re-renders. */
    var renderGeneration by mutableStateOf(0); private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private var clipboard: Pair<List<PageItem>, Map<String, Story>>? = null
    private var clipboardSpread: String? = null
    private var pendingPlaceFrame: String? = null

    val shown: Document? get() = preview ?: doc

    init {
        viewModelScope.launch {
            val p = prefs.load()
            if (!workspaceChanged) workspace = p.workspace
            if (!unitChanged) view = view.copy(unit = p.unit)
            recentFiles = p.recent
            if (autosaveFile.exists() && doc == null) dialog = Dialog.Recover
        }
        viewModelScope.launch {
            while (true) {
                delay(45_000)
                autosave()
            }
        }
    }

    fun message(text: String) { _messages.tryEmit(text) }

    // ---- document lifecycle ------------------------------------------------------------------

    fun newDocument(params: DocumentFactory.Params) {
        val d = DocumentFactory.create(params)
        load(d, null)
        dirty = true
    }

    private fun load(d: Document, uri: Uri?) {
        doc = d
        preview = null
        history = History()
        fileUri = uri
        dirty = false
        selection = emptyList()
        textEdit = null
        editingMasters = false
        activeLayerId = d.layers.firstOrNull { !it.locked }?.id ?: d.layers.firstOrNull()?.id
        engine.text.clear()
        tool = Tool.Selection
        fitPage(0)
    }

    fun requestClose(then: () -> Unit = {}) {
        if (doc != null && dirty) dialog = Dialog.ConfirmDiscard { closeNow(); then() }
        else { closeNow(); then() }
    }

    private fun closeNow() {
        doc = null; preview = null; history = History(); fileUri = null; dirty = false
        selection = emptyList(); textEdit = null
        autosaveFile.delete()
    }

    fun openUri(uri: Uri) {
        val ctx = getApplication<Application>()
        val name = displayName(uri)
        busy = "Opening $name…"
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                when (InddDetector.sniff(bytes.copyOf(minOf(16, bytes.size)))) {
                    InddDetector.Kind.INDD -> { dialog = Dialog.InddInfo(name, uri); return@launch }
                    InddDetector.Kind.UNKNOWN -> { message("$name is not an IDML file"); return@launch }
                    else -> {}
                }
                val d = withContext(Dispatchers.Default) { IdmlReader.read(bytes, name.substringBeforeLast('.')) }
                runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                load(d, uri)
                addRecent(uri, name)
                val missing = d.paragraphStyles.mapNotNull { it.attrs.fontFamily }.toSet()
                    .plus(d.stories.values.flatMap { s -> s.paragraphs.flatMap { p -> p.runs.mapNotNull { it.format.overrides.fontFamily } } })
                    .filterNot { engine.fonts.isAvailable(it) }
                if (missing.isNotEmpty()) message("Substituted fonts: ${missing.take(4).joinToString()}${if (missing.size > 4) "…" else ""}. Import them via File › Import Font.")
            } catch (e: IdmlFormatException) {
                message(e.message ?: "Could not open file")
            } catch (e: Exception) {
                message("Could not open $name: ${e.message}")
            } finally {
                busy = null
            }
        }
    }

    /** Build a new document from an .indd's embedded metadata and page preview (see [InddImporter]). */
    fun importIndd(uri: Uri) {
        val ctx = getApplication<Application>()
        val name = displayName(uri)
        dialog = null
        busy = "Importing $name…"
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { InddImporter.import(it, name) } }
                load(r.document.copy(name = r.document.name.removeSuffix(".idml")), null)
                dirty = true
                dialog = Dialog.InddImported(name, r)
            } catch (e: Exception) {
                message("Could not import $name: ${e.message}")
            } finally {
                busy = null
            }
        }
    }

    fun save(saveAs: Boolean = false) {
        val d = doc ?: return
        val uri = fileUri
        if (uri == null || saveAs) {
            fileRequest = FileRequest.SaveDocument("${d.name}.idml")
            return
        }
        writeTo(uri)
    }

    fun writeTo(uri: Uri) {
        val d = doc ?: return
        val ctx = getApplication<Application>()
        busy = "Saving…"
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) { IdmlWriter.write(d) }
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }
                runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                val name = displayName(uri)
                fileUri = uri
                if (doc === d) {
                    doc = d.copy(name = name.substringBeforeLast('.'))
                    dirty = false
                }
                addRecent(uri, name)
                autosaveFile.delete()
                message("Saved $name")
            } catch (e: Exception) {
                message("Save failed: ${e.message}")
            } finally {
                busy = null
            }
        }
    }

    fun exportPdf(uri: Uri, pages: List<Int>, bleed: Boolean) {
        val d = doc ?: return
        val ctx = getApplication<Application>()
        busy = "Exporting PDF…"
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { PdfExporter.export(d, engine.renderer, pages, bleed, it) }
                }
                message("Exported ${pages.size} page(s) to PDF")
            } catch (e: Exception) {
                message("PDF export failed: ${e.message}")
            } finally { busy = null }
        }
    }

    fun exportPng(uri: Uri) {
        val d = doc ?: return
        val ctx = getApplication<Application>()
        val pageIdx = currentPageIndex()
        busy = "Exporting PNG…"
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val (spread, page) = d.spreads.flatMap { s -> s.pages.map { s to it } }[pageIdx]
                    // 150 ppi
                    val bmp = engine.renderer.renderPageBitmap(d, spread, page, (page.bounds.width / 72.0 * 150).toInt())
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                }
                message("Exported page ${pageIdx + 1} as PNG")
            } catch (e: Exception) {
                message("PNG export failed: ${e.message}")
            } finally { busy = null }
        }
    }

    private val autosaveFile get() = File(getApplication<Application>().filesDir, "autosave/recovery.idml")

    private suspend fun autosave() {
        val d = doc ?: return
        if (!dirty) return
        withContext(Dispatchers.IO) {
            runCatching {
                autosaveFile.parentFile?.mkdirs()
                val tmp = File(autosaveFile.parentFile, "recovery.tmp")
                tmp.writeBytes(IdmlWriter.write(d))
                tmp.renameTo(autosaveFile)
            }
        }
    }

    fun recover(accept: Boolean) {
        dialog = null
        if (!accept) { autosaveFile.delete(); return }
        viewModelScope.launch {
            try {
                val d = withContext(Dispatchers.IO) { IdmlReader.read(autosaveFile.readBytes(), "Recovered") }
                load(d, null)
                dirty = true
            } catch (e: Exception) {
                message("Recovery failed: ${e.message}")
                autosaveFile.delete()
            }
        }
    }

    private fun addRecent(uri: Uri, name: String) {
        viewModelScope.launch { recentFiles = prefs.addRecent(uri.toString(), name) }
    }

    fun removeRecent(r: Prefs.Recent) {
        viewModelScope.launch { recentFiles = prefs.removeRecent(r.uri) }
    }

    private fun displayName(uri: Uri): String {
        val ctx = getApplication<Application>()
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document.idml"
    }

    // ---- edit plumbing -----------------------------------------------------------------------

    /** Replace the document, recording an undo step. */
    fun commit(newDoc: Document, label: String, coalesce: String? = null) {
        val old = doc ?: return
        preview = null
        if (newDoc === old) return
        history = history.record(old, label, coalesceKey = coalesce)
        doc = newDoc
        dirty = true
        pruneSelection()
    }

    fun showPreview(d: Document?) { preview = d }

    private fun pruneSelection() {
        val d = doc ?: return
        val idx = DocIndex.of(d)
        val kept = selection.filter { idx[it] != null }
        if (kept != selection) selection = kept
        textEdit?.let { te ->
            val st = d.stories[te.storyId]
            if (st == null || idx[te.frameId] == null) textEdit = null
            else if (te.caret > st.length || te.anchor > st.length) textEdit = te.copy(caret = te.caret.coerceAtMost(st.length), anchor = te.anchor.coerceAtMost(st.length))
        }
    }

    fun undo() {
        val d = doc ?: return
        val (prev, h) = history.undo(d) ?: return
        doc = prev; history = h; dirty = true; preview = null
        pruneSelection()
    }

    fun redo() {
        val d = doc ?: return
        val (next, h) = history.redo(d) ?: return
        doc = next; history = h; dirty = true; preview = null
        pruneSelection()
    }

    // ---- selection ---------------------------------------------------------------------------

    fun select(ids: List<String>) {
        selection = ids.distinct()
        if (textEdit != null && ids.isNotEmpty()) textEdit = null
    }

    fun toggleSelect(id: String) {
        selection = if (id in selection) selection - id else selection + id
        textEdit = null
    }

    fun clearSelection() { selection = emptyList(); textEdit = null }

    val selectedItems: List<PageItem>
        get() {
            val d = shown ?: return emptyList()
            val idx = DocIndex.of(d)
            return selection.mapNotNull { idx[it]?.item }
        }

    fun selectAll() {
        val d = doc ?: return
        if (textEdit != null) {
            val st = d.stories[textEdit!!.storyId] ?: return
            textEdit = textEdit!!.copy(caret = st.length, anchor = 0)
            return
        }
        val spread = currentSpread() ?: return
        val layers = d.layers.filter { it.visible && !it.locked }.map { it.id }.toSet()
        selection = spread.items.filter { it.layerId in layers && !it.locked }.map { it.id }
    }

    // ---- spreads & viewport ------------------------------------------------------------------

    val visibleSpreads: List<Spread> get() = shown?.let { if (editingMasters) it.masters else it.spreads } ?: emptyList()

    val placements: List<Placement> get() = SpreadLayout.place(visibleSpreads)

    fun placementOf(spreadId: String): Placement? = placements.firstOrNull { it.spread.id == spreadId }

    fun currentSpread(): Spread? {
        val (w, h) = canvasSize
        val center = viewport.toView(w / 2, h / 2)
        val pl = placements
        if (pl.isEmpty()) return null
        return pl.minBy { p ->
            val b = p.pageBounds
            val dy = maxOf(b.top - center.y, 0.0, center.y - b.bottom)
            dy
        }.spread
    }

    fun currentPageIndex(): Int {
        val d = doc ?: return 0
        if (editingMasters) return 0
        val (w, h) = canvasSize
        val center = viewport.toView(w / 2, h / 2)
        var best = 0; var bestD = Double.MAX_VALUE; var i = 0
        for (p in placements) for (pg in p.spread.pages) {
            val b = pg.boundsInSpread.offset(p.dx, p.dy)
            val dx = maxOf(b.left - center.x, 0.0, center.x - b.right)
            val dy = maxOf(b.top - center.y, 0.0, center.y - b.bottom)
            val dist = dx * dx + dy * dy
            if (dist < bestD) { bestD = dist; best = i }
            i++
        }
        return best.coerceAtMost(d.pageCount - 1)
    }

    /** Smallest zoom: whole document; largest: 4000%. */
    private val density get() = getApplication<Application>().resources.displayMetrics.density
    val actualSizeScale get() = density * 160f / 72f
    fun zoomPercent() = viewport.scale / actualSizeScale * 100f

    fun zoomBy(factor: Float, sx: Float? = null, sy: Float? = null) {
        val (w, h) = canvasSize
        autoFit = null
        viewport = viewport.zoomAt(factor, sx ?: w / 2, sy ?: h / 2, actualSizeScale * 0.02f, actualSizeScale * 40f)
    }

    fun setZoomPercent(pct: Float) {
        val (w, h) = canvasSize
        autoFit = null
        viewport = viewport.zoomAt(pct / 100f * actualSizeScale / viewport.scale, w / 2, h / 2, actualSizeScale * 0.02f, actualSizeScale * 40f)
    }

    fun panBy(dx: Float, dy: Float) {
        autoFit = null
        viewport = viewport.copy(panX = viewport.panX + dx, panY = viewport.panY + dy)
    }

    /** A viewport change made directly by the user (drag-pan, pinch, zoom rectangle). */
    fun moveViewport(v: Viewport) {
        autoFit = null
        viewport = v
    }

    /** What to refit when the canvas is resized (first layout, fold/unfold, DeX window resize); null once the user zooms or pans. */
    private var autoFit: (() -> Unit)? = null

    fun fitPage(index: Int) {
        val (w, h) = canvasSize
        autoFit = { fitPage(index) }
        if (w <= 0f) { pendingFit = index; return }
        var i = 0
        for (p in placements) for (pg in p.spread.pages) {
            if (i == index) {
                viewport = Viewport.fit(pg.boundsInSpread.offset(p.dx, p.dy), w, h, 24f * density)
                return
            }
            i++
        }
        placements.firstOrNull()?.let { viewport = Viewport.fit(it.pageBounds, w, h, 24f * density) }
    }

    private var pendingFit: Int? = 0

    fun onCanvasSize(w: Float, h: Float) {
        val old = canvasSize
        canvasSize = w to h
        val pf = pendingFit
        if (pf != null && w > 0) { pendingFit = null; fitPage(pf); return }
        if (w > 0 && h > 0 && (old.first != w || old.second != h)) autoFit?.let { it(); return }
        // Keep the centre stable across fold/unfold and window resizes.
        if (old.first > 0 && (old.first != w || old.second != h)) {
            viewport = viewport.copy(panX = viewport.panX + (w - old.first) / 2, panY = viewport.panY + (h - old.second) / 2)
        }
    }

    fun fitSpread() {
        val (w, h) = canvasSize
        val s = currentSpread() ?: return
        autoFit = { fitSpread() }
        placementOf(s.id)?.let { viewport = Viewport.fit(it.pageBounds, w, h, 24f * density) }
    }

    fun goToPage(index: Int) {
        val d = doc ?: return
        if (editingMasters) switchMasters(false)
        fitPage(index.coerceIn(0, d.pageCount - 1))
    }

    fun switchMasters(on: Boolean) {
        if (editingMasters == on) return
        editingMasters = on
        selection = emptyList(); textEdit = null
        pendingFit = null
        fitPage(0)
    }

    // ---- workspace / view --------------------------------------------------------------------

    private var workspaceChanged = false
    private var unitChanged = false

    fun changeWorkspace(mode: WorkspaceMode) {
        workspaceChanged = true
        workspace = mode
        viewModelScope.launch { prefs.setWorkspace(mode) }
    }

    fun setUnit(u: io.layertrace.core.model.MeasureUnit) {
        unitChanged = true
        view = view.copy(unit = u)
        viewModelScope.launch { prefs.setUnit(u) }
    }

    // ---- item creation -----------------------------------------------------------------------

    private fun activeLayer(d: Document): String {
        val l = d.layer(activeLayerId)
        if (l != null && !l.locked && l.visible) return l.id
        return d.layers.firstOrNull { !it.locked && it.visible }?.id ?: d.layers.first().id
    }

    /** Create a new item from a drag rectangle in spread coordinates. Returns its id. */
    fun createItem(spreadId: String, t: Tool, a: Point, b: Point): String? {
        val d0 = doc ?: return null
        if (d0.layers.isEmpty()) return null
        val r = Rect.ofPoints(a, b)
        var (id, d) = d0.newId()
        val layer = activeLayer(d)
        val item = when (t) {
            Tool.Rectangle -> PageItem(id, ItemKind.Rectangle, layer, paths = Shapes.rect(r), stroke = Ids.COLOR_BLACK, strokeWeight = 1.0)
            Tool.Frame -> PageItem(
                id, ItemKind.Rectangle, layer, paths = Shapes.rect(r),
                passthrough = XmlNode("Rectangle", linkedMapOf("ContentType" to "GraphicType", "AppliedObjectStyle" to "ObjectStyle/\$ID/[Normal Graphics Frame]")),
            )
            Tool.Ellipse -> PageItem(id, ItemKind.Oval, layer, paths = Shapes.ellipse(r), stroke = Ids.COLOR_BLACK, strokeWeight = 1.0)
            Tool.Polygon -> PageItem(id, ItemKind.Polygon, layer, paths = Shapes.polygon(r, 6), stroke = Ids.COLOR_BLACK, strokeWeight = 1.0)
            Tool.Line -> PageItem(id, ItemKind.GraphicLine, layer, paths = Shapes.line(a, b), stroke = Ids.COLOR_BLACK, strokeWeight = 1.0)
            Tool.Type -> {
                val (sid, d2) = d.newId(); d = d2
                val paraStyle = d.paragraphStyle(Ids.PARA_BASIC)?.id ?: d.paragraphStyles.firstOrNull()?.id ?: Ids.PARA_BASIC
                d = d.copy(stories = d.stories + (sid to StoryEditing.ofText(sid, "", paraStyle)))
                PageItem(id, ItemKind.TextFrame, layer, paths = Shapes.rect(r), storyId = sid)
            }
            else -> return null
        }
        d = d.copy(spreads = d.spreads.map { if (it.id == spreadId) it.copy(items = it.items + item) else it },
            masters = d.masters.map { if (it.id == spreadId) it.copy(items = it.items + item) else it })
        commit(d, "Create ${t.label}")
        selection = listOf(id)
        if (t == Tool.Type) textEdit = TextEdit(item.storyId!!, id, 0)
        return id
    }

    // ---- actions -----------------------------------------------------------------------------

    fun isEnabled(a: Act): Boolean {
        val d = doc
        val hasDoc = d != null
        val sel = selection.isNotEmpty()
        val editing = textEdit != null
        return when (a) {
            Act.New, Act.Open, Act.ShortcutsHelp, Act.About, Act.ImportFont, Act.ToggleWorkspace -> true
            Act.Undo -> history.canUndo
            Act.Redo -> history.canRedo
            Act.Cut, Act.Copy, Act.Delete -> sel || (editing && textEdit!!.hasSelection)
            Act.Paste, Act.PasteInPlace -> hasDoc && (clipboard != null || editing)
            Act.Duplicate, Act.BringToFront, Act.BringForward, Act.SendBackward, Act.SendToBack, Act.Lock,
            Act.FlipH, Act.FlipV, Act.Rotate90CW, Act.Rotate90CCW -> sel && !editing
            Act.Group, Act.DistributeH, Act.DistributeV -> selection.size >= 2 && !editing
            Act.AlignLeft, Act.AlignCenterH, Act.AlignRight, Act.AlignTop, Act.AlignCenterV, Act.AlignBottom -> sel && !editing
            Act.Ungroup -> selectedItems.any { it.isGroup }
            Act.ThreadFrames -> selectedItems.size == 2 && selectedItems.all { it.isText }
            Act.UnthreadFrame -> selectedItems.size == 1 && selectedItems[0].nextFrameId != null
            Act.FillPlaceholder -> editing || selectedItems.any { it.isText }
            Act.FitContentProportionally, Act.FillFrameProportionally, Act.CenterContent -> selectedItems.any { it.image != null }
            Act.DeletePage -> hasDoc && !editingMasters && d!!.pageCount > 1
            Act.NewCharacterStyle -> editing
            else -> hasDoc
        }
    }

    fun perform(a: Act) {
        if (!isEnabled(a)) return
        contextMenu = null
        when (a) {
            Act.New -> dialog = if (doc != null && dirty) Dialog.ConfirmDiscard { dialog = Dialog.NewDocument } else Dialog.NewDocument
            Act.Open -> if (doc != null && dirty) dialog = Dialog.ConfirmDiscard { closeNow(); fileRequest = FileRequest.OpenDocument } else fileRequest = FileRequest.OpenDocument
            Act.Save -> save()
            Act.SaveAs -> save(saveAs = true)
            Act.Close -> requestClose()
            Act.ExportPdf -> dialog = Dialog.ExportPdf
            Act.ExportPng -> fileRequest = FileRequest.ExportPng("${doc?.name ?: "page"}-${currentPageIndex() + 1}.png")
            Act.DocumentSetup -> dialog = Dialog.DocumentSetup
            Act.ImportFont -> fileRequest = FileRequest.ImportFont
            Act.Place -> { pendingPlaceFrame = selectedItems.singleOrNull()?.takeIf { !it.isText && !it.isGroup && it.kind != ItemKind.GraphicLine }?.id; fileRequest = FileRequest.PlaceImage }

            Act.Undo -> undo()
            Act.Redo -> redo()
            Act.Copy -> copy()
            Act.Cut -> { copy(); deleteSelection("Cut") }
            Act.Paste -> paste(inPlace = false)
            Act.PasteInPlace -> paste(inPlace = true)
            Act.Duplicate -> { val (d, ids) = ItemOps.duplicate(doc!!, selection, 12.0, 12.0); commit(d, "Duplicate"); selection = ids }
            Act.Delete -> if (textEdit?.hasSelection == true) textReplace("") else deleteSelection("Delete")
            Act.SelectAll -> selectAll()
            Act.Deselect -> clearSelection()

            Act.BringToFront -> commit(ItemOps.arrange(doc!!, selection.toSet(), ItemOps.Arrange.BringToFront), a.label)
            Act.BringForward -> commit(ItemOps.arrange(doc!!, selection.toSet(), ItemOps.Arrange.BringForward), a.label)
            Act.SendBackward -> commit(ItemOps.arrange(doc!!, selection.toSet(), ItemOps.Arrange.SendBackward), a.label)
            Act.SendToBack -> commit(ItemOps.arrange(doc!!, selection.toSet(), ItemOps.Arrange.SendToBack), a.label)
            Act.Group -> { val (d, gid) = ItemOps.group(doc!!, selection); if (gid != null) { commit(d, "Group"); selection = listOf(gid) } else message("Select items on the same spread to group") }
            Act.Ungroup -> { val (d, ids) = ItemOps.ungroup(doc!!, selection.toSet()); commit(d, "Ungroup"); selection = ids }
            Act.Lock -> { commit(doc!!.updateItems(selection.toSet()) { it.copy(locked = true) }, "Lock"); selection = emptyList() }
            Act.UnlockAll -> { val s = currentSpread() ?: return; val ids = s.items.filter { it.locked }.map { it.id }.toSet(); commit(doc!!.updateItems(ids) { it.copy(locked = false) }, "Unlock All"); selection = ids.toList() }
            Act.AlignLeft -> align(ItemOps.Align.Left)
            Act.AlignCenterH -> align(ItemOps.Align.CenterH)
            Act.AlignRight -> align(ItemOps.Align.Right)
            Act.AlignTop -> align(ItemOps.Align.Top)
            Act.AlignCenterV -> align(ItemOps.Align.CenterV)
            Act.AlignBottom -> align(ItemOps.Align.Bottom)
            Act.DistributeH -> commit(ItemOps.distribute(doc!!, selection, true), a.label)
            Act.DistributeV -> commit(ItemOps.distribute(doc!!, selection, false), a.label)
            Act.FlipH -> selectionBounds()?.let { commit(ItemOps.flip(doc!!, selection.toSet(), true, it.center), a.label) }
            Act.FlipV -> selectionBounds()?.let { commit(ItemOps.flip(doc!!, selection.toSet(), false, it.center), a.label) }
            Act.Rotate90CW -> selectionBounds()?.let { commit(ItemOps.rotate(doc!!, selection.toSet(), 90.0, it.center), a.label) }
            Act.Rotate90CCW -> selectionBounds()?.let { commit(ItemOps.rotate(doc!!, selection.toSet(), -90.0, it.center), a.label) }
            Act.FitContentProportionally -> fitContent(FitMode.Fit)
            Act.FillFrameProportionally -> fitContent(FitMode.Fill)
            Act.CenterContent -> fitContent(FitMode.Center)

            Act.ThreadFrames -> {
                val (f1, f2) = selection
                when (val r = Threading.link(doc!!, f1, f2)) {
                    is Threading.Result.Ok -> commit(r.doc, "Thread Frames")
                    is Threading.Result.Error -> message(r.message)
                }
            }
            Act.UnthreadFrame -> commit(Threading.unlinkAfter(doc!!, selection.single()), "Break Thread")
            Act.FillPlaceholder -> fillPlaceholder()
            Act.NewParagraphStyle -> dialog = Dialog.EditParagraphStyle(null)
            Act.NewCharacterStyle -> dialog = Dialog.EditCharacterStyle(null)
            Act.ClearOverrides -> clearOverrides()
            Act.Bold -> toggleFontStyle(bold = true)
            Act.Italic -> toggleFontStyle(bold = false)
            Act.Underline -> applyTextAttrs(TextAttrs(underline = !(currentTextAttrs()?.underline ?: false)), "Underline")
            Act.AlignTextLeft -> applyParagraphAttrs(TextAttrs(justification = Justification.LeftAlign), "Align Left")
            Act.AlignTextCenter -> applyParagraphAttrs(TextAttrs(justification = Justification.CenterAlign), "Align Center")
            Act.AlignTextRight -> applyParagraphAttrs(TextAttrs(justification = Justification.RightAlign), "Align Right")
            Act.AlignTextJustify -> applyParagraphAttrs(TextAttrs(justification = Justification.LeftJustified), "Justify")
            Act.IncreaseSize -> currentTextAttrs()?.let { applyTextAttrs(TextAttrs(pointSize = (it.pointSize ?: 12.0) + 2), "Point Size") }
            Act.DecreaseSize -> currentTextAttrs()?.let { applyTextAttrs(TextAttrs(pointSize = ((it.pointSize ?: 12.0) - 2).coerceAtLeast(1.0)), "Point Size") }

            Act.AddPage -> addPage()
            Act.DeletePage -> deletePage(currentPageIndex())
            Act.EditMasters -> switchMasters(!editingMasters)
            Act.NextPage -> goToPage(currentPageIndex() + 1)
            Act.PrevPage -> goToPage(currentPageIndex() - 1)

            Act.ZoomIn -> zoomBy(1.25f)
            Act.ZoomOut -> zoomBy(0.8f)
            Act.FitPage -> fitPage(currentPageIndex())
            Act.FitSpread -> fitSpread()
            Act.ActualSize -> setZoomPercent(100f)
            Act.ToggleGuides -> view = view.copy(showGuides = !view.showGuides)
            Act.ToggleFrameEdges -> view = view.copy(showFrameEdges = !view.showFrameEdges)
            Act.ToggleRulers -> view = view.copy(showRulers = !view.showRulers)
            Act.ToggleSnap -> view = view.copy(snap = !view.snap)
            Act.TogglePreview -> view = view.copy(preview = !view.preview)
            Act.TogglePanels -> if (compactLayout) drawerOpen = !drawerOpen else view = view.copy(panelsVisible = !view.panelsVisible)
            Act.ToggleWorkspace -> changeWorkspace(if (workspace == WorkspaceMode.Touch) WorkspaceMode.KeyboardMouse else WorkspaceMode.Touch)

            Act.ShowProperties -> showPanel(PanelTab.Properties)
            Act.ShowPages -> showPanel(PanelTab.Pages)
            Act.ShowLayers -> showPanel(PanelTab.Layers)
            Act.ShowSwatches -> showPanel(PanelTab.Swatches)
            Act.ShowStyles -> showPanel(PanelTab.Styles)

            Act.ShortcutsHelp -> dialog = Dialog.Shortcuts
            Act.About -> dialog = Dialog.About
        }
    }

    private fun showPanel(t: PanelTab) {
        panelTab = t
        if (compactLayout) drawerOpen = true else view = view.copy(panelsVisible = true)
    }

    fun selectionBounds(d: Document? = shown): Rect? {
        val dd = d ?: return null
        val idx = DocIndex.of(dd)
        return Rect.unionAll(selection.mapNotNull { idx[it]?.worldBounds })
    }

    private fun align(how: ItemOps.Align) {
        val d = doc ?: return
        val idx = DocIndex.of(d)
        val target = if (selection.size == 1) {
            // A single object aligns to its page.
            val loc = idx[selection[0]] ?: return
            Pagination.owningPage(loc.spread, loc.worldBounds.center)?.boundsInSpread
        } else null
        commit(ItemOps.align(d, selection, how, target), "Align")
    }

    private fun deleteSelection(label: String) {
        val d = doc ?: return
        if (selection.isEmpty()) return
        commit(ItemOps.delete(d, selection.toSet()), label)
        selection = emptyList()
    }

    private fun copy() {
        val d = doc ?: return
        textEdit?.let { te ->
            if (!te.hasSelection) return
            val st = d.stories[te.storyId] ?: return
            setSystemClipboard(StoryEditing.substring(st, te.start, te.end))
            return
        }
        val idx = DocIndex.of(d)
        val locs = selection.mapNotNull { idx[it] }
        if (locs.isEmpty()) return
        // Copy in world space so nested items paste in place.
        val items = locs.map { l -> if (l.isTopLevel) l.item else ItemOps.bake(l.item.copy(transform = l.world), true) }
        val storyIds = HashSet<String>()
        items.forEach { it.walk { i, _ -> i.storyId?.let { s -> storyIds += s } } }
        clipboard = items to d.stories.filterKeys { it in storyIds }
        clipboardSpread = locs.first().spread.id
    }

    private fun paste(inPlace: Boolean) {
        val d = doc ?: return
        if (textEdit != null) {
            val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(getApplication())?.toString() ?: return
            textReplace(t.replace("\r\n", "\n").replace('\r', '\n'), "Paste")
            return
        }
        val (items, stories) = clipboard ?: return
        val spread = currentSpread() ?: return
        val (d2, copies, _) = ItemOps.cloneItems(d, items, stories)
        val offset = if (inPlace || spread.id != clipboardSpread) 0.0 else 12.0
        var d3 = ItemOps.insertItems(d2, spread.id, copies, offset, offset)
        if (spread.id != clipboardSpread && !inPlace) {
            // Centre on the visible part of the target spread.
            val idx = DocIndex.of(d3)
            val b = Rect.unionAll(copies.mapNotNull { idx[it.id]?.worldBounds })
            val pl = placementOf(spread.id)
            if (b != null && pl != null) {
                val (w, h) = canvasSize
                val c = pl.toSpread(viewport.toView(w / 2, h / 2))
                d3 = ItemOps.move(d3, copies.map { it.id }.toSet(), c.x - b.centerX, c.y - b.centerY)
            }
        }
        commit(d3, "Paste")
        selection = copies.map { it.id }
    }

    private fun setSystemClipboard(text: String) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("LayerTrace", text))
    }

    // ---- images ------------------------------------------------------------------------------

    enum class FitMode { Fit, Fill, Center }

    private fun fitContent(mode: FitMode) {
        val d = doc ?: return
        val ids = selectedItems.filter { it.image != null }.map { it.id }.toSet()
        commit(d.updateItems(ids) { item -> item.copy(image = item.image?.let { fitImage(it, item.innerBounds, mode) }) }, "Fit Content")
    }

    private fun fitImage(img: PlacedImage, frame: Rect, mode: FitMode): PlacedImage {
        val b = img.bounds
        if (b.width <= 0 || b.height <= 0) return img
        val s = when (mode) {
            FitMode.Fit -> minOf(frame.width / b.width, frame.height / b.height)
            FitMode.Fill -> maxOf(frame.width / b.width, frame.height / b.height)
            FitMode.Center -> img.transform.scaleX
        }
        val w = b.width * s; val h = b.height * s
        val target = Rect(frame.centerX - w / 2, frame.centerY - h / 2, frame.centerX + w / 2, frame.centerY + h / 2)
        return img.copy(transform = Matrix.rectToRect(b, target))
    }

    fun placeImage(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                val (pw, ph) = ImageCache.size(bytes) ?: run { message("Unsupported image"); return@launch }
                val d0 = doc ?: return@launch
                var (imgId, d) = d0.newId()
                val img = PlacedImage(imgId, Matrix.IDENTITY, Rect(0.0, 0.0, pw.toDouble(), ph.toDouble()), bytes, linkUri = "file:" + displayName(uri))
                val frameId = pendingPlaceFrame?.takeIf { DocIndex.of(d)[it] != null }
                if (frameId != null) {
                    d = d.updateItem(frameId) { it.copy(image = fitImage(img, it.innerBounds, FitMode.Fill), passthrough = it.passthrough?.withAttr("ContentType", "GraphicType")) }
                    commit(d, "Place Image")
                } else {
                    val spread = currentSpread() ?: return@launch
                    val pl = placementOf(spread.id) ?: return@launch
                    val (w, h) = canvasSize
                    val c = pl.toSpread(viewport.toView(w / 2, h / 2))
                    val page = Pagination.owningPage(spread, c)
                    val maxR = page?.let { it.transform.mapRect(it.marginRect) } ?: Rect(c.x - 200, c.y - 200, c.x + 200, c.y + 200)
                    val s = minOf(1.0, maxR.width / pw, maxR.height / ph)
                    val fw = pw * s; val fh = ph * s
                    val fr = Rect(maxR.centerX - fw / 2, maxR.centerY - fh / 2, maxR.centerX + fw / 2, maxR.centerY + fh / 2)
                    val (fid, d2) = d.newId(); d = d2
                    val frame = PageItem(fid, ItemKind.Rectangle, activeLayer(d), paths = Shapes.rect(fr), image = fitImage(img, fr, FitMode.Fit))
                    d = d.copy(spreads = d.spreads.map { if (it.id == spread.id) it.copy(items = it.items + frame) else it },
                        masters = d.masters.map { if (it.id == spread.id) it.copy(items = it.items + frame) else it })
                    commit(d, "Place Image")
                    selection = listOf(fid)
                }
            } catch (e: Exception) {
                message("Could not place image: ${e.message}")
            } finally {
                pendingPlaceFrame = null
            }
        }
    }

    fun importFont(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val name = displayName(uri)
            val info = withContext(Dispatchers.IO) {
                runCatching { engine.fonts.import(ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }, name) }.getOrNull()
            }
            if (info == null) message("$name is not a TrueType/OpenType font")
            else {
                engine.text.clear()
                renderGeneration++
                message("Imported ${info.family} ${info.style}")
            }
        }
    }

    // ---- pages / layers / swatches / styles --------------------------------------------------

    fun addPage(after: Int = currentPageIndex()) {
        val d = doc ?: return
        if (editingMasters) return
        commit(Pagination.addPages(d, after, 1, null), "Add Page")
        goToPage(after + 1)
    }

    fun deletePage(index: Int) {
        val d = doc ?: return
        if (d.pageCount <= 1) return
        val id = d.pages[index].id
        commit(Pagination.deletePages(d, setOf(id)), "Delete Page")
    }

    fun movePage(index: Int, to: Int) {
        val d = doc ?: return
        if (to !in 0 until d.pageCount) return
        commit(Pagination.movePage(d, d.pages[index].id, to), "Move Page")
    }

    fun applyMaster(pageIndex: Int, masterId: String?) {
        val d = doc ?: return
        commit(Pagination.applyMaster(d, setOf(d.pages[pageIndex].id), masterId), "Apply Master")
    }

    fun newMaster() {
        val d0 = doc ?: return
        val facing = d0.setup.facingPages
        val n = if (facing) 2 else 1
        var (ids, d) = d0.newIds(n + 1)
        val prefix = ('A' + d.masters.size).toString()
        val transforms = Pagination.pageTransforms(n, facing, d.setup.pageWidth, d.setup.pageHeight, true)
        val bounds = Rect(0.0, 0.0, d.setup.pageWidth, d.setup.pageHeight)
        val m = Spread(ids[0], (0 until n).map { i -> io.layertrace.core.model.Page(ids[i + 1], prefix, bounds, transforms[i], null, d.setup.margins) }, isMaster = true, masterPrefix = prefix, masterBaseName = "Master")
        d = d.copy(masters = d.masters + m)
        commit(d, "New Master")
    }

    fun setPageSetup(w: Double, h: Double, facing: Boolean, margins: io.layertrace.core.model.Margins, bleed: Double) {
        val d = doc ?: return
        var nd = d
        if (w != d.setup.pageWidth || h != d.setup.pageHeight || facing != d.setup.facingPages) nd = Pagination.resizePages(nd, w, h, facing)
        nd = nd.copy(
            setup = nd.setup.copy(margins = margins, bleedTop = bleed, bleedBottom = bleed, bleedInside = bleed, bleedOutside = bleed),
            spreads = nd.spreads.map { s -> s.copy(pages = s.pages.map { it.copy(margins = margins) }) },
            masters = nd.masters.map { s -> s.copy(pages = s.pages.map { it.copy(margins = margins) }) },
        )
        commit(nd, "Document Setup")
    }

    fun updateLayers(layers: List<Layer>, label: String) {
        val d = doc ?: return
        commit(d.copy(layers = layers), label)
    }

    fun addLayer() {
        val d0 = doc ?: return
        val (id, d) = d0.newId()
        val color = LayerColor.rotation[d.layers.size % LayerColor.rotation.size]
        val l = Layer(id, "Layer ${d.layers.size + 1}", color = color)
        commit(d.copy(layers = listOf(l) + d.layers), "New Layer")
        activeLayerId = id
    }

    fun deleteLayer(id: String) {
        val d = doc ?: return
        if (d.layers.size <= 1) { message("A document needs at least one layer"); return }
        val ids = DocIndex.of(d).all.filter { it.isTopLevel && it.item.layerId == id }.map { it.item.id }.toSet()
        var nd = ItemOps.delete(d, ids)
        nd = nd.copy(layers = nd.layers.filterNot { it.id == id })
        commit(nd, "Delete Layer")
        if (activeLayerId == id) activeLayerId = nd.layers.first().id
    }

    fun moveSelectionToLayer(layerId: String) {
        val d = doc ?: return
        commit(ItemOps.moveToLayer(d, selection.toSet(), layerId), "Move to Layer")
    }

    fun upsertSwatch(s: Swatch) {
        val d = doc ?: return
        val exists = d.swatches.any { it.id == s.id }
        commit(d.copy(swatches = if (exists) d.swatches.map { if (it.id == s.id) s else it } else d.swatches + s), if (exists) "Edit Swatch" else "New Swatch")
    }

    fun newSwatchId(): String {
        val (id, d) = doc!!.newId()
        doc = d
        return "Color/$id"
    }

    fun newStyleId(prefix: String, name: String): String {
        val d = doc ?: return "$prefix/$name"
        var id = "$prefix/${name.replace(":", "%3a")}"
        var n = 2
        while (d.paragraphStyles.any { it.id == id } || d.characterStyles.any { it.id == id }) id = "$prefix/${name.replace(":", "%3a")} ${n++}"
        return id
    }

    fun deleteSwatch(id: String) {
        val d = doc ?: return
        val sw = d.swatch(id) ?: return
        if (sw.builtIn) { message("${sw.name} cannot be deleted"); return }
        // References fall back to Black (like InDesign's default replacement).
        val nd = io.layertrace.core.edit.ItemOps.run {
            d.mapItems { it.copy(fill = if (it.fill == id) Ids.COLOR_BLACK else it.fill, stroke = if (it.stroke == id) Ids.COLOR_BLACK else it.stroke) }
        }.let { x -> x.copy(swatches = x.swatches.filterNot { it.id == id || it.baseId == id }) }
        commit(nd, "Delete Swatch")
    }

    fun applySwatch(id: String) {
        val d = doc ?: return
        val value = if (id == Ids.SWATCH_NONE) null else id
        textEdit?.let {
            applyTextAttrs(TextAttrs(fillColor = id), "Text Color")
            return
        }
        if (selection.isEmpty()) return
        val nd = d.updateItems(selection.toSet()) {
            if (colorTarget == ColorTarget.Fill) it.copy(fill = value)
            else it.copy(stroke = value, strokeWeight = if (value != null && it.strokeWeight <= 0) 1.0 else it.strokeWeight)
        }
        commit(nd, if (colorTarget == ColorTarget.Fill) "Fill" else "Stroke")
    }

    fun upsertParagraphStyle(s: ParagraphStyle) {
        val d = doc ?: return
        val exists = d.paragraphStyles.any { it.id == s.id }
        commit(d.copy(paragraphStyles = if (exists) d.paragraphStyles.map { if (it.id == s.id) s else it } else d.paragraphStyles + s), "Paragraph Style")
    }

    fun upsertCharacterStyle(s: CharacterStyle) {
        val d = doc ?: return
        val exists = d.characterStyles.any { it.id == s.id }
        commit(d.copy(characterStyles = if (exists) d.characterStyles.map { if (it.id == s.id) s else it } else d.characterStyles + s), "Character Style")
    }

    fun deleteParagraphStyle(id: String) {
        val d = doc ?: return
        if (id == Ids.PARA_NONE || id == Ids.PARA_BASIC) return
        val stories = d.stories.mapValues { (_, st) ->
            if (st.paragraphs.none { it.styleId == id }) st else st.copy(paragraphs = st.paragraphs.map { if (it.styleId == id) it.copy(styleId = Ids.PARA_BASIC) else it }, edited = true)
        }
        commit(d.copy(paragraphStyles = d.paragraphStyles.filterNot { it.id == id }.map { if (it.basedOn == id) it.copy(basedOn = null) else it }, stories = stories), "Delete Style")
    }

    fun deleteCharacterStyle(id: String) {
        val d = doc ?: return
        if (id == Ids.CHAR_NONE) return
        val stories = d.stories.mapValues { (_, st) ->
            if (st.paragraphs.none { p -> p.runs.any { it.format.styleId == id } }) st
            else st.copy(paragraphs = st.paragraphs.map { p -> p.copy(runs = p.runs.map { if (it.format.styleId == id) it.copy(format = it.format.copy(styleId = Ids.CHAR_NONE)) else it }) }, edited = true)
        }
        commit(d.copy(characterStyles = d.characterStyles.filterNot { it.id == id }, stories = stories), "Delete Style")
    }

    /** Apply a paragraph style to the text selection, or to whole stories of selected text frames. */
    fun applyParagraphStyle(id: String) = editStories("Apply Paragraph Style") { st, a, b -> StoryEditing.applyParagraphStyle(st, a, b, id) }

    fun applyCharacterStyle(id: String) = editStories("Apply Character Style", wholeParagraphIfEmpty = false) { st, a, b ->
        if (a == b) st else StoryEditing.applyCharacterStyle(st, a, b, id)
    }

    // ---- item properties ---------------------------------------------------------------------

    fun updateSelected(label: String, f: (PageItem) -> PageItem) {
        val d = doc ?: return
        if (selection.isEmpty()) return
        commit(d.updateItems(selection.toSet(), f), label)
    }

    /** Set selection geometry from the properties panel (spread coordinates). */
    fun setSelectionBounds(target: Rect) {
        val d = doc ?: return
        val idx = DocIndex.of(d)
        if (selection.size == 1) {
            val loc = idx[selection[0]] ?: return
            if (!loc.world.isRigid || loc.item.isGroup) {
                val from = loc.worldBounds
                commit(ItemOps.scale(d, setOf(loc.item.id), from, target, scaleContent = loc.item.isGroup), "Transform")
                return
            }
            // Single rigid item: width/height are its unrotated size; x/y position the reference corner.
            val inner = loc.item.innerBounds
            val wb = loc.worldBounds
            val sx = if (wb.width > 0) target.width / wb.width else 1.0
            val sy = if (wb.height > 0) target.height / wb.height else 1.0
            val nd0 = if (Math.abs(sx - 1) < 1e-9 && Math.abs(sy - 1) < 1e-9) d
            else ItemOps.resizeLocal(d, loc.item.id, Rect(inner.left, inner.top, inner.left + inner.width * sx, inner.top + inner.height * sy))
            val newBounds = DocIndex(nd0)[loc.item.id]!!.worldBounds
            commit(ItemOps.move(nd0, setOf(loc.item.id), target.left - newBounds.left, target.top - newBounds.top), "Transform")
        } else {
            val from = selectionBounds(d) ?: return
            commit(ItemOps.scale(d, selection.toSet(), from, target, scaleContent = true), "Transform")
        }
    }

    // ---- text editing ------------------------------------------------------------------------

    fun beginTextEdit(frameId: String, offset: Int) {
        val d = doc ?: return
        val item = DocIndex.of(d)[frameId]?.item ?: return
        val sid = item.storyId ?: return
        selection = listOf(frameId)
        textEdit = TextEdit(sid, frameId, offset)
    }

    fun endTextEdit() {
        val te = textEdit ?: return
        textEdit = null
        selection = listOf(te.frameId)
    }

    fun setCaret(caret: Int, anchor: Int = caret, frameId: String? = null) {
        val te = textEdit ?: return
        val st = doc?.stories?.get(te.storyId) ?: return
        textEdit = te.copy(caret = caret.coerceIn(0, st.length), anchor = anchor.coerceIn(0, st.length), frameId = frameId ?: te.frameId)
    }

    fun storyLayoutFor(storyId: String): StoryLayout? {
        val d = doc ?: return null
        val chain = DocIndex.of(d).chain(storyId)
        return engine.text.layout(d, storyId, chain)
    }

    /** Replace the current text selection with [text]. */
    fun textReplace(text: String, label: String = "Typing") {
        val d = doc ?: return
        val te = textEdit ?: return
        val st = d.stories[te.storyId] ?: return
        val ns = StoryEditing.replace(st, te.start, te.end, text)
        commit(d.copy(stories = d.stories + (st.id to ns)), label, coalesce = if (label == "Typing") "Typing" else null)
        val c = te.start + text.length
        textEdit = te.copy(caret = c, anchor = c)
        followCaret()
    }

    fun textBackspace(word: Boolean) {
        val d = doc ?: return
        val te = textEdit ?: return
        val st = d.stories[te.storyId] ?: return
        if (te.hasSelection) { textReplace(""); return }
        if (te.caret == 0) return
        val from = if (word) StoryEditing.wordBoundaryLeft(st.text, te.caret) else prevCharBoundary(st.text, te.caret)
        val ns = StoryEditing.delete(st, from, te.caret)
        commit(d.copy(stories = d.stories + (st.id to ns)), "Typing", coalesce = "Typing")
        textEdit = te.copy(caret = from, anchor = from)
    }

    fun textDeleteForward(word: Boolean) {
        val d = doc ?: return
        val te = textEdit ?: return
        val st = d.stories[te.storyId] ?: return
        if (te.hasSelection) { textReplace(""); return }
        if (te.caret >= st.length) return
        val to = if (word) StoryEditing.wordBoundaryRight(st.text, te.caret) else nextCharBoundary(st.text, te.caret)
        val ns = StoryEditing.delete(st, te.caret, to)
        commit(d.copy(stories = d.stories + (st.id to ns)), "Typing", coalesce = "Typing")
    }

    private fun prevCharBoundary(t: String, i: Int): Int = if (i >= 2 && Character.isLowSurrogate(t[i - 1]) && Character.isHighSurrogate(t[i - 2])) i - 2 else (i - 1).coerceAtLeast(0)
    private fun nextCharBoundary(t: String, i: Int): Int = if (i + 1 < t.length && Character.isHighSurrogate(t[i]) && Character.isLowSurrogate(t[i + 1])) i + 2 else (i + 1).coerceAtMost(t.length)

    enum class CaretMove { Left, Right, Up, Down, WordLeft, WordRight, LineStart, LineEnd, StoryStart, StoryEnd }

    fun moveCaret(m: CaretMove, extend: Boolean) {
        val d = doc ?: return
        val te = textEdit ?: return
        val st = d.stories[te.storyId] ?: return
        val t = st.text
        val layout = storyLayoutFor(st.id)
        if (!extend && te.hasSelection && (m == CaretMove.Left || m == CaretMove.Right)) {
            val c = if (m == CaretMove.Left) te.start else te.end
            textEdit = te.copy(caret = c, anchor = c); return
        }
        val c = te.caret
        val nc = when (m) {
            CaretMove.Left -> prevCharBoundary(t, c)
            CaretMove.Right -> nextCharBoundary(t, c)
            CaretMove.WordLeft -> StoryEditing.wordBoundaryLeft(t, c)
            CaretMove.WordRight -> StoryEditing.wordBoundaryRight(t, c)
            CaretMove.Up -> layout?.verticalMove(c, false) ?: 0
            CaretMove.Down -> layout?.verticalMove(c, true) ?: t.length
            CaretMove.LineStart -> layout?.lineBounds(c)?.first ?: c
            CaretMove.LineEnd -> layout?.lineBounds(c)?.last ?: c
            CaretMove.StoryStart -> 0
            CaretMove.StoryEnd -> t.length
        }.coerceIn(0, t.length)
        textEdit = te.copy(caret = nc, anchor = if (extend) te.anchor else nc)
        followCaret()
    }

    /** Make the frame containing the caret the active one (caret may flow into the next frame). */
    private fun followCaret() {
        val te = textEdit ?: return
        val layout = storyLayoutFor(te.storyId) ?: return
        val fid = layout.caret(te.caret)?.first ?: return
        if (fid != te.frameId) { textEdit = te.copy(frameId = fid); selection = listOf(fid) }
    }

    /** Resolved attributes at the caret (text edit) or of the first selected text frame's story. */
    fun currentTextAttrs(): TextAttrs? {
        val d = shown ?: return null
        val resolver = StyleResolver(d)
        val te = textEdit
        if (te != null) {
            val st = d.stories[te.storyId] ?: return null
            val pos = StoryEditing.locate(st, if (te.hasSelection) te.start + 1 else te.caret)
            val para = st.paragraphs[pos.paragraph]
            return resolver.run(para, StoryEditing.formatAt(st, if (te.hasSelection) te.start + 1 else te.caret))
        }
        val frame = selectedItems.firstOrNull { it.isText } ?: return null
        val st = d.stories[frame.storyId] ?: return null
        val p = st.paragraphs.first()
        return resolver.run(p, p.runs.first().format)
    }

    fun currentParagraphStyleId(): String? {
        val d = shown ?: return null
        val te = textEdit
        if (te != null) {
            val st = d.stories[te.storyId] ?: return null
            return st.paragraphs[StoryEditing.locate(st, te.caret).paragraph].styleId
        }
        val frame = selectedItems.firstOrNull { it.isText } ?: return null
        return d.stories[frame.storyId]?.paragraphs?.firstOrNull()?.styleId
    }

    fun currentCharacterStyleId(): String? {
        val d = shown ?: return null
        val te = textEdit ?: return null
        val st = d.stories[te.storyId] ?: return null
        return StoryEditing.formatAt(st, te.caret).styleId
    }

    /**
     * Apply an edit to text: in text edit mode to the selection (or the caret's paragraph when
     * nothing is selected); otherwise to the whole stories of selected text frames.
     */
    private fun editStories(label: String, wholeParagraphIfEmpty: Boolean = true, f: (Story, Int, Int) -> Story) {
        val d = doc ?: return
        val te = textEdit
        if (te != null) {
            val st = d.stories[te.storyId] ?: return
            var a = te.start; var b = te.end
            if (a == b && wholeParagraphIfEmpty) {
                val pi = StoryEditing.locate(st, a).paragraph
                a = StoryEditing.paragraphStart(st, pi); b = a + st.paragraphs[pi].length
            }
            commit(d.copy(stories = d.stories + (st.id to f(st, a, b))), label)
            return
        }
        val ids = selectedItems.filter { it.isText }.mapNotNull { it.storyId }.toSet()
        if (ids.isEmpty()) return
        commit(d.copy(stories = d.stories.mapValues { (id, st) -> if (id in ids) f(st, 0, st.length) else st }), label)
    }

    fun applyTextAttrs(attrs: TextAttrs, label: String) = editStories(label) { st, a, b ->
        val para = attrs.paragraphOnly()
        val chars = attrs.characterOnly()
        var s = st
        if (!chars.isEmpty) s = StoryEditing.overrideCharAttrs(s, a, b, chars)
        if (!para.isEmpty) s = StoryEditing.overrideParagraphAttrs(s, a, b, para)
        s
    }

    fun applyParagraphAttrs(attrs: TextAttrs, label: String) = editStories(label) { st, a, b -> StoryEditing.overrideParagraphAttrs(st, a, b, attrs) }

    private fun toggleFontStyle(bold: Boolean) {
        val cur = currentTextAttrs() ?: return
        val style = if (bold) FontRegistry.boldVariant(cur.fontStyle) else FontRegistry.italicVariant(cur.fontStyle)
        applyTextAttrs(TextAttrs(fontStyle = style), if (bold) "Bold" else "Italic")
    }

    private fun clearOverrides() = editStories("Clear Overrides") { st, a, b ->
        val s = StoryEditing.mapCharFormats(st, a, b) { CharFormat(it.styleId) }
        StoryEditing.mapParagraphs(s, a, b) { it.copy(overrides = TextAttrs.EMPTY) }
    }

    private fun fillPlaceholder() {
        val d = doc ?: return
        val te = textEdit
        val ids = if (te != null) setOf(te.storyId) else selectedItems.filter { it.isText }.mapNotNull { it.storyId }.toSet()
        commit(d.copy(stories = d.stories.mapValues { (id, st) ->
            if (id in ids) StoryEditing.replace(st, 0, st.length, Placeholder.text(6)) else st
        }), "Placeholder Text")
        if (te != null) textEdit = te.copy(caret = 0, anchor = 0)
    }

    fun overflowFrames(): Int {
        val d = shown ?: return 0
        val idx = DocIndex.of(d)
        return d.stories.keys.count { sid -> storyLayoutFor(sid)?.overset == true && idx.chain(sid).isNotEmpty() }
    }
}
