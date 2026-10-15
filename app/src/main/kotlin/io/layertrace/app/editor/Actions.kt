package io.layertrace.app.editor

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode

/** A keyboard shortcut. `ctrl` also matches Meta (Cmd) on keyboards that have it. */
data class Shortcut(val key: Key, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false) {
    val display: String
        get() = buildString {
            if (ctrl) append("Ctrl+")
            if (alt) append("Alt+")
            if (shift) append("Shift+")
            append(keyName(key))
        }

    companion object {
        fun keyName(k: Key): String = when (k) {
            Key.Equals -> "="
            Key.Minus -> "−"
            Key.LeftBracket -> "["
            Key.RightBracket -> "]"
            Key.Backslash -> "\\"
            Key.Delete -> "Del"
            Key.Backspace -> "Backspace"
            Key.Tab -> "Tab"
            Key.Escape -> "Esc"
            Key.Zero -> "0"
            Key.One -> "1"
            Key.Two -> "2"
            Key.Three -> "3"
            Key.Semicolon -> ";"
            Key.Apostrophe -> "'"
            Key.Comma -> ","
            Key.Period -> "."
            Key.Slash -> "/"
            Key.F1 -> "F1"
            Key.F5 -> "F5"
            Key.F6 -> "F6"
            Key.F7 -> "F7"
            Key.F11 -> "F11"
            Key.F12 -> "F12"
            Key.PageUp -> "PgUp"
            Key.PageDown -> "PgDn"
            // Key.toString() has no names on devices ("Key code: 214748364800"), so name keys from their Android key code.
            else -> {
                val code = k.nativeKeyCode
                when (code) {
                    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (code - KeyEvent.KEYCODE_A)).toString()
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (code - KeyEvent.KEYCODE_0)).toString()
                    in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${code - KeyEvent.KEYCODE_F1 + 1}"
                    else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
                        .split('_').joinToString(" ") { w -> if (w.length <= 1) w else w[0] + w.substring(1).lowercase() }
                }
            }
        }
    }
}

/** Every user command. Menus, context menus, the command palette and shortcuts all dispatch these. */
enum class Act(val label: String, val shortcut: Shortcut? = null) {
    // File
    New("New Document…", Shortcut(Key.N, ctrl = true)),
    Open("Open…", Shortcut(Key.O, ctrl = true)),
    Save("Save", Shortcut(Key.S, ctrl = true)),
    SaveAs("Save As…", Shortcut(Key.S, ctrl = true, shift = true)),
    ExportPdf("Export PDF…", Shortcut(Key.E, ctrl = true)),
    ExportPng("Export Page as PNG…", Shortcut(Key.E, ctrl = true, alt = true)),
    DocumentSetup("Document Setup…", Shortcut(Key.P, ctrl = true, alt = true)),
    ImportFont("Import Font…"),
    Close("Close Document", Shortcut(Key.W, ctrl = true)),

    // Edit
    Undo("Undo", Shortcut(Key.Z, ctrl = true)),
    Redo("Redo", Shortcut(Key.Z, ctrl = true, shift = true)),
    Cut("Cut", Shortcut(Key.X, ctrl = true)),
    Copy("Copy", Shortcut(Key.C, ctrl = true)),
    Paste("Paste", Shortcut(Key.V, ctrl = true)),
    PasteInPlace("Paste in Place", Shortcut(Key.V, ctrl = true, alt = true, shift = true)),
    Duplicate("Duplicate", Shortcut(Key.D, ctrl = true, alt = true, shift = true)),
    Delete("Delete", Shortcut(Key.Delete)),
    SelectAll("Select All", Shortcut(Key.A, ctrl = true)),
    Deselect("Deselect All", Shortcut(Key.A, ctrl = true, shift = true)),

