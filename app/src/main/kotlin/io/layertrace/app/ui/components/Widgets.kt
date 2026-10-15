package io.layertrace.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.layertrace.app.ui.theme.LocalEditorColors
import io.layertrace.core.model.MeasureUnit

/**
 * Compact numeric field. Accepts expressions and units ("12mm", "3p6", "10+2"), commits on Enter
 * or focus loss, and supports ↑/↓ (Shift ×10) for keyboard users.
 */
@Composable
fun UnitField(
    label: String,
    valuePt: Double?,
    unit: MeasureUnit,
    onCommit: (Double) -> Unit,
    modifier: Modifier = Modifier,
    step: Double = 1.0,
    width: Dp = 84.dp,
    min: Double? = null,
) {
    val display = valuePt?.let { unit.format(it, withUnit = false) } ?: ""
    NumberTextField(label, display, unit.label, modifier, width, onCommit = { text ->
        unit.parse(text)?.let { v -> onCommit(if (min != null) v.coerceAtLeast(min) else v) }
    }, onStep = { dir -> valuePt?.let { onCommit((it + unit.toPoints(step) * dir).let { v -> if (min != null) v.coerceAtLeast(min) else v }) } })
}

/** Plain number field (points, percent, degrees...). */
@Composable
fun PlainNumberField(
    label: String,
    value: Double?,
    suffix: String,
    onCommit: (Double) -> Unit,
    modifier: Modifier = Modifier,
    step: Double = 1.0,
    width: Dp = 72.dp,
    range: ClosedFloatingPointRange<Double>? = null,
) {
    val display = value?.let { io.layertrace.core.geom.fmt(Math.round(it * 100) / 100.0) } ?: ""
    fun clamp(v: Double) = if (range != null) v.coerceIn(range) else v
    NumberTextField(label, display, suffix, modifier, width, onCommit = { text ->
        text.trim().removeSuffix(suffix).trim().replace(',', '.').toDoubleOrNull()?.let { onCommit(clamp(it)) }
    }, onStep = { dir -> value?.let { onCommit(clamp(it + step * dir)) } })
}

@Composable
private fun NumberTextField(
    label: String,
    display: String,
    suffix: String,
    modifier: Modifier,
    width: Dp,
    onCommit: (String) -> Unit,
    onStep: (Int) -> Unit,
) {
    val colors = LocalEditorColors.current
    var text by remember(display) { mutableStateOf(display) }
    var focused by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (label.isNotEmpty()) Text(label, color = colors.textDim, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(color = colors.text, fontSize = 12.sp),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit(text) }),
            modifier = Modifier
                .width(width)
                .height(26.dp)
                .background(colors.chromeLow, RoundedCornerShape(4.dp))
                .border(1.dp, if (focused) colors.accent else colors.divider, RoundedCornerShape(4.dp))
                .onFocusChanged { f ->
                    if (focused && !f.isFocused && text != display) onCommit(text)
                    focused = f.isFocused
                }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Enter, Key.NumPadEnter -> { onCommit(text); true }
                        Key.DirectionUp -> { onStep(if (e.isShiftPressed) 10 else 1); true }
                        Key.DirectionDown -> { onStep(if (e.isShiftPressed) -10 else -1); true }
                        else -> false
                    }
                }
                .padding(horizontal = 6.dp, vertical = 5.dp),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { inner() }
                    if (suffix.isNotEmpty()) Text(suffix, color = colors.textDim, fontSize = 10.sp)
                }
            },
        )
    }
}

@Composable
fun TextInput(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "") {
    val colors = LocalEditorColors.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = colors.text, fontSize = 13.sp),
        cursorBrush = SolidColor(colors.accent),
        modifier = modifier
            .height(32.dp)
            .background(colors.chromeLow, RoundedCornerShape(4.dp))
            .border(1.dp, colors.divider, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = colors.textDim, fontSize = 13.sp)
                inner()
            }
        },
    )
}

/** Icon button with tooltip (shows shortcut on hover with a mouse, long-press on touch). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ToolIcon(
    icon: ImageVector,
    tip: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 36.dp,
    onClick: () -> Unit,
) {
    val colors = LocalEditorColors.current
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tip) } },
        state = rememberTooltipState(),
    ) {
        Box(
            Modifier
                .size(size)
                .padding(2.dp)
                .background(if (selected) colors.accentDim else Color.Transparent, RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = tip, tint = if (!enabled) colors.textDim.copy(alpha = 0.4f) else if (selected) Color.White else colors.text, modifier = Modifier.size(size * 0.55f))
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = LocalEditorColors.current.textDim, fontSize = 10.sp,
        modifier = modifier.padding(top = 10.dp, bottom = 4.dp), letterSpacing = 0.8.sp,
    )
}

/** Small dropdown selector. */
@Composable
fun <T> Dropdown(
    value: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 140.dp,
) {
    val colors = LocalEditorColors.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .width(width)
                .height(28.dp)
                .background(colors.chromeLow, RoundedCornerShape(4.dp))
                .border(1.dp, colors.divider, RoundedCornerShape(4.dp))
                .clickable { open = true }
                .padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label(value), color = colors.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null, tint = colors.textDim)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(label(o), fontSize = 13.sp) }, onClick = { open = false; onSelect(o) })
            }
        }
    }
}

@Composable
fun Swatch(color: Color, size: Dp = 18.dp, none: Boolean = false, selected: Boolean = false, modifier: Modifier = Modifier) {
    val colors = LocalEditorColors.current
    Box(
        modifier
            .size(size)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.divider, RoundedCornerShape(3.dp))
            .padding(if (selected) 2.dp else 1.dp)
            .background(if (none) Color.White else color, RoundedCornerShape(2.dp)),
    ) {
        if (none) androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            drawLine(Color.Red, androidx.compose.ui.geometry.Offset(0f, size.toPx()), androidx.compose.ui.geometry.Offset(size.toPx(), 0f), strokeWidth = 2f)
        }
    }
}

@Composable
fun LabeledRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
fun SmallButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalEditorColors.current
    Box(
        modifier
            .height(28.dp)
            .background(colors.chromeHigh, RoundedCornerShape(4.dp))
            .border(1.dp, colors.divider, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) colors.text else colors.textDim, fontSize = 12.sp) }
}

@Composable
fun AutoFocus(requester: androidx.compose.ui.focus.FocusRequester) {
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
}

@Suppress("unused")
val MaterialThemeRef = MaterialTheme
