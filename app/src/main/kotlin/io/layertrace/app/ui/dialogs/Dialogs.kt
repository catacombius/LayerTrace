package io.layertrace.app.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.layertrace.app.editor.Act
import io.layertrace.app.editor.Dialog
import io.layertrace.app.editor.EditorViewModel
import io.layertrace.app.editor.FileRequest
import io.layertrace.app.editor.Menus
import io.layertrace.app.editor.ShortcutMap
import io.layertrace.app.editor.Tool
import io.layertrace.app.render.PdfExporter
import io.layertrace.app.ui.components.Dropdown
import io.layertrace.app.ui.components.LabeledRow
import io.layertrace.app.ui.components.PlainNumberField
import io.layertrace.app.ui.components.SectionHeader
import io.layertrace.app.ui.components.Swatch
import io.layertrace.app.ui.components.TextInput
import io.layertrace.app.ui.components.UnitField
import io.layertrace.app.ui.theme.LocalEditorColors
import io.layertrace.core.edit.DocumentFactory
import io.layertrace.core.model.CharacterStyle
import io.layertrace.core.model.ColorSpace
import io.layertrace.core.model.Colors
import io.layertrace.core.model.Ids
import io.layertrace.core.model.Justification
import io.layertrace.core.model.LayerColor
import io.layertrace.core.model.Margins
import io.layertrace.core.model.ParagraphStyle
import io.layertrace.core.model.Swatch
import io.layertrace.core.model.SwatchKind
import io.layertrace.core.model.TextAttrs
import kotlin.math.roundToInt

@Composable
fun DialogHost(vm: EditorViewModel) {
    when (val d = vm.dialog) {
        null -> {}
        Dialog.NewDocument -> DocumentSetupDialog(vm, isNew = true)
        Dialog.DocumentSetup -> DocumentSetupDialog(vm, isNew = false)
        Dialog.ExportPdf -> ExportPdfDialog(vm)
        is Dialog.EditSwatch -> SwatchDialog(vm, d.swatch)
        is Dialog.EditParagraphStyle -> ParagraphStyleDialog(vm, d.style)
        is Dialog.EditCharacterStyle -> CharacterStyleDialog(vm, d.style)
        is Dialog.RenameLayer -> LayerDialog(vm, d)
        is Dialog.InddInfo -> InddDialog(vm, d.fileName)
        Dialog.Shortcuts -> ShortcutsDialog(vm)
        Dialog.About -> AboutDialog(vm)
        is Dialog.ConfirmDiscard -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text("Discard unsaved changes?") },
            text = { Text("The current document has changes that have not been saved.") },
            confirmButton = { TextButton({ vm.dialog = null; d.then() }) { Text("Discard") } },
            dismissButton = {
                Row {
                    TextButton({ vm.dialog = null }) { Text("Cancel") }
                    TextButton({ vm.dialog = null; vm.save() }) { Text("Save first") }
                }
            },
        )
        Dialog.Recover -> AlertDialog(
            onDismissRequest = { vm.recover(false) },
            title = { Text("Recover unsaved work?") },
            text = { Text("LayerTrace found an automatically saved copy of a document that was not saved before the app closed.") },
            confirmButton = { TextButton({ vm.recover(true) }) { Text("Recover") } },
            dismissButton = { TextButton({ vm.recover(false) }) { Text("Discard") } },
        )
    }
}

