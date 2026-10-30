package io.github.jameskbb.hearth.ui.components

import android.view.ViewConfiguration
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import io.github.jameskbb.hearth.ui.state.FocusTarget
import io.github.jameskbb.hearth.ui.state.UiLogic
import io.github.jameskbb.hearth.ui.theme.UiTokens
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Maps the focus targets on one screen to their [FocusRequester]s. Items register while they are
 * composed, so a request is only ever made against a node that exists.
 */
@Stable
class FocusRegistry {
    private val requesters = mutableStateMapOf<FocusTarget, FocusRequester>()

    fun register(target: FocusTarget, requester: FocusRequester) {
        requesters[target] = requester
    }

    fun unregister(target: FocusTarget, requester: FocusRequester) {
        if (requesters[target] === requester) requesters.remove(target)
    }

    /** Focuses [target], waiting a few frames for it to be composed. Returns false if it never appeared. */
    suspend fun focus(target: FocusTarget, frames: Int = 8): Boolean {
        repeat(frames) {
            val requester = requesters[target]
            if (requester != null) {
                try {
                    requester.requestFocus()
                    return true
                } catch (_: IllegalStateException) {
                    // Not attached yet; try again next frame.
                }
            }
            withFrameNanos { }
        }
        return false
    }
}

/** Registers this node as [target] in [registry] and attaches its requester. */
@Composable
fun Modifier.focusTarget(registry: FocusRegistry, target: FocusTarget): Modifier {
    val requester = remember { FocusRequester() }
    DisposableEffect(registry, target, requester) {
        registry.register(target, requester)
        onDispose { registry.unregister(target, requester) }
    }
    return this.focusRequester(requester)
}

private fun Key.isSelect() = this == Key.DirectionCenter || this == Key.Enter || this == Key.NumPadEnter

/**
 * Makes a node focusable and activatable from a remote: Select (D-pad center or Enter) on key up
 * calls [onClick]; holding Select calls [onLongClick] once; Menu calls [onMenu].
 *
 * Key-down must be seen by this node before its key-up counts, so a key-up that arrives after a
 * dialog opened or closed never triggers an action by accident.
 */
@Composable
fun Modifier.remoteClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    role: Role = Role.Button,
): Modifier {
    val press = remember { PressTracker() }
    return this
        .semantics {
            this.role = role
            onClick { onClick(); true }
            if (onLongClick != null) onLongClick { onLongClick(); true }
        }
        .onKeyEvent { event ->
            val native = event.nativeKeyEvent
            when {
                event.key.isSelect() -> {
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (native.repeatCount == 0) {
                                press.selectDown = true
                                press.longFired = false
                            } else if (press.selectDown && !press.longFired && onLongClick != null &&
                                (native.isLongPress || native.eventTime - native.downTime >= longPressMillis)
                            ) {
                                press.longFired = true
                                onLongClick()
                            }
                            true
                        }
                        KeyEventType.KeyUp -> {
                            val fire = press.selectDown && !press.longFired
                            press.selectDown = false
                            if (fire) onClick()
                            true
                        }
                        else -> false
                    }
                }
                event.key == Key.Menu && onMenu != null -> {
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (native.repeatCount == 0) press.menuDown = true
                            true
                        }
                        KeyEventType.KeyUp -> {
                            val fire = press.menuDown
                            press.menuDown = false
                            if (fire) onMenu()
                            true
                        }
                        else -> false
                    }
                }
                else -> false
            }
        }
        .focusable()
}

private val longPressMillis = ViewConfiguration.getLongPressTimeout().toLong()

/** Per-node key bookkeeping; plain fields because it never drives composition. */
private class PressTracker {
    var selectDown = false
    var longFired = false
    var menuDown = false
}

/** True for a key-up of Menu that followed a key-down seen by the same handler. */
class MenuKeyGate {
    private var down = false
    fun onEvent(type: KeyEventType, repeatCount: Int): Boolean {
        if (type == KeyEventType.KeyDown) {
            if (repeatCount == 0) down = true
            return false
        }
        if (type == KeyEventType.KeyUp) {
            val fire = down
            down = false
            return fire
        }
        return false
    }
}

/**
 * Keeps the focused item inside a scrolling row or grid with a lead before it, instead of the
 * platform default (which on TV pivots the item toward the middle).
 */
@OptIn(ExperimentalFoundationApi::class)
class LeadBringIntoViewSpec(
    private val leadPx: Float,
    private val trailPx: Float,
    reducedMotion: Boolean,
) : BringIntoViewSpec {
    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> =
        if (reducedMotion) snap() else tween(UiTokens.motionMillis, easing = FastOutSlowInEasing)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        UiLogic.scrollDistanceToReveal(offset, size, containerSize, leadPx, trailPx)
}

/**
 * Scrolls [index] fully into view with the given lead, without moving the row when it already is,
 * and otherwise by the smallest distance that shows it.
 *
 * Waits (up to [layoutTimeoutMillis]) for a layout pass of the current list first: one with
 * [count] items in which the item [key] (when given) is at [index] if it is visible at all. Right
 * after the activity is recreated, or in the frames after the list changed, [layoutInfo] is
 * empty or describes the previous list; deciding from it would jump the row (for instance put a
 * restored tile at the leading edge, pushing the tiles before it off screen) or reveal the wrong
 * item.
 */
suspend fun LazyListState.reveal(
    index: Int,
    leadPx: Float,
    trailPx: Float,
    animate: Boolean,
    count: Int,
    key: Any? = null,
    layoutTimeoutMillis: Long = 500,
) {
    if (index !in 0 until count) return
    withTimeoutOrNull(layoutTimeoutMillis) {
        snapshotFlow { layoutInfo }.first { info ->
            info.totalItemsCount == count && info.visibleItemsInfo.isNotEmpty() &&
                (key == null || info.visibleItemsInfo.all { (it.key == key) == (it.index == index) })
        }
    }
    var info = layoutInfo
    var item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        // Off screen: jump next to it, then let the minimal-distance pass below place it.
        val after = (info.visibleItemsInfo.lastOrNull()?.index ?: -1) < index
        scrollToItem(index)
        if (after) {
            // scrollToItem put it at the leading edge (or as far as the row's end allows); step
            // back so it ends at the trailing edge instead, the least movement that shows it.
            val placed = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            if (placed != null) {
                val viewport = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
                val offset = (placed.offset - layoutInfo.viewportStartOffset).toFloat()
                val back = (viewport - trailPx - placed.size) - offset
                if (back > 0f) scrollBy(-back)
            }
        }
        info = layoutInfo
        item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    }
    val start = info.viewportStartOffset
    val container = (info.viewportEndOffset - start).toFloat()
    val distance = UiLogic.scrollDistanceToReveal(
        (item.offset - start).toFloat(), item.size.toFloat(), container, leadPx, trailPx,
    )
    if (distance != 0f) {
        if (animate) animateScrollBy(distance) else scrollBy(distance)
    }
}

/** Grid counterpart of [reveal] (vertical). */
suspend fun LazyGridState.reveal(index: Int) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
}
