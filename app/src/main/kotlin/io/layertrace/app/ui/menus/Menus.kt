package io.layertrace.app.ui.menus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.layertrace.app.editor.Act
import io.layertrace.app.editor.EditorViewModel
import io.layertrace.app.editor.MenuDef
import io.layertrace.app.editor.MenuEntry
import io.layertrace.app.editor.Menus
import io.layertrace.app.editor.Tool
import io.layertrace.app.editor.WorkspaceMode
import io.layertrace.app.ui.AppIcons
import io.layertrace.app.ui.theme.LocalEditorColors

/** Checked state for toggle actions shown in menus. */
fun isChecked(vm: EditorViewModel, a: Act): Boolean? = when (a) {
    Act.ToggleGuides -> vm.view.showGuides
    Act.ToggleFrameEdges -> vm.view.showFrameEdges
    Act.ToggleRulers -> vm.view.showRulers
    Act.ToggleSnap -> vm.view.snap
    Act.TogglePreview -> vm.view.preview
    Act.TogglePanels -> vm.view.panelsVisible
    Act.EditMasters -> vm.editingMasters
    Act.ToggleWorkspace -> vm.workspace == WorkspaceMode.Touch
    else -> null
}

@Composable
fun MenuItems(vm: EditorViewModel, entries: List<MenuEntry>, onDone: () -> Unit) {
    val colors = LocalEditorColors.current
    for (e in entries) {
        if (e.separator) { HorizontalDivider(Modifier.padding(vertical = 2.dp), color = colors.divider); continue }
        val a = e.act ?: continue
        val enabled = vm.isEnabled(a)
        val checked = isChecked(vm, a)
        DropdownMenuItem(
            modifier = Modifier.height(34.dp),
            leadingIcon = {
                when {
                    checked == true -> Icon(Icons.Default.Check, null, Modifier.width(18.dp))
                    AppIcons.act(a) != null -> Icon(AppIcons.act(a)!!, null, Modifier.width(18.dp), tint = colors.textDim)
                    else -> Spacer(Modifier.width(18.dp))
                }
            },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.label, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(24.dp))
                    a.shortcut?.let { Text(it.display, fontSize = 11.sp, color = colors.textDim) }
                }
            },
            enabled = enabled,
            onClick = { onDone(); vm.perform(a) },
        )
    }
}

/** Desktop-style menu bar for the keyboard & mouse workspace. */
@Composable
fun MenuBar(vm: EditorViewModel, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val colors = LocalEditorColors.current
    var open by remember { mutableStateOf<String?>(null) }
    Row(
        modifier.fillMaxWidth().height(32.dp).background(colors.chromeLow).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("LayerTrace", color = colors.accent, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
        for (m in Menus.all) {
            Box {
                Text(
                    m.title, color = colors.text, fontSize = 13.sp,
                    modifier = Modifier
                        .background(if (open == m.title) colors.chromeHigh else colors.chromeLow)
                        .clickable { open = if (open == m.title) null else m.title }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                DropdownMenu(expanded = open == m.title, onDismissRequest = { open = null }) {
                    MenuItems(vm, m.entries) { open = null }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** Overflow menu for compact layouts: every menu as a section. */
@Composable
fun OverflowMenu(vm: EditorViewModel, expanded: Boolean, onDismiss: () -> Unit) {
    val colors = LocalEditorColors.current
    var section by remember { mutableStateOf<MenuDef?>(null) }
    DropdownMenu(expanded = expanded, onDismissRequest = { section = null; onDismiss() }) {
        val s = section
        if (s == null) {
            for (m in Menus.all) DropdownMenuItem(text = { Text("${m.title}  ›", fontSize = 14.sp) }, onClick = { section = m })
        } else {
            DropdownMenuItem(text = { Text("‹ ${s.title}", color = colors.accent, fontSize = 14.sp) }, onClick = { section = null })
            MenuItems(vm, s.entries) { section = null; onDismiss() }
        }
    }
}

/** Right-click / touch-and-hold menu, contextual to what is selected. */
@Composable
fun CanvasContextMenu(vm: EditorViewModel) {
    val req = vm.contextMenu ?: return
    val density = LocalDensity.current
    val items = buildList {
        val te = vm.textEdit
        val sel = vm.selectedItems
        if (te != null) {
            addAll(listOf(Act.Cut, Act.Copy, Act.Paste, Act.SelectAll, null, Act.Bold, Act.Italic, Act.Underline, null,
                Act.AlignTextLeft, Act.AlignTextCenter, Act.AlignTextRight, Act.AlignTextJustify, null, Act.ClearOverrides, Act.FillPlaceholder, Act.NewParagraphStyle, Act.NewCharacterStyle))
        } else if (req.onItem && sel.isNotEmpty()) {
            addAll(listOf(Act.Cut, Act.Copy, Act.Paste, Act.Duplicate, Act.Delete, null))
            if (sel.any { it.isText }) addAll(listOf(Act.FillPlaceholder, Act.UnthreadFrame, null))
            if (sel.any { !it.isText && !it.isGroup }) addAll(listOf(Act.Place, Act.FitContentProportionally, Act.FillFrameProportionally, null))
            addAll(listOf(Act.BringToFront, Act.BringForward, Act.SendBackward, Act.SendToBack, null))
            if (sel.size > 1) addAll(listOf(Act.Group, Act.AlignLeft, Act.AlignCenterH, Act.AlignTop, Act.AlignCenterV, null))
            if (sel.any { it.isGroup }) add(Act.Ungroup)
            addAll(listOf(Act.Rotate90CW, Act.FlipH, Act.FlipV, Act.Lock))
        } else {
            addAll(listOf(Act.Paste, Act.PasteInPlace, Act.SelectAll, null, Act.Place, Act.AddPage, null, Act.ZoomIn, Act.ZoomOut, Act.FitPage, null, Act.ToggleGuides, Act.TogglePreview))
        }
    }
    val tools = vm.workspace == WorkspaceMode.Touch && vm.textEdit == null && !req.onItem
    Box(Modifier.padding(start = with(density) { req.x.toDp() }, top = with(density) { req.y.toDp() })) {
        DropdownMenu(expanded = true, onDismissRequest = { vm.contextMenu = null }, offset = DpOffset(0.dp, 0.dp)) {
            val sel = vm.selectedItems
            if (vm.textEdit == null && sel.size == 1 && sel[0].isText) {
                DropdownMenuItem(text = { Text("Edit Text", fontSize = 13.sp) }, onClick = {
                    vm.contextMenu = null
                    vm.beginTextEdit(sel[0].id, vm.doc?.stories?.get(sel[0].storyId)?.length ?: 0)
                })
                DropdownMenuItem(text = { Text("Thread to Another Frame…", fontSize = 13.sp) }, onClick = {
                    vm.contextMenu = null; vm.threadRequest = sel[0].id
                })
            }
            if (tools) {
                // Touch mode: quick tool switching from the canvas.
                for (t in listOf(Tool.Selection, Tool.Type, Tool.Frame, Tool.Rectangle, Tool.Ellipse, Tool.Line)) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(AppIcons.tool(t), null) },
                        text = { Text("${t.label} tool", fontSize = 13.sp) },
                        onClick = { vm.contextMenu = null; vm.tool = t },
                    )
                }
                HorizontalDivider()
            }
            MenuItems(vm, items.map { if (it == null) MenuEntry.SEP else MenuEntry(it) }) { vm.contextMenu = null }
        }
    }
}
