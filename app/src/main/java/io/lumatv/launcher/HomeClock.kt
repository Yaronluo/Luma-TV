package io.lumatv.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.tv.material3.Text
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Local device time only. This information area never takes remote focus.
 *
 * One small tracked line over one very large numeral line: the date reads as a caption
 * for the clock instead of a second label competing with it. The numerals are drawn in a
 * single solid tone with a wide dark shadow so they stay legible over the brightest part
 * of the wallpaper; tinting them themselves would turn that shadow into a light halo.
 */
@Composable
internal fun HomeClock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm", Locale.SIMPLIFIED_CHINESE) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("M月d日", Locale.SIMPLIFIED_CHINESE) }
    val weekdayFormat = remember { DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { now = ZonedDateTime.now() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now()
            delay(60_000L - System.currentTimeMillis().mod(60_000L))
        }
    }
    val dateLine = remember(now) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = Ink.copy(alpha = .70f))) { append(now.format(dateFormat)) }
            withStyle(SpanStyle(color = Ink.copy(alpha = .34f))) { append(" · ") }
            withStyle(SpanStyle(color = Accent.copy(alpha = .95f), fontWeight = FontWeight.Medium)) {
                append(now.format(weekdayFormat))
            }
        }
    }
    val timeStyle = remember {
        TextStyle(
            color = Color(0xFFF6FAFC),
            fontSize = 96.sp,
            lineHeight = 104.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 0.sp,
            textAlign = TextAlign.Center,
            fontFeatureSettings = "tnum",
            shadow = Shadow(Color(0xFF02060C).copy(alpha = .55f), Offset(0f, 8f), 30f),
        )
    }
    Column(modifier.testTag("home-clock"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(dateLine, style = TextStyle(fontSize = 16.sp, letterSpacing = 2.2.sp, textAlign = TextAlign.Center),
            modifier = Modifier.testTag("home-date"))
        Spacer(Modifier.height(2.dp))
        Text(now.format(timeFormat), style = timeStyle, modifier = Modifier.testTag("home-time"))
    }
}
