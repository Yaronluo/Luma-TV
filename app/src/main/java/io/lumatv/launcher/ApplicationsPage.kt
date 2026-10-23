package io.lumatv.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val Columns = 6
private val FocusInset = 16.dp

/** A single quiet header and a continuous library; actions live behind the title. */
@Composable
internal fun ApplicationsPage(
    title: String, screen: String, apps: List<AppEntry>, config: LumaConfig, model: LauncherModel,
    revision: Int, memory: PageMemory, epoch: Int, moving: String?,
    onSettings: () -> Unit, onAll: () -> Unit, onManage: () -> Unit, onOther: () -> Unit,
    onBack: () -> Unit, onActivate: (AppEntry, Bookmark) -> Unit, onMenu: (AppEntry) -> Unit,
    onMove: (String, Int) -> Unit, hasOther: Boolean, homeCatalog: List<AppEntry>,
) {
    if (screen == "home") {
        HomePage(apps, homeCatalog, config, model, revision, memory, epoch, onAll, onSettings, onManage, onActivate, onMenu)
        return
    }
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState(memory.mark.index.coerceAtMost((apps.size - 1).coerceAtLeast(0)), memory.mark.offset)
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    apps.forEach { requesters.getOrPut(it.id) { FocusRequester() } }
    val settings = remember { FocusRequester() }
    val sections = remember { FocusRequester() }
    var sectionsOpen by remember { mutableStateOf(false) }
    var cursor by remember { mutableStateOf(memory.mark.id) }
    var actualFocus by remember { mutableStateOf("") }
    var leaving by remember { mutableStateOf(false) }
    val confirmation = remember { DeferredConfirmation() }
    var moveJob by remember { mutableStateOf<Job?>(null) }
    val reveal = remember { Animatable(if (config.reducedMotion) 1f else 0f) }
    LaunchedEffect(config.reducedMotion) {
        if (config.reducedMotion) reveal.snapTo(1f)
        else reveal.animateTo(1f, tween(440, easing = LinearEasing))
    }

    fun mark(id: String = cursor) = Bookmark(id, grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset, memory.lastCard)
    fun depart(action: () -> Unit) {
        if (leaving) return
        leaving = true
        moveJob?.cancel()
        scope.launch {
            if (!config.reducedMotion) reveal.animateTo(0f, tween(140, easing = FastOutSlowInEasing))
            action()
        }
    }
    BackHandler(enabled = moving == null) { depart(onBack) }

    fun controlFocus(id: String) {
        actualFocus = id
        // Removing a focused lazy item can temporarily focus the header automatically.
        // Keep the logical target until catalog restoration chooses its neighbouring app.
        if (cursor != id) return
        moveJob?.cancel()
        memory.mark = mark()
    }
    fun aim(index: Int) {
        if (apps.isEmpty()) { cursor = "@sections"; sections.requestFocus(); return }
        val target = index.coerceIn(0, apps.lastIndex)
        cursor = apps[target].id
        memory.lastCard = cursor
        memory.mark = mark()
        moveJob?.cancel()
        moveJob = scope.launch {
            val bounds = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            if (bounds == null || bounds.offset.y < 12 || bounds.offset.y + bounds.size.height > grid.layoutInfo.viewportEndOffset - 12) {
                if (config.reducedMotion) grid.scrollToItem(target / Columns * Columns)
                else grid.animateScrollToItem(target / Columns * Columns)
            }
            withFrameNanos { }
            requesters[apps[target].id]?.requestFocus()
        }
    }
    LaunchedEffect(apps.map { it.id }, epoch) {
        if (apps.isEmpty()) { cursor = "@sections"; sections.requestFocus() }
        else {
            val savedId = fallbackFocus(memory.ids, memory.mark.id, apps.map { it.id })
            memory.ids = apps.map { it.id }
            when {
                moving != null -> aim(apps.indexOfFirst { it.id == moving }.coerceAtLeast(0))
                memory.mark.id == "@settings" -> { cursor = "@settings"; settings.requestFocus() }
                memory.mark.id in listOf("@sections", "@footer", "@manage", "@other") -> {
                    cursor = "@sections"; sections.requestFocus()
                }
                else -> aim(apps.indexOfFirst { it.id == savedId }.coerceAtLeast(0))
            }
        }
    }
    LaunchedEffect(grid, cursor) {
        snapshotFlow { mark() }.collect {
            memory.mark = it
            if (screen == "all") model.bookmark(screen, it)
        }
    }
    DisposableEffect(Unit) { onDispose { memory.mark = mark() } }

    fun pageKey(event: KeyEvent): Boolean {
        if (leaving) return true
        if (confirmation.handle(event, cursor, actualFocus) { id, held ->
                moveJob?.cancel()
                when (id) {
                    "@settings" -> depart(onSettings)
                    "@sections" -> sectionsOpen = true
                    else -> apps.firstOrNull { it.id == id }?.let { if (held) onMenu(it) else onActivate(it, mark(id)) }
                }
            }) return true
        if (event.type != KeyEventType.KeyDown || event.key !in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) return false
        val index = apps.indexOfFirst { it.id == cursor }
        if (index >= 0) {
            val delta = when (event.key) {
                Key.DirectionLeft -> if (index % Columns > 0) -1 else 0
                Key.DirectionRight -> if (index % Columns < Columns - 1 && index < apps.lastIndex) 1 else 0
                Key.DirectionUp -> if (index >= Columns) -Columns else 0
                else -> if (index / Columns < apps.lastIndex / Columns) minOf(Columns, apps.lastIndex - index) else 0
            }
            when {
                moving != null -> if (delta != 0) onMove(moving, delta)
                delta != 0 -> aim(index + delta)
                event.key == Key.DirectionUp && index < Columns -> {
                    moveJob?.cancel(); cursor = "@settings"; settings.requestFocus()
                }
            }
        } else when (event.key) {
            Key.DirectionDown -> if (apps.isNotEmpty()) aim(apps.indexOfFirst { it.id == memory.lastCard }.coerceAtLeast(0))
            Key.DirectionLeft -> { cursor = "@sections"; sections.requestFocus() }
            Key.DirectionRight -> { cursor = "@settings"; settings.requestFocus() }
        }
        return true
    }

    Column(Modifier.fillMaxSize().onPreviewKeyEvent(::pageKey)
        .padding(horizontal = config.safeInset.dp, vertical = 34.dp).testTag("page:$screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = FocusInset).height(48.dp)
            .graphicsLayer {
                val p = FastOutSlowInEasing.transform(reveal.value)
                alpha = p; translationY = -8.dp.toPx() * (1f - p)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }, verticalAlignment = Alignment.CenterVertically) {
            LibraryHeaderControl(title, "library-sections", sections, onFocus = { controlFocus("@sections") },
                onClick = { sectionsOpen = true })
            Spacer(Modifier.weight(1f))
            if (moving != null) Text("正在排序", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(end = 22.dp))
            LibraryHeaderControl(null, "settings", settings, onFocus = { controlFocus("@settings") },
                onClick = { depart(onSettings) })
        }
        Spacer(Modifier.height(30.dp))
        if (apps.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = FocusInset), contentAlignment = Alignment.Center) {
            Text(if (screen == "hidden") "没有隐藏的应用" else "暂无应用", color = Muted.copy(alpha = .7f), fontSize = 18.sp)
        } else LazyVerticalGrid(columns = GridCells.Fixed(Columns), state = grid,
            modifier = Modifier.weight(1f).testTag("app-grid"),
            contentPadding = PaddingValues(horizontal = FocusInset, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(23.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            itemsIndexed(apps, key = { _, app -> app.id }) { index, app ->
                Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                    placementSpec = if (config.reducedMotion) tween(0) else tween(260, easing = FastOutSlowInEasing))
                    .graphicsLayer {
                        val delay = (index / Columns).coerceAtMost(2) * 32 + index % Columns * 18
                        val p = if (config.reducedMotion) 1f else FastOutSlowInEasing.transform(((reveal.value * 440 - delay) / 280).coerceIn(0f, 1f))
                        alpha = p; translationY = 22.dp.toPx() * (1f - p)
                        scaleX = .96f + .04f * p; scaleY = scaleX
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }) {
                    AppTile(app, model.repository, revision, config.reducedMotion, app.id in config.favorites,
                        requesters.getValue(app.id), app.id == moving,
                        onFocus = {
                            actualFocus = app.id
                            if (cursor == app.id) { memory.lastCard = app.id; memory.mark = mark(app.id) }
                        },
                        onKey = { false }, onClick = { onActivate(app, mark(app.id)) }, onLongClick = { onMenu(app) },
                        name = TileLabel.Focused, star = screen in listOf("manage", "favorites"),
                        aspect = 5f / 3f, banner = true, softFocus = true)
                }
            }
        }
    }
    if (sectionsOpen) LibraryActionSheet(title, config.reducedMotion, onClose = {
        sectionsOpen = false
        cursor = "@sections"
        scope.launch { withFrameNanos { }; sections.requestFocus() }
    }, actions = buildList {
        if (screen != "all") add(LibraryAction("全部应用", "section-all") { sectionsOpen = false; depart(onAll) })
        if (screen != "manage") add(LibraryAction("管理应用", "manage") { sectionsOpen = false; depart(onManage) })
        if (hasOther && screen != "other") add(LibraryAction("其他应用", "other") { sectionsOpen = false; depart(onOther) })
    })
}

