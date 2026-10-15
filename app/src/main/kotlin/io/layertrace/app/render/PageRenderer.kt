package io.layertrace.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix as AMatrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import io.layertrace.core.edit.DocIndex
import io.layertrace.core.edit.Pagination
import io.layertrace.core.geom.Matrix
import io.layertrace.core.geom.Rect
import io.layertrace.core.geom.Shapes
import io.layertrace.core.geom.SubPath
import io.layertrace.core.model.Colors
import io.layertrace.core.model.Document
import io.layertrace.core.model.ItemKind
import io.layertrace.core.model.Page
import io.layertrace.core.model.PageItem
import io.layertrace.core.model.Spread
import io.layertrace.core.model.SwatchKind

fun Matrix.toAndroid(): AMatrix = AMatrix().apply {
    setValues(floatArrayOf(a.toFloat(), c.toFloat(), tx.toFloat(), b.toFloat(), d.toFloat(), ty.toFloat(), 0f, 0f, 1f))
}

fun Rect.toRectF() = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

fun pathOf(paths: List<SubPath>): Path {
    val p = Path()
    p.fillType = Path.FillType.EVEN_ODD
    for (sp in paths) {
        val pts = sp.points
        if (pts.isEmpty()) continue
        p.moveTo(pts[0].anchor.x.toFloat(), pts[0].anchor.y.toFloat())
        val n = if (sp.open) pts.size - 1 else pts.size
        for (i in 0 until n) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            if (a.right == a.anchor && b.left == b.anchor) p.lineTo(b.anchor.x.toFloat(), b.anchor.y.toFloat())
            else p.cubicTo(
                a.right.x.toFloat(), a.right.y.toFloat(), b.left.x.toFloat(), b.left.y.toFloat(),
                b.anchor.x.toFloat(), b.anchor.y.toFloat(),
            )
        }
        if (!sp.open) p.close()
    }
    return p
}

data class RenderOptions(
    /** Draw only printable layers/items (PDF export). */
    val printOnly: Boolean = false,
    /** Draw placeholders for empty graphic frames and missing images. */
    val placeholders: Boolean = true,
    val drawMasters: Boolean = true,
)