@Composable
private fun DocumentSetupDialog(vm: EditorViewModel, isNew: Boolean) {
    val doc = vm.doc
    val u = vm.view.unit
    val s = if (isNew) null else doc?.setup
    var name by remember { mutableStateOf(if (isNew) "Untitled" else doc?.name ?: "") }
    var w by remember { mutableStateOf(s?.pageWidth ?: DocumentFactory.presets[0].widthPt) }
    var h by remember { mutableStateOf(s?.pageHeight ?: DocumentFactory.presets[0].heightPt) }
    var pages by remember { mutableStateOf(1) }
    var facing by remember { mutableStateOf(s?.facingPages ?: true) }
    val m0 = s?.margins ?: Margins(36.0, 36.0, 36.0, 36.0, 1, 12.0)
    var top by remember { mutableStateOf(m0.top) }
    var bottom by remember { mutableStateOf(m0.bottom) }
    var left by remember { mutableStateOf(m0.left) }
    var right by remember { mutableStateOf(m0.right) }
    var cols by remember { mutableStateOf(m0.columns) }
    var gutter by remember { mutableStateOf(m0.gutter) }
    var bleed by remember { mutableStateOf(s?.bleedTop ?: 0.0) }
    val preset = DocumentFactory.presets.firstOrNull { p ->
        (Math.abs(p.widthPt - w) < 0.5 && Math.abs(p.heightPt - h) < 0.5) || (Math.abs(p.widthPt - h) < 0.5 && Math.abs(p.heightPt - w) < 0.5)
    }
    AlertDialog(
        onDismissRequest = { vm.dialog = null },
        title = { Text(if (isNew) "New Document" else "Document Setup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (isNew) LabeledRow { Text("Name", fontSize = 12.sp); TextInput(name, { name = it }, Modifier.width(220.dp)) }
                LabeledRow {
                    Text("Size", fontSize = 12.sp)
                    Dropdown(preset, DocumentFactory.presets + listOf(null), { it?.name ?: "Custom" }, { p -> if (p != null) { w = p.widthPt; h = p.heightPt } }, width = 210.dp)
                }
                LabeledRow {
                    UnitField("W", w, u, { w = it }, min = 1.0)
                    UnitField("H", h, u, { h = it }, min = 1.0)
                    TextButton({ val t = w; w = h; h = t }) { Text(if (w > h) "Landscape" else "Portrait", fontSize = 12.sp) }
                }
                if (isNew) LabeledRow { PlainNumberField("Pages", pages.toDouble(), "", { pages = it.toInt().coerceIn(1, 9999) }, range = 1.0..9999.0) }
                LabeledRow {
                    Checkbox(facing, { facing = it })
                    Text("Facing pages (spreads)", fontSize = 13.sp)
                }
                SectionHeader("Margins & columns")
                LabeledRow { UnitField("Top", top, u, { top = it }, min = 0.0); UnitField("Bottom", bottom, u, { bottom = it }, min = 0.0) }
                LabeledRow { UnitField(if (facing) "Inside" else "Left", left, u, { left = it }, min = 0.0); UnitField(if (facing) "Outside" else "Right", right, u, { right = it }, min = 0.0) }
                LabeledRow { PlainNumberField("Columns", cols.toDouble(), "", { cols = it.toInt().coerceIn(1, 40) }, range = 1.0..40.0); UnitField("Gutter", gutter, u, { gutter = it }, min = 0.0) }
                SectionHeader("Bleed")
                LabeledRow { UnitField("All sides", bleed, u, { bleed = it }, min = 0.0) }
            }
        },
        confirmButton = {
            TextButton({
                vm.dialog = null
                val m = Margins(top, bottom, left, right, cols, gutter)
                if (isNew) vm.newDocument(DocumentFactory.Params(name.ifBlank { "Untitled" }, w, h, pages, facing, m, bleed))
                else vm.setPageSetup(w, h, facing, m, bleed)
            }) { Text(if (isNew) "Create" else "OK") }
        },
        dismissButton = { TextButton({ vm.dialog = null }) { Text("Cancel") } },
    )
}

