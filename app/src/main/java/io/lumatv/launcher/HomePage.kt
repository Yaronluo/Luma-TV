package io.lumatv.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Dock geometry: a wide cover per favourite, the same 5:3 shape whether it floats or joins the grid. */
private val DockTileWidth = 110.dp
private val DockTileHeight = 66.dp
private val DockGap = 9.dp
private val DockInset = 13.dp
private val DockAspect = 5f / 3f
private val PanelShape = RoundedCornerShape(26.dp)

/**
 * tvOS home, two states on one screen:
 *  - collapsed: the selected channel owns the whole frame and the dock floats over it;
 *  - expanded: the dock row lines up with a six-column app grid.
 * The screen is deliberately empty of chrome: no panels, no captions, no clock.
 */
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
    val scope = rememberCoroutineScope()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    ids.forEach { requesters.getOrPut(it) { FocusRequester() } }
    var cursor by remember { mutableStateOf(memory.mark.id) }
    var actualFocus by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(memory.mark.expanded || memory.mark.id in bodyIds) }
    // The stage previews the app under the cursor: the dock and the shelf share one channel index.
    var channel by remember {
        mutableIntStateOf(
            (when (memory.mark.id) {
                in heroes -> heroes.indexOf(memory.mark.id)
                in dockIds -> dockIds.indexOf(memory.mark.id).coerceAtMost(dock.lastIndex)
                else -> 0
            }).coerceIn(0, dock.lastIndex.coerceAtLeast(0))
        )
    }
    var moveJob by remember { mutableStateOf<Job?>(null) }
    val confirmation = remember { DeferredConfirmation() }
    val motion = if (config.reducedMotion) snap<Dp>() else spring(dampingRatio = 1f, stiffness = 380f)
    val dockTop by animateDpAsState(if (expanded) 52.dp else 360.dp, motion, label = "home-dock")
    // Expanded, the dock row and the grid share one pitch: they read as a single library.
    val bodyTop by animateDpAsState(if (expanded) 131.dp else 504.dp, motion, label = "home-grid")
    // Expanded, the channel leaves the frame entirely: the library sits on the wallpapered home.
    val stageTone by animateFloatAsState(if (expanded) 0f else 1f, tween(if (config.reducedMotion) 0 else 320), label = "home-stage")
    val dockTone by animateFloatAsState(if (!expanded && cursor in heroes) .24f else 0f,
        tween(if (config.reducedMotion) 0 else 220), label = "home-dock-tone")
    val stageScale by animateFloatAsState(if (!expanded && cursor in heroes) 1.012f else 1f,
        tween(if (config.reducedMotion) 0 else 320), label = "home-stage-scale")
    val tileWidthDp by animateDpAsState(if (expanded) 121.5.dp else DockTileWidth, motion, label = "home-tile")
    val gapDp by animateDpAsState(if (expanded) 27.dp else DockGap, motion, label = "home-gap")
    val insetDp by animateDpAsState(if (expanded) 0.dp else DockInset, motion, label = "home-inset")

    // The stage belongs to the highlighted channel, not to the dock: exactly the tvOS top shelf.
    val featured = if (dock.isEmpty()) null else dock[channel.coerceIn(0, dock.lastIndex)]
    var front by remember { mutableStateOf<Stage?>(null) }
    var rear by remember { mutableStateOf<Stage?>(null) }
    val fade = remember { Animatable(1f) }
    val loaded = remember { mutableMapOf<String, Artwork?>() }
    LaunchedEffect(featured?.id, revision) {
        val app = featured ?: return@LaunchedEffect
        if (front?.key == app.id) return@LaunchedEffect
        val art = if (loaded.containsKey(app.id)) loaded[app.id]
            else model.repository.artwork(app, preferBanner = true).also { loaded[app.id] = it }
        rear = front
        front = Stage(app.id, art)
        fade.snapTo(0f)
        fade.animateTo(1f, tween(if (config.reducedMotion) 0 else 460))
        rear = null
    }

    fun mark() = Bookmark(cursor, grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset, memory.lastCard, expanded)
    fun aim(id: String) {
        cursor = id
        expanded = id in bodyIds
        val hero = heroes.indexOf(id)
        val slot = dockIds.indexOf(id)
        if (hero >= 0) channel = hero
        else if (slot >= 0) channel = slot.coerceIn(0, dock.lastIndex.coerceAtLeast(0))
        if (!id.startsWith("@")) memory.lastCard = id
        memory.mark = mark()
        moveJob?.cancel()
        moveJob = scope.launch {
            val index = bodyIds.indexOf(id)
            if (index >= 0) {
                val bounds = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                if (bounds == null || bounds.offset.y < 16 || bounds.offset.y + bounds.size.height > grid.layoutInfo.viewportEndOffset - 20)
                    grid.scrollToItem(index / 6 * 6)
            }
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
        val columnWidth = (maxWidth - edge * 2 - 27.dp * 5) / 6
        val slots = dock.size + 1
        val heroWidth = (maxWidth - edge * 2 - 28.dp * (slots - 1).coerceAtLeast(0)) / slots
        val heroHeight = heroWidth * 9f / 16f
        val panelWidth = tileWidthDp * slots + gapDp * (slots - 1) + insetDp * 2
        val panelHeight = tileWidthDp / DockAspect + insetDp * 2
        val panelLeft = ((maxWidth - panelWidth) / 2).coerceAtLeast(0.dp)

        val stageArt = (front ?: rear)?.art?.takeIf { it.banner }
        val frost = remember(stageArt, maxWidth.value, maxHeight.value) {
            stageArt?.let { art ->
                val slots = dock.size + 1
                val width = DockTileWidth * slots + DockGap * (slots - 1) + DockInset * 2
                val height = DockTileWidth / DockAspect + DockInset * 2
                val left = (maxWidth - width) / 2
                frostCrop(art.bitmap, left.value / maxWidth.value, 360f / maxHeight.value,
                    width.value / maxWidth.value, height.value / maxHeight.value)
            }
        }

        // Bright banners get a proportional ink wash so a white logo never turns the stage into a lightbox.
        val inkTarget = remember(stageArt) { stageArt?.let { stageInk(it.bitmap) } ?: .12f }
        val stageInk by animateFloatAsState(inkTarget, tween(if (config.reducedMotion) 0 else 320), label = "stage-ink")

        // 1. The cinematic stage: one channel owns the frame, crossfaded when the shelf moves.
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = stageTone
            scaleX = stageScale
            scaleY = stageScale
        }) {
            rear?.let { StageLayer(it) }
            front?.let { StageLayer(it, Modifier.graphicsLayer { alpha = fade.value }) }
            Box(Modifier.fillMaxSize().background(Color(0xFF04070B).copy(alpha = stageInk)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color(0x33040A10), .24f to Color(0x08040A10), .56f to Color(0x10040A10),
                .84f to Color(0x44040A10), 1f to Color(0x8C040A10))))
            // A quiet vignette keeps flat logo banners from reading as a pasted-on splash screen.
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(
                listOf(Color(0x0002080D), Color(0x2402080D)))))
        }

        // 1b. Expanded, the channel recedes behind the wallpaper the app already owns: the library
        // belongs to the home, not to the app you were just previewing.

        // 2. The top shelf itself is invisible: focus lives on the stage, like tvOS.
        Row(Modifier.offset(y = 64.dp).padding(start = edge, end = edge, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            dock.forEachIndexed { index, app ->
                Surface(onClick = { activate(heroes[index]) }, onLongClick = { activate(heroes[index], true) },
                    modifier = Modifier.width(heroWidth).height(heroHeight).focusRequester(requesters.getValue(heroes[index]))
                        .onFocusChanged { if (it.isFocused) { actualFocus = heroes[index]; channel = index } }
                        .semantics { contentDescription = app.displayName }.testTag("hero:${app.id}"),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    border = quietBorder(), glow = quietGlow()) { }
            }
        }

        // 3. The floating dock. Collapsed it is a glass shelf; expanded it only becomes the grid's
        // first row, so it carries no plate, no shadow and no fill behind the tiles.
        Box(Modifier.offset { IntOffset(panelLeft.roundToPx(), dockTop.roundToPx()) }
            .width(panelWidth).height(panelHeight)) {
            if (!expanded) {
                Box(Modifier.matchParentSize().shadow(26.dp, PanelShape, clip = false)
                    .clip(PanelShape)) {
                    // Collapsed the panel is a blurred slice of the artwork darkened to the same level as the
                    // stage under it, so it reads as glass rather than a plate lying on the picture.
                    val slice = frost
                    if (slice != null) {
                        Image(slice.bitmap.asImageBitmap(), null, Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds)
                        val glassInk = 1f - (1f - .34f) * (1f - stageInk)
                        Box(Modifier.matchParentSize().background(Color(0xFF04070B).copy(alpha = glassInk)))
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                            0f to Color.White.copy(alpha = .10f), .38f to Color.White.copy(alpha = .02f),
                            1f to Color.Transparent)))
                    } else {
                        Box(Modifier.matchParentSize().background(Color(0xFF04070B).copy(alpha = .42f))
                            .background(Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = .10f), Color.White.copy(alpha = .02f)))))
                    }
                }
            }
            Row(Modifier.fillMaxSize().padding(insetDp.coerceAtLeast(0.dp)), horizontalArrangement = Arrangement.spacedBy(gapDp.coerceAtLeast(0.dp)),
                verticalAlignment = Alignment.CenterVertically) {
                dock.forEachIndexed { index, app ->
                    Box(Modifier.width(tileWidthDp)) {
                        AppTile(app, model.repository, revision, config.reducedMotion, app.id in config.favorites,
                            requesters.getValue(app.id), false,
                            onFocus = { actualFocus = app.id; channel = index }, onKey = { false },
                            onClick = { activate(app.id) }, onLongClick = { activate(app.id, true) },
                            name = TileLabel.None, aspect = DockAspect, banner = true)
                    }
                }
                Box(Modifier.width(tileWidthDp)) {
                    HomeAction("全部应用", "footer-primary", 0, requesters.getValue("@footer"), config.reducedMotion,
                        onFocus = { actualFocus = "@footer" }, onClick = { activate("@footer") },
                        name = TileLabel.None, aspect = DockAspect)
                }
            }
            // When the stage holds focus the dock recedes, but its icons stay opaque.
            if (dockTone > 0f) Box(Modifier.matchParentSize().clip(PanelShape)
                .background(Color(0xFF05080D).copy(alpha = dockTone)))
        }

        // 4. The library: a sliver peeks under the dock, a full six-column grid once expanded.
        LazyVerticalGrid(GridCells.Fixed(6), state = grid,
            modifier = Modifier.offset { IntOffset(0, bodyTop.roundToPx()) }.height((maxHeight - bodyTop).coerceAtLeast(1.dp))
                .fillMaxWidth().testTag("app-grid"),
            contentPadding = PaddingValues(start = edge, end = edge, top = 16.dp, bottom = 38.dp),
            horizontalArrangement = Arrangement.spacedBy(27.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            items(body, key = { it.id }) { app ->
                AppTile(app, model.repository, revision, config.reducedMotion, app.id in config.favorites,
                    requesters.getValue(app.id), false, onFocus = { actualFocus = app.id }, onKey = { false },
                    onClick = { activate(app.id) }, onLongClick = { activate(app.id, true) },
                    name = TileLabel.Focused, star = true, aspect = DockAspect, banner = true)
            }
            item(key = "@settings") {
                HomeAction("设置", "settings", 1, requesters.getValue("@settings"), config.reducedMotion,
                    onFocus = { actualFocus = "@settings" }, onClick = { activate("@settings") },
                    name = TileLabel.Focused, aspect = DockAspect)
            }
            item(key = "@manage") {
                HomeAction("管理应用", "manage", 2, requesters.getValue("@manage"), config.reducedMotion,
                    onFocus = { actualFocus = "@manage" }, onClick = { activate("@manage") },
                    name = TileLabel.Focused, aspect = DockAspect)
            }
        }
    }
}

