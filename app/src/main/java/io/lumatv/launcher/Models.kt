package io.lumatv.launcher

data class AppEntry(val id: String, val packageName: String, val label: String, val television: Boolean) {
    val variant: String get() = when {
        packageName.endsWith(".beta") -> "Beta"
        packageName.endsWith(".debug") -> "Debug"
        packageName.endsWith(".layoutcheck") -> "布局测试"
        else -> ""
    }
    val displayName: String get() = if (variant.isEmpty()) label else "$label · $variant"
}

data class Bookmark(val id: String = "", val index: Int = 0, val offset: Int = 0,
    val appId: String = id.takeUnless { it.startsWith("@") }.orEmpty(), val expanded: Boolean = false)

data class LumaConfig(
    val favorites: List<String> = emptyList(),
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val background: String = "dusk",
    val brightness: Int = 1,
    val reducedMotion: Boolean = false,
    val safeInset: Int = 48,
    val route: String = "home",
    val home: Bookmark = Bookmark(),
    val all: Bookmark = Bookmark(),
    val originalHome: String = "",
    val wallpaperRevision: Long = 0L,
    val backgroundBlur: Boolean = true,
)

fun orderedApps(apps: List<AppEntry>, ids: List<String>): List<AppEntry> {
    val ranks = ids.withIndex().associate { it.value to it.index }
    return apps.sortedWith(compareBy<AppEntry> { ranks[it.id] ?: Int.MAX_VALUE }
        .thenBy { it.label }.thenBy { it.id })
}

/** A removed focused item falls back to its old slot, or the previous final item. */
fun fallbackFocus(old: List<String>, focused: String, fresh: List<String>): String {
    if (focused in fresh) return focused
    if (fresh.isEmpty()) return ""
    return fresh[old.indexOf(focused).coerceAtLeast(0).coerceAtMost(fresh.lastIndex)]
}

fun moveItem(ids: List<String>, id: String, delta: Int): List<String> {
    val from = ids.indexOf(id)
    if (from == -1) return ids
    val to = (from + delta).coerceIn(0, ids.lastIndex)
    return ids.toMutableList().apply { removeAt(from); add(to, id) }
}
