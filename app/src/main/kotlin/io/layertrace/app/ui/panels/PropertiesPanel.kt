package io.layertrace.app.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.layertrace.app.editor.Act
import io.layertrace.app.editor.Dialog
import io.layertrace.app.editor.EditorViewModel
import io.layertrace.app.ui.AppIcons
import io.layertrace.app.ui.components.Dropdown
import io.layertrace.app.ui.components.LabeledRow
import io.layertrace.app.ui.components.PlainNumberField
import io.layertrace.app.ui.components.SectionHeader
import io.layertrace.app.ui.components.SmallButton
import io.layertrace.app.ui.components.Swatch
import io.layertrace.app.ui.components.ToolIcon
import io.layertrace.app.ui.components.UnitField
import io.layertrace.app.ui.theme.LocalEditorColors
import io.layertrace.core.edit.DocIndex
import io.layertrace.core.edit.Pagination
import io.layertrace.core.geom.Point
import io.layertrace.core.geom.Rect
import io.layertrace.core.geom.RefPoint
import io.layertrace.core.model.Capitalization
import io.layertrace.core.model.Colors
import io.layertrace.core.model.Ids
import io.layertrace.core.model.Insets
import io.layertrace.core.model.ItemKind
import io.layertrace.core.model.TextAttrs

@Composable
fun PropertiesPanel(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val d = vm.shown ?: return
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp)) {
        when {
            vm.textEdit != null -> TextSection(vm)
            vm.selection.isNotEmpty() -> {
                TransformSection(vm)
                AppearanceSection(vm)
                if (vm.selectedItems.any { it.isText }) {
                    FrameSection(vm)
                    TextSection(vm)
                }
                ArrangeSection(vm)
            }
            else -> DocumentSection(vm, d)
        }
    }
}

@Composable
private fun DocumentSection(vm: EditorViewModel, d: io.layertrace.core.model.Document) {
    val colors = LocalEditorColors.current
    val u = vm.view.unit
    SectionHeader("Document")
    Text(d.name, color = colors.text, fontSize = 14.sp)
    Text("${u.format(d.setup.pageWidth)} × ${u.format(d.setup.pageHeight)} · ${d.pageCount} page(s)${if (d.setup.facingPages) " · facing" else ""}", color = colors.textDim, fontSize = 12.sp)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallButton("Document Setup…") { vm.dialog = Dialog.DocumentSetup }
        SmallButton("Add Page") { vm.perform(Act.AddPage) }
    }
    SectionHeader("Tips")
    Text(
        "Select a tool on the left, drag on a page to create frames. Double-click a text frame to type. " +
            "Right-click (or touch & hold) for context actions. Ctrl+/ lists every shortcut.",
        color = colors.textDim, fontSize = 12.sp,
    )
}

