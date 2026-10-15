package io.layertrace.app.workspace

import android.graphics.Rect
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo

enum class SizeClass { Compact, Medium, Expanded }

fun sizeClassFor(widthDp: Float, heightDp: Float): SizeClass = when {
    widthDp >= 840f -> SizeClass.Expanded
    widthDp >= 600f -> if (heightDp < 480f) SizeClass.Compact else SizeClass.Medium
    else -> SizeClass.Compact
}

enum class Posture { Flat, Tabletop, Book }

/** Fold state relevant to layout. [hinge] is in window pixels. */
data class FoldState(val posture: Posture = Posture.Flat, val hinge: Rect? = null)

fun foldStateOf(info: WindowLayoutInfo?): FoldState {
    val f = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull() ?: return FoldState()
    val halfOpen = f.state == FoldingFeature.State.HALF_OPENED
    // Only split the UI when the hinge actually divides the screen.
    if (!halfOpen && !f.isSeparating) return FoldState()
    val posture = if (f.orientation == FoldingFeature.Orientation.HORIZONTAL) Posture.Tabletop else Posture.Book
    return FoldState(posture, Rect(f.bounds))
}
