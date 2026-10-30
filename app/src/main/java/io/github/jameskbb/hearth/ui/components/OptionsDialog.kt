package io.github.jameskbb.hearth.ui.components

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.jameskbb.hearth.R
import io.github.jameskbb.hearth.ui.OptionsState
import io.github.jameskbb.hearth.ui.state.Screen
import io.github.jameskbb.hearth.ui.theme.Dimens
import io.github.jameskbb.hearth.ui.theme.Hearth
import io.github.jameskbb.hearth.ui.theme.HearthType
import io.github.jameskbb.hearth.ui.theme.UiTokens
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.roundToInt

/** Choices offered by the options dialog. */
class OptionsCallbacks(
    val onOpen: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onMove: () -> Unit,
    val onHide: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * Options for one app: Open, Add to or Remove from favorites, Move (favorites only), Hide.
 * Back or Menu closes it. A separate window, so focus cannot leave it and returns to the
 * originating tile when it closes (the view model also asks for that explicitly).
 *
 * The panel never covers the tile it belongs to: it sits left-aligned above the tile (on Home,
 * above the name and hint too), else below it, else beside it, always inside the safe margins.
 */
@Composable
fun OptionsDialog(state: OptionsState, callbacks: OptionsCallbacks) {
    // Read where the originating tile is before the dialog window takes focus: the host view
    // still reports its focused node then. Screen coordinates, so it maps into the dialog.
    val hostView = LocalView.current
    val anchor = remember { focusedRectOnScreen(hostView) }
    Dialog(
        onDismissRequest = callbacks.onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // No window dim. The panel is fully opaque (a translucent fill on a separate dialog
        // window composites unreliably on some GPUs) and lifted slightly off the window color.
        val dialogView = LocalView.current
        val window = (dialogView.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }

        val palette = Hearth.palette
        val type = Hearth.type
        val panelColor = remember(palette) { lerp(palette.window, palette.ink, 0.05f) }
        val first = remember { FocusRequester() }
        val menuGate = remember { MenuKeyGate() }

        LaunchedEffect(Unit) {
            repeat(8) {
                try {
                    first.requestFocus()
                    return@LaunchedEffect
                } catch (_: IllegalStateException) {
                    awaitFrame()
                }
            }
        }

        val density = LocalDensity.current
        val keepClear = remember(anchor, state.origin, type, density) {
            anchor?.let { keepClearZone(it, state.origin, type, density) }
        }

        Layout(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Menu) {
                        if (menuGate.onEvent(event.type, event.nativeKeyEvent.repeatCount)) callbacks.onDismiss()
                        true
                    } else {
                        false
                    }
                },
            content = {
                Column(
                    Modifier
                        .width(320.dp)
                        .background(panelColor, RoundedCornerShape(14.dp))
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                ) {
                    BasicText(
                        state.entry.label,
                        style = type.title.copy(color = palette.ink),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    TextAction(
                        stringResource(R.string.option_open),
                        onClick = callbacks.onOpen,
                        style = type.dialogOption,
                        modifier = Modifier.focusRequester(first),
                    )
                    TextAction(
                        stringResource(if (state.isFavorite) R.string.option_remove_favorite else R.string.option_add_favorite),
                        onClick = callbacks.onToggleFavorite,
                        style = type.dialogOption,
                    )
                    if (state.canMove) {
                        TextAction(stringResource(R.string.option_move), onClick = callbacks.onMove, style = type.dialogOption)
                    }
                    TextAction(stringResource(R.string.option_hide), onClick = callbacks.onHide, style = type.dialogOption)
                }
            },
        ) { measurables, constraints ->
            val panel = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
            val window = IntSize(constraints.maxWidth, constraints.maxHeight)
            val origin = IntArray(2).also { dialogView.getLocationOnScreen(it) }
            val zone = keepClear?.translate(-origin[0].toFloat(), -origin[1].toFloat())
            val safe = Rect(
                Dimens.marginH.toPx(),
                Dimens.marginV.toPx(),
                window.width - Dimens.marginH.toPx(),
                window.height - Dimens.marginV.toPx(),
            )
            val at = placePanel(IntSize(panel.width, panel.height), safe, zone)
            layout(window.width, window.height) { panel.place(at) }
        }
    }
}

/** Bounds of the host window's focused node in screen pixels, or null when nothing is focused. */
private fun focusedRectOnScreen(view: View): Rect? {
    if (!view.hasFocus()) return null
    val r = android.graphics.Rect()
    view.getFocusedRect(r)
    // With no focused node the view reports its own bounds; that tells us nothing.
    if (r.isEmpty || (r.width() >= view.width && r.height() >= view.height)) return null
    val loc = IntArray(2).also { view.getLocationOnScreen(it) }
    return Rect(
        (r.left + loc[0]).toFloat(),
        (r.top + loc[1]).toFloat(),
        (r.right + loc[0]).toFloat(),
        (r.bottom + loc[1]).toFloat(),
    )
}

/**
 * What the panel must not cover: the tile (the focused rect already carries its focus scale)
 * with its ring, the glow beneath it, and a gap. On Home the focused app's name and hint sit right above the row and stay
 * readable too, so the zone reaches up over them.
 */
private fun keepClearZone(tile: Rect, origin: Screen, type: HearthType, density: Density): Rect = with(density) {
    val ring = (Dimens.ringWidth + Dimens.ringGap).toPx()
    val gap = 16.dp.toPx()
    val glow = 14.dp.toPx()
    val nameArea = if (origin == Screen.Home) {
        UiTokens.rowPaddingTop.toPx() + type.focusedName.lineHeight.toPx() + 6.dp.toPx() + type.hint.lineHeight.toPx()
    } else {
        0f
    }
    Rect(
        tile.left - ring - gap,
        tile.top - ring - gap - nameArea,
        tile.right + ring + gap,
        tile.bottom + ring + glow + gap,
    )
}

/**
 * Where the panel goes: left-aligned at the margin just above the zone, else just below it,
 * else beside it (right, then left), vertically centered on it. Without a zone, or if nothing
 * fits, the top of the safe area.
 */
private fun placePanel(panel: IntSize, safe: Rect, zone: Rect?): IntOffset {
    val w = panel.width.toFloat()
    val h = panel.height.toFloat()
    val fallback = IntOffset(safe.left.roundToInt(), safe.top.roundToInt())
    if (zone == null) return fallback
    val besideY = (zone.center.y - h / 2).coerceIn(safe.top, (safe.bottom - h).coerceAtLeast(safe.top))
    val candidates = listOf(
        Offset(safe.left, zone.top - h),
        Offset(safe.left, zone.bottom),
        Offset(zone.right, besideY),
        Offset(zone.left - w, besideY),
    )
    val fits = candidates.firstOrNull { c ->
        c.x >= safe.left && c.y >= safe.top && c.x + w <= safe.right && c.y + h <= safe.bottom &&
            !Rect(c, Size(w, h)).overlaps(zone)
    }
    return fits?.let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } ?: fallback
}
