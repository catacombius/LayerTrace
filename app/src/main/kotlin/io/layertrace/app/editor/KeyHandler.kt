package io.layertrace.app.editor

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import io.layertrace.core.edit.ItemOps
import io.layertrace.app.editor.EditorViewModel.CaretMove

/** Keyboard handling for the canvas: text editing keys first, then shortcuts and tool keys. */
class KeyHandler(private val vm: EditorViewModel) {
    /** True while the space bar is held (temporary Hand tool). */
    var spaceDown = false
        private set

    fun onKey(e: KeyEvent): Boolean {
        if (e.key == Key.Spacebar && vm.textEdit == null) {
            spaceDown = e.type == KeyEventType.KeyDown
            return true
        }
        if (e.type != KeyEventType.KeyDown) return false
        if (vm.doc == null) {
            val ctrl = e.isCtrlPressed || e.isMetaPressed
            val act = ShortcutMap.find(e.key, ctrl, e.isShiftPressed, e.isAltPressed) ?: return false
            if (act == Act.New || act == Act.Open || act == Act.ShortcutsHelp) { vm.perform(act); return true }
            return false
        }
        return if (vm.textEdit != null) textKey(e) else objectKey(e)
    }

    private fun textKey(e: KeyEvent): Boolean {
        val ctrl = e.isCtrlPressed || e.isMetaPressed
        val shift = e.isShiftPressed
        when (e.key) {
            Key.Escape -> { vm.endTextEdit(); return true }
            Key.DirectionLeft -> { vm.moveCaret(if (ctrl) CaretMove.WordLeft else CaretMove.Left, shift); return true }
            Key.DirectionRight -> { vm.moveCaret(if (ctrl) CaretMove.WordRight else CaretMove.Right, shift); return true }
            Key.DirectionUp -> { vm.moveCaret(CaretMove.Up, shift); return true }
            Key.DirectionDown -> { vm.moveCaret(CaretMove.Down, shift); return true }
            Key.MoveHome -> { vm.moveCaret(if (ctrl) CaretMove.StoryStart else CaretMove.LineStart, shift); return true }
            Key.MoveEnd -> { vm.moveCaret(if (ctrl) CaretMove.StoryEnd else CaretMove.LineEnd, shift); return true }
            Key.Backspace -> { vm.textBackspace(ctrl); return true }
            Key.Delete -> { vm.textDeleteForward(ctrl); return true }
            Key.Enter, Key.NumPadEnter -> { vm.textReplace(if (shift) " " else "\n"); return true }
            Key.Tab -> { vm.textReplace("\t"); return true }
        }
        if (ctrl) {
            val act = ShortcutMap.find(e.key, true, shift, e.isAltPressed)
            if (act != null) { vm.perform(act); return true }
            return false
        }
        val cp = e.utf16CodePoint
        if (cp != 0 && !Character.isISOControl(cp)) {
            vm.textReplace(String(Character.toChars(cp)))
            return true
        }
        return false
    }

    private fun objectKey(e: KeyEvent): Boolean {
        val ctrl = e.isCtrlPressed || e.isMetaPressed
        val shift = e.isShiftPressed
        val alt = e.isAltPressed
        ShortcutMap.find(e.key, ctrl, shift, alt)?.let { vm.perform(it); return true }
        if (!ctrl && !alt) {
            val step = if (shift) 10.0 else 1.0
            val nudge = when (e.key) {
                Key.DirectionLeft -> -step to 0.0
                Key.DirectionRight -> step to 0.0
                Key.DirectionUp -> 0.0 to -step
                Key.DirectionDown -> 0.0 to step
                else -> null
            }
            if (nudge != null && vm.selection.isNotEmpty()) {
                vm.doc?.let { vm.commit(ItemOps.move(it, vm.selection.toSet(), nudge.first, nudge.second), "Nudge", coalesce = "Nudge") }
                return true
            }
            when (e.key) {
                Key.Backspace -> { vm.perform(Act.Delete); return true }
                Key.Escape -> { if (vm.selection.isNotEmpty()) vm.clearSelection() else vm.tool = Tool.Selection; return true }
                Key.Enter -> {
                    val f = vm.selectedItems.singleOrNull()?.takeIf { it.isText }
                    if (f != null) { vm.beginTextEdit(f.id, vm.doc?.stories?.get(f.storyId)?.length ?: 0); return true }
                }
            }
            if (!shift) ShortcutMap.toolKeys[e.key]?.let { vm.tool = it; return true }
            if (shift && e.key == Key.X) { vm.colorTarget = if (vm.colorTarget == ColorTarget.Fill) ColorTarget.Stroke else ColorTarget.Fill; return true }
        }
        return false
    }
}