@Composable
private fun RefPointPicker(value: RefPoint, onChange: (RefPoint) -> Unit) {
    val colors = LocalEditorColors.current
    Column(Modifier.border(1.dp, colors.divider, RoundedCornerShape(3.dp)).padding(3.dp)) {
        for (row in 0..2) Row {
            for (col in 0..2) {
                val rp = RefPoint.entries[row * 3 + col]
                Box(
                    Modifier.size(14.dp).clickable { onChange(rp) }.padding(3.dp)
                        .background(if (rp == value) colors.accent else colors.textDim.copy(alpha = 0.4f), RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransformSection(vm: EditorViewModel) {
    val d = vm.shown ?: return
    val u = vm.view.unit
    var ref by remember { mutableStateOf(RefPoint.TOP_LEFT) }
    val idx = DocIndex.of(d)
    val locs = vm.selection.mapNotNull { idx[it] }
    if (locs.isEmpty()) return
    val single = locs.singleOrNull()?.takeIf { it.world.isRigid && !it.item.isGroup }
    val world = Rect.unionAll(locs.map { it.worldBounds }) ?: return
    // Page origin for X/Y (rulers measure from the page's top-left).
    val page = Pagination.owningPage(locs[0].spread, world.center)
    val origin = page?.boundsInSpread?.let { Point(it.left, it.top) } ?: Point(0.0, 0.0)
    val w = single?.item?.innerBounds?.width ?: world.width
    val h = single?.item?.innerBounds?.height ?: world.height
    val refPt = world.refPoint(ref)
    val angle = locs.singleOrNull()?.world?.rotationDegrees ?: 0.0

    SectionHeader("Transform")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RefPointPicker(ref) { ref = it }
        Column {
            LabeledRow {
                UnitField("X", refPt.x - origin.x, u, { x -> vm.setSelectionBounds(world.offset(x + origin.x - refPt.x, 0.0)) }, width = 76.dp)
                UnitField("Y", refPt.y - origin.y, u, { y -> vm.setSelectionBounds(world.offset(0.0, y + origin.y - refPt.y)) }, width = 76.dp)
            }
            LabeledRow {
                UnitField("W", w, u, { nw -> vm.setSelectionBounds(resizeFromRef(world, ref, nw * world.width / w, world.height)) }, width = 76.dp, min = 0.1)
                UnitField("H", h, u, { nh -> vm.setSelectionBounds(resizeFromRef(world, ref, world.width, nh * world.height / h)) }, width = 76.dp, min = 0.1)
            }
        }
    }
    LabeledRow {
        PlainNumberField("∠", -angle, "°", { a ->
            if (locs.size == 1) vm.doc?.let { vm.commit(io.layertrace.core.edit.ItemOps.setRotation(it, locs[0].item.id, -a, refPt), "Rotate") }
        }, step = 1.0, width = 70.dp)
        ToolIcon(AppIcons.act(Act.Rotate90CCW)!!, "Rotate 90° CCW", size = 30.dp) { vm.perform(Act.Rotate90CCW) }
        ToolIcon(AppIcons.act(Act.Rotate90CW)!!, "Rotate 90° CW", size = 30.dp) { vm.perform(Act.Rotate90CW) }
        ToolIcon(AppIcons.act(Act.FlipH)!!, "Flip horizontal", size = 30.dp) { vm.perform(Act.FlipH) }
    }
}

private fun resizeFromRef(r: Rect, ref: RefPoint, nw: Double, nh: Double): Rect {
    val ax = r.left + r.width * ref.fx; val ay = r.top + r.height * ref.fy
    val l = ax - nw * ref.fx; val t = ay - nh * ref.fy
    return Rect(l, t, l + nw, t + nh)
}

@Composable
private fun AppearanceSection(vm: EditorViewModel) {
    val d = vm.shown ?: return
    val items = vm.selectedItems
    val first = items.firstOrNull() ?: return
    val colors = LocalEditorColors.current
    SectionHeader("Appearance")
    val swatches = d.swatches
    LabeledRow {
        Text("Fill", color = colors.textDim, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
        Dropdown(d.swatch(first.fill) ?: d.swatch(Ids.SWATCH_NONE), swatches, { it?.name ?: "None" }, { s -> vm.updateSelected("Fill") { it.copy(fill = s?.id?.takeUnless { id -> id == Ids.SWATCH_NONE }) } }, width = 150.dp)
        Swatch(Color(Colors.argb(d, first.fill)), none = first.fill == null)
    }
    LabeledRow {
        Text("Stroke", color = colors.textDim, fontSize = 11.sp)
        Dropdown(d.swatch(first.stroke) ?: d.swatch(Ids.SWATCH_NONE), swatches, { it?.name ?: "None" }, { s ->
            vm.updateSelected("Stroke") { it.copy(stroke = s?.id?.takeUnless { id -> id == Ids.SWATCH_NONE }, strokeWeight = if (s != null && s.id != Ids.SWATCH_NONE && it.strokeWeight <= 0) 1.0 else it.strokeWeight) }
        }, width = 136.dp)
        Swatch(Color(Colors.argb(d, first.stroke)), none = first.stroke == null)
    }
    LabeledRow {
        PlainNumberField("Weight", first.strokeWeight, "pt", { v -> vm.updateSelected("Stroke Weight") { it.copy(strokeWeight = v.coerceAtLeast(0.0)) } }, step = 0.5, width = 64.dp)
        PlainNumberField("Opacity", first.opacity, "%", { v -> vm.updateSelected("Opacity") { it.copy(opacity = v) } }, step = 5.0, width = 60.dp, range = 0.0..100.0)
    }
    if (items.any { it.kind == ItemKind.Rectangle || it.kind == ItemKind.TextFrame }) {
        LabeledRow {
            UnitField("Corner", first.cornerRadius, vm.view.unit, { v -> vm.updateSelected("Corner Radius") { it.copy(cornerRadius = v.coerceAtLeast(0.0)) } }, width = 76.dp, min = 0.0)
        }
    }
    if (d.layers.size > 1) LabeledRow {
        Text("Layer", color = colors.textDim, fontSize = 11.sp)
        Dropdown(d.layer(first.layerId), d.layers, { it?.name ?: "?" }, { l -> l?.let { vm.moveSelectionToLayer(it.id) } }, width = 150.dp)
    }
}

@Composable
private fun FrameSection(vm: EditorViewModel) {
    val f = vm.selectedItems.firstOrNull { it.isText } ?: return
    val u = vm.view.unit
    SectionHeader("Text Frame")
    LabeledRow {
        PlainNumberField("Columns", f.columns.toDouble(), "", { v -> vm.updateSelected("Columns") { if (it.isText) it.copy(columns = v.toInt().coerceIn(1, 40)) else it } }, width = 48.dp, range = 1.0..40.0)
        UnitField("Gutter", f.columnGutter, u, { v -> vm.updateSelected("Gutter") { if (it.isText) it.copy(columnGutter = v.coerceAtLeast(0.0)) else it } }, width = 70.dp, min = 0.0)
    }
    LabeledRow {
        UnitField("Inset", f.inset.top, u, { v -> vm.updateSelected("Inset") { if (it.isText) it.copy(inset = Insets.all(v.coerceAtLeast(0.0))) else it } }, width = 76.dp, min = 0.0)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallButton("Thread…") { vm.threadRequest = f.id }
        if (f.nextFrameId != null) SmallButton("Break thread") { vm.perform(Act.UnthreadFrame) }
        SmallButton("Placeholder") { vm.perform(Act.FillPlaceholder) }
    }
}

@Composable
fun TextSection(vm: EditorViewModel) {
    val d = vm.shown ?: return
    val a = vm.currentTextAttrs() ?: TextAttrs.DEFAULTS
    val colors = LocalEditorColors.current
    val fonts = vm.engine.fonts
    val docFamilies = (d.paragraphStyles.mapNotNull { it.attrs.fontFamily } + d.characterStyles.mapNotNull { it.attrs.fontFamily }).toSet()

    SectionHeader("Character")
    LabeledRow {
        Dropdown(a.fontFamily ?: "Minion Pro", fonts.menuFamilies(docFamilies + listOfNotNull(a.fontFamily)), { f ->
            if (fonts.isAvailable(f)) f else "$f (substituted)"
        }, { f -> vm.applyTextAttrs(TextAttrs(fontFamily = f), "Font") }, width = 210.dp)
    }
    LabeledRow {
        Dropdown(a.fontStyle ?: "Regular", (fonts.stylesFor(a.fontFamily) + listOfNotNull(a.fontStyle)).distinct(), { it }, { s -> vm.applyTextAttrs(TextAttrs(fontStyle = s), "Font Style") }, width = 120.dp)
        PlainNumberField("", a.pointSize, "pt", { v -> vm.applyTextAttrs(TextAttrs(pointSize = v.coerceIn(0.1, 1296.0)), "Point Size") }, step = 1.0, width = 70.dp)
    }
    LabeledRow {
        val lead = a.leading
        PlainNumberField("Leading", if (lead == null || lead < 0) null else lead, "pt", { v -> vm.applyTextAttrs(TextAttrs(leading = v.coerceAtLeast(0.0)), "Leading") }, width = 64.dp)
        SmallButton("Auto") { vm.applyTextAttrs(TextAttrs(leading = TextAttrs.AUTO_LEADING), "Leading") }
    }
    LabeledRow {
        PlainNumberField("Tracking", a.tracking, "", { v -> vm.applyTextAttrs(TextAttrs(tracking = v), "Tracking") }, step = 10.0, width = 60.dp)
        PlainNumberField("Shift", a.baselineShift, "pt", { v -> vm.applyTextAttrs(TextAttrs(baselineShift = v), "Baseline Shift") }, width = 56.dp)
    }
    LabeledRow {
        ToolIcon(AppIcons.act(Act.Bold)!!, "Bold (Ctrl+Shift+B)", size = 30.dp) { vm.perform(Act.Bold) }
        ToolIcon(AppIcons.act(Act.Italic)!!, "Italic (Ctrl+Shift+I)", size = 30.dp) { vm.perform(Act.Italic) }
        ToolIcon(AppIcons.act(Act.Underline)!!, "Underline", selected = a.underline == true, size = 30.dp) { vm.perform(Act.Underline) }
        Text("S", color = if (a.strikeThrough == true) colors.accent else colors.text, fontSize = 15.sp,
            modifier = Modifier.clickable { vm.applyTextAttrs(TextAttrs(strikeThrough = a.strikeThrough != true), "Strikethrough") }.padding(6.dp))
        Text("TT", color = if (a.capitalization == Capitalization.AllCaps) colors.accent else colors.text, fontSize = 13.sp,
            modifier = Modifier.clickable { vm.applyTextAttrs(TextAttrs(capitalization = if (a.capitalization == Capitalization.AllCaps) Capitalization.Normal else Capitalization.AllCaps), "All Caps") }.padding(6.dp))
        Text("Tt", color = if (a.capitalization == Capitalization.SmallCaps) colors.accent else colors.text, fontSize = 13.sp,
            modifier = Modifier.clickable { vm.applyTextAttrs(TextAttrs(capitalization = if (a.capitalization == Capitalization.SmallCaps) Capitalization.Normal else Capitalization.SmallCaps), "Small Caps") }.padding(6.dp))
    }
    LabeledRow {
        Text("Color", color = colors.textDim, fontSize = 11.sp)
        Dropdown(d.swatch(a.fillColor), d.swatches, { it?.name ?: "Black" }, { s -> s?.let { vm.applyTextAttrs(TextAttrs(fillColor = it.id), "Text Color") } }, width = 150.dp)
        Swatch(Color(Colors.argb(d, a.fillColor)))
    }

    SectionHeader("Paragraph")
    LabeledRow {
        for (act in listOf(Act.AlignTextLeft, Act.AlignTextCenter, Act.AlignTextRight, Act.AlignTextJustify)) {
            val sel = when (act) {
                Act.AlignTextLeft -> a.justification == io.layertrace.core.model.Justification.LeftAlign
                Act.AlignTextCenter -> a.justification == io.layertrace.core.model.Justification.CenterAlign
                Act.AlignTextRight -> a.justification == io.layertrace.core.model.Justification.RightAlign
                else -> a.justification?.isJustified == true
            }
            ToolIcon(AppIcons.act(act)!!, act.label, selected = sel, size = 30.dp) { vm.perform(act) }
        }
    }
    val u = vm.view.unit
    LabeledRow {
        UnitField("⇥", a.leftIndent, u, { v -> vm.applyParagraphAttrs(TextAttrs(leftIndent = v), "Indent") }, width = 64.dp)
        UnitField("⇤", a.rightIndent, u, { v -> vm.applyParagraphAttrs(TextAttrs(rightIndent = v), "Indent") }, width = 64.dp)
    }
    LabeledRow {
        UnitField("1st", a.firstLineIndent, u, { v -> vm.applyParagraphAttrs(TextAttrs(firstLineIndent = v), "First Line Indent") }, width = 64.dp)
    }
    LabeledRow {
        UnitField("Before", a.spaceBefore, u, { v -> vm.applyParagraphAttrs(TextAttrs(spaceBefore = v.coerceAtLeast(0.0)), "Space Before") }, width = 60.dp, min = 0.0)
        UnitField("After", a.spaceAfter, u, { v -> vm.applyParagraphAttrs(TextAttrs(spaceAfter = v.coerceAtLeast(0.0)), "Space After") }, width = 60.dp, min = 0.0)
    }

    SectionHeader("Styles")
    LabeledRow {
        Text("¶", color = colors.textDim, fontSize = 13.sp)
        val cur = d.paragraphStyle(vm.currentParagraphStyleId())
        Dropdown(cur, d.paragraphStyles, { it?.name ?: "—" }, { s -> s?.let { vm.applyParagraphStyle(it.id) } }, width = 200.dp)
    }
    if (vm.textEdit != null) LabeledRow {
        Text("A", color = colors.textDim, fontSize = 13.sp)
        val cur = d.characterStyle(vm.currentCharacterStyleId())
        Dropdown(cur, d.characterStyles, { it?.name ?: "—" }, { s -> s?.let { vm.applyCharacterStyle(it.id) } }, width = 200.dp)
    }
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallButton("Clear overrides") { vm.perform(Act.ClearOverrides) }
        SmallButton("New ¶ style") { vm.perform(Act.NewParagraphStyle) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArrangeSection(vm: EditorViewModel) {
    SectionHeader("Arrange & Align")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (a in listOf(Act.BringToFront, Act.BringForward, Act.SendBackward, Act.SendToBack, Act.Group, Act.Ungroup,
            Act.AlignLeft, Act.AlignCenterH, Act.AlignRight, Act.AlignTop, Act.AlignCenterV, Act.AlignBottom, Act.DistributeH, Act.DistributeV)) {
            ToolIcon(AppIcons.act(a)!!, a.label + (a.shortcut?.let { " (${it.display})" } ?: ""), enabled = vm.isEnabled(a), size = 32.dp) { vm.perform(a) }
        }
    }
    if (vm.selectedItems.any { it.image != null }) {
        SectionHeader("Image")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("Fit") { vm.perform(Act.FitContentProportionally) }
            SmallButton("Fill") { vm.perform(Act.FillFrameProportionally) }
            SmallButton("Center") { vm.perform(Act.CenterContent) }
        }
    }
    Box(Modifier.fillMaxWidth().padding(8.dp))
}
