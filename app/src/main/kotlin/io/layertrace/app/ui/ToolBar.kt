package io.layertrace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.layertrace.app.editor.Act
import io.layertrace.app.editor.ColorTarget
import io.layertrace.app.editor.EditorViewModel
import io.layertrace.app.editor.Tool
import io.layertrace.app.ui.components.Swatch
import io.layertrace.app.ui.components.ToolIcon
import io.layertrace.app.ui.theme.LocalEditorColors
import io.layertrace.core.model.Colors

private val mainTools = listOf(Tool.Selection, Tool.Direct, Tool.Type, Tool.Line, Tool.Frame, Tool.Rectangle, Tool.Ellipse, Tool.Polygon, Tool.Hand, Tool.Zoom)

@Composable
fun VerticalToolBar(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val colors = LocalEditorColors.current
    Column(
        modifier.fillMaxHeight().width(44.dp).background(colors.chrome).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (t in mainTools) ToolIcon(AppIcons.tool(t), "${t.label} (${t.key})", selected = vm.tool == t) { vm.tool = t }
        HorizontalDivider(Modifier.padding(vertical = 6.dp, horizontal = 8.dp), color = colors.divider)
        FillStrokeProxy(vm)
        HorizontalDivider(Modifier.padding(vertical = 6.dp, horizontal = 8.dp), color = colors.divider)
        ToolIcon(AppIcons.act(Act.Place)!!, "Place Image (Ctrl+D)") { vm.perform(Act.Place) }
        ToolIcon(AppIcons.act(Act.TogglePreview)!!, "Preview Mode (W)", selected = vm.view.preview) { vm.perform(Act.TogglePreview) }
    }
}

@Composable
fun HorizontalToolBar(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val colors = LocalEditorColors.current
    Row(
        modifier.fillMaxWidth().height(48.dp).background(colors.chrome).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (t in mainTools) ToolIcon(AppIcons.tool(t), t.label, selected = vm.tool == t, size = 44.dp) { vm.tool = t }
        Spacer(Modifier.width(6.dp))
        FillStrokeProxy(vm)
        ToolIcon(AppIcons.act(Act.Place)!!, "Place Image", size = 44.dp) { vm.perform(Act.Place) }
    }
}

/** InDesign-style overlapping fill/stroke squares; click to choose which one swatches apply to. */
@Composable
fun FillStrokeProxy(vm: EditorViewModel) {
    val colors = LocalEditorColors.current
    val d = vm.shown
    val item = vm.selectedItems.firstOrNull()
    val fill = if (vm.textEdit != null) vm.currentTextAttrs()?.fillColor else item?.fill
    val stroke = item?.stroke
    fun c(id: String?) = d?.let { Color(Colors.argb(it, id)) } ?: Color.Transparent
    Box(Modifier.size(40.dp)) {
        val strokeFirst = vm.colorTarget == ColorTarget.Stroke
        val fillMod = Modifier.offset(2.dp, 2.dp).clickable { vm.colorTarget = ColorTarget.Fill }
        val strokeMod = Modifier.offset(16.dp, 16.dp).clickable { vm.colorTarget = ColorTarget.Stroke }
        if (strokeFirst) Swatch(c(fill), 22.dp, none = fill == null, modifier = fillMod)
        Box(strokeMod.size(22.dp).background(colors.chrome)) {
            Swatch(c(stroke), 22.dp, none = stroke == null, selected = strokeFirst)
            Box(Modifier.size(10.dp).align(Alignment.Center).background(colors.chrome))
        }
        if (!strokeFirst) Swatch(c(fill), 22.dp, none = fill == null, selected = true, modifier = fillMod)
    }
    Text(if (vm.colorTarget == ColorTarget.Fill) "Fill" else "Stroke", color = colors.textDim, fontSize = 9.sp)
}
