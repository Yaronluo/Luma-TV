package io.lumatv.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Both layouts share columns and one animation progress, so the rows travel as one library. */
private val LibraryRowTop = 52.dp
private val BottomInset = 24.dp
private val GridGap = 23.dp
private const val LibraryWidthFraction = .85f
private val RowGap = 22.dp
private val DockAspect = 5f / 3f
private val GlassInset = 12.dp
private val GlassShape = RoundedCornerShape(23.dp)

/**
 * The favourite row rests over a fixed wallpaper, then travels with the library when
 * opened. One bounded progress drives both rows and the tray fade, including reversals.
 * The collapsed row has a frosted glass tray; it fades out as the library opens.
 */
@Composable
internal fun HomePage(apps: List<AppEntry>, catalog: List<AppEntry>, config: LumaConfig, model: LauncherModel, revision: Int,
    memory: PageMemory, epoch: Int, onAll: () -> Unit, onSettings: () -> Unit, onManage: () -> Unit,
    onActivate: (AppEntry, Bookmark) -> Unit, onMenu: (AppEntry) -> Unit) {
    val dock = apps.take(5)
    val body = (apps.drop(5) + catalog).distinctBy { it.id }.filter { app -> dock.none { it.id == app.id } }
    val dockIds = dock.map { it.id } + "@footer"
    val bodyIds = body.map { it.id } + listOf("@settings", "@manage")
    val ids = dockIds + bodyIds
    val grid = rememberLazyGridState(memory.mark.index.coerceAtMost(bodyIds.lastIndex), memory.mark.offset)
    val scope = rememberCoroutineScope()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    ids.forEach { requesters.getOrPut(it) { FocusRequester() } }
    var cursor by remember { mutableStateOf(memory.mark.id) }
    var actualFocus by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(memory.mark.expanded || memory.mark.id in bodyIds) }
    var moveJob by remember { mutableStateOf<Job?>(null) }
    val confirmation = remember { DeferredConfirmation() }
    val expansion by animateFloatAsState(if (expanded) 1f else 0f,
        tween(if (config.reducedMotion) 0 else if (expanded) 380 else 320, easing = FastOutSlowInEasing), label = "home-expansion")
    val focusColumn = dockIds.indexOf(cursor).takeIf { it >= 0 }
        ?: bodyIds.indexOf(cursor).coerceAtLeast(0).rem(6)
    val backdropX by animateFloatAsState(if (config.reducedMotion) 0f else (2.5f - focusColumn) * 3.2f,
        tween(if (config.reducedMotion) 0 else 1000, easing = FastOutSlowInEasing), label = "wallpaper-parallax-x")
    val backdropY by animateFloatAsState(if (config.reducedMotion) 0f else if (expanded) -6f else 4f,
        tween(if (config.reducedMotion) 0 else 800, easing = FastOutSlowInEasing), label = "wallpaper-parallax-y")
    val context = LocalContext.current
    val wallpaper by produceState<android.graphics.Bitmap?>(null, config.background, config.wallpaperRevision, config.backgroundBlur) {
        value = BackgroundImages.load(context, config)
    }

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
        // Old stage bookmarks return to the corresponding visible app, never an invisible target.
        val saved = memory.mark.id.removePrefix("@hero:")
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
        val d = dockIds.indexOf(cursor)
        val b = bodyIds.indexOf(cursor)
        when {
            d >= 0 -> when (event.key) {
                Key.DirectionLeft -> aim(dockIds[(d - 1).coerceAtLeast(0)])
                Key.DirectionRight -> aim(dockIds[(d + 1).coerceAtMost(dockIds.lastIndex)])
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
        val libraryWidth = (maxWidth - config.safeInset.dp * 2) * LibraryWidthFraction
        val edge = (maxWidth - libraryWidth) / 2
        val columnWidth = (libraryWidth - GridGap * 5) / 6
        val slots = dock.size + 1
        val rowHeight = columnWidth / DockAspect
        val collapsedTop = maxHeight - rowHeight - BottomInset
        val firstRowTop = collapsedTop + (LibraryRowTop - collapsedTop) * expansion
        // The body includes 16dp of top padding for focused-tile overflow.
        val libraryTop = LibraryRowTop + rowHeight + RowGap - 16.dp
        // Only at zero opacity park the viewport fully outside the window. During motion its
        // first row stays exactly one row pitch below the favourites, with no stretching gap.
        val bodyTop = if (expansion == 0f) maxHeight + 32.dp else firstRowTop + rowHeight + RowGap - 16.dp

        val glassLeft = edge - GlassInset
        val glassWidth = columnWidth * slots + GridGap * (slots - 1) + GlassInset * 2
        val glassHeight = rowHeight + GlassInset * 2
        // Sample at the resting tray position, not on every animation frame.
        val frost = remember(wallpaper, maxWidth, maxHeight, glassLeft, glassWidth, glassHeight, collapsedTop) {
            wallpaper?.let { bitmap ->
                frostCrop(bitmap, glassLeft.value / maxWidth.value,
                    (collapsedTop - GlassInset).value / maxHeight.value,
                    glassWidth.value / maxWidth.value, glassHeight.value / maxHeight.value)
            }
        }

        // Move and zoom only a cached bitmap layer; never recompute Gaussian blur per frame.
        WallpaperLayers(wallpaper, config, Modifier.fillMaxSize().graphicsLayer {
            scaleX = if (config.reducedMotion) 1f else 1.045f + .035f * expansion
            scaleY = scaleX
            translationX = backdropX.dp.toPx()
            translationY = backdropY.dp.toPx()
        }, wash = .22f)
        // Keep the blurred light in the upper stage and give the floating dock a quiet base.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Transparent, .40f to Color.Transparent,
            .68f to Color(0xFF030C19).copy(alpha = .40f),
            1f to Color(0xFF030A14).copy(alpha = .88f))))
        Box(Modifier.fillMaxSize().background(Color(0xFF04070B).copy(alpha = .10f * expansion)))

        // 3. The frosted tray follows the row, fading away as the library opens. It is a
        // decoration behind the tiles and never changes their geometry or receives focus.
        if (expansion < 1f) {
            Box(Modifier.offset(x = glassLeft, y = firstRowTop - GlassInset)
                .width(glassWidth).height(glassHeight).graphicsLayer { alpha = 1f - expansion }
                .shadow(18.dp, GlassShape, clip = false).clip(GlassShape)
                .border(.75.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .12f),
                    Color.White.copy(alpha = .025f), Color.White.copy(alpha = .035f))), GlassShape)
                .testTag("home-dock-glass")) {
                val slice = frost
                if (slice != null) {
                    Image(slice.asImageBitmap(), null, Modifier.matchParentSize().graphicsLayer { alpha = .48f },
                        contentScale = ContentScale.FillBounds)
                    Box(Modifier.matchParentSize().background(Color(0xFF13263B).copy(alpha = .13f)))
                } else {
                    Box(Modifier.matchParentSize().background(Color(0xFF13263B).copy(alpha = .20f)))
                }
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                    0f to Color.White.copy(alpha = .055f), .38f to Color.White.copy(alpha = .015f),
                    1f to Color.Transparent)))
            }
        }

        // The first row leads the library as one group, with no size interpolation.
        Box(Modifier.offset(y = firstRowTop).padding(horizontal = edge)
            .fillMaxWidth().height(rowHeight).testTag("home-first-row")) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(GridGap),
                verticalAlignment = Alignment.CenterVertically) {
                dock.forEach { app ->
                    Box(Modifier.width(columnWidth)) {
                        AppTile(app, model.repository, revision, config.reducedMotion, app.id in config.favorites,
                            requesters.getValue(app.id), false,
                            onFocus = { actualFocus = app.id }, onKey = { false },
                            onClick = { activate(app.id) }, onLongClick = { activate(app.id, true) },
                            name = TileLabel.None, aspect = DockAspect, banner = true, softFocus = true)
                    }
                }
                Box(Modifier.width(columnWidth)) {
                    HomeAction("全部应用", "footer-primary", 0, requesters.getValue("@footer"), config.reducedMotion,
                        onFocus = { actualFocus = "@footer" }, onClick = { activate("@footer") },
                        name = TileLabel.None, aspect = DockAspect, softFocus = true)
                }
            }
        }

        // A separate caption does not move the tiles or consume the bottom safe inset.
        val captionColumn = dockIds.indexOf(actualFocus)
        if (captionColumn >= 0 && cursor == actualFocus) {
            val name = dock.getOrNull(captionColumn)?.displayName ?: "全部应用"
            Box(Modifier.offset(x = edge, y = firstRowTop - 36.dp)
                .width(libraryWidth).height(20.dp).testTag("dock-caption"), contentAlignment = Alignment.CenterStart) {
                AnimatedContent(name, transitionSpec = {
                    (fadeIn(tween(if (config.reducedMotion) 0 else 180)) +
                        slideInVertically(tween(if (config.reducedMotion) 0 else 220)) { it / 3 }) togetherWith
                        fadeOut(tween(if (config.reducedMotion) 0 else 90))
                }, contentAlignment = Alignment.CenterStart, label = "dock-caption-change") { title ->
                    Text(title, color = Ink.copy(alpha = .90f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start)
                }
            }
        }

        // 4. Keep the grid mounted to preserve scroll/focus readiness, but fully offscreen and
        // transparent while collapsed: no next-row preview, focused overflow or shadow can peek out.
        LazyVerticalGrid(GridCells.Fixed(6), state = grid,
            modifier = Modifier.offset(y = bodyTop).height((maxHeight - libraryTop).coerceAtLeast(1.dp))
                .graphicsLayer {
                    alpha = expansion
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .fillMaxWidth().testTag("app-grid"),
            contentPadding = PaddingValues(start = edge, end = edge, top = 16.dp, bottom = 38.dp),
            horizontalArrangement = Arrangement.spacedBy(GridGap), verticalArrangement = Arrangement.spacedBy(RowGap)) {
            itemsIndexed(body, key = { _, app -> app.id }) { index, app ->
                LibraryReveal(index, expansion, config.reducedMotion) {
                    AppTile(app, model.repository, revision, config.reducedMotion, app.id in config.favorites,
                        requesters.getValue(app.id), false, onFocus = { actualFocus = app.id }, onKey = { false },
                        onClick = { activate(app.id) }, onLongClick = { activate(app.id, true) },
                        name = TileLabel.Focused, star = true, aspect = DockAspect, banner = true, softFocus = true)
                }
            }
            item(key = "@settings") {
                LibraryReveal(body.size, expansion, config.reducedMotion) {
                    HomeAction("设置", "settings", 1, requesters.getValue("@settings"), config.reducedMotion,
                        onFocus = { actualFocus = "@settings" }, onClick = { activate("@settings") },
                        name = TileLabel.Focused, aspect = DockAspect, softFocus = true)
                }
            }
            item(key = "@manage") {
                LibraryReveal(body.size + 1, expansion, config.reducedMotion) {
                    HomeAction("管理应用", "manage", 2, requesters.getValue("@manage"), config.reducedMotion,
                        onFocus = { actualFocus = "@manage" }, onClick = { activate("@manage") },
                        name = TileLabel.Focused, aspect = DockAspect, softFocus = true)
                }
            }
        }
    }
}

