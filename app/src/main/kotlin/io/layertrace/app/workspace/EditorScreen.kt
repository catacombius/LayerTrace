package io.layertrace.app.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.layertrace.app.canvas.DocumentCanvas
import io.layertrace.app.editor.Act
import io.layertrace.app.editor.EditorViewModel
import io.layertrace.app.editor.KeyHandler
import io.layertrace.app.ui.HorizontalToolBar
import io.layertrace.app.ui.StatusBar
import io.layertrace.app.ui.VerticalToolBar
import io.layertrace.app.ui.components.ToolIcon
import io.layertrace.app.ui.menus.CanvasContextMenu
import io.layertrace.app.ui.menus.MenuBar
import io.layertrace.app.ui.menus.OverflowMenu
import io.layertrace.app.ui.panels.PanelHost
import io.layertrace.app.ui.theme.LocalEditorColors

/**
 * Root editor layout. Chooses an arrangement from the window size and fold posture, so the same
 * code serves the Fold cover screen, the unfolded inner screen (flat, tabletop or book), tablets
 * and DeX windows.
 */
@Composable
fun EditorScreen(vm: EditorViewModel, keys: KeyHandler, fold: FoldState) {
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(0f to 0f) }
    BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned { c -> c.positionInWindow().let { origin = it.x to it.y } },
    ) {
        val size = sizeClassFor(maxWidth.value, maxHeight.value)
        val hinge = fold.hinge
        SideEffect { vm.compactLayout = size != SizeClass.Expanded && fold.posture == Posture.Flat }
        when {
            fold.posture == Posture.Tabletop && hinge != null -> {
                val top = with(density) { (hinge.top - origin.second).toDp() }
                val gap = with(density) { hinge.height().toDp() }
                TabletopLayout(vm, keys, top, gap)
            }
            fold.posture == Posture.Book && hinge != null -> {
                val left = with(density) { (hinge.left - origin.first).toDp() }
                val gap = with(density) { hinge.width().toDp() }
                BookLayout(vm, keys, left, gap)
            }
            size == SizeClass.Expanded -> ExpandedLayout(vm, keys)
            size == SizeClass.Medium -> MediumLayout(vm, keys)
            else -> CompactLayout(vm, keys)
        }
        CanvasContextMenu(vm)
    }
}

@Composable
private fun CanvasArea(vm: EditorViewModel, keys: KeyHandler, modifier: Modifier) {
    Box(modifier) { DocumentCanvas(vm, keys, Modifier.fillMaxSize()) }
}

@Composable
private fun Title(vm: EditorViewModel) {
    val colors = LocalEditorColors.current
    Text(
        (vm.doc?.name ?: "") + if (vm.dirty) " •" else "", color = colors.textDim, fontSize = 12.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp),
    )
}

@Composable
private fun ExpandedLayout(vm: EditorViewModel, keys: KeyHandler) {
    val colors = LocalEditorColors.current
    Column(Modifier.fillMaxSize().background(colors.chrome)) {
        MenuBar(vm) { Title(vm) }
        Row(Modifier.weight(1f)) {
            VerticalToolBar(vm)
            Column(Modifier.weight(1f)) {
                CanvasArea(vm, keys, Modifier.weight(1f).fillMaxWidth())
                StatusBar(vm)
            }
            if (vm.view.panelsVisible) PanelHost(vm, Modifier.width(300.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun MediumLayout(vm: EditorViewModel, keys: KeyHandler) {
    val colors = LocalEditorColors.current
    Column(Modifier.fillMaxSize().background(colors.chrome)) {
        MenuBar(vm) {
            ToolIcon(Icons.Default.Tune, "Panels (Tab)", selected = vm.drawerOpen, size = 30.dp) { vm.drawerOpen = !vm.drawerOpen }
        }
        Row(Modifier.weight(1f)) {
            VerticalToolBar(vm)
            CanvasArea(vm, keys, Modifier.weight(1f).fillMaxHeight())
            // Docked rather than overlaid so the page stays visible on the Fold's inner screen.
            if (vm.drawerOpen) PanelHost(vm, Modifier.width(280.dp).fillMaxHeight())
        }
        StatusBar(vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactLayout(vm: EditorViewModel, keys: KeyHandler) {
    val colors = LocalEditorColors.current
    var overflow by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(colors.chrome)) {
        Row(Modifier.fillMaxWidth().height(44.dp).background(colors.chromeLow), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Title(vm) }
            ToolIcon(Icons.AutoMirrored.Filled.Undo, "Undo", enabled = vm.history.canUndo, size = 40.dp) { vm.perform(Act.Undo) }
            ToolIcon(Icons.AutoMirrored.Filled.Redo, "Redo", enabled = vm.history.canRedo, size = 40.dp) { vm.perform(Act.Redo) }
            ToolIcon(Icons.Default.Tune, "Panels", selected = vm.drawerOpen, size = 40.dp) { vm.drawerOpen = !vm.drawerOpen }
            Box {
                ToolIcon(Icons.Default.MoreVert, "Menu", size = 40.dp) { overflow = true }
                OverflowMenu(vm, overflow) { overflow = false }
            }
        }
        CanvasArea(vm, keys, Modifier.weight(1f).fillMaxWidth())
        HorizontalToolBar(vm)
        StatusBar(vm)
    }
    if (vm.drawerOpen) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(onDismissRequest = { vm.drawerOpen = false }, sheetState = sheet, containerColor = colors.chrome) {
            PanelHost(vm, Modifier.fillMaxWidth().height(460.dp))
        }
    }
}

/** Half-folded with a horizontal hinge: canvas above the fold, controls below. */
@Composable
private fun TabletopLayout(vm: EditorViewModel, keys: KeyHandler, hingeTop: Dp, gap: Dp) {
    val colors = LocalEditorColors.current
    Column(Modifier.fillMaxSize().background(colors.chrome)) {
        CanvasArea(vm, keys, Modifier.fillMaxWidth().height(hingeTop.coerceAtLeast(120.dp)))
        Spacer(Modifier.height(gap).fillMaxWidth().background(colors.chromeLow))
        HorizontalToolBar(vm)
        Row(Modifier.weight(1f)) {
            PanelHost(vm, Modifier.weight(1f).fillMaxHeight())
        }
        StatusBar(vm)
    }
}

/** Unfolded like a book (vertical hinge): canvas on one side, panels on the other. */
@Composable
private fun BookLayout(vm: EditorViewModel, keys: KeyHandler, hingeLeft: Dp, gap: Dp) {
    val colors = LocalEditorColors.current
    Column(Modifier.fillMaxSize().background(colors.chrome)) {
        MenuBar(vm) { Title(vm) }
        Row(Modifier.weight(1f)) {
            Row(Modifier.width(hingeLeft.coerceAtLeast(200.dp)).fillMaxHeight()) {
                VerticalToolBar(vm)
                CanvasArea(vm, keys, Modifier.weight(1f).fillMaxHeight())
            }
            Spacer(Modifier.width(gap).fillMaxHeight().background(colors.chromeLow))
            PanelHost(vm, Modifier.weight(1f).fillMaxHeight())
        }
        StatusBar(vm)
    }
}