@Composable
private fun ExportPdfDialog(vm: EditorViewModel) {
    val d = vm.doc ?: return
    var range by remember { mutableStateOf("all") }
    var bleed by remember { mutableStateOf(false) }
    val pages = PdfExporter.parseRange(range, d.pageCount)
    AlertDialog(
        onDismissRequest = { vm.dialog = null },
        title = { Text("Export PDF") },
        text = {
            Column {
                LabeledRow { Text("Pages", fontSize = 12.sp); TextInput(range, { range = it }, Modifier.width(160.dp), "all, or 1-3, 5") }
                Text("${pages.size} page(s) selected", fontSize = 11.sp, color = LocalEditorColors.current.textDim)
                LabeledRow { Checkbox(bleed, { bleed = it }); Text("Include document bleed", fontSize = 13.sp) }
                Text(
                    "Exports vector PDF in RGB with embedded fonts. For commercial print (CMYK, PDF/X), open the saved IDML in InDesign.",
                    fontSize = 11.sp, color = LocalEditorColors.current.textDim,
                )
            }
        },
        confirmButton = {
            TextButton({
                vm.dialog = null
                vm.fileRequest = FileRequest.ExportPdf("${d.name}.pdf", pages, bleed)
            }, enabled = pages.isNotEmpty()) { Text("Export…") }
        },
        dismissButton = { TextButton({ vm.dialog = null }) { Text("Cancel") } },
    )
}

@Composable
private fun SwatchDialog(vm: EditorViewModel, existing: Swatch?) {
    var name by remember { mutableStateOf(existing?.name ?: "New Color") }
    var space by remember { mutableStateOf(existing?.space ?: ColorSpace.CMYK) }
    var values by remember { mutableStateOf(existing?.values ?: listOf(0.0, 0.0, 0.0, 100.0)) }
    var spot by remember { mutableStateOf(existing?.spot ?: false) }
    var hex by remember { mutableStateOf("") }
    val preview = Colors.toRgb(space, values)
    val channels = when (space) {
        ColorSpace.CMYK -> listOf("C" to 100f, "M" to 100f, "Y" to 100f, "K" to 100f)
        ColorSpace.RGB -> listOf("R" to 255f, "G" to 255f, "B" to 255f)
        ColorSpace.LAB -> listOf("L" to 100f, "a" to 128f, "b" to 128f)
    }
    fun convert(to: ColorSpace) {
        if (to == space) return
        val rgb = Colors.rgbComponents(Colors.toRgb(space, values))
        values = when (to) {
            ColorSpace.RGB -> rgb.map { it.roundToInt().toDouble() }
            ColorSpace.CMYK -> Colors.rgbToCmyk(rgb[0], rgb[1], rgb[2])
            ColorSpace.LAB -> listOf(50.0, 0.0, 0.0)
        }
        space = to
    }
    AlertDialog(
        onDismissRequest = { vm.dialog = null },
        title = { Text(if (existing == null) "New Color Swatch" else "Edit Swatch") },
        text = {
            Column {
                LabeledRow { Swatch(Color(preview), 36.dp); TextInput(name, { name = it }, Modifier.width(200.dp)) }
                LabeledRow {
                    Dropdown(space, listOf(ColorSpace.CMYK, ColorSpace.RGB), { it.name }, { convert(it) }, width = 100.dp)
                    Checkbox(spot, { spot = it }); Text("Spot", fontSize = 13.sp)
                }
                channels.forEachIndexed { i, (label, max) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.width(18.dp), fontSize = 12.sp)
                        Slider(
                            value = values.getOrElse(i) { 0.0 }.toFloat(),
                            onValueChange = { v -> values = values.toMutableList().also { it[i] = (v * 10).roundToInt() / 10.0 } },
                            valueRange = (if (space == ColorSpace.LAB && i > 0) -128f else 0f)..max,
                            modifier = Modifier.weight(1f),
                        )
                        PlainNumberField("", values.getOrElse(i) { 0.0 }, "", { v -> values = values.toMutableList().also { it[i] = v.coerceIn(if (space == ColorSpace.LAB && i > 0) -128.0 else 0.0, max.toDouble()) } }, width = 56.dp)
                    }
                }
                LabeledRow {
                    Text("Hex", fontSize = 12.sp)
                    TextInput(hex, { hex = it }, Modifier.width(110.dp), Colors.hex(preview))
                    TextButton({
                        val v = hex.trim().removePrefix("#").toLongOrNull(16)
                        if (v != null && hex.trim().removePrefix("#").length == 6) {
                            val rgb = listOf(((v shr 16) and 0xFF).toDouble(), ((v shr 8) and 0xFF).toDouble(), (v and 0xFF).toDouble())
                            values = if (space == ColorSpace.RGB) rgb else Colors.rgbToCmyk(rgb[0], rgb[1], rgb[2])
                        }
                    }) { Text("Apply") }
                }
            }
        },
        confirmButton = {
            TextButton({
                vm.dialog = null
                val id = existing?.id ?: vm.newSwatchId()
                vm.upsertSwatch((existing ?: Swatch(id, name)).copy(name = name, kind = SwatchKind.Color, space = space, values = values, spot = spot))
            }) { Text("OK") }
        },
        dismissButton = { TextButton({ vm.dialog = null }) { Text("Cancel") } },
    )
}