    // Object
    BringToFront("Bring to Front", Shortcut(Key.RightBracket, ctrl = true, shift = true)),
    BringForward("Bring Forward", Shortcut(Key.RightBracket, ctrl = true)),
    SendBackward("Send Backward", Shortcut(Key.LeftBracket, ctrl = true)),
    SendToBack("Send to Back", Shortcut(Key.LeftBracket, ctrl = true, shift = true)),
    Group("Group", Shortcut(Key.G, ctrl = true)),
    Ungroup("Ungroup", Shortcut(Key.G, ctrl = true, shift = true)),
    Lock("Lock", Shortcut(Key.L, ctrl = true)),
    UnlockAll("Unlock All on Spread", Shortcut(Key.L, ctrl = true, alt = true)),
    AlignLeft("Align Left Edges"),
    AlignCenterH("Align Horizontal Centers"),
    AlignRight("Align Right Edges"),
    AlignTop("Align Top Edges"),
    AlignCenterV("Align Vertical Centers"),
    AlignBottom("Align Bottom Edges"),
    DistributeH("Distribute Horizontal Centers"),
    DistributeV("Distribute Vertical Centers"),
    FlipH("Flip Horizontal"),
    FlipV("Flip Vertical"),
    Rotate90CW("Rotate 90° Clockwise"),
    Rotate90CCW("Rotate 90° Counter-clockwise"),
    Place("Place Image…", Shortcut(Key.D, ctrl = true)),
    FitContentProportionally("Fit Content Proportionally", Shortcut(Key.E, ctrl = true, alt = true, shift = true)),
    FillFrameProportionally("Fill Frame Proportionally", Shortcut(Key.C, ctrl = true, alt = true, shift = true)),
    CenterContent("Center Content", Shortcut(Key.E, ctrl = true, shift = true)),

    // Type
    ThreadFrames("Thread Selected Text Frames"),
    UnthreadFrame("Break Thread After Frame"),
    FillPlaceholder("Fill with Placeholder Text"),
    NewParagraphStyle("New Paragraph Style from Selection…"),
    NewCharacterStyle("New Character Style from Selection…"),
    ClearOverrides("Clear Text Overrides"),
    Bold("Bold", Shortcut(Key.B, ctrl = true, shift = true)),
    Italic("Italic", Shortcut(Key.I, ctrl = true, shift = true)),
    Underline("Underline", Shortcut(Key.U, ctrl = true, shift = true)),
    AlignTextLeft("Align Text Left", Shortcut(Key.L, ctrl = true, shift = true)),
    AlignTextCenter("Align Text Center", Shortcut(Key.C, ctrl = true, shift = true)),
    AlignTextRight("Align Text Right", Shortcut(Key.R, ctrl = true, shift = true)),
    AlignTextJustify("Justify Text", Shortcut(Key.J, ctrl = true, shift = true)),
    IncreaseSize("Increase Point Size", Shortcut(Key.Period, ctrl = true, shift = true)),
    DecreaseSize("Decrease Point Size", Shortcut(Key.Comma, ctrl = true, shift = true)),

    // Layout
    AddPage("Add Page", Shortcut(Key.P, ctrl = true, shift = true)),
    DeletePage("Delete Page"),
    EditMasters("Edit Master Pages"),
    NextPage("Next Page", Shortcut(Key.PageDown, shift = true)),
    PrevPage("Previous Page", Shortcut(Key.PageUp, shift = true)),

    // View
    ZoomIn("Zoom In", Shortcut(Key.Equals, ctrl = true)),
    ZoomOut("Zoom Out", Shortcut(Key.Minus, ctrl = true)),
    FitPage("Fit Page in Window", Shortcut(Key.Zero, ctrl = true)),
    FitSpread("Fit Spread in Window", Shortcut(Key.Zero, ctrl = true, alt = true)),
    ActualSize("Actual Size", Shortcut(Key.One, ctrl = true)),
    ToggleGuides("Show/Hide Guides", Shortcut(Key.Semicolon, ctrl = true)),
    ToggleFrameEdges("Show/Hide Frame Edges", Shortcut(Key.H, ctrl = true)),
    ToggleRulers("Show/Hide Rulers", Shortcut(Key.R, ctrl = true)),
    ToggleSnap("Snap to Guides & Objects", Shortcut(Key.Semicolon, ctrl = true, shift = true)),
    TogglePreview("Preview Mode", Shortcut(Key.W)),
    TogglePanels("Show/Hide Panels", Shortcut(Key.Tab)),
    ToggleWorkspace("Switch Workspace (Touch ↔ Keyboard & Mouse)", Shortcut(Key.F6, ctrl = true)),

