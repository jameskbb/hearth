package io.github.jameskbb.hearth.ui.theme

import io.github.jameskbb.hearth.theme.Breath
import io.github.jameskbb.hearth.theme.Drift
import io.github.jameskbb.hearth.theme.Fade
import io.github.jameskbb.hearth.theme.LayerSpec
import io.github.jameskbb.hearth.theme.Sway
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * The motion of a theme's layers (docs/theme-packs.md, Layers and Rest state) as pure
 * functions of time, so the renderer only draws. Time [t] is seconds since the theme appeared;
 * a null time means the rest state (Reduce motion, or Hearth in the background). Distances
 * are canvas dp: 1/960 of the screen width, whatever the display's density.
 */
object ThemeMotion {
    const val CANVAS_WIDTH_DP = 960f

    /** At most 30 updates a second; the slack lets a 60 Hz display update on every second frame. */
    const val MIN_FRAME_NANOS = 1_000_000_000L / 30 - 2_000_000L

    /** sin of a cycle of [periodSeconds] at [t], offset by [phase] cycles. Exact for any t. */
    private fun wave(t: Double, periodSeconds: Float, phase: Float = 0f): Float {
        val cycles = t / periodSeconds + phase
        val frac = cycles - floor(cycles)
        return sin(2.0 * PI * frac).toFloat()
    }

    /** The scene's sway at [t], as followed by a layer with [parallax]: a sine, 0 at t = 0. */
    fun swayOffset(t: Double, sway: Sway, parallax: Float): Float {
        if (sway.amplitudeDp == 0f || parallax == 0f) return 0f
        return sway.amplitudeDp * parallax * wave(t, sway.periodSeconds)
    }

    /**
     * Drift at [t]. Without [wrap]: to and fro on a sine between -x and x, 0 at t = 0. With
     * [wrap]: x per period, continuously, reduced to one canvas width (the image repeats there),
     * in [0, 960).
     */
    fun driftOffset(t: Double, drift: Drift, wrap: Boolean): Float {
        if (!wrap) return drift.xDp * wave(t, drift.periodSeconds)
        val travelled = drift.xDp.toDouble() * t / drift.periodSeconds
        val w = CANVAS_WIDTH_DP.toDouble()
        return (((travelled % w) + w) % w).toFloat()
    }

    /** Opacity breathing at [t]: the midpoint at t = 0 (phase 0), the maximum a quarter cycle on. */
    fun breath(t: Double, breath: Breath): Float {
        val mid = (breath.min + breath.max) / 2f
        val half = (breath.max - breath.min) / 2f
        return mid + half * wave(t, breath.periodSeconds, breath.phase)
    }

    /** A one-time fade at [t]: [Fade.from] until the delay, then smoothstep to [Fade.to]. */
    fun fade(t: Double, fade: Fade): Float {
        if (t <= fade.delaySeconds) return fade.from
        if (fade.durationSeconds <= 0f) return fade.to
        val p = ((t - fade.delaySeconds) / fade.durationSeconds).coerceIn(0.0, 1.0).toFloat()
        val s = p * p * (3f - 2f * p)
        return fade.from + (fade.to - fade.from) * s
    }

    /** A layer's opacity at [t] (null: at rest): breathing times fade. */
    fun layerAlpha(t: Double?, spec: LayerSpec): Float {
        if (t == null) return restAlpha(spec)
        val b = spec.opacity?.let { breath(t, it) } ?: 1f
        val f = spec.fade?.let { fade(t, it) } ?: 1f
        return (b * f).coerceIn(0f, 1f)
    }

    /** The rest state's opacity: breathing at its midpoint, fades at their end value. */
    fun restAlpha(spec: LayerSpec): Float {
        val b = spec.opacity?.let { (it.min + it.max) / 2f } ?: 1f
        val f = spec.fade?.to ?: 1f
        return (b * f).coerceIn(0f, 1f)
    }

    /**
     * Extra width on each side, in canvas dp, so motion never reveals an edge: the furthest the
     * layer travels (sway times parallax, plus drift). It applies at rest too, so a layer is
     * placed the same way whether or not it moves; art for a moving layer is authored with this
     * margin built in. None for a wrapping layer, which repeats instead.
     */
    fun overscan(spec: LayerSpec, sway: Sway): Float {
        if (spec.wrap) return 0f
        val swayTravel = abs(sway.amplitudeDp * spec.parallax)
        val driftTravel = spec.drift?.let { abs(it.xDp) } ?: 0f
        return swayTravel + driftTravel
    }

    /** True when nothing in the theme ever changes, so no frame clock is needed. */
    fun isStill(sway: Sway, layers: List<LayerSpec>): Boolean = layers.all { l ->
        (sway.amplitudeDp == 0f || l.parallax == 0f) &&
            (l.drift == null || l.drift.xDp == 0f) &&
            (l.opacity == null || l.opacity.min == l.opacity.max) &&
            (l.fade == null || l.fade.from == l.fade.to)
    }

    /**
     * Where a layer lands on a canvas [canvasWidthPx] wide, in px. The image is scaled so its
     * width spans the canvas plus its overscan on both sides (starting at minus the overscan),
     * its height scaled with it, and its top edge sits `y` below the canvas top. [tiled] layers repeat every [LayerFrame.width] from [LayerFrame.left],
     * which is then in (-width, 0].
     */
    fun frame(
        spec: LayerSpec,
        imageWidth: Int,
        imageHeight: Int,
        canvasWidthPx: Float,
        sway: Sway,
        t: Double?,
    ): LayerFrame {
        val unit = canvasWidthPx / CANVAS_WIDTH_DP
        val over = overscan(spec, sway) * unit
        val width = canvasWidthPx + 2f * over
        val height = width * imageHeight / imageWidth.coerceAtLeast(1)
        val top = spec.yDp * unit
        val alpha = layerAlpha(t, spec)
        val swayPx = if (t == null) 0f else swayOffset(t, sway, spec.parallax) * unit
        if (spec.wrap) {
            val driftPx = if (t == null) 0f else (spec.drift?.let { driftOffset(t, it, wrap = true) } ?: 0f) * unit
            var left = (swayPx + driftPx) % width
            if (left > 0f) left -= width
            return LayerFrame(left, top, width, height, alpha, tiled = true)
        }
        val driftPx = if (t == null) 0f else (spec.drift?.let { driftOffset(t, it, wrap = false) } ?: 0f) * unit
        return LayerFrame(-over + swayPx + driftPx, top, width, height, alpha, tiled = false)
    }
}

/** A layer's placement for one frame, in px. */
data class LayerFrame(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val alpha: Float,
    val tiled: Boolean,
)
