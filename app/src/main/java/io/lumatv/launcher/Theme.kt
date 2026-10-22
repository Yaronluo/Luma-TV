package io.lumatv.launcher

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.*

val Ink = Color(0xFFF5F7F8)
val Muted = Color(0xFFD1DDE1)
val Accent = Color(0xFFD8E9ED)
val Panel = Color(0xFF354A58)

/** tvOS-like continuous-corner icon: one shared squircle for apps, actions and system tiles. */
val TileShape = RoundedCornerShape(22.dp)

/** Where a tile shows its name: the home shelf is unlabeled like tvOS, grids stay readable. */
enum class TileLabel { None, Focused, Always }

@Composable
fun LumaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color(0xFF26343E),
        background = Color(0xFF070B10), surface = Panel, onSurface = Ink), content = content)
}

/**
 * Wallpaper plus the cinematic wash that makes the home screen read like a TV stage:
 * a light scrim at the top for the clock, a deep fade at the bottom so floating icons
 * never sit on a busy picture. The wash is independent of backgroundBlur.
 */
@Composable
fun Atmosphere(config: LumaConfig, modifier: Modifier = Modifier, wash: Float = 1f) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, config.background, config.wallpaperRevision, config.backgroundBlur) {
        value = BackgroundImages.load(context, config)
    }
    val ease = wash.coerceIn(0f, 1f)
    fun ink(alpha: Float) = Color(0xFF070B10).copy(alpha = alpha * ease)
    Box(modifier.background(Color(0xFF070B10))) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to ink(.62f), 0.16f to ink(.40f), 0.52f to ink(.28f),
            0.74f to ink(.66f), 1f to ink(.95f))))
        if (config.brightness != 1) Box(Modifier.fillMaxSize().background(
            if (config.brightness == 0) Color.Black.copy(alpha = .26f) else Color.White.copy(alpha = .07f)))
    }
}

/**
 * tv-material draws a light focus glow and a hairline border by default. The tvOS look wants
 * neither: focus reads from scale, lift and shadow alone.
 */
@Composable
internal fun quietBorder() = ClickableSurfaceDefaults.border(
    Border.None, Border.None, Border.None, Border.None, Border.None)

@Composable
internal fun quietGlow() = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None)

@Composable
fun LumaButton(
    text: String, tag: String, modifier: Modifier = Modifier, requester: FocusRequester? = null,
    active: Boolean = false, flat: Boolean = false, onFocus: () -> Unit = {}, onKey: (KeyEvent) -> Boolean = { false },
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = onClick,
        modifier = modifier.then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onPreviewKeyEvent(onKey).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .zIndex(if (focused) 2f else 0f).shadow(if (focused) 14.dp else 0.dp, RoundedCornerShape(12.dp), clip = false).testTag(tag),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = if (active) .14f else if (flat) 0f else .07f),
            contentColor = Ink, focusedContainerColor = Ink, focusedContentColor = Color(0xFF1D2733)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        border = quietBorder(), glow = quietGlow()) {
        Box(Modifier.defaultMinSize(minHeight = 44.dp).padding(horizontal = 18.dp, vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
            Text(text, fontSize = 17.sp, fontWeight = if (focused || active) FontWeight.Medium else FontWeight.Normal)
        }
    }
}

/**
 * A tvOS tile: one flat colour field, the mark sitting on it, and nothing else — no outline, no
 * glass sheen, no shadow at rest. Focus alone lifts it, lights it and throws a soft shadow, and the
 * motion is a spring so it settles like a physical object instead of sliding to a stop.
 */
