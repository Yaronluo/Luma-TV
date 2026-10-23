package io.lumatv.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                subtitle = when {
                    moving != null -> "方向键调整位置 · 确认保存 · 返回取消"
                    screen == "manage" -> "选择应用，管理收藏、顺序和可见性"
                    screen == "hidden" -> "选择应用恢复显示，原收藏设置会保留"
                    screen == "other" -> "这些应用没有电视入口，可能需要鼠标操作"
                    home && favorites.isEmpty() -> "你的电视，从这里开始"
                    home -> "喜欢的应用，就在眼前"
                    else -> "${items.size} 个电视应用"
                }, moving = moving,
                onFocused = {},
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
            ApplicationMenu(selected, selected.id in c.favorites, onClose = { closeMenu() },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ApplicationsPage(
    title: String, screen: String, apps: List<AppEntry>, config: LumaConfig, model: LauncherModel,
    revision: Int, memory: PageMemory, epoch: Int, subtitle: String, moving: String?,
    onFocused: (String) -> Unit, onSettings: () -> Unit, onAll: () -> Unit, onManage: () -> Unit,
    onOther: () -> Unit, onBack: () -> Unit, onActivate: (AppEntry, Bookmark) -> Unit,
    onMenu: (AppEntry) -> Unit, onMove: (String, Int) -> Unit, hasOther: Boolean, homeCatalog: List<AppEntry>,
) {
    val home = screen == "home"
    if (home) {
        HomePage(apps, homeCatalog, config, model, revision, memory, epoch, onAll, onSettings, onManage, onActivate, onMenu)
        return
    }
    val columns = if (home || screen == "favorites") 5 else 6
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState(memory.mark.index.coerceAtMost((apps.size - 1).coerceAtLeast(0)), memory.mark.offset)
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    apps.forEach { requesters.getOrPut(it.id) { FocusRequester() } }
    val settings = remember { FocusRequester() }
    val footer = remember { FocusRequester() }
    val manage = remember { FocusRequester() }
    val other = remember { FocusRequester() }
    var cursor by remember { mutableStateOf(memory.mark.id) }
    var actualFocus by remember { mutableStateOf("") }
    val confirmation = remember { DeferredConfirmation() }
    var moveJob by remember { mutableStateOf<Job?>(null) }

    fun mark(id: String = cursor) = Bookmark(id, grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset, memory.lastCard)
    fun aim(index: Int) {
        if (apps.isEmpty()) { footer.requestFocus(); return }
        val target = index.coerceIn(0, apps.lastIndex)
        cursor = apps[target].id
        memory.lastCard = cursor
        memory.mark = mark()
        moveJob?.cancel()
        moveJob = scope.launch {
            val bounds = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            if (bounds == null || bounds.offset.y < 12 || bounds.offset.y + bounds.size.height > grid.layoutInfo.viewportEndOffset - 12) {
                grid.scrollToItem((target / columns) * columns)
            }
            withFrameNanos { }
            requesters[apps[target].id]?.requestFocus()
        }
    }

    LaunchedEffect(apps.map { it.id }, epoch) {
        if (apps.isEmpty()) footer.requestFocus()
        else {
            val savedId = fallbackFocus(memory.ids, memory.mark.id, apps.map { it.id })
            memory.ids = apps.map { it.id }
            val button = when (memory.mark.id) {
                "@settings" -> settings; "@footer" -> footer; "@manage" -> manage; "@other" -> other; else -> null
            }
            if (button != null && moving == null) button.requestFocus()
            else aim(apps.indexOfFirst { it.id == (moving ?: savedId) }.coerceAtLeast(0))
        }
    }
    LaunchedEffect(grid, cursor) {
        snapshotFlow { mark() }.collect {
            memory.mark = it
            if (screen == "home" || screen == "all") model.bookmark(screen, it)
        }
    }
    DisposableEffect(Unit) { onDispose { memory.mark = mark() } }

    fun tileKey(app: AppEntry, event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val index = apps.indexOfFirst { it.id == cursor }.takeIf { it >= 0 } ?: apps.indexOf(app)
        val lastRow = index / columns == apps.lastIndex / columns
        val delta = when (event.key) {
            Key.DirectionLeft -> if (index % columns > 0) -1 else 0
            Key.DirectionRight -> if (index % columns < columns - 1 && index < apps.lastIndex) 1 else 0
            Key.DirectionUp -> if (index >= columns) -columns else 0
            Key.DirectionDown -> if (!lastRow) minOf(columns, apps.lastIndex - index) else 0
            else -> return false
        }
        if (moving != null) {
            if (delta != 0) onMove(moving, delta)
        } else when {
            delta != 0 -> aim(index + delta)
            event.key == Key.DirectionUp && index < columns -> { moveJob?.cancel(); cursor = "@settings"; settings.requestFocus() }
            event.key == Key.DirectionDown && lastRow -> { moveJob?.cancel(); cursor = "@footer"; footer.requestFocus() }
        }
        return true
    }

    fun controlFocus(id: String) {
        moveJob?.cancel()
        actualFocus = id
        cursor = id
        memory.mark = mark()
    }

    fun pageKey(event: KeyEvent): Boolean {
        if (confirmation.handle(event, cursor, actualFocus) { id, held ->
                moveJob?.cancel()
                when (id) {
                    "@settings" -> onSettings()
                    "@footer" -> onBack()
                    "@manage" -> onManage()
                    "@other" -> onOther()
                    else -> apps.firstOrNull { it.id == id }?.let { if (held) onMenu(it) else onActivate(it, mark(id)) }
                }
            }) return true
        if (event.type != KeyEventType.KeyDown || event.key !in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) return false
        // Route by the logical target, even while a lazy item is waiting to be placed.
        // A second key must not race the first key's deferred focus request.
        apps.firstOrNull { it.id == cursor }?.let { return tileKey(it, event) }
        if (cursor == "@settings") {
            if (event.key == Key.DirectionDown) aim(apps.indexOfFirst { it.id == memory.lastCard }.coerceAtLeast(0))
            return true
        }
        val controls = buildList {
            add("@footer" to footer)
            if (home || screen == "all") add("@manage" to manage)
            if (screen == "all" && hasOther) add("@other" to other)
        }
        val position = controls.indexOfFirst { it.first == cursor }.coerceAtLeast(0)
        when (event.key) {
            Key.DirectionUp -> if (apps.isNotEmpty()) aim(apps.indexOfFirst { it.id == memory.lastCard }.takeIf { it >= 0 } ?: apps.lastIndex)
            Key.DirectionLeft, Key.DirectionRight -> {
                val target = (position + if (event.key == Key.DirectionRight) 1 else -1).coerceIn(0, controls.lastIndex)
                moveJob?.cancel(); cursor = controls[target].first; controls[target].second.requestFocus()
            }
        }
        return true
    }

    Column(Modifier.fillMaxSize().onPreviewKeyEvent(::pageKey)
        .padding(horizontal = config.safeInset.dp, vertical = 28.dp).testTag("page:$screen")) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(8.dp).background(Accent, RoundedCornerShape(4.dp)))
                Text("Luma TV", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
            }
            Spacer(Modifier.weight(1f))
            LumaClock()
            Spacer(Modifier.width(22.dp))
            LumaButton("设置", "settings", requester = settings,
                onFocus = { controlFocus("@settings") }, onClick = onSettings)
        }
        Spacer(Modifier.height(if (home) 42.dp else 18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(title, color = Ink, fontSize = if (home) 27.sp else 26.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(18.dp))
            Text(subtitle, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.weight(1f)) {
            if (apps.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                Text(if (screen == "hidden") "没有隐藏的应用" else "这里还没有应用，可以通过下方入口继续操作", color = Muted, fontSize = 18.sp)
            } else LazyVerticalGrid(columns = GridCells.Fixed(columns), state = grid,
                modifier = Modifier.fillMaxSize().testTag("app-grid"),
                contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(if (columns == 5) 24.dp else 18.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)) {
                itemsIndexed(apps, key = { _, app -> app.id }) { _, app ->
                    AppTile(app, model.repository, revision, config.reducedMotion, app.id in config.favorites,
                        requesters.getValue(app.id), app.id == moving,
                        onFocus = { actualFocus = app.id; cursor = app.id; memory.lastCard = app.id; memory.mark = mark(app.id); onFocused(app.id) },
                        onKey = { false },
                        onClick = { onActivate(app, mark(app.id)) }, onLongClick = { onMenu(app) }, name = TileLabel.Always)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            LumaButton(if (home) "全部应用" else "返回", "footer-primary", requester = footer,
                onFocus = { controlFocus("@footer") }, onClick = if (home) onAll else onBack)
            if (home || screen == "all") LumaButton("管理应用", "manage", requester = manage,
                onFocus = { controlFocus("@manage") }, onClick = onManage)
            if (screen == "all" && hasOther) LumaButton("其他应用", "other", requester = other,
                onFocus = { controlFocus("@other") }, onClick = onOther)
            Spacer(Modifier.weight(1f))
            Text(when {
                moving != null -> "确认保存 · 返回取消"
                screen == "hidden" -> "确认恢复 · 返回上一级"
                screen == "manage" || screen == "favorites" -> "确认管理 · 返回上一级"
                else -> "确认打开 · 长按管理"
            }, color = Muted, fontSize = 14.sp)
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
private fun ApplicationMenu(app: AppEntry, favorite: Boolean, onClose: () -> Unit,
    onFavorite: () -> Unit, onHide: () -> Unit, onSort: () -> Unit, onFavoriteSort: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.width(340.dp).background(Panel, RoundedCornerShape(22.dp)).padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(app.displayName, color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Medium)
            Text("应用管理", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
            LumaButton(if (favorite) "取消收藏" else "收藏到首页", "favorite-action", Modifier.fillMaxWidth(), first, onClick = onFavorite)
            if (app.television) LumaButton("调整应用顺序", "sort-action", Modifier.fillMaxWidth(), onClick = onSort)
            if (favorite) LumaButton("调整收藏顺序", "favorite-sort-action", Modifier.fillMaxWidth(), onClick = onFavoriteSort)
            LumaButton("隐藏应用", "hide-action", Modifier.fillMaxWidth(), onClick = onHide)
            LumaButton("返回", "close-menu", Modifier.fillMaxWidth(), onClick = onClose)
        }
        LaunchedEffect(Unit) { withFrameNanos { }; first.requestFocus() }
    }
}
