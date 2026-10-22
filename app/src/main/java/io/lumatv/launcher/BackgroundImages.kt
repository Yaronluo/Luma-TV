package io.lumatv.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/** Only the wallpaper is filtered. Cached default rasters avoid a per-frame blur pass. */
internal object BackgroundImages {
    private var key: String? = null
    private var cached: Bitmap? = null

    suspend fun load(context: Context, config: LumaConfig): Bitmap? = withContext(Dispatchers.IO) {
        val next = "${config.background}:${config.wallpaperRevision}:${config.backgroundBlur}"
        synchronized(this@BackgroundImages) {
            if (key == next) return@withContext cached
            val default = config.background !in listOf("ocean", "warm", "custom")
            val image = if (default) BitmapFactory.decodeResource(context.resources,
                if (config.backgroundBlur) R.drawable.wallpaper_ambient_blur else R.drawable.wallpaper_ambient)
            else {
                val original = if (config.background == "custom") BitmapFactory.decodeFile(File(context.filesDir, "wallpaper.jpg").path)
                    else BitmapFactory.decodeResource(context.resources, if (config.background == "ocean") R.drawable.wallpaper_ocean else R.drawable.wallpaper_warm)
                if (original != null && config.backgroundBlur) gaussian(original) else original
            }
            cached = image ?: BitmapFactory.decodeResource(context.resources,
                if (config.backgroundBlur) R.drawable.wallpaper_ambient_blur else R.drawable.wallpaper_ambient)
            key = next
            cached
        }
    }

    // Three sliding box passes approximate a Gaussian (sigma=38dp) on API26+.
    // The work is bounded, happens once on IO, and does not depend on RenderEffect support.
    private fun gaussian(source: Bitmap): Bitmap {
        val width = minOf(source.width, 1280)
        val height = (source.height.toFloat() * width / source.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        var input = IntArray(width * height)
        var output = IntArray(input.size)
        scaled.getPixels(input, 0, width, 0, 0, width, height)
        val sigma = width * 38f / 960f
        val radius = ((sqrt(4f * sigma * sigma + 1f) - 1f) / 2f).toInt().coerceAtLeast(1)
        repeat(3) {
            for (vertical in listOf(false, true)) {
                val length = if (vertical) height else width
                val lines = if (vertical) width else height
                fun at(line: Int, p: Int) = if (vertical) p.coerceIn(0, length - 1) * width + line else line * width + p.coerceIn(0, length - 1)
                for (line in 0 until lines) {
                    var r = 0L; var g = 0L; var b = 0L
                    fun add(pixel: Int, sign: Int) {
                        r += ((pixel shr 16) and 255) * sign; g += ((pixel shr 8) and 255) * sign; b += (pixel and 255) * sign
                    }
                    for (p in -radius..radius) add(input[at(line, p)], 1)
                    val count = radius * 2 + 1
                    for (p in 0 until length) {
                        output[at(line, p)] = (255 shl 24) or ((r / count).toInt() shl 16) or ((g / count).toInt() shl 8) or (b / count).toInt()
                        add(input[at(line, p - radius)], -1); add(input[at(line, p + radius + 1)], 1)
                    }
                }
                val swap = input; input = output; output = swap
            }
        }
        val result = Bitmap.createBitmap(input, width, height, Bitmap.Config.ARGB_8888)
        if (scaled !== source) scaled.recycle()
        source.recycle()
        return result
    }
}