/** One channel on the stage: its banner fills the frame, an icon-only app gets a quiet colour wash. */
private class Stage(val key: String, val art: Artwork?)

/** Frost is the stage squeezed to a postage stamp and cropped to the dock; the mean decides how much glass ink it needs. */
private class Frost(val bitmap: android.graphics.Bitmap, val darken: Float)

/**
 * How much ink a full-frame banner needs to stop reading as a blown-out light box.
 * Flat white logo banners (YouTube Music, Play Store) otherwise wash the whole stage out.
 */
private fun stageInk(source: android.graphics.Bitmap): Float = runCatching {
    val w = 64
    val h = 36
    val tiny = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(tiny).drawBitmap(source, null, android.graphics.Rect(0, 0, w, h),
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    val pixels = IntArray(w * h)
    tiny.getPixels(pixels, 0, w, 0, 0, w, h)
    var sum = 0
    for (p in pixels) {
        sum += ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    }
    val mean = (sum / pixels.size) / 255f
    (.22f + ((mean - .25f).coerceAtLeast(0f)) * .70f).coerceIn(.22f, .66f)
}.getOrDefault(.22f)

private fun frostCrop(source: android.graphics.Bitmap, fx: Float, fy: Float, fw: Float, fh: Float): Frost? =
    runCatching {
        val tinyWidth = 160
        val tinyHeight = 90
        val tiny = android.graphics.Bitmap.createBitmap(tinyWidth, tinyHeight, android.graphics.Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        android.graphics.Canvas(tiny).drawBitmap(source, null, android.graphics.Rect(0, 0, tinyWidth, tinyHeight), paint)
        val x = (fx * tinyWidth).toInt().coerceIn(0, tinyWidth - 1)
        val y = (fy * tinyHeight).toInt().coerceIn(0, tinyHeight - 1)
        val w = (fw * tinyWidth).toInt().coerceIn(1, tinyWidth - x)
        val h = (fh * tinyHeight).toInt().coerceIn(1, tinyHeight - y)
        val patch = android.graphics.Bitmap.createBitmap(tiny, x, y, w, h)
        // Bright artwork needs a heavier tint or the glass dissolves into the picture.
        val pixels = IntArray(w * h)
        patch.getPixels(pixels, 0, w, 0, 0, w, h)
        var sum = 0
        for (p in pixels) {
            sum += ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        val mean = (sum / pixels.size.coerceAtLeast(1)) / 255f
        val darken = ((mean - .18f) * 1.15f + .30f).coerceIn(.30f, .60f)
        Frost(patch, darken)
    }.getOrNull()

@Composable
private fun StageLayer(stage: Stage, modifier: Modifier = Modifier) {
    val art = stage.art ?: return
    val image = remember(art) { art.bitmap.asImageBitmap() }
    Box(modifier.fillMaxSize()) {
        if (art.banner) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                listOf(Color(art.background).copy(alpha = .34f), Color(0xFF05080D).copy(alpha = .92f)))))
            Image(image, null, Modifier.fillMaxHeight(.40f).aspectRatio(1f).align(Alignment.Center),
                contentScale = ContentScale.Fit)
        }
    }
}