@Composable
private fun IconTile(
    label: String, tag: String, requester: FocusRequester, reduced: Boolean, background: Color,
    name: TileLabel, moving: Boolean = false, onFocus: () -> Unit,
    onKey: (KeyEvent) -> Boolean = { false }, onClick: () -> Unit, onLongClick: (() -> Unit)? = null,
    aspect: Float = 1f, glass: Boolean = false, content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val springFloat = spring<Float>(dampingRatio = .74f, stiffness = if (reduced) 2600f else 430f)
    val springDp = spring<Dp>(dampingRatio = .74f, stiffness = if (reduced) 2600f else 430f)
    val scale by animateFloatAsState(if (focused) (if (reduced) 1.012f else 1.075f) else 1f, springFloat, label = "icon-scale")
    val lift by animateFloatAsState(if (focused && !reduced) -4f else 0f, springFloat, label = "icon-lift")
    val elevation by animateDpAsState(if (focused) 20.dp else 0.dp, springDp, label = "icon-elevation")
    val light by animateFloatAsState(if (focused) 1f else 0f, springFloat, label = "icon-light")
    val nameAlpha by animateFloatAsState(when (name) {
        TileLabel.Always -> 1f
        TileLabel.Focused -> if (focused) 1f else 0f
        TileLabel.None -> 0f
    }, tween(if (reduced) 0 else 140), label = "icon-name")
    Column(Modifier.zIndex(if (focused) 3f else 0f), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(aspect).focusRequester(requester)
                .onPreviewKeyEvent(onKey).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
                .graphicsLayer { translationY = lift.dp.toPx(); scaleX = scale; scaleY = scale }
                .shadow(elevation, TileShape, clip = false)
                .semantics { contentDescription = label }.testTag(tag),
            shape = ClickableSurfaceDefaults.shape(TileShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            border = quietBorder(), glow = quietGlow()) {
            Box(Modifier.fillMaxSize().clip(TileShape).background(
                if (glass) Brush.verticalGradient(listOf(Color.White.copy(alpha = .11f),
                    Color.White.copy(alpha = .05f), Color.White.copy(alpha = .02f)))
                else Brush.verticalGradient(listOf(background.tint(.07f), background, background.tint(-.13f))))) {
                content()
                Box(Modifier.matchParentSize().background(Color.White.copy(alpha = .07f * light)))
                if (moving) Box(Modifier.matchParentSize().border(BorderStroke(2.dp, Accent), TileShape))
            }
        }
        if (name != TileLabel.None) Box(Modifier.padding(top = 7.dp).height(18.dp).fillMaxWidth().graphicsLayer { alpha = nameAlpha },
            contentAlignment = Alignment.TopCenter) {
            Text(label, color = if (focused) Ink else Muted.copy(alpha = .7f), fontSize = 13.sp,
                fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Lift a plate colour towards white or black; one flat field, no second material. */
private fun Color.tint(f: Float): Color = if (f >= 0f) lerp(this, Color.White, f) else lerp(this, Color.Black, -f)

@Composable
fun AppTile(
    app: AppEntry, repository: AppRepository, revision: Int, reduced: Boolean, favorite: Boolean,
    requester: FocusRequester, moving: Boolean, onFocus: () -> Unit,
    onKey: (KeyEvent) -> Boolean, onClick: () -> Unit, onLongClick: () -> Unit,
    name: TileLabel = TileLabel.Focused, star: Boolean = false, aspect: Float = 1f, banner: Boolean = false,
) {
    // The tile is the app's own TV banner when it has one; only banner-less apps fall back to an
    // icon sitting on the colour field.
    var art by remember(app.id) { mutableStateOf<Artwork?>(null) }
    LaunchedEffect(app.id, revision, banner) {
        val fresh = repository.artwork(app, preferBanner = banner)
        if (fresh != null) art = fresh
    }
    IconTile(app.displayName, "app:${app.id}", requester, reduced,
        art?.let { Color(it.background) } ?: Color(0xFF171C24),
        name, moving, onFocus, onKey, onClick, onLongClick,
        aspect = aspect, glass = false) {
        val artwork = art
        when {
            artwork == null -> Text(app.label.take(1), color = Ink.copy(alpha = .38f), fontSize = 22.sp,
                fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.Center))
            artwork.banner -> Image(artwork.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
            // A full-bleed icon already is the app's tile; a transparent mark sits on the field.
            artwork.opaque -> Image(artwork.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
            else -> {
                val ratio = artwork.bitmap.width.toFloat() / artwork.bitmap.height.coerceAtLeast(1)
                Image(artwork.bitmap.asImageBitmap(), null,
                    Modifier.fillMaxHeight(if (aspect < 1.25f) .62f else .56f).aspectRatio(ratio)
                        .align(Alignment.Center),
                    contentScale = ContentScale.Fit)
            }
        }
        if (star && favorite) Text("★", Modifier.align(Alignment.TopEnd).padding(7.dp),
            color = Color.White.copy(alpha = .7f), fontSize = 10.sp)
    }
}

/** System tiles (all apps, settings, management) share the tile geometry but carry their own glyph. */
@Composable
fun HomeAction(label: String, tag: String, kind: Int, requester: FocusRequester, reduced: Boolean,
    onFocus: () -> Unit, onClick: () -> Unit, name: TileLabel = TileLabel.Focused, aspect: Float = 1f) {
    IconTile(label, tag, requester, reduced, Color(0xFF1E242E), name, onFocus = onFocus, onClick = onClick,
        aspect = aspect) {
        Canvas(Modifier.fillMaxHeight(if (aspect < 1.25f) .46f else .42f).aspectRatio(1f).align(Alignment.Center)) {
            val unit = size.width / 56f
            fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
            val tint = Ink.copy(alpha = .92f)
            val stroke = 2.4f * unit
            when (kind) {
                0 -> for (row in 0..1) for (col in 0..2) drawRoundRect(tint,
                    point(8f + col * 15f, 12f + row * 15f), Size(11 * unit, 11 * unit), CornerRadius(3.2f * unit))
                1 -> listOf(17f to 36f, 28f to 20f, 39f to 30f).forEach { (y, x) ->
                    drawLine(tint.copy(alpha = .42f), point(8f, y), point(48f, y), stroke, StrokeCap.Round)
                    drawCircle(tint, 6.6f * unit, point(x, y))
                }
                else -> for (row in 0..1) for (col in 0..1) {
                    val tile = point(8f + col * 21f, 8f + row * 21f)
                    if (row == 0 && col == 1) drawRoundRect(tint, tile, Size(19 * unit, 19 * unit), CornerRadius(5 * unit))
                    else drawRoundRect(tint, tile, Size(19 * unit, 19 * unit), CornerRadius(5 * unit), style = Stroke(stroke))
                }
            }
        }
    }
}
