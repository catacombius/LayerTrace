package io.layertrace.app.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.layertrace.app.editor.Act
import io.layertrace.app.editor.EditorViewModel
import io.layertrace.app.editor.WorkspaceMode
import io.layertrace.app.ui.theme.LocalEditorColors

@Composable
fun WelcomeScreen(vm: EditorViewModel, onOpenRecent: (String) -> Unit) {
    val colors = LocalEditorColors.current
    Box(Modifier.fillMaxSize().background(colors.pasteboard), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("LayerTrace", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = colors.text)
            Text("Page layout for tablets, foldables and phones. Opens and saves InDesign IDML.", color = colors.textDim, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({ vm.perform(Act.New) }) { Icon(Icons.Default.NoteAdd, null); Spacer(Modifier.size(6.dp)); Text("New document") }
                OutlinedButton({ vm.perform(Act.Open) }) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.size(6.dp)); Text("Open IDML…") }
            }
            if (vm.recentFiles.isNotEmpty()) {
                Text("Recent", color = colors.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                for (r in vm.recentFiles) {
                    Row(
                        Modifier.fillMaxWidth().background(colors.chrome, RoundedCornerShape(6.dp)).clickable { onOpenRecent(r.uri) }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Description, null, tint = colors.accent)
                        Text(r.name, color = colors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp))
                        Icon(Icons.Default.Close, "Remove from list", tint = colors.textDim, modifier = Modifier.size(18.dp).clickable { vm.removeRecent(r) })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("About .indd files", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Native .indd is Adobe's closed binary format. Save it as IDML in InDesign (File › Save As › InDesign Markup) and open it here. " +
                    "Everything you save from LayerTrace is IDML, which InDesign and Affinity Publisher can open.",
                color = colors.textDim, fontSize = 12.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Workspace: ${vm.workspace.label}", color = colors.textDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton({ vm.changeWorkspace(if (vm.workspace == WorkspaceMode.Touch) WorkspaceMode.KeyboardMouse else WorkspaceMode.Touch) }) { Text("Switch") }
                TextButton({ vm.perform(Act.ShortcutsHelp) }) { Icon(Icons.Default.Keyboard, null); Text(" Shortcuts") }
            }
        }
    }
}
