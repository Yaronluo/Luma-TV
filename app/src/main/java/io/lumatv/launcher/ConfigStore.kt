package io.lumatv.launcher

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.lumaData by preferencesDataStore("luma")

class ConfigStore(context: Context) {
    private val data = context.lumaData
    private val key = stringPreferencesKey("configuration_v1")
    val config = data.data.catch { if (it is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw it }
        .map { decode(it[key]) }

    suspend fun update(change: (LumaConfig) -> LumaConfig) {
        data.edit { prefs -> prefs[key] = encode(change(decode(prefs[key]))) }
    }

    private fun decode(raw: String?): LumaConfig = runCatching {
        val o = JSONObject(raw ?: "{}")
        fun list(key: String): List<String> = o.optJSONArray(key)?.let { a ->
            (0 until a.length()).map { a.getString(it) }.distinct()
        }.orEmpty()
        fun mark(key: String): Bookmark = o.optJSONObject(key)?.let {
            Bookmark(it.optString("id"), it.optInt("index").coerceAtLeast(0), it.optInt("offset").coerceAtLeast(0),
                it.optString("appId", it.optString("id").takeUnless { id -> id.startsWith("@") }.orEmpty()), it.optBoolean("expanded"))
        } ?: Bookmark()
        LumaConfig(list("favorites"), list("order"), list("hidden").toSet(),
            o.optString("background", "dusk"), o.optInt("brightness", 1).coerceIn(0, 2),
            o.optBoolean("reducedMotion"), o.optInt("safeInset", 48).coerceIn(32, 64),
            o.optString("route", "home").takeIf { it == "all" } ?: "home",
            mark("home"), mark("all"), o.optString("originalHome"), o.optLong("wallpaperRevision"), o.optBoolean("backgroundBlur", true))
    }.getOrDefault(LumaConfig())

    private fun encode(c: LumaConfig): String = JSONObject().apply {
        put("favorites", JSONArray(c.favorites)); put("order", JSONArray(c.order)); put("hidden", JSONArray(c.hidden.toList()))
        put("background", c.background); put("brightness", c.brightness); put("reducedMotion", c.reducedMotion)
        put("safeInset", c.safeInset); put("route", c.route); put("originalHome", c.originalHome)
        put("wallpaperRevision", c.wallpaperRevision)
        put("backgroundBlur", c.backgroundBlur)
        fun mark(b: Bookmark) = JSONObject().put("id", b.id).put("index", b.index).put("offset", b.offset).put("appId", b.appId).put("expanded", b.expanded)
        put("home", mark(c.home)); put("all", mark(c.all))
    }.toString()
}