@Composable
private fun TextAttrsEditor(vm: EditorViewModel, attrs: TextAttrs, paragraph: Boolean, onChange: (TextAttrs) -> Unit) {
    val d = vm.doc ?: return
    val fonts = vm.engine.fonts
    val u = vm.view.unit
    LabeledRow {
        Text("Font", fontSize = 12.sp)
        Dropdown(attrs.fontFamily, listOf<String?>(null) + fonts.menuFamilies(listOfNotNull(attrs.fontFamily)), { it ?: "(inherit)" }, { onChange(attrs.copy(fontFamily = it)) }, width = 190.dp)
    }
    LabeledRow {
        Dropdown(attrs.fontStyle, listOf<String?>(null) + fonts.stylesFor(attrs.fontFamily), { it ?: "(inherit)" }, { onChange(attrs.copy(fontStyle = it)) }, width = 130.dp)
        PlainNumberField("", attrs.pointSize, "pt", { onChange(attrs.copy(pointSize = it.coerceIn(0.1, 1296.0))) }, width = 70.dp)
    }
    LabeledRow {
        PlainNumberField("Leading", attrs.leading?.takeIf { it >= 0 }, "pt", { onChange(attrs.copy(leading = it)) }, width = 64.dp)
        PlainNumberField("Tracking", attrs.tracking, "", { onChange(attrs.copy(tracking = it)) }, width = 56.dp)
    }
    LabeledRow {
        Text("Color", fontSize = 12.sp)
        Dropdown(attrs.fillColor, listOf<String?>(null) + d.swatches.map { it.id }, { id -> id?.let { d.swatch(it)?.name } ?: "(inherit)" }, { onChange(attrs.copy(fillColor = it)) }, width = 160.dp)
    }
    if (paragraph) {
        LabeledRow {
            Text("Align", fontSize = 12.sp)
            Dropdown(attrs.justification, listOf<Justification?>(null) + Justification.entries, { it?.name ?: "(inherit)" }, { onChange(attrs.copy(justification = it)) }, width = 160.dp)
        }
        LabeledRow {
            UnitField("Before", attrs.spaceBefore, u, { onChange(attrs.copy(spaceBefore = it)) }, width = 64.dp)
            UnitField("After", attrs.spaceAfter, u, { onChange(attrs.copy(spaceAfter = it)) }, width = 64.dp)
        }
        LabeledRow {
            UnitField("Left", attrs.leftIndent, u, { onChange(attrs.copy(leftIndent = it)) }, width = 64.dp)
            UnitField("1st", attrs.firstLineIndent, u, { onChange(attrs.copy(firstLineIndent = it)) }, width = 64.dp)
        }
    }
}

