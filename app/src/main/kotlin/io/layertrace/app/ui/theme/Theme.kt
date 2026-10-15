package io.layertrace.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Editor chrome colours (dark, like most layout apps, so the page stands out). */
@Immutable
data class EditorColors(
    val chrome: Color = Color(0xFF2B2D31),
    val chromeHigh: Color = Color(0xFF34373C),
    val chromeLow: Color = Color(0xFF232428),
    val pasteboard: Color = Color(0xFF1B1C1F),
    val divider: Color = Color(0xFF45484E),
    val text: Color = Color(0xFFE3E5E8),
    val textDim: Color = Color(0xFFA0A4AB),
    val accent: Color = Color(0xFF5B8CFF),
    val accentDim: Color = Color(0xFF3A5BA8),
    val danger: Color = Color(0xFFFF5C5C),
    val selection: Color = Color(0xFF4F99FF),
    val snap: Color = Color(0xFFFF33CC),
    val margin: Color = Color(0xFFD65AD6),
    val column: Color = Color(0xFF8A6DF0),
    val guide: Color = Color(0xFF38D1E0),
    val frameEdge: Color = Color(0xFF9AA3AF),
    val overset: Color = Color(0xFFFF3030),
)

val LocalEditorColors = staticCompositionLocalOf { EditorColors() }

private val scheme = darkColorScheme(
    primary = Color(0xFF5B8CFF),
    onPrimary = Color.White,
    secondary = Color(0xFF8AA4D6),
    background = Color(0xFF2B2D31),
    surface = Color(0xFF2B2D31),
    surfaceVariant = Color(0xFF34373C),
    surfaceContainer = Color(0xFF303236),
    surfaceContainerHigh = Color(0xFF383A3F),
    surfaceContainerHighest = Color(0xFF404247),
    surfaceContainerLow = Color(0xFF26282B),
    onSurface = Color(0xFFE3E5E8),
    onSurfaceVariant = Color(0xFFA0A4AB),
    outline = Color(0xFF5A5E66),
    outlineVariant = Color(0xFF45484E),
    error = Color(0xFFFF5C5C),
)

private val type = Typography(
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelMedium = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp),
    titleSmall = TextStyle(fontSize = 13.sp),
)

@Composable
fun LayerTraceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = type,
        shapes = Shapes(small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(6.dp), large = RoundedCornerShape(10.dp)),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalEditorColors provides EditorColors(), content = content)
    }
}
