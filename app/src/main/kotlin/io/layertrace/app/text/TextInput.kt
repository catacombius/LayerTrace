@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.layertrace.app.text

import android.content.Context
import android.content.res.Configuration
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Receives input method events for the story being edited. Offsets are story offsets. */
interface ImeSink {
    val selection: IntRange
    fun commit(text: String)
    fun compose(text: String)
    fun finishComposing()
    fun setComposingRegion(start: Int, end: Int)
    fun deleteSurrounding(before: Int, after: Int)
    fun key(event: KeyEvent)
    fun textBefore(n: Int): String
    fun textAfter(n: Int): String
    val composing: IntRange?
}

private class LtInputConnection(view: View, private val sink: ImeSink) : BaseInputConnection(view, false) {
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { sink.commit(text?.toString() ?: ""); return true }
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean { sink.compose(text?.toString() ?: ""); return true }
    override fun finishComposingText(): Boolean { sink.finishComposing(); return true }
    override fun setComposingRegion(start: Int, end: Int): Boolean { sink.setComposingRegion(start, end); return true }
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean { sink.deleteSurrounding(beforeLength, afterLength); return true }
    override fun sendKeyEvent(event: KeyEvent): Boolean { sink.key(event); return true }
    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = sink.textBefore(n)
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = sink.textAfter(n)
    override fun getSelectedText(flags: Int): CharSequence? = null
    override fun getEditable() = null
}

private data class ImeElement(val sink: ImeSink, val active: Boolean, val showKeyboard: Boolean, val sel: IntRange) : ModifierNodeElement<ImeNode>() {
    override fun create() = ImeNode(sink, active, showKeyboard)
    override fun update(node: ImeNode) = node.update(sink, active, showKeyboard)
    override fun InspectorInfo.inspectableProperties() { name = "storyInput" }
}

private class ImeNode(var sink: ImeSink, var active: Boolean, var showKeyboard: Boolean) :
    Modifier.Node(), PlatformTextInputModifierNode, CompositionLocalConsumerModifierNode {

    private var job: Job? = null

    override fun onAttach() { if (active) start() }
    override fun onDetach() { job?.cancel(); job = null }

    fun update(s: ImeSink, a: Boolean, show: Boolean) {
        sink = s
        showKeyboard = show
        if (a && !active) { active = true; start() }
        else if (!a && active) { active = false; job?.cancel(); job = null }
        else if (a) notifySelection()
    }

    private fun notifySelection() {
        val view = currentValueOf(LocalView)
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val sel = sink.selection
        val comp = sink.composing
        imm.updateSelection(view, sel.first, sel.last, comp?.first ?: -1, comp?.last ?: -1)
    }

    private fun start() {
        if (!isAttached) return
        job?.cancel()
        job = coroutineScope.launch {
            establishTextInputSession {
                launch {
                    delay(60)
                    if (showKeyboard) {
                        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(view, 0)
                    }
                }
                startInputMethod(PlatformTextInputMethodRequest { out: EditorInfo ->
                    out.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    out.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
                    out.initialSelStart = sink.selection.first
                    out.initialSelEnd = sink.selection.last
                    LtInputConnection(view, sink) as InputConnection
                })
            }
        }
    }
}

/** Connects the system input method to on-canvas text editing while [active]. */
fun Modifier.storyInput(sink: ImeSink, active: Boolean, showKeyboard: Boolean): Modifier =
    this then ImeElement(sink, active, showKeyboard, sink.selection)

fun hasHardwareKeyboard(config: Configuration) =
    config.keyboard == Configuration.KEYBOARD_QWERTY && config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
