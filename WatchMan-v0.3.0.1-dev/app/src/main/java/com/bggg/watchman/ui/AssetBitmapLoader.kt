package com.bggg.watchman.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/** Downsample large approved originals to the actual HUD/marker size before decoding. */
object AssetBitmapLoader {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    @Synchronized
    fun load(context: Context, asset: String, targetPx: Int): Bitmap? {
        val key = "$asset@$targetPx"
        cache.get(key)?.let { return it }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(asset).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val wanted = targetPx.coerceAtLeast(1) * 2
            while (bounds.outWidth / (sample * 2) >= wanted && bounds.outHeight / (sample * 2) >= wanted) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.assets.open(asset).use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            val scaled = if (decoded.width == targetPx && decoded.height == targetPx) decoded
            else Bitmap.createScaledBitmap(decoded, targetPx, targetPx, true).also { if (it !== decoded) decoded.recycle() }
            cache.put(key, scaled)
            scaled
        } catch (_: Exception) { null }
    }
}