/** Renders document content onto an [android.graphics.Canvas] in spread coordinates (points). */
class PageRenderer(val fonts: FontRegistry, val images: ImageCache, val text: TextLayoutEngine) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66808080 }

    /** Page rectangles in spread coordinates, filled with paper colour. */
    fun drawPaper(canvas: Canvas, doc: Document, spread: Spread) {
        fill.shader = null
        fill.color = Colors.argb(doc, io.layertrace.core.model.Ids.COLOR_PAPER).let { if (it == 0) -1 else it }
        for (p in spread.pages) canvas.drawRect(p.boundsInSpread.toRectF(), fill)
    }

    /** Spread content: master items under each page, then the spread's own items by layer. */
    fun drawSpread(canvas: Canvas, doc: Document, spread: Spread, opts: RenderOptions = RenderOptions()) {
        val idx = DocIndex.of(doc)
        if (opts.drawMasters && !spread.isMaster) {
            for (page in spread.pages) drawMasterOnPage(canvas, doc, idx, page, opts)
        }
        drawItems(canvas, doc, idx, spread.items, opts)
    }

    private fun drawMasterOnPage(canvas: Canvas, doc: Document, idx: DocIndex, page: Page, opts: RenderOptions) {
        val (master, mp) = Pagination.masterPageFor(doc, page) ?: return
        val m = page.transform * mp.transform.inverse()
        canvas.save()
        canvas.clipRect(page.boundsInSpread.toRectF())
        canvas.concat(m.toAndroid())
        // Only items that sit on this master page.
        val items = master.items.filter { Pagination.owningPage(master, it.boundsIn(Matrix.IDENTITY).center)?.id == mp.id }
        drawItems(canvas, doc, idx, items, opts)
        canvas.restore()
    }

    fun drawItems(canvas: Canvas, doc: Document, idx: DocIndex, items: List<PageItem>, opts: RenderOptions) {
        val layers = doc.layers
        val known = layers.map { it.id }.toSet()
        for (layer in layers.asReversed()) {
            if (!layer.visible || (opts.printOnly && !layer.printable)) continue
            for (item in items) if (item.layerId == layer.id) drawItem(canvas, doc, idx, item, opts)
        }
        for (item in items) if (item.layerId !in known) drawItem(canvas, doc, idx, item, opts)
    }

    fun drawItem(canvas: Canvas, doc: Document, idx: DocIndex, item: PageItem, opts: RenderOptions) {
        if (!item.visible) return
        val count = canvas.save()
        canvas.concat(item.transform.toAndroid())
        val alpha = (item.opacity.coerceIn(0.0, 100.0) * 2.55).toInt()
        if (alpha < 255) {
            val b = item.innerBounds.inset(-item.strokeWeight - 2).toRectF()
            canvas.saveLayerAlpha(b, alpha)
        }
        when (item.kind) {
            ItemKind.Group -> item.children.forEach { drawItem(canvas, doc, idx, it, opts) }
            ItemKind.Unknown -> if (opts.placeholders) {
                canvas.drawRect(item.innerBounds.toRectF(), placeholder)
            }
            else -> drawShape(canvas, doc, idx, item, opts)
        }
        canvas.restoreToCount(count)
    }

    private fun shapePath(item: PageItem): Path {
        if (item.cornerRadius > 0 && item.kind == ItemKind.Rectangle || item.cornerRadius > 0 && item.kind == ItemKind.TextFrame) {
            if (Shapes.isAxisRect(item.paths)) {
                val r = item.cornerRadius.toFloat()
                return Path().apply { addRoundRect(item.innerBounds.toRectF(), r, r, Path.Direction.CW) }
            }
        }
        return pathOf(item.paths)
    }

    private fun applyFill(doc: Document, swatchId: String?, bounds: RectF, paint: Paint): Boolean {
        paint.shader = null
        val sw = doc.swatch(swatchId)
        if (sw != null && sw.kind == SwatchKind.Gradient && sw.stops.size >= 2) {
            val colors = sw.stops.map { Colors.argb(doc, it.swatchId) }.toIntArray()
            val pos = sw.stops.map { (it.location / 100.0).toFloat().coerceIn(0f, 1f) }.toFloatArray()
            paint.shader = if (sw.radial) RadialGradient(bounds.centerX(), bounds.centerY(), maxOf(bounds.width(), bounds.height()) / 2, colors, pos, Shader.TileMode.CLAMP)
            else LinearGradient(bounds.left, 0f, bounds.right, 0f, colors, pos, Shader.TileMode.CLAMP)
            paint.color = -1
            return true
        }
        val c = Colors.argb(doc, swatchId)
        if (c == 0) return false
        paint.color = c
        return true
    }

    private fun drawShape(canvas: Canvas, doc: Document, idx: DocIndex, item: PageItem, opts: RenderOptions) {
        val path = shapePath(item)
        val bounds = item.innerBounds.toRectF()
        val closed = item.paths.any { !it.open }
        if (closed && item.fill != null && applyFill(doc, item.fill, bounds, fill)) canvas.drawPath(path, fill)

        item.image?.let { img ->
            canvas.save()
            canvas.clipPath(path)
            val bmp = images.get(img)
            if (bmp != null) {
                canvas.concat(img.transform.toAndroid())
                canvas.drawBitmap(bmp, null, img.bounds.toRectF(), bmpPaint)
            } else if (opts.placeholders) {
                drawMissing(canvas, bounds)
            }
            canvas.restore()
        }

        if (item.kind == ItemKind.TextFrame) {
            val storyId = item.storyId
            if (storyId != null) {
                val chain = idx.chain(storyId)
                val layout = text.layout(doc, storyId, chain)
                layout?.frames?.get(item.id)?.let { ft -> drawFrameText(canvas, ft) }
            }
        } else if (item.image == null && item.kind != ItemKind.GraphicLine && item.fill == null && opts.placeholders &&
            item.passthrough?.get("ContentType") == "GraphicType"
        ) {
            drawMissing(canvas, bounds)
        }

        if (item.stroke != null && item.strokeWeight > 0 && applyFill(doc, item.stroke, bounds, stroke)) {
            stroke.strokeWidth = item.strokeWeight.toFloat()
            stroke.strokeJoin = Paint.Join.MITER
            canvas.drawPath(path, stroke)
        }
    }

    fun drawFrameText(canvas: Canvas, ft: FrameText) {
        for (s in ft.slices) {
            canvas.save()
            canvas.translate(s.x, s.dy)
            canvas.scale(1 / TEXT_SCALE, 1 / TEXT_SCALE)
            val top = s.layout.getLineTop(s.firstLine).toFloat()
            val bottom = s.layout.getLineBottom(s.lastLine).toFloat()
            canvas.clipRect(-4000f, top, s.layout.width + 4000f, bottom)
            s.layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawMissing(canvas: Canvas, b: RectF) {
        placeholder.strokeWidth = 0.5f
        canvas.drawLine(b.left, b.top, b.right, b.bottom, placeholder)
        canvas.drawLine(b.right, b.top, b.left, b.bottom, placeholder)
    }

    /**
     * Render one page (plus [bleed] points around it) into [canvas] whose origin is the
     * top-left of the bleed box. Used for PDF and PNG export and thumbnails.
     */
    fun drawPage(canvas: Canvas, doc: Document, spread: Spread, page: Page, bleed: Double, scale: Float = 1f, opts: RenderOptions = RenderOptions(printOnly = true, placeholders = false)) {
        val pb = page.boundsInSpread
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate((-pb.left + bleed).toFloat(), (-pb.top + bleed).toFloat())
        canvas.clipRect(pb.inset(-bleed).toRectF())
        drawPaper(canvas, doc, spread)
        drawSpread(canvas, doc, spread, opts)
        canvas.restore()
    }

    fun renderPageBitmap(doc: Document, spread: Spread, page: Page, widthPx: Int): Bitmap {
        val pb = page.boundsInSpread
        val scale = widthPx / pb.width.toFloat()
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), (pb.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        drawPage(Canvas(bmp), doc, spread, page, 0.0, scale)
        return bmp
    }
}
