package io.github.jameskbb.hearth.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import io.github.jameskbb.hearth.theme.LayerBlend
import io.github.jameskbb.hearth.theme.LoadedTheme
import io.github.jameskbb.hearth.ui.theme.Hearth
import io.github.jameskbb.hearth.ui.theme.ThemeMotion
import io.github.jameskbb.hearth.ui.theme.UiTokens
import kotlin.math.roundToInt

/** A theme on screen, and when it appeared on the backdrop's clock (seconds). */
private class Shown(val theme: LoadedTheme, var startedAt: Double)

/**
 * The theme's art, full bleed: every layer drawn with drawImage on one canvas (docs/theme-packs.md).
 *
 * - [animate] (Hearth in front, Reduce motion off): a frame clock runs and the layers move, at
 *   most [ThemeMotion.MIN_FRAME_NANOS] apart (30 fps). Otherwise the theme is drawn once, at
 *   rest, and nothing ticks. A theme with no motion never starts the clock.
 * - Time restarts at zero when a theme appears and when animation starts again (back in front,
 *   or Reduce motion turned off), so one-time fades play each time Hearth is shown.
 * - A new theme crossfades in over the old one ([UiTokens.motionMillis]; at once under Reduce
 *   motion). The caller keeps passing the old theme until the new one is decoded.
 * - Null draws nothing: the window color behind shows.
 *
 * The clock is read only while drawing, so a frame redraws this canvas (its own layer) and
 * recomposes nothing.
 */
@Composable
fun ThemeBackdrop(theme: LoadedTheme?, animate: Boolean, modifier: Modifier = Modifier) {
    val reducedMotion = Hearth.reducedMotion
    val clock = remember { mutableDoubleStateOf(0.0) }
    var current by remember { mutableStateOf<Shown?>(null) }
    var leaving by remember { mutableStateOf<Shown?>(null) }
    val fadeIn = remember { Animatable(1f) }

    val motionEnabled = animate && !reducedMotion
    LaunchedEffect(theme, motionEnabled) {
        val previous = current
        if (theme == null) {
            current = null
            leaving = null
            return@LaunchedEffect
        }
        val changed = previous?.theme !== theme
        if (changed) current = Shown(theme, clock.doubleValue)
        if (previous == null || !motionEnabled || !changed) {
            // A pause or Reduce motion cancels an in-flight fade and fully shows its target.
            // Do this for the same theme too, or cancellation can strand a partial alpha.
            leaving = null
            fadeIn.snapTo(1f)
            return@LaunchedEffect
        }
        leaving = previous
        try {
            fadeIn.snapTo(0f)
            fadeIn.animateTo(1f, tween(UiTokens.motionMillis, easing = LinearEasing))
        } finally {
            // Also when a newer theme cancels this fade: it starts its own from here.
            leaving = null
        }
    }

    fun Shown.hasMotion() = !ThemeMotion.isStill(this.theme.sway, this.theme.layers.map { it.spec })
    // Keep the outgoing scene moving during a transition to a still user-installed pack.
    val moving = motionEnabled && (current?.hasMotion() == true || leaving?.hasMotion() == true)
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        clock.doubleValue = 0.0
        current?.startedAt = 0.0
        leaving?.startedAt = 0.0
        var origin = -1L
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (origin < 0) {
                    origin = now
                    last = now
                } else if (now - last >= ThemeMotion.MIN_FRAME_NANOS) {
                    last = now
                    clock.doubleValue = (now - origin) / 1e9
                }
            }
        }
    }

    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val now = clock.doubleValue
        fun timeOf(s: Shown): Double? = if (moving) (now - s.startedAt).coerceAtLeast(0.0) else null
        if (motionEnabled) leaving?.let { drawTheme(it.theme, timeOf(it)) }
        val c = current ?: return@Canvas
        // The draw guard takes effect immediately, before the cancellation effect can run.
        val a = if (motionEnabled) fadeIn.value else 1f
        if (a >= 1f) {
            drawTheme(c.theme, timeOf(c))
        } else {
            val bounds = Rect(Offset.Zero, size)
            drawIntoCanvas { it.saveLayer(bounds, Paint().apply { alpha = a }) }
            drawTheme(c.theme, timeOf(c))
            drawIntoCanvas { it.restore() }
        }
    }
}

/** Draws [theme]'s layers first to last at time [t] (null: the rest state). */
private fun DrawScope.drawTheme(theme: LoadedTheme, t: Double?) {
    for (layer in theme.layers) {
        val image = layer.bitmap
        val f = ThemeMotion.frame(layer.spec, image.width, image.height, size.width, theme.sway, t)
        if (f.alpha <= 0f || f.top >= size.height) continue
        val w = f.width.roundToInt().coerceAtLeast(1)
        val dst = IntSize(w, f.height.roundToInt().coerceAtLeast(1))
        val blend = if (layer.spec.blend == LayerBlend.Screen) BlendMode.Screen else BlendMode.SrcOver
        if (f.tiled) {
            // Fractional offsets keep slow motion smooth; whole-pixel tile steps leave no seams.
            var x = f.left
            while (x < size.width) {
                translate(x, f.top) { drawImage(image, dstSize = dst, alpha = f.alpha, blendMode = blend) }
                x += w
            }
        } else {
            translate(f.left, f.top) { drawImage(image, dstSize = dst, alpha = f.alpha, blendMode = blend) }
        }
    }
}
