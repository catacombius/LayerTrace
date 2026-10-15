package io.layertrace.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import io.layertrace.app.editor.Act
import io.layertrace.app.editor.Tool

object AppIcons {
    fun tool(t: Tool): ImageVector = when (t) {
        Tool.Selection -> Icons.Default.NearMe
        Tool.Direct -> Icons.Outlined.NearMe
        Tool.Type -> Icons.Default.TextFields
        Tool.Line -> Icons.Default.HorizontalRule
        Tool.Frame -> Icons.Outlined.Image
        Tool.Rectangle -> Icons.Outlined.CropSquare
        Tool.Ellipse -> Icons.Outlined.Circle
        Tool.Polygon -> Icons.Outlined.Hexagon
        Tool.Hand -> Icons.Outlined.PanTool
        Tool.Zoom -> Icons.Default.ZoomIn
    }

    fun act(a: Act): ImageVector? = when (a) {
        Act.Undo -> Icons.AutoMirrored.Filled.Undo
        Act.Redo -> Icons.AutoMirrored.Filled.Redo
        Act.Save -> Icons.Default.Save
        Act.Open -> Icons.Default.FolderOpen
        Act.New -> Icons.Default.NoteAdd
        Act.ExportPdf -> Icons.Default.PictureAsPdf
        Act.Cut -> Icons.Default.ContentCut
        Act.Copy -> Icons.Default.ContentCopy
        Act.Paste -> Icons.Default.ContentPaste
        Act.Delete -> Icons.Default.Delete
        Act.Duplicate -> Icons.Default.FileCopy
        Act.Group -> Icons.Default.Workspaces
        Act.Ungroup -> Icons.Outlined.Workspaces
        Act.BringToFront -> Icons.Default.FlipToFront
        Act.SendToBack -> Icons.Default.FlipToBack
        Act.BringForward -> Icons.Default.ArrowUpward
        Act.SendBackward -> Icons.Default.ArrowDownward
        Act.Lock -> Icons.Default.Lock
        Act.Place -> Icons.Default.AddPhotoAlternate
        Act.AlignLeft -> Icons.Default.AlignHorizontalLeft
        Act.AlignCenterH -> Icons.Default.AlignHorizontalCenter
        Act.AlignRight -> Icons.Default.AlignHorizontalRight
        Act.AlignTop -> Icons.Default.AlignVerticalTop
        Act.AlignCenterV -> Icons.Default.AlignVerticalCenter
        Act.AlignBottom -> Icons.Default.AlignVerticalBottom
        Act.DistributeH -> Icons.Default.HorizontalDistribute
        Act.DistributeV -> Icons.Default.VerticalDistribute
        Act.FlipH -> Icons.Default.Flip
        Act.Rotate90CW -> Icons.Default.RotateRight
        Act.Rotate90CCW -> Icons.Default.RotateLeft
        Act.ZoomIn -> Icons.Default.ZoomIn
        Act.ZoomOut -> Icons.Default.ZoomOut
        Act.FitPage -> Icons.Default.FitScreen
        Act.AlignTextLeft -> Icons.AutoMirrored.Filled.FormatAlignLeft
        Act.AlignTextCenter -> Icons.Default.FormatAlignCenter
        Act.AlignTextRight -> Icons.AutoMirrored.Filled.FormatAlignRight
        Act.AlignTextJustify -> Icons.Default.FormatAlignJustify
        Act.Bold -> Icons.Default.FormatBold
        Act.Italic -> Icons.Default.FormatItalic
        Act.Underline -> Icons.Default.FormatUnderlined
        Act.ThreadFrames -> Icons.Default.Link
        Act.UnthreadFrame -> Icons.Default.LinkOff
        Act.AddPage -> Icons.Default.Add
        Act.NextPage -> Icons.AutoMirrored.Filled.ArrowForward
        Act.ToggleWorkspace -> Icons.Default.Keyboard
        Act.TogglePreview -> Icons.Default.Visibility
        Act.ShortcutsHelp -> Icons.Default.Keyboard
        else -> null
    }
}