    // Window
    ShowProperties("Properties", Shortcut(Key.F1, ctrl = true)),
    ShowPages("Pages", Shortcut(Key.F12)),
    ShowLayers("Layers", Shortcut(Key.F7)),
    ShowSwatches("Swatches", Shortcut(Key.F5)),
    ShowStyles("Paragraph & Character Styles", Shortcut(Key.F11)),

    // Help
    ShortcutsHelp("Keyboard Shortcuts…", Shortcut(Key.Slash, ctrl = true)),
    About("About LayerTrace & IDML…"),
}

/** Shortcut lookup, built once from [Act]. */
object ShortcutMap {
    private val map: Map<Shortcut, Act> = Act.entries.mapNotNull { a -> a.shortcut?.let { it to a } }.toMap()

    fun find(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean): Act? = map[Shortcut(key, ctrl, shift, alt)]

    /** Single-key tool shortcuts (only active when not typing). */
    val toolKeys: Map<Key, Tool> = mapOf(
        Key.V to Tool.Selection, Key.A to Tool.Direct, Key.T to Tool.Type, Key.Backslash to Tool.Line,
        Key.F to Tool.Frame, Key.M to Tool.Rectangle, Key.L to Tool.Ellipse, Key.P to Tool.Polygon,
        Key.H to Tool.Hand, Key.Z to Tool.Zoom,
    )

    fun conflicts(): List<Pair<Shortcut, List<Act>>> =
        Act.entries.filter { it.shortcut != null }.groupBy { it.shortcut!! }.filter { it.value.size > 1 }.map { it.key to it.value }
}

data class MenuEntry(val act: Act? = null, val separator: Boolean = false) {
    companion object {
        val SEP = MenuEntry(separator = true)
    }
}

data class MenuDef(val title: String, val entries: List<MenuEntry>)

object Menus {
    private fun m(vararg a: Any) = a.map { if (it is Act) MenuEntry(it) else MenuEntry.SEP }
    private const val S = "-"

    val all = listOf(
        MenuDef("File", m(Act.New, Act.Open, S, Act.Save, Act.SaveAs, Act.Close, S, Act.Place, Act.ImportFont, S, Act.ExportPdf, Act.ExportPng, S, Act.DocumentSetup)),
        MenuDef("Edit", m(Act.Undo, Act.Redo, S, Act.Cut, Act.Copy, Act.Paste, Act.PasteInPlace, Act.Duplicate, Act.Delete, S, Act.SelectAll, Act.Deselect)),
        MenuDef("Layout", m(Act.AddPage, Act.DeletePage, S, Act.NextPage, Act.PrevPage, S, Act.EditMasters)),
        MenuDef("Type", m(Act.Bold, Act.Italic, Act.Underline, S, Act.AlignTextLeft, Act.AlignTextCenter, Act.AlignTextRight, Act.AlignTextJustify, S, Act.IncreaseSize, Act.DecreaseSize, Act.ClearOverrides, S, Act.NewParagraphStyle, Act.NewCharacterStyle, S, Act.ThreadFrames, Act.UnthreadFrame, Act.FillPlaceholder)),
        MenuDef("Object", m(Act.BringToFront, Act.BringForward, Act.SendBackward, Act.SendToBack, S, Act.Group, Act.Ungroup, Act.Lock, Act.UnlockAll, S, Act.AlignLeft, Act.AlignCenterH, Act.AlignRight, Act.AlignTop, Act.AlignCenterV, Act.AlignBottom, Act.DistributeH, Act.DistributeV, S, Act.Rotate90CW, Act.Rotate90CCW, Act.FlipH, Act.FlipV, S, Act.FitContentProportionally, Act.FillFrameProportionally, Act.CenterContent)),
        MenuDef("View", m(Act.ZoomIn, Act.ZoomOut, Act.FitPage, Act.FitSpread, Act.ActualSize, S, Act.TogglePreview, Act.ToggleGuides, Act.ToggleFrameEdges, Act.ToggleRulers, Act.ToggleSnap, S, Act.TogglePanels, Act.ToggleWorkspace)),
        MenuDef("Window", m(Act.ShowProperties, Act.ShowPages, Act.ShowLayers, Act.ShowSwatches, Act.ShowStyles)),
        MenuDef("Help", m(Act.ShortcutsHelp, Act.About)),
    )
}
