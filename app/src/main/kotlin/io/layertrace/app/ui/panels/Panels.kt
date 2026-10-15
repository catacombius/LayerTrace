package io.layertrace.app.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.layertrace.app.editor.ColorTarget
import io.layertrace.app.editor.Dialog
import io.layertrace.app.editor.EditorViewModel
import io.layertrace.app.editor.PanelTab
import io.layertrace.app.render.RenderOptions
import io.layertrace.app.ui.components.SectionHeader
import io.layertrace.app.ui.components.SmallButton
import io.layertrace.app.ui.components.Swatch
import io.layertrace.app.ui.components.ToolIcon
import io.layertrace.app.ui.theme.LocalEditorColors
import io.layertrace.core.model.Colors
import io.layertrace.core.model.Document
import io.layertrace.core.model.Page
import io.layertrace.core.model.Spread
import io.layertrace.core.model.SwatchKind

/** Tabbed panel column used by every layout. */
@Composable
fun PanelHost(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val colors = LocalEditorColors.current
    Column(modifier.background(colors.chrome)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(colors.chromeLow)) {
            for (t in PanelTab.entries) {
                val sel = vm.panelTab == t
                Text(
                    t.label, fontSize = 12.sp, color = if (sel) colors.text else colors.textDim,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .clickable { vm.panelTab = t }
                        .background(if (sel) colors.chrome else colors.chromeLow)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (vm.panelTab) {
                PanelTab.Properties -> PropertiesPanel(vm, Modifier.fillMaxSize())
                PanelTab.Pages -> PagesPanel(vm, Modifier.fillMaxSize())
                PanelTab.Layers -> LayersPanel(vm, Modifier.fillMaxSize())
                PanelTab.Swatches -> SwatchesPanel(vm, Modifier.fillMaxSize())
                PanelTab.Styles -> StylesPanel(vm, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun PageThumb(vm: EditorViewModel, d: Document, spread: Spread, page: Page, selected: Boolean, modifier: Modifier) {
    val colors = LocalEditorColors.current
    val b = page.bounds
    Box(
        modifier
            .aspectRatio((b.width / b.height).toFloat().coerceIn(0.2f, 5f))
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.divider)
            .background(Color.White),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val g = vm.renderGeneration
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                val s = size.width / page.boundsInSpread.width.toFloat()
                nc.save()
                nc.clipRect(0f, 0f, size.width, size.height)
                vm.engine.renderer.drawPage(nc, d, spread, page, 0.0, s, RenderOptions(printOnly = false, placeholders = false))
                nc.restore()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PagesPanel(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val d = vm.shown ?: return
    val colors = LocalEditorColors.current
    var menuFor by remember { mutableStateOf<Int?>(null) }
    val current = vm.currentPageIndex()
    Column(modifier.padding(8.dp)) {
        SectionHeader("Masters")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (m in d.masters) {
                Text(
                    m.masterName, fontSize = 12.sp, color = colors.text,
                    modifier = Modifier
                        .background(if (vm.editingMasters) colors.accentDim else colors.chromeHigh, RoundedCornerShape(4.dp))
                        .clickable { vm.switchMasters(true) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
            ToolIcon(Icons.Default.Add, "New master", size = 28.dp) { vm.newMaster() }
            if (vm.editingMasters) SmallButton("Done") { vm.switchMasters(false) }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = colors.divider)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Pages", Modifier.weight(1f))
            ToolIcon(Icons.Default.Add, "Add page after current (Ctrl+Shift+P)", size = 28.dp) { vm.addPage() }
            ToolIcon(Icons.Default.Delete, "Delete current page", enabled = d.pageCount > 1, size = 28.dp) { vm.deletePage(current) }
        }
        var pageIndex = 0
        val spreads = d.spreads.map { s -> s to s.pages.map { pg -> (pageIndex++) to pg } }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(spreads, key = { _, s -> s.first.id }) { _, (spread, pages) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    // Lone right-hand first page sits to the right of the spine.
                    if (d.setup.facingPages && pages.size == 1 && pages[0].second.boundsInSpread.left >= -1e-3) Spacer(Modifier.width(76.dp))
                    for ((i, pg) in pages) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box {
                                PageThumb(
                                    vm, d, spread, pg, i == current && !vm.editingMasters,
                                    Modifier.width(76.dp).combinedClickable(onClick = { vm.goToPage(i) }, onLongClick = { menuFor = i }),
                                )
                                DropdownMenu(expanded = menuFor == i, onDismissRequest = { menuFor = null }) {
                                    DropdownMenuItem(text = { Text("Insert page after") }, onClick = { menuFor = null; vm.addPage(i) })
                                    DropdownMenuItem(text = { Text("Move up") }, enabled = i > 0, onClick = { menuFor = null; vm.movePage(i, i - 1) })
                                    DropdownMenuItem(text = { Text("Move down") }, enabled = i < d.pageCount - 1, onClick = { menuFor = null; vm.movePage(i, i + 1) })
                                    DropdownMenuItem(text = { Text("Delete page") }, enabled = d.pageCount > 1, onClick = { menuFor = null; vm.deletePage(i) })
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text("Apply [None]") }, onClick = { menuFor = null; vm.applyMaster(i, null) })
                                    for (m in d.masters) DropdownMenuItem(text = { Text("Apply ${m.masterName}") }, onClick = { menuFor = null; vm.applyMaster(i, m.id) })
                                }
                            }
                            val master = d.master(pg.appliedMasterId)?.masterPrefix ?: ""
                            Text("${i + 1}${if (master.isNotEmpty()) " · $master" else ""}", color = colors.textDim, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        Text("Tap a page to go there; touch & hold or right-click for page actions.", color = colors.textDim, fontSize = 10.sp)
    }
}

@Composable
fun LayersPanel(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val d = vm.shown ?: return
    val colors = LocalEditorColors.current
    Column(modifier.padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Layers (top first)", Modifier.weight(1f))
            ToolIcon(Icons.Default.Add, "New layer", size = 28.dp) { vm.addLayer() }
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(d.layers, key = { _, l -> l.id }) { i, l ->
                val active = vm.activeLayerId == l.id
                val selCount = vm.selectedItems.count { it.layerId == l.id }
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (active) colors.accentDim.copy(alpha = 0.45f) else Color.Transparent)
                        .clickable { vm.activeLayerId = l.id }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolIcon(if (l.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff, "Show/hide", size = 30.dp) {
                        vm.updateLayers(d.layers.map { if (it.id == l.id) it.copy(visible = !it.visible) else it }, "Layer Visibility")
                    }
                    ToolIcon(if (l.locked) Icons.Default.Lock else Icons.Default.LockOpen, "Lock/unlock", size = 30.dp) {
                        vm.updateLayers(d.layers.map { if (it.id == l.id) it.copy(locked = !it.locked) else it }, "Lock Layer")
                    }
                    Box(Modifier.size(10.dp).background(Color(l.color.argb.toInt())))
                    Text(l.name, color = colors.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    if (selCount > 0) Box(Modifier.size(8.dp).background(Color(l.color.argb.toInt())))
                    ToolIcon(Icons.Default.KeyboardArrowUp, "Move up", enabled = i > 0, size = 28.dp) {
                        val ls = d.layers.toMutableList(); ls.add(i - 1, ls.removeAt(i)); vm.updateLayers(ls, "Reorder Layers")
                    }
                    ToolIcon(Icons.Default.KeyboardArrowDown, "Move down", enabled = i < d.layers.size - 1, size = 28.dp) {
                        val ls = d.layers.toMutableList(); ls.add(i + 1, ls.removeAt(i)); vm.updateLayers(ls, "Reorder Layers")
                    }
                    ToolIcon(Icons.Default.Edit, "Rename / colour", size = 28.dp) { vm.dialog = Dialog.RenameLayer(l) }
                    ToolIcon(Icons.Default.Delete, "Delete layer and its items", enabled = d.layers.size > 1, size = 28.dp) { vm.deleteLayer(l.id) }
                }
            }
        }
        if (vm.selection.isNotEmpty()) SmallButton("Move selection to active layer") { vm.activeLayerId?.let { vm.moveSelectionToLayer(it) } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwatchesPanel(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val d = vm.shown ?: return
    val colors = LocalEditorColors.current
    Column(modifier.padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in ColorTarget.entries) {
                val sel = vm.colorTarget == t
                Text(t.name, fontSize = 12.sp, color = if (sel) colors.text else colors.textDim,
                    modifier = Modifier.background(if (sel) colors.accentDim else colors.chromeHigh, RoundedCornerShape(4.dp)).clickable { vm.colorTarget = t }.padding(horizontal = 10.dp, vertical = 5.dp))
            }
            Spacer(Modifier.weight(1f))
            ToolIcon(Icons.Default.Add, "New colour swatch", size = 28.dp) { vm.dialog = Dialog.EditSwatch(null) }
        }
        Text(if (vm.textEdit != null) "Applies to selected text" else "Applies to ${vm.colorTarget.name.lowercase()} of selected objects (X toggles)", color = colors.textDim, fontSize = 10.sp, modifier = Modifier.padding(vertical = 4.dp))
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(d.swatches, key = { _, s -> s.id }) { _, s ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(onClick = { vm.applySwatch(s.id) }, onDoubleClick = { if (s.kind == SwatchKind.Color && !s.builtIn) vm.dialog = Dialog.EditSwatch(s) }, onLongClick = { menu = true })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Swatch(Color(Colors.argb(d, s.id)), 20.dp, none = s.isNone)
                    Text(s.name, color = colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when (s.kind) {
                            SwatchKind.Color -> if (s.spot) "Spot · ${s.space}" else s.space.name
                            SwatchKind.Tint -> "Tint ${s.tint.toInt()}%"
                            SwatchKind.Gradient -> "Gradient"
                            SwatchKind.None -> ""
                        }, color = colors.textDim, fontSize = 10.sp,
                    )
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Apply to fill") }, onClick = { menu = false; vm.colorTarget = ColorTarget.Fill; vm.applySwatch(s.id) })
                        DropdownMenuItem(text = { Text("Apply to stroke") }, onClick = { menu = false; vm.colorTarget = ColorTarget.Stroke; vm.applySwatch(s.id) })
                        if (s.kind == SwatchKind.Color && !s.builtIn) {
                            DropdownMenuItem(text = { Text("Edit…") }, onClick = { menu = false; vm.dialog = Dialog.EditSwatch(s) })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; vm.deleteSwatch(s.id) })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StylesPanel(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val d = vm.shown ?: return
    val colors = LocalEditorColors.current
    val curPara = vm.currentParagraphStyleId()
    val curChar = vm.currentCharacterStyleId()
    Column(modifier.padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Paragraph styles", Modifier.weight(1f))
            ToolIcon(Icons.Default.Add, "New paragraph style", size = 28.dp) { vm.dialog = Dialog.EditParagraphStyle(null) }
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(d.paragraphStyles, key = { _, s -> s.id }) { _, s ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (s.id == curPara) colors.accentDim.copy(alpha = 0.45f) else Color.Transparent)
                        .combinedClickable(onClick = { vm.applyParagraphStyle(s.id) }, onDoubleClick = { vm.dialog = Dialog.EditParagraphStyle(s) }, onLongClick = { menu = true })
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                ) {
                    Text(s.name, color = colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Apply") }, onClick = { menu = false; vm.applyParagraphStyle(s.id) })
                        DropdownMenuItem(text = { Text("Edit…") }, onClick = { menu = false; vm.dialog = Dialog.EditParagraphStyle(s) })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; vm.deleteParagraphStyle(s.id) })
                    }
                }
            }
        }
        HorizontalDivider(color = colors.divider)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Character styles", Modifier.weight(1f))
            ToolIcon(Icons.Default.Add, "New character style", size = 28.dp) { vm.dialog = Dialog.EditCharacterStyle(null) }
        }
        LazyColumn(Modifier.weight(0.7f)) {
            itemsIndexed(d.characterStyles, key = { _, s -> s.id }) { _, s ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (s.id == curChar) colors.accentDim.copy(alpha = 0.45f) else Color.Transparent)
                        .combinedClickable(onClick = { vm.applyCharacterStyle(s.id) }, onDoubleClick = { vm.dialog = Dialog.EditCharacterStyle(s) }, onLongClick = { menu = true })
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                ) {
                    Text(s.name, color = colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Apply") }, onClick = { menu = false; vm.applyCharacterStyle(s.id) })
                        DropdownMenuItem(text = { Text("Edit…") }, onClick = { menu = false; vm.dialog = Dialog.EditCharacterStyle(s) })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; vm.deleteCharacterStyle(s.id) })
                    }
                }
            }
        }
        Text("Click applies · double-click edits · hold for more", color = colors.textDim, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
    }
}