@Composable
private fun ParagraphStyleDialog(vm: EditorViewModel, existing: ParagraphStyle?) {
    val d = vm.doc ?: return
    // New styles start from the formatting at the cursor/selection.
    val initial = existing?.attrs ?: (vm.currentTextAttrs()?.let { a ->
        TextAttrs(fontFamily = a.fontFamily, fontStyle = a.fontStyle, pointSize = a.pointSize, leading = a.leading, tracking = a.tracking,
            fillColor = a.fillColor, justification = a.justification, spaceBefore = a.spaceBefore, spaceAfter = a.spaceAfter,
            leftIndent = a.leftIndent, firstLineIndent = a.firstLineIndent)
    } ?: TextAttrs.EMPTY)
    var name by remember { mutableStateOf(existing?.name ?: "Paragraph Style ${d.paragraphStyles.size}") }
    var attrs by remember { mutableStateOf(initial) }
    var basedOn by remember { mutableStateOf(existing?.basedOn ?: if (existing == null) Ids.PARA_BASIC else null) }
    AlertDialog(
        onDismissRequest = { vm.dialog = null },
        title = { Text(if (existing == null) "New Paragraph Style" else "Edit Paragraph Style") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LabeledRow { Text("Name", fontSize = 12.sp); TextInput(name, { name = it }, Modifier.width(200.dp)) }
                LabeledRow {
                    Text("Based on", fontSize = 12.sp)
                    Dropdown(basedOn, listOf<String?>(null) + d.paragraphStyles.filter { it.id != existing?.id }.map { it.id }, { id -> id?.let { d.paragraphStyle(it)?.name } ?: "[No paragraph style]" }, { basedOn = it }, width = 180.dp)
                }
                TextAttrsEditor(vm, attrs, paragraph = true) { attrs = it }
            }
        },
        confirmButton = {
            TextButton({
                vm.dialog = null
                val id = existing?.id ?: vm.newStyleId("ParagraphStyle", name)
                vm.upsertParagraphStyle((existing ?: ParagraphStyle(id, name)).copy(name = name, attrs = attrs.copy(extraAttrs = emptyMap(), extraProps = emptyList()), basedOn = basedOn))
                if (existing == null && (vm.textEdit != null || vm.selectedItems.any { it.isText })) vm.applyParagraphStyle(id)
            }) { Text("OK") }
        },
        dismissButton = { TextButton({ vm.dialog = null }) { Text("Cancel") } },
    )
}

