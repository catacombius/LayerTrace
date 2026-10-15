package io.layertrace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.layertrace.app.editor.Act
import io.layertrace.app.editor.EditorViewModel
import io.layertrace.app.editor.WorkspaceMode
import io.layertrace.app.ui.components.ToolIcon
import io.layertrace.app.ui.theme.LocalEditorColors
import io.layertrace.core.model.MeasureUnit
import kotlin.math.roundToInt

@Composable
fun StatusBar(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val colors = LocalEditorColors.current
    val d = vm.doc ?: return
    var zoomMenu by remember { mutableStateOf(false) }
    var unitMenu by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().height(30.dp).background(colors.chromeLow).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            Text("${vm.zoomPercent().roundToInt()}%", color = colors.text, fontSize = 12.sp, modifier = Modifier.clickable { zoomMenu = true }.padding(4.dp))
            DropdownMenu(zoomMenu, onDismissRequest = { zoomMenu = false }) {
                for (z in listOf(25, 50, 75, 100, 150, 200, 400, 800)) DropdownMenuItem(text = { Text("$z%") }, onClick = { zoomMenu = false; vm.setZoomPercent(z.toFloat()) })
                DropdownMenuItem(text = { Text("Fit Page") }, onClick = { zoomMenu = false; vm.perform(Act.FitPage) })
                DropdownMenuItem(text = { Text("Fit Spread") }, onClick = { zoomMenu = false; vm.perform(Act.FitSpread) })
            }
        }
        ToolIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page", size = 26.dp) { vm.perform(Act.PrevPage) }
        Text(
            if (vm.editingMasters) "Masters" else "Page ${vm.currentPageIndex() + 1} / ${d.pageCount}",
            color = colors.text, fontSize = 12.sp,
        )
        ToolIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page", size = 26.dp) { vm.perform(Act.NextPage) }
        Box {
            Text(vm.view.unit.label, color = colors.text, fontSize = 12.sp, modifier = Modifier.clickable { unitMenu = true }.padding(4.dp))
            DropdownMenu(unitMenu, onDismissRequest = { unitMenu = false }) {
                for (u in MeasureUnit.entries) DropdownMenuItem(text = { Text(u.name) }, onClick = { unitMenu = false; vm.setUnit(u) })
            }
        }
        val overset = vm.overflowFrames()
        if (overset > 0) {
            Icon(Icons.Default.Warning, null, tint = colors.overset, modifier = Modifier.width(14.dp))
            Text("$overset overset ${if (overset == 1) "story" else "stories"}", color = colors.overset, fontSize = 11.sp)
        }
        vm.textEdit?.let { te ->
            val st = d.stories[te.storyId]
            Text("Story: ${st?.length ?: 0} chars", color = colors.textDim, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        Text(if (vm.dirty) "● Unsaved" else "Saved", color = if (vm.dirty) colors.textDim else colors.textDim.copy(alpha = 0.6f), fontSize = 11.sp)
        val touch = vm.workspace == WorkspaceMode.Touch
        Row(
            Modifier.clickable { vm.perform(Act.ToggleWorkspace) }.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (touch) Icons.Default.TouchApp else Icons.Default.Keyboard, null, tint = colors.accent, modifier = Modifier.width(16.dp))
            Text(" ${vm.workspace.label}", color = colors.accent, fontSize = 11.sp)
        }
    }
}