@Composable
private fun LibraryHeaderControl(title: String?, tag: String, requester: FocusRequester, onFocus: () -> Unit, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.focusRequester(requester)
        .onFocusChanged { if (it.isFocused) onFocus() }.testTag(tag)
        .semantics { contentDescription = if (title == null) "设置" else "$title，切换分组" },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White.copy(alpha = .12f)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f), border = quietBorder(), glow = quietGlow()) {
        Row(Modifier.height(48.dp).padding(horizontal = if (title == null) 12.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (title == null) Icon(Icons.Default.Settings, "设置", Modifier.size(23.dp), tint = Ink.copy(alpha = .78f))
            else {
                Text(title, color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                Canvas(Modifier.padding(start = 12.dp).size(12.dp)) {
                    val a = Offset(size.width * .15f, size.height * .35f)
                    val b = Offset(size.width * .5f, size.height * .7f)
                    val c = Offset(size.width * .85f, size.height * .35f)
                    drawLine(Muted.copy(alpha = .5f), a, b, 1.5.dp.toPx(), StrokeCap.Round)
                    drawLine(Muted.copy(alpha = .5f), b, c, 1.5.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}

internal data class LibraryAction(val title: String, val tag: String, val invoke: () -> Unit)

/** One floating list, without a separate card or a duplicate Back button for each action. */
@Composable
internal fun LibraryActionSheet(title: String, reduced: Boolean, onClose: () -> Unit, actions: List<LibraryAction>) {
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val reveal = remember { Animatable(if (reduced) 1f else 0f) }
    var closing by remember { mutableStateOf(false) }
    fun dismiss(action: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch {
            if (!reduced) reveal.animateTo(0f, tween(110))
            action()
        }
    }
    Dialog(onDismissRequest = { dismiss(onClose) }) {
        Column(Modifier.width(310.dp).graphicsLayer {
            alpha = reveal.value; translationY = 16.dp.toPx() * (1f - reveal.value)
            scaleX = .96f + .04f * reveal.value; scaleY = scaleX
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }.background(Color(0xF2222E40), RoundedCornerShape(22.dp)).padding(12.dp)
            .onPreviewKeyEvent { closing }) {
            Text(title, color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp))
            actions.forEachIndexed { index, action ->
                LumaButton(action.title, action.tag, Modifier.fillMaxWidth(),
                    requester = if (index == 0) first else null, flat = true, onClick = { dismiss(action.invoke) })
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }; first.requestFocus()
            if (!reduced) reveal.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        }
    }
}
