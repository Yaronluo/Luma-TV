package io.lumatv.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

internal class PageMemory(var mark: Bookmark = Bookmark(), var ids: List<String> = emptyList()) {
    var lastCard = mark.appId
}

@Composable
fun LumaApp(model: LauncherModel, onExit: () -> Unit) {
    val config by model.config.collectAsStateWithLifecycle()
    val catalog by model.apps.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val revision by model.iconRevision.collectAsStateWithLifecycle()
    val homeRevision by model.homeRequests.collectAsStateWithLifecycle()
    val c = config
    val all = catalog
    if (c == null || all == null) {
        Box(Modifier.fillMaxSize().background(Color(0xFF101526)), contentAlignment = Alignment.Center) {
            Text("Luma TV", color = Ink, fontSize = 28.sp)
        }
        return
    }
    var screen by rememberSaveable { mutableStateOf(c.route) }
    var settingsOrigin by rememberSaveable { mutableStateOf("home") }
    var settingsTab by rememberSaveable { mutableStateOf("外观") }
    var settingsReturnToContent by rememberSaveable { mutableStateOf(false) }
    var overlay by remember { mutableStateOf<AppEntry?>(null) }
    var moving by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<List<String>?>(null) }
    var epoch by remember { mutableIntStateOf(0) }
    val memories = remember { mutableMapOf("home" to PageMemory(c.home), "all" to PageMemory(c.all)) }
    val visible = all.filter { it.id !in c.hidden }
    val television = orderedApps(visible.filter { it.television }, c.order)
    val favorites = orderedApps(visible.filter { it.id in c.favorites }, c.favorites)
    val items = when (screen) {
        "home" -> favorites.ifEmpty { television.sortedBy { it.variant.isNotEmpty() }.take(5) }
        "favorites" -> orderedApps(favorites, draft ?: c.favorites)
        "hidden" -> orderedApps(all.filter { it.id in c.hidden }, c.order)
        "other" -> orderedApps(visible.filter { !it.television }, c.order)
        else -> orderedApps(television, draft ?: c.order)
    }
    val memory = memories.getOrPut(screen) { PageMemory() }

    fun closeMenu() { overlay = null; epoch++ }
    fun back() {
        when {
            moving != null -> { moving = null; draft = null; epoch++ }
            overlay != null -> closeMenu()
            screen == "hidden" -> screen = "settings"
            screen == "settings" -> screen = settingsOrigin
            screen == "favorites" -> screen = "manage"
            screen != "home" -> screen = "home"
            !model.isDefaultHome() -> onExit()
            else -> epoch++
        }
    }
    BackHandler { back() }
    LaunchedEffect(homeRevision) {
        if (homeRevision > 0) {
            overlay = null; moving = null; draft = null
            if (screen !in listOf("home", "all", "other")) screen = "home"
            epoch++
        }
    }

    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        if (screen != "home") Atmosphere(c, Modifier.fillMaxSize())
        if (screen == "settings") {
            SettingsPage(c, model, settingsTab, settingsReturnToContent, onTab = { settingsTab = it },
                onHidden = { settingsReturnToContent = true; screen = "hidden" }, onBack = { back() })
        } else key(screen) {
            val home = screen == "home"
            val title = when (screen) {
                "home" -> if (favorites.isEmpty()) "我的应用" else "收藏应用"
                "manage" -> "管理应用"
                "favorites" -> "收藏排序"
                "hidden" -> "已隐藏应用"
                "other" -> "其他应用"
                else -> "全部应用"
            }
            ApplicationsPage(title, screen, items, c, model, revision, memory, epoch,
                moving = moving,
                onSettings = { settingsOrigin = screen; settingsReturnToContent = false; screen = "settings" },
                onAll = { screen = "all" }, onManage = { screen = "manage" },
                onOther = { screen = "other" }, onBack = { back() },
                onActivate = { app, mark ->
                    when {
                        moving != null -> {
                            val saved = draft ?: items.map { it.id }
                            if (screen == "favorites") model.change { it.copy(favorites = saved) }
                            else model.change { it.copy(order = saved) }
                            moving = null; draft = null; epoch++
                        }
                        screen == "hidden" -> model.change { it.copy(hidden = it.hidden - app.id) }
                        screen in listOf("manage", "favorites") -> overlay = app
                        else -> model.launch(app, if (home) "home" else "all", mark)
                    }
                },
                onMenu = { if (moving == null) overlay = it },
                onMove = { id, delta -> draft = moveItem(draft ?: items.map { it.id }, id, delta) },
                hasOther = visible.any { !it.television }, homeCatalog = television)
        }
        overlay?.let { selected ->
            ApplicationMenu(selected, selected.id in c.favorites, c.reducedMotion, onClose = { closeMenu() },
                onFavorite = {
                    model.change { it.copy(favorites = if (selected.id in it.favorites) it.favorites - selected.id else it.favorites + selected.id) }
                    closeMenu()
                }, onHide = { model.change { it.copy(hidden = it.hidden + selected.id) }; closeMenu() },
                onSort = {
                    if (screen !in listOf("manage", "favorites")) screen = "manage"
                    draft = null; moving = selected.id; closeMenu()
                    memories.getOrPut(screen) { PageMemory() }.mark = Bookmark(selected.id)
                }, onFavoriteSort = {
                    screen = "favorites"; draft = null; moving = selected.id; closeMenu()
                    memories.getOrPut("favorites") { PageMemory() }.mark = Bookmark(selected.id)
                })
        }
        message?.let { text ->
            LaunchedEffect(text) { delay(4500); model.clearMessage() }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
                .background(Color(0xF0303D55), RoundedCornerShape(12.dp)).padding(18.dp, 12.dp).testTag("notice")) {
                Text(text, color = Ink, fontSize = 16.sp)
            }
        }
    }
}

@Composable
internal fun LumaClock(fontSize: TextUnit = 18.sp) {
    var time by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            delay(1000L * (60 - java.util.Calendar.getInstance().get(java.util.Calendar.SECOND)))
        }
    }
    Text(time, color = Ink.copy(alpha = .88f), fontSize = fontSize, fontWeight = FontWeight.Normal, letterSpacing = .2.sp)
}

@Composable
private fun ApplicationMenu(app: AppEntry, favorite: Boolean, reduced: Boolean, onClose: () -> Unit,
    onFavorite: () -> Unit, onHide: () -> Unit, onSort: () -> Unit, onFavoriteSort: () -> Unit) {
    LibraryActionSheet(app.displayName, reduced, onClose, buildList {
        add(LibraryAction(if (favorite) "取消收藏" else "收藏到首页", "favorite-action", onFavorite))
        if (app.television) add(LibraryAction("调整应用顺序", "sort-action", onSort))
        if (favorite) add(LibraryAction("调整收藏顺序", "favorite-sort-action", onFavoriteSort))
        add(LibraryAction("隐藏应用", "hide-action", onHide))
    })
}