@Composable
private fun CharacterStyleDialog(vm: EditorViewModel, existing: CharacterStyle?) {
    val d = vm.doc ?: return
    var name by remember { mutableStateOf(existing?.name ?: "Character Style ${d.characterStyles.size}") }
    var attrs by remember { mutableStateOf(existing?.attrs ?: TextAttrs.EMPTY) }
    AlertDialog(
        onDismissRequest = { vm.dialog = null },
        title = { Text(if (existing == null) "New Character Style" else "Edit Character Style") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LabeledRow { Text("Name", fontSize = 12.sp); TextInput(name, { name = it }, Modifier.width(200.dp)) }
                Text("Only the attributes you set are applied; others are inherited from the paragraph.", fontSize = 11.sp, color = LocalEditorColors.current.textDim)
                TextAttrsEditor(vm, attrs, paragraph = false) { attrs = it }
            }
        },
        confirmButton = {
            TextButton({
                vm.dialog = null
                val id = existing?.id ?: vm.newStyleId("CharacterStyle", name)
                vm.upsertCharacterStyle((existing ?: CharacterStyle(id, name)).copy(name = name, attrs = attrs))
                if (existing == null && vm.textEdit?.hasSelection == true) vm.applyCharacterStyle(id)
            }) { Text("OK") }
        },
        dismissButton = { TextButton({ vm.dialog = null }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayerDialog(vm: EditorViewModel, dlg: Dialog.RenameLayer) {
    val d = vm.doc ?: return
    var name by remember { mutableStateOf(dlg.layer.name) }
    var color by remember { mutableStateOf(dlg.layer.color) }
    var printable by remember { mutableStateOf(dlg.layer.printable) }
    AlertDialog(
        onDismissRequest = { vm.dialog = null },
        title = { Text("Layer Options") },
        text = {
            Column {
                TextInput(name, { name = it }, Modifier.fillMaxWidth())
                SectionHeader("Color")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (c in LayerColor.rotation) Box(Modifier.size(24.dp).clickable { color = c }) { Swatch(Color(c.argb.toInt()), 24.dp, selected = c == color) }
                }
                LabeledRow { Checkbox(printable, { printable = it }); Text("Print / export this layer", fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton({
                vm.dialog = null
                vm.updateLayers(d.layers.map { if (it.id == dlg.layer.id) it.copy(name = name, color = color, printable = printable) else it }, "Layer Options")
            }) { Text("OK") }
        },
        dismissButton = { TextButton({ vm.dialog = null }) { Text("Cancel") } },
    )
}

@Composable
private fun InddDialog(vm: EditorViewModel, file: String) {
    AlertDialog(
        onDismissRequest = { vm.dialog = null },
        title = { Text("$file is a native InDesign file") },
        text = {
            Text(
                "Native .indd files use Adobe's proprietary, undocumented binary format, which no app except InDesign can edit reliably. " +
                    "LayerTrace works with IDML, InDesign's open exchange format, which keeps pages, frames, text, styles, colors and images.\n\n" +
                    "To convert: open the file in InDesign (desktop, or a colleague's) and choose File › Save As… › InDesign Markup (IDML), " +
                    "or File › Export › InDesign Markup. Then open the .idml here. Files you save in LayerTrace are IDML and open directly in InDesign.",
            )
        },
        confirmButton = { TextButton({ vm.dialog = null }) { Text("OK") } },
        dismissButton = { TextButton({ vm.dialog = null; vm.fileRequest = FileRequest.OpenDocument }) { Text("Open an IDML…") } },
    )
}

@Composable
private fun ShortcutsDialog(vm: EditorViewModel) {
    val colors = LocalEditorColors.current
    AlertDialog(
        onDismissRequest = { vm.dialog = null },
        title = { Text("Keyboard Shortcuts") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                SectionHeader("Tools")
                for (t in Tool.entries) ShortcutRow(t.label, t.key)
                SectionHeader("Canvas")
                ShortcutRow("Pan", "Space+drag · middle-drag · wheel")
                ShortcutRow("Zoom at cursor", "Ctrl+wheel · pinch")
                ShortcutRow("Nudge", "Arrows (Shift ×10)")
                ShortcutRow("Constrain / proportional", "Shift while dragging")
                ShortcutRow("Duplicate while moving", "Alt+drag")
                ShortcutRow("Select inside group", "Ctrl+click · double-click")
                ShortcutRow("Edit text frame", "Double-click · Enter")
                ShortcutRow("Fill ↔ Stroke target", "X")
                for (m in Menus.all) {
                    val acts = m.entries.mapNotNull { it.act }.filter { it.shortcut != null }
                    if (acts.isEmpty()) continue
                    SectionHeader(m.title)
                    for (a in acts) ShortcutRow(a.label, a.shortcut!!.display)
                }
                val conflicts = ShortcutMap.conflicts()
                if (conflicts.isNotEmpty()) Text("Conflicts: $conflicts", color = colors.danger)
            }
        },
        confirmButton = { TextButton({ vm.dialog = null }) { Text("Close") } },
    )
}

@Composable
private fun ShortcutRow(label: String, keys: String) {
    val colors = LocalEditorColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(keys, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.accent,
            modifier = Modifier.background(colors.chromeLow, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

@Composable
private fun AboutDialog(vm: EditorViewModel) {
    AlertDialog(
        onDismissRequest = { vm.dialog = null },
        title = { Text("LayerTrace") },
        text = {
            Text(
                "A page-layout editor for Android tablets, foldables and phones.\n\n" +
                    "• Opens and saves InDesign Markup (IDML). Content LayerTrace doesn't edit yet (tables, effects, " +
                    "object styles, XML tags…) is preserved when you save.\n" +
                    "• Native .indd files must be converted to IDML in InDesign first.\n" +
                    "• PDF export is RGB vector PDF.\n\n" +
                    "Workspaces: Keyboard & Mouse (menus, shortcuts, hover, right-click) and Touch (touch & hold for actions). " +
                    "Switch in View › Switch Workspace or from the status bar.",
            )
        },
        confirmButton = { TextButton({ vm.dialog = null }) { Text("OK") } },
        dismissButton = { TextButton({ vm.dialog = null; vm.perform(Act.ShortcutsHelp) }) { Text("Shortcuts") } },
    )
}
