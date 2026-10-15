package io.layertrace.app.editor

import android.view.KeyEvent
import io.layertrace.app.text.ImeSink
import io.layertrace.core.text.StoryEditing

/** Bridges input-method callbacks to [EditorViewModel] text editing. */
class StoryImeSink(private val vm: EditorViewModel) : ImeSink {
    private var composeRange: IntRange? = null

    override val composing: IntRange? get() = composeRange
    override val selection: IntRange get() = vm.textEdit?.let { it.start..it.end } ?: 0..0

    private fun story() = vm.textEdit?.let { vm.doc?.stories?.get(it.storyId) }

    override fun commit(text: String) {
        replaceComposing(text)
        composeRange = null
    }

    override fun compose(text: String) {
        val start = replaceComposing(text) ?: return
        composeRange = if (text.isEmpty()) null else start..(start + text.length)
    }

    private fun replaceComposing(text: String): Int? {
        val te = vm.textEdit ?: return null
        val st = story() ?: return null
        val r = composeRange
        val (a, b) = if (r != null && r.last <= st.length) r.first to r.last else te.start to te.end
        vm.setCaret(b, a)
        vm.textReplace(text.replace("\r\n", "\n"))
        return a
    }

    override fun finishComposing() { composeRange = null }

    override fun setComposingRegion(start: Int, end: Int) {
        val st = story() ?: return
        val a = minOf(start, end).coerceIn(0, st.length); val b = maxOf(start, end).coerceIn(0, st.length)
        composeRange = if (a == b) null else a..b
    }

    override fun deleteSurrounding(before: Int, after: Int) {
        val te = vm.textEdit ?: return
        val st = story() ?: return
        val a = (te.start - before).coerceAtLeast(0)
        val b = (te.end + after).coerceAtMost(st.length)
        if (te.end < b) { vm.setCaret(b, te.end); vm.textReplace("") }
        if (a < te.start) { vm.setCaret(te.start, a); vm.textReplace("") }
        composeRange = null
    }

    override fun key(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> vm.textBackspace(event.isCtrlPressed)
            KeyEvent.KEYCODE_FORWARD_DEL -> vm.textDeleteForward(event.isCtrlPressed)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> vm.textReplace(if (event.isShiftPressed) " " else "\n")
            KeyEvent.KEYCODE_DPAD_LEFT -> vm.moveCaret(EditorViewModel.CaretMove.Left, event.isShiftPressed)
            KeyEvent.KEYCODE_DPAD_RIGHT -> vm.moveCaret(EditorViewModel.CaretMove.Right, event.isShiftPressed)
            KeyEvent.KEYCODE_DPAD_UP -> vm.moveCaret(EditorViewModel.CaretMove.Up, event.isShiftPressed)
            KeyEvent.KEYCODE_DPAD_DOWN -> vm.moveCaret(EditorViewModel.CaretMove.Down, event.isShiftPressed)
            else -> {
                val c = event.unicodeChar
                if (c != 0 && !event.isCtrlPressed) vm.textReplace(String(Character.toChars(c)))
            }
        }
        composeRange = null
    }

    override fun textBefore(n: Int): String {
        val te = vm.textEdit ?: return ""
        val st = story() ?: return ""
        return StoryEditing.substring(st, (te.start - n).coerceAtLeast(0), te.start)
    }

    override fun textAfter(n: Int): String {
        val te = vm.textEdit ?: return ""
        val st = story() ?: return ""
        return StoryEditing.substring(st, te.end, (te.end + n).coerceAtMost(st.length))
    }
}
