package io.lumatv.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.tv.material3.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The shelf and grid share stable targets. Moving a card never changes its layout size. */
@Composable
internal fun HomePage(apps: List<AppEntry>, catalog: List<AppEntry>, config: LumaConfig, model: LauncherModel, revision: Int,
    memory: PageMemory, epoch: Int, onAll: () -> Unit, onSettings: () -> Unit, onManage: () -> Unit,
    onActivate: (AppEntry, Bookmark) -> Unit, onMenu: (AppEntry) -> Unit) {
    val dock = apps.take(5)
    val body = (apps.drop(5) + catalog).distinctBy { it.id }.filter { app -> dock.none { it.id == app.id } }
    val dockIds = dock.map { it.id } + "@footer"
    val bodyIds = body.map { it.id } + listOf("@settings", "@manage")
    val heroes = dock.map { "@hero:${it.id}" }
    val ids = dockIds + bodyIds + heroes
    val grid = rememberLazyGridState(memory.mark.index.coerceAtMost(bodyIds.lastIndex), memory.mark.offset)
    val shelf = rememberScrollState()
    val scope = rememberCoroutineScope()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    ids.forEach { requesters.getOrPut(it) { FocusRequester() } }
    var cursor by remember { mutableStateOf(memory.mark.id) }
    var actualFocus by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(memory.mark.expanded || memory.mark.id in bodyIds) }
    var moveJob by remember { mutableStateOf<Job?>(null) }
    val confirmation = remember { DeferredConfirmation() }
    val duration = if (config.reducedMotion) 0 else 300
    val dockTop by animateDpAsState(if (expanded) 51.dp else 401.dp, tween(duration), label = "home-dock")
    val bodyTop by animateDpAsState(if (expanded) 162.dp else 504.dp, tween(duration), label = "home-grid")
    val shelfAlpha by animateFloatAsState(if (expanded) 0f else 1f, tween(duration), label = "home-shelf")
    fun mark() = Bookmark(cursor, grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset, memory.lastCard, expanded)
    fun aim(id: String) {
        cursor = id
        expanded = id in bodyIds
        if (!id.startsWith("@")) memory.lastCard = id
        memory.mark = mark()
        moveJob?.cancel()
        moveJob = scope.launch {
            val index = bodyIds.indexOf(id)
            if (index >= 0) {
                val bounds = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                if (bounds == null || bounds.offset.y < 16 || bounds.offset.y + bounds.size.height > grid.layoutInfo.viewportEndOffset - 20)
                    grid.scrollToItem(index / 6 * 6)
            } else if (id in heroes) shelf.scrollTo(heroes.indexOf(id) * (shelf.maxValue / (heroes.size - 1).coerceAtLeast(1)))
            withFrameNanos { }
            requesters[id]?.requestFocus()
        }
    }
    fun activate(id: String, held: Boolean = false) {
        moveJob?.cancel()
        memory.mark = mark()
        when (id) {
            "@footer" -> onAll()
            "@settings" -> onSettings()
            "@manage" -> onManage()
            else -> (apps + catalog).firstOrNull { it.id == id.removePrefix("@hero:") }?.let {
                if (held) onMenu(it) else onActivate(it, mark())
            }
        }
    }
    LaunchedEffect(ids, epoch) {
        val saved = memory.mark.id
        val target = if (saved in ids) saved else fallbackFocus(memory.ids, saved, dockIds + bodyIds)
        memory.ids = dockIds + bodyIds
        aim(target)
    }
    LaunchedEffect(grid, cursor, expanded) {
        snapshotFlow { mark() }.collect { memory.mark = it; model.bookmark("home", it) }
    }
    DisposableEffect(Unit) { onDispose { moveJob?.cancel(); memory.mark = mark() } }
    BackHandler(expanded) {
        val col = bodyIds.indexOf(cursor).coerceAtLeast(0) % 6
        aim(dockIds[col.coerceAtMost(dockIds.lastIndex)])
    }
    fun remote(event: KeyEvent): Boolean {
        if (confirmation.handle(event, cursor, actualFocus, ::activate)) return true
        if (event.type != KeyEventType.KeyDown || event.key !in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)) return false
        val hero = heroes.indexOf(cursor)
        val d = dockIds.indexOf(cursor)
        val b = bodyIds.indexOf(cursor)
        when {
            hero >= 0 -> when (event.key) {
                Key.DirectionLeft -> aim(heroes[(hero - 1).coerceAtLeast(0)])
                Key.DirectionRight -> aim(heroes[(hero + 1).coerceAtMost(heroes.lastIndex)])
                Key.DirectionDown -> aim(dockIds[hero])
            }
            d >= 0 -> when (event.key) {
                Key.DirectionLeft -> aim(dockIds[(d - 1).coerceAtLeast(0)])
                Key.DirectionRight -> aim(dockIds[(d + 1).coerceAtMost(dockIds.lastIndex)])
                Key.DirectionUp -> if (heroes.isNotEmpty()) aim(heroes[d.coerceAtMost(heroes.lastIndex)])
                Key.DirectionDown -> aim(bodyIds[d.coerceAtMost(bodyIds.lastIndex)])
            }
            b >= 0 -> when (event.key) {
                Key.DirectionLeft -> if (b % 6 > 0) aim(bodyIds[b - 1])
                Key.DirectionRight -> if (b % 6 < 5 && b < bodyIds.lastIndex) aim(bodyIds[b + 1])
                Key.DirectionUp -> if (b >= 6) aim(bodyIds[b - 6]) else aim(dockIds[(b % 6).coerceAtMost(dockIds.lastIndex)])
                Key.DirectionDown -> if (b / 6 < bodyIds.lastIndex / 6) aim(bodyIds[(b + 6).coerceAtMost(bodyIds.lastIndex)])
            }
        }
        return true
    }
    BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent(::remote).testTag("page:home")) {
        val edge = config.safeInset.dp
        val tileWidth = (maxWidth - edge * 2 - 27.dp * 5) / 6
        val heroWidth = (maxWidth - edge * 2 - 28.dp) / 2
        val heroHeight = heroWidth * 9f / 16f
        Row(Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = edge).graphicsLayer { alpha = shelfAlpha }) { LumaClock() }
        Row(Modifier.offset(y = 43.dp).graphicsLayer { alpha = shelfAlpha; translationY = -350.dp.toPx() * (1f - shelfAlpha) }
            .horizontalScroll(shelf).padding(start = edge, end = edge, top = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            dock.forEachIndexed { index, app ->
                Column(Modifier.width(heroWidth)) {
                    Text(app.displayName, color = Ink.copy(alpha = .85f), fontSize = 21.sp, maxLines = 1)
                    Spacer(Modifier.height(12.dp))
                    HeroCard(app, model.repository, revision, requesters.getValue(heroes[index]), heroHeight,
                        config.reducedMotion, onFocus = { actualFocus = heroes[index] }, onClick = { activate(heroes[index]) },
                        onLongClick = { activate(heroes[index], true) })
                }
            }
        }
        val trayShape = RoundedCornerShape(30.dp)
        Box(Modifier.offset { IntOffset((edge - 18.dp).roundToPx(), (dockTop - 19.dp).roundToPx()) }.width(maxWidth - edge * 2 + 36.dp).height(tileWidth * .6f + 44.dp)
            .background(if (config.backgroundBlur) Brush.linearGradient(listOf(Color(0x78102430), Color(0x4D203A42), Color(0x73172C33)))
                else Brush.linearGradient(listOf(Color(0xF0233541), Color(0xF02C4249))), trayShape)
            .border(.8.dp, Color.White.copy(alpha = .10f), trayShape))
        Row(Modifier.offset { IntOffset(edge.roundToPx(), dockTop.roundToPx()) }, horizontalArrangement = Arrangement.spacedBy(27.dp)) {
            dock.forEach { app ->
                Box(Modifier.width(tileWidth)) {
                    AppTile(app, model.repository, revision, config.reducedMotion, app.id in config.favorites,
                        requesters.getValue(app.id), false, onFocus = { actualFocus = app.id }, onKey = { false },
                        onClick = { activate(app.id) }, onLongClick = { activate(app.id, true) }, alwaysLabel = false)
                }
            }
            Box(Modifier.width(tileWidth)) {
                HomeAction("全部应用", "footer-primary", 0, requesters.getValue("@footer"), config.reducedMotion,
                    onFocus = { actualFocus = "@footer" }, onClick = { activate("@footer") })
            }
        }
        LazyVerticalGrid(GridCells.Fixed(6), state = grid,
            modifier = Modifier.offset { IntOffset(0, bodyTop.roundToPx()) }.height((maxHeight - 162.dp).coerceAtLeast(1.dp)).fillMaxWidth().testTag("app-grid"),
            contentPadding = PaddingValues(start = edge, end = edge, top = 22.dp, bottom = 38.dp),
            horizontalArrangement = Arrangement.spacedBy(27.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(body, key = { it.id }) { app ->
                AppTile(app, model.repository, revision, config.reducedMotion, app.id in config.favorites,
                    requesters.getValue(app.id), false, onFocus = { actualFocus = app.id }, onKey = { false },
                    onClick = { activate(app.id) }, onLongClick = { activate(app.id, true) }, alwaysLabel = false)
            }
            item(key = "@settings") {
                HomeAction("设置", "settings", 1, requesters.getValue("@settings"), config.reducedMotion,
                    onFocus = { actualFocus = "@settings" }, onClick = { activate("@settings") })
            }
            item(key = "@manage") {
                HomeAction("管理应用", "manage", 2, requesters.getValue("@manage"), config.reducedMotion,
                    onFocus = { actualFocus = "@manage" }, onClick = { activate("@manage") })
            }
        }
    }
}

@Composable
private fun HeroCard(app: AppEntry, repository: AppRepository, revision: Int, requester: FocusRequester,
    height: androidx.compose.ui.unit.Dp, reduced: Boolean, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val art by produceState<Artwork?>(null, app.id, revision) { value = repository.artwork(app) }
    val shape = RoundedCornerShape(12.dp)
    Surface(onClick = onClick, onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth().height(height).focusRequester(requester).onFocusChanged { if (it.isFocused) onFocus() }
            .semantics { contentDescription = app.displayName }.testTag("hero:${app.id}"), shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = art?.let { Color(it.background) } ?: Panel),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (reduced) 1.01f else 1.035f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(.8.dp, Color.White.copy(alpha = .6f)), shape = shape))) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            art?.let { artwork -> Image(artwork.bitmap.asImageBitmap(), null,
                if (artwork.banner) Modifier.fillMaxSize() else Modifier.fillMaxHeight(.7f).aspectRatio(1f),
                contentScale = if (artwork.banner) ContentScale.Crop else ContentScale.Fit) }
                ?: Text(app.label.take(1), color = Ink, fontSize = 56.sp)
        }
    }
}
