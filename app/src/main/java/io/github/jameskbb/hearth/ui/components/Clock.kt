package io.github.jameskbb.hearth.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jameskbb.hearth.ui.state.UiLogic
import io.github.jameskbb.hearth.system.primaryLocale
import io.github.jameskbb.hearth.ui.theme.Hearth
import io.github.jameskbb.hearth.ui.theme.UiTokens
import java.text.SimpleDateFormat
import java.util.Date

/**
 * A second column beside the clock (the weather on Home): a glyph and a value that sit on the
 * time's baseline, and a caption on the date's baseline.
 */
@Immutable
data class ClockAside(
    @DrawableRes val icon: Int?,
    val value: String,
    val caption: String,
    /** What a screen reader says for the whole column. */
    val description: String,
)

/**
 * Time and date, top left, with an optional [aside] column. Updates on the system's minute tick
 * (ACTION_TIME_TICK), so it changes exactly on the minute without any per-second work, and also
 * when the time, time zone, locale or 12/24-hour setting changes, and on resume.
 */
@Composable
fun Clock(modifier: Modifier = Modifier, aside: ClockAside? = null) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.primaryLocale()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                now = System.currentTimeMillis()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        // System broadcasts still arrive with NOT_EXPORTED; nothing else may send these to us.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) now = System.currentTimeMillis()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The 12/24-hour setting is read on every tick and resume, so a change shows within a minute.
    val is24 = DateFormat.is24HourFormat(context)
    val timeFormat = remember(locale, is24) {
        val skeleton = if (is24) "Hm" else "hm"
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
        SimpleDateFormat(if (is24) pattern else UiLogic.stripDayPeriod(pattern), locale)
    }
    val dateFormat = remember(locale) {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale)
    }
    val time = remember(now, timeFormat) { timeFormat.format(Date(now)) }
    val date = remember(now, dateFormat) { dateFormat.format(Date(now)) }

    val palette = Hearth.palette
    val type = Hearth.type
    val gap = UiTokens.clockAsideGap
    val dateGap = 6.dp
    Layout(
        modifier = modifier,
        content = {
            BasicText(time, style = type.clock.copy(color = palette.ink))
            BasicText(date, style = type.date.copy(color = palette.inkMuted))
            if (aside != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = aside.description },
                ) {
                    if (aside.icon != null) {
                        Image(
                            painter = painterResource(aside.icon),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(palette.ink),
                            modifier = Modifier.padding(end = UiTokens.weatherGlyphGap).size(UiTokens.weatherGlyph),
                        )
                    }
                    BasicText(aside.value, style = type.temperature.copy(color = palette.ink))
                }
                BasicText(
                    aside.caption,
                    style = type.date.copy(color = palette.inkMuted),
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val timeP = measurables[0].measure(loose)
        val dateP = measurables[1].measure(loose)
        val dateY = timeP.height + dateGap.roundToPx()
        val left = maxOf(timeP.width, dateP.width)
        if (measurables.size < 4) {
            return@Layout layout(left, dateY + dateP.height) {
                timeP.place(0, 0)
                dateP.place(0, dateY)
            }
        }
        val x = left + gap.roundToPx()
        val rest = loose.copy(maxWidth = (loose.maxWidth - x).coerceAtLeast(0))
        val valueP = measurables[2].measure(rest)
        val captionP = measurables[3].measure(rest)
        // Baselines line up across the columns: value with time, caption with date.
        val valueY = baselineOffset(timeP[FirstBaseline], valueP[FirstBaseline], 0)
        val captionY = baselineOffset(dateY + dateP[FirstBaseline], captionP[FirstBaseline], dateY)
        val height = maxOf(dateY + dateP.height, captionY + captionP.height, valueY + valueP.height)
        val top = minOf(0, valueY)
        layout(x + maxOf(valueP.width, captionP.width), height - top) {
            timeP.place(0, -top)
            dateP.place(0, dateY - top)
            valueP.place(x, valueY - top)
            captionP.place(x, captionY - top)
        }
    }
}

/** Where to place a child so its baseline [childBaseline] lands on [target]; [fallback] if unknown. */
private fun baselineOffset(target: Int, childBaseline: Int, fallback: Int): Int =
    if (childBaseline == AlignmentLine.Unspecified || target < 0) fallback else target - childBaseline
