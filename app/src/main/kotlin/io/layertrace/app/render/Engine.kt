package io.layertrace.app.render

import android.content.Context

/** Rendering services shared by the canvas, thumbnails and exporters. */
class Engine(val fonts: FontRegistry) {
    val images = ImageCache()
    val text = TextLayoutEngine(fonts)
    val renderer = PageRenderer(fonts, images, text)

    constructor(context: Context) : this(FontRegistry(context))
}
