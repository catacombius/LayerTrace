package io.layertrace.app.editor

import io.layertrace.core.model.MeasureUnit

enum class Tool(val label: String, val key: String) {
    Selection("Selection", "V"),
    Direct("Direct Selection", "A"),
    Type("Type", "T"),
    Line("Line", "\\"),
    Frame("Rectangle Frame", "F"),
    Rectangle("Rectangle", "M"),
    Ellipse("Ellipse", "L"),
    Polygon("Polygon", "P"),
    Hand("Hand", "H"),
    Zoom("Zoom", "Z");

    val isDrawing get() = this == Line || this == Frame || this == Rectangle || this == Ellipse || this == Polygon
}

enum class WorkspaceMode(val label: String) { KeyboardMouse("Keyboard & Mouse"), Touch("Touch") }

/** Caret/selection inside a story. [anchor] == [caret] means no text selected. */
data class TextEdit(val storyId: String, val frameId: String, val caret: Int, val anchor: Int = caret) {
    val start get() = minOf(caret, anchor)
    val end get() = maxOf(caret, anchor)
    val hasSelection get() = caret != anchor
}

data class ViewOptions(
    val showGuides: Boolean = true,
    val showFrameEdges: Boolean = true,
    val showRulers: Boolean = true,
    val snap: Boolean = true,
    val preview: Boolean = false,
    val panelsVisible: Boolean = true,
    val unit: MeasureUnit = MeasureUnit.Millimeters,
)

enum class PanelTab(val label: String) {
    Properties("Properties"), Pages("Pages"), Layers("Layers"), Swatches("Swatches"), Styles("Styles")
}

/** Which colour target swatch clicks apply to. */
enum class ColorTarget { Fill, Stroke }
