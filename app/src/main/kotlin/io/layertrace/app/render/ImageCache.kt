package io.layertrace.app.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.layertrace.core.model.PlacedImage

/** Decodes embedded images once, downsampled to a sensible working resolution. */
class ImageCache(maxBytes: Int = 96 * 1024 * 1024) {
    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<String>()

    private fun key(img: PlacedImage) = img.id + ":" + (img.data?.let { System.identityHashCode(it) } ?: 0)

    fun get(img: PlacedImage, maxDim: Int = 2048): Bitmap? {
        val data = img.data ?: return null
        val k = key(img)
        cache.get(k)?.let { return it }
        if (k in failed) return null
        val bmp = decode(data, maxDim)
        if (bmp == null) failed += k else cache.put(k, bmp)
        return bmp
    }

    companion object {
        fun decode(data: ByteArray, maxDim: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
            return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        /** Pixel size of an encoded image without decoding it. */
        fun size(data: ByteArray): Pair<Int, Int>? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, o)
            return if (o.outWidth > 0) o.outWidth to o.outHeight else null
        }
    }
}
