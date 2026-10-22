package io.lumatv.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.*

val Ink = Color(0xFFF5F7F8)
val Muted = Color(0xFFD1DDE1)
val Accent = Color(0xFFD8E9ED)
val Panel = Color(0xFF354A58)

@Composable
fun LumaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color(0xFF26343E),
        background = Color(0xFF455766), surface = Panel, onSurface = Ink), content = content)
}

@Composable
fun Atmosphere(config: LumaConfig, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, config.background, config.wallpaperRevision, config.backgroundBlur) {
        value = BackgroundImages.load(context, config)
    }
    Box(modifier.background(Color(0xFF455766))) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x2010273C), Color(0x4013262F)))))
        if (config.brightness != 1) Box(Modifier.fillMaxSize().background(
            if (config.brightness == 0) Color.Black.copy(alpha = .23f) else Color.White.copy(alpha = .08f)))
    }
}

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
            .zIndex(if (focused) 2f else 0f).shadow(if (focused) 12.dp else 0.dp, RoundedCornerShape(10.dp), clip = false).testTag(tag),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = if (active) .15f else if (flat) 0f else .065f),
            contentColor = Ink, focusedContainerColor = Ink, focusedContentColor = Color(0xFF26343E)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.025f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(0.dp, Color.Transparent)))) {
        Box(Modifier.defaultMinSize(minHeight = 44.dp).padding(horizontal = 18.dp, vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
            Text(text, fontSize = 18.sp, fontWeight = if (focused || active) FontWeight.Medium else FontWeight.Normal)
        }
    }
}

@Composable
private fun TileFrame(
    label: String, tag: String, requester: FocusRequester, reduced: Boolean, background: Color,
    alwaysLabel: Boolean, moving: Boolean = false, onFocus: () -> Unit,
    onKey: (KeyEvent) -> Boolean = { false }, onClick: () -> Unit, onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (focused && !reduced) -3f else 0f, tween(if (reduced) 0 else 180), label = "tile-lift")
    val nameAlpha by animateFloatAsState(if (focused || alwaysLabel) 1f else 0f, tween(if (reduced) 0 else 120), label = "tile-name")
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.zIndex(if (focused) 3f else 0f), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(5f / 3f).focusRequester(requester)
                .onPreviewKeyEvent(onKey).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
                .graphicsLayer { translationY = lift.dp.toPx() }
                .shadow(if (focused) 22.dp else 5.dp, shape, clip = false)
                .semantics { contentDescription = label }.testTag(tag),
            shape = ClickableSurfaceDefaults.shape(shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = if (reduced) 1.025f else 1.18f),
            colors = ClickableSurfaceDefaults.colors(containerColor = background, focusedContainerColor = background),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(if (moving) 2.dp else .6.dp,
                if (moving) Accent else Color.White.copy(alpha = .25f)), shape = shape))) {
            Box(Modifier.fillMaxSize().clip(shape), contentAlignment = Alignment.Center) {
                content()
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(
                    listOf(Color.White.copy(alpha = if (focused) .18f else .09f), Color.Transparent, Color.White.copy(alpha = .025f)))))
            }
        }
        Box(Modifier.padding(top = 18.dp).height(24.dp).fillMaxWidth().graphicsLayer { alpha = nameAlpha },
            contentAlignment = Alignment.TopCenter) {
            Text(label, color = Ink, fontSize = 16.sp, fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun AppTile(
    app: AppEntry, repository: AppRepository, revision: Int, reduced: Boolean, favorite: Boolean,
    requester: FocusRequester, moving: Boolean, onFocus: () -> Unit,
    onKey: (KeyEvent) -> Boolean, onClick: () -> Unit, onLongClick: () -> Unit,
    alwaysLabel: Boolean = true,
) {
    val art by produceState<Artwork?>(null, app.id, revision) { value = repository.artwork(app) }
    TileFrame(app.displayName, "app:${app.id}", requester, reduced,
        art?.let { Color(it.background) } ?: Color(0xFF647C92), alwaysLabel, moving, onFocus, onKey, onClick, onLongClick) {
        val artwork = art
        if (artwork != null) Image(artwork.bitmap.asImageBitmap(), null,
            if (artwork.banner) Modifier.fillMaxSize() else Modifier.fillMaxHeight(.82f).aspectRatio(1f),
            contentScale = if (artwork.banner) ContentScale.Crop else ContentScale.Fit)
        else Text(app.label.take(1), color = Ink, fontSize = 34.sp)
        if (favorite && alwaysLabel) Text("★", Modifier.align(Alignment.TopEnd).padding(7.dp), color = Color(0xFF526D7E), fontSize = 12.sp)
    }
}

@Composable
fun HomeAction(label: String, tag: String, kind: Int, requester: FocusRequester, reduced: Boolean,
    onFocus: () -> Unit, onClick: () -> Unit) {
    TileFrame(label, tag, requester, reduced, Color(0xFF899EAC), false, onFocus = onFocus, onClick = onClick) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(when (kind) {
            0 -> listOf(Color(0xFF99ACBD), Color(0xFF647C92))
            1 -> listOf(Color(0xFFB8C1C6), Color(0xFF7D8B98))
            else -> listOf(Color(0xFFA8B8B6), Color(0xFF6F8886))
        })))
        Canvas(Modifier.size(53.dp)) {
            val unit = size.width / 56f
            val color = Ink
            val stroke = 1.6f * unit
            fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
            when (kind) {
                0 -> for (row in 0..1) for (col in 0..2) drawRoundRect(color,
                    point(7f + col * 16, 10f + row * 17), Size(10 * unit, 10 * unit), CornerRadius(2.5f * unit))
                1 -> listOf(14f to 20f, 28f to 37f, 42f to 24f).forEach { (y, x) ->
                    drawLine(color, point(7f, y), point(49f, y), stroke, StrokeCap.Round)
                    drawCircle(Color(0xFF9DADB7), 5f * unit, point(x, y))
                    drawCircle(color, 5f * unit, point(x, y), style = Stroke(stroke))
                }
                else -> {
                    drawRoundRect(color, point(9f, 12f), Size(29 * unit, 28 * unit), CornerRadius(6 * unit), style = Stroke(stroke))
                    drawLine(color, point(24f, 34f), point(43f, 15f), 4 * unit, StrokeCap.Round)
                    drawLine(color, point(22f, 40f), point(24f, 34f), stroke, StrokeCap.Round)
                }
            }
        }
    }
}
