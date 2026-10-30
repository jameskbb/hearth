package io.github.jameskbb.hearth.ui.home

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jameskbb.hearth.ui.components.Clock
import io.github.jameskbb.hearth.ui.components.ClockAside
import io.github.jameskbb.hearth.ui.theme.Hearth

/** All arguments and the result use the same units, dp or pixels. Zero hides the label. */
internal fun homeLabelWidth(available: Int, clock: Int, gap: Int, cap: Int, minimum: Int): Int =
    (available - clock - gap).coerceIn(0, cap.coerceAtLeast(0)).takeIf { it >= minimum } ?: 0

/** Measure the entire clock/weather group before allocating the optional title's space. */
@Composable
internal fun HomeHeader(label: String?, aside: ClockAside?, modifier: Modifier = Modifier) {
    Layout(
        modifier = modifier,
        content = {
            Clock(aside = aside)
            if (label != null) {
                BasicText(
                    label,
                    style = Hearth.type.title.copy(
                        color = Hearth.palette.ink,
                        fontSize = 26.sp,
                        lineHeight = 32.sp,
                        textAlign = TextAlign.Right,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val clock = measurables[0].measure(loose)
        val width = homeLabelWidth(constraints.maxWidth, clock.width, 32.dp.roundToPx(), 300.dp.roundToPx(), 80.dp.roundToPx())
        val title = measurables.getOrNull(1)?.measure(loose.copy(maxWidth = width))
        val titleY = 12.dp.roundToPx()
        layout(constraints.maxWidth, maxOf(clock.height, (title?.height ?: 0) + titleY)) {
            clock.place(0, 0)
            if (title != null && width > 0) title.place(constraints.maxWidth - title.width, titleY)
        }
    }
}