/** A brief cascade within the shared moving viewport; layout and focus coordinates stay stable. */
@Composable
private fun LibraryReveal(index: Int, progress: Float, reduced: Boolean, content: @Composable () -> Unit) {
    val delay = ((index / 6) * .055f + (index % 6) * .018f).coerceAtMost(.18f)
    val reveal = if (reduced) 1f else ((progress - delay) / (1f - delay)).coerceIn(0f, 1f)
    Box(Modifier.graphicsLayer {
        alpha = reveal
        // Auto alpha creates a cell-sized offscreen buffer and cuts off the focused tile's
        // scale, lift and shadow. Modulate draw commands so those can overflow the cell.
        compositingStrategy = CompositingStrategy.ModulateAlpha
        translationY = 12.dp.toPx() * (1f - reveal)
        scaleX = .965f + .035f * reveal
        scaleY = scaleX
    }) { content() }
}

/** A tiny crop stretched into a frosted backdrop, matching the original dock treatment. */
private fun frostCrop(source: android.graphics.Bitmap, fx: Float, fy: Float, fw: Float, fh: Float): android.graphics.Bitmap? =
    runCatching {
        val tiny = android.graphics.Bitmap.createBitmap(160, 90, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(tiny).drawBitmap(source, null, android.graphics.Rect(0, 0, 160, 90),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        val x = (fx * 160).toInt().coerceIn(0, 159)
        val y = (fy * 90).toInt().coerceIn(0, 89)
        val width = (fw * 160).toInt().coerceIn(1, 160 - x)
        val height = (fh * 90).toInt().coerceIn(1, 90 - y)
        val patch = android.graphics.Bitmap.createBitmap(tiny, x, y, width, height)
        if (patch !== tiny) tiny.recycle()
        patch
    }.getOrNull()
