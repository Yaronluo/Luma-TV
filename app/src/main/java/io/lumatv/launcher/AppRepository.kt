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

data class Artwork(val bitmap: Bitmap, val banner: Boolean, val background: Int)

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

    suspend fun artwork(app: AppEntry): Artwork? = withContext(Dispatchers.IO) {
        cache.get(app.id) ?: permits.withPermit {
            cache.get(app.id) ?: runCatching {
                val info = pm.getActivityInfo(ComponentName.unflattenFromString(app.id)!!, 0)
                val banner = runCatching { info.loadBanner(pm) ?: pm.getApplicationBanner(app.packageName) }.getOrNull()
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
                Artwork(bitmap, banner != null, tileBackground(bitmap)).also { cache.put(app.id, it) }
            }.getOrNull()
        }
    }

    /** Preserve opaque icon backgrounds; transparent marks get a quiet light backing. */
    private fun tileBackground(bitmap: Bitmap): Int {
        val buckets = mutableMapOf<Int, MutableList<Int>>()
        var solid = 0
        var minX = bitmap.width
        var minY = bitmap.height
        var maxX = 0
        var maxY = 0
        for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) > 240) {
                solid++
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
                val key = (Color.red(pixel) / 16 shl 8) or (Color.green(pixel) / 16 shl 4) or (Color.blue(pixel) / 16)
                buckets.getOrPut(key) { mutableListOf() }.add(pixel)
            }
        }
        val width = (maxX - minX) / 8 + 1
        val height = (maxY - minY) / 8 + 1
        val ratio = width.toFloat() / height.coerceAtLeast(1)
        // A square backing should extend into its tile. Circles and wide transparent marks should not.
        if (solid == 0 || ratio !in .85f..1.15f || solid < width * height * .85f) return Color.rgb(246, 246, 247)
        val pixels = buckets.maxByOrNull { it.value.size }?.value ?: return Color.rgb(246, 246, 247)
        return Color.rgb(pixels.sumOf { Color.red(it) } / pixels.size,
            pixels.sumOf { Color.green(it) } / pixels.size, pixels.sumOf { Color.blue(it) } / pixels.size)
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
