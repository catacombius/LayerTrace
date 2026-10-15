package io.layertrace.app

import android.content.Intent
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.view.InputDevice
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import io.layertrace.app.editor.EditorViewModel
import io.layertrace.app.editor.FileRequest
import io.layertrace.app.editor.KeyHandler
import io.layertrace.app.editor.WorkspaceMode
import io.layertrace.app.ui.dialogs.DialogHost
import io.layertrace.app.ui.theme.LayerTraceTheme
import io.layertrace.app.ui.theme.LocalEditorColors
import io.layertrace.app.workspace.EditorScreen
import io.layertrace.app.workspace.WelcomeScreen
import io.layertrace.app.workspace.foldStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()
    private val layoutInfo = MutableStateFlow<WindowLayoutInfo?>(null)

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = checkDevices(added = true)
        override fun onInputDeviceRemoved(deviceId: Int) = checkDevices(added = false)
        override fun onInputDeviceChanged(deviceId: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collect { layoutInfo.value = it }
        }
        (getSystemService(INPUT_SERVICE) as InputManager).registerInputDeviceListener(deviceListener, null)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            LayerTraceTheme { App(vm) }
        }
    }

    override fun onDestroy() {
        (getSystemService(INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(deviceListener)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        if (vm.doc != null && vm.dirty) vm.dialog = io.layertrace.app.editor.Dialog.ConfirmDiscard { vm.openUri(uri) }
        else vm.openUri(uri)
    }

    private fun hasPointerOrKeyboard(): Boolean {
        val im = getSystemService(INPUT_SERVICE) as InputManager
        return im.inputDeviceIds.any { id ->
            val d = im.getInputDevice(id) ?: return@any false
            if (d.isVirtual) return@any false
            d.supportsSource(InputDevice.SOURCE_MOUSE) || d.supportsSource(InputDevice.SOURCE_TOUCHPAD) ||
                (d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC && d.isExternal)
        }
    }

    private fun checkDevices(added: Boolean) {
        val present = hasPointerOrKeyboard()
        vm.workspaceSuggestion = when {
            added && present && vm.workspace == WorkspaceMode.Touch -> WorkspaceMode.KeyboardMouse
            !added && !present && vm.workspace == WorkspaceMode.KeyboardMouse -> WorkspaceMode.Touch
            else -> null
        }
    }

    @Composable
    private fun App(vm: EditorViewModel) {
        val colors = LocalEditorColors.current
        val keys = remember(vm) { KeyHandler(vm) }
        val info by layoutInfo.collectAsState()
        val snackbar = remember { SnackbarHostState() }

        val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.openUri(it) } }
        val saveDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.adobe.indesign-idml-package")) { uri -> uri?.let { vm.writeTo(it) } }
        val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            val r = pdfRequest
            if (uri != null && r != null) vm.exportPdf(uri, r.pages, r.bleed)
        }
        val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri -> uri?.let { vm.exportPng(it) } }
        val placeImg = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.placeImage(it) } }
        val importFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importFont(it) } }

        LaunchedEffect(vm.fileRequest) {
            val r = vm.fileRequest ?: return@LaunchedEffect
            vm.fileRequest = null
            runCatching {
                when (r) {
                    FileRequest.OpenDocument -> openDoc.launch(arrayOf("application/vnd.adobe.indesign-idml-package", "application/zip", "application/octet-stream", "application/x-indesign", "*/*"))
                    is FileRequest.SaveDocument -> saveDoc.launch(r.suggestedName)
                    is FileRequest.ExportPdf -> { pdfRequest = r; savePdf.launch(r.suggestedName) }
                    is FileRequest.ExportPng -> savePng.launch(r.suggestedName)
                    FileRequest.PlaceImage -> placeImg.launch(arrayOf("image/*"))
                    FileRequest.ImportFont -> importFont.launch(arrayOf("font/ttf", "font/otf", "font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*"))
                }
            }.onFailure { vm.message("No app available to pick files") }
        }
        LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

        BackHandler(enabled = vm.textEdit != null || vm.selection.isNotEmpty() || vm.contextMenu != null) {
            when {
                vm.contextMenu != null -> vm.contextMenu = null
                vm.textEdit != null -> vm.endTextEdit()
                else -> vm.clearSelection()
            }
        }

        Box(Modifier.fillMaxSize().background(colors.chromeLow).safeDrawingPadding()) {
            if (vm.doc == null) WelcomeScreen(vm) { vm.openUri(Uri.parse(it)) }
            else EditorScreen(vm, keys, foldStateOf(info))
            DialogHost(vm)
            vm.busy?.let { msg ->
                Row(
                    Modifier.align(Alignment.Center).background(colors.chromeHigh).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text("  $msg")
                }
            }
            vm.workspaceSuggestion?.let { mode ->
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    action = { TextButton({ vm.changeWorkspace(mode); vm.workspaceSuggestion = null }) { Text("Switch") } },
                    dismissAction = { TextButton({ vm.workspaceSuggestion = null }) { Text("Dismiss") } },
                ) {
                    Text(if (mode == WorkspaceMode.KeyboardMouse) "Keyboard or mouse connected. Use the Keyboard & Mouse workspace?" else "No keyboard or mouse. Use the Touch workspace?")
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
        }
    }

    /** Pending PDF export options while the system file picker is open. */
    private var pdfRequest: FileRequest.ExportPdf? = null
}
