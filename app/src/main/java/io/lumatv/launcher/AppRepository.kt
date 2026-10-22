package io.lumatv.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class Artwork(val bitmap: Bitmap, val banner: Boolean, val background: Int, val opaque: Boolean = false)

class AppRepository(private val context: Context) {
    private val pm = context.packageManager
    private val cache = object : LruCache<String, Artwork>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Artwork) = value.bitmap.allocationByteCount
    }
    private val permits = Semaphore(2)

    suspend fun discover(): List<AppEntry> = withContext(Dispatchers.IO) {
        fun query(category: String, tv: Boolean): List<AppEntry> = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(category), PackageManager.MATCH_ALL
        ).mapNotNull { info ->
            val a = info.activityInfo
            if (!a.enabled || !a.exported || !a.applicationInfo.enabled || a.packageName == context.packageName) null
            else AppEntry(ComponentName(a.packageName, a.name).flattenToString(), a.packageName,
                runCatching { info.loadLabel(pm).toString() }.getOrDefault(a.packageName), tv)
        }.sortedBy { it.id }.distinctBy { it.packageName }
        val tv = query(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        val tvPackages = tv.map { it.packageName }.toSet()
        tv + query(Intent.CATEGORY_LAUNCHER, false).filter { it.packageName !in tvPackages }
    }

    /**
     * [preferBanner] keeps the wide 16:9 artwork for the shelf; app icons use the square launcher
     * icon instead, which is what a tvOS-style home reads as.
     */
    suspend fun artwork(app: AppEntry, preferBanner: Boolean = true): Artwork? = withContext(Dispatchers.IO) {
        val key = app.id + if (preferBanner) ":art" else ":icon"
        cache.get(key) ?: permits.withPermit {
            cache.get(key) ?: runCatching {
                val info = pm.getActivityInfo(ComponentName.unflattenFromString(app.id)!!, 0)
                val banner = if (preferBanner)
                    runCatching { info.loadBanner(pm) ?: pm.getApplicationBanner(app.packageName) }.getOrNull() else null
                val icon = if (banner == null && (info.icon != 0 || info.applicationInfo.icon != 0))
                    runCatching { info.loadIcon(pm) }.getOrNull() else null
                val drawable = icon ?: banner ?: return@runCatching null
                val width = if (banner != null) 800 else 256
                val height = if (banner != null) 450 else 256
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.TRANSPARENT)
                val ratio = minOf(width.toFloat() / drawable.intrinsicWidth.coerceAtLeast(1),
                    height.toFloat() / drawable.intrinsicHeight.coerceAtLeast(1))
                val w = (drawable.intrinsicWidth.coerceAtLeast(1) * ratio).toInt()
                val h = (drawable.intrinsicHeight.coerceAtLeast(1) * ratio).toInt()
                drawable.setBounds((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
                drawable.draw(Canvas(bitmap))
                // A banner is a composed 16:9 image and must keep its own margins; only a bare icon
                // gets its transparent padding cropped off so the mark can fill a tile.
                val trimmed = if (banner != null) bitmap else trim(bitmap) ?: bitmap
                val plate = plateFor(trimmed)
                Artwork(trimmed, banner != null, plate.first, plate.second).also { cache.put(key, it) }
            }.getOrNull()
        }
    }

    /**
     * A tvOS tile is one flat colour with the mark on top. An opaque launcher icon already carries
     * its own field, so the tile borrows its dominant colour and the icon can fill the tile. A
     * transparent mark gets a deep neutral plate instead of a white sticker.
     */
    private fun plateFor(bitmap: Bitmap): Pair<Int, Boolean> {
        var sampled = 0
        var solid = 0
        var rs = 0f
        var gs = 0f
        var bs = 0f
        var ws = 0f
        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
            sampled++
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) < 200) continue
            solid++
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val saturation = (maxOf(r, g, b) - minOf(r, g, b)) / 255f
            val weight = .12f + saturation * .88f
            rs += r * weight; gs += g * weight; bs += b * weight; ws += weight
        }
        if (ws <= 0f) return Color.rgb(23, 28, 36) to false
        val coverage = solid.toFloat() / sampled.coerceAtLeast(1)
        var r = rs / ws / 255f
        var g = gs / ws / 255f
        var b = bs / ws / 255f
        // Deep enough for a white name, bright enough to read as the app's own field.
        val luminance = .299f * r + .587f * g + .114f * b
        val ceiling = if (coverage >= .80f) .24f else .19f
        if (luminance > ceiling) {
            val k = ceiling / luminance
            r *= k; g *= k; b *= k
        }
        return Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt()) to (coverage >= .80f)
    }

    /** Drop transparent margins so a wide leanback mark can fill its tile instead of floating small. */
    private fun trim(bitmap: Bitmap): Bitmap? {
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
            if (Color.alpha(bitmap.getPixel(x, y)) <= 8) continue
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        if (right < left || bottom < top) return null
        if (left == 0 && top == 0 && right == bitmap.width - 1 && bottom == bitmap.height - 1) return bitmap
        val width = (right - left + 1).coerceAtLeast(1)
        val height = (bottom - top + 1).coerceAtLeast(1)
        return runCatching { Bitmap.createBitmap(bitmap, left, top, width, height) }.getOrNull()
    }

    fun invalidate() { cache.evictAll() }

    fun launch(app: AppEntry) {
        context.startActivity(Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName.unflattenFromString(app.id)
            addCategory(if (app.television) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        })
    }
}
