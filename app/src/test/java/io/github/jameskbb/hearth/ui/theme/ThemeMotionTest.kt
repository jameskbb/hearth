package io.github.jameskbb.hearth.ui.theme

import io.github.jameskbb.hearth.theme.Breath
import io.github.jameskbb.hearth.theme.Drift
import io.github.jameskbb.hearth.theme.Fade
import io.github.jameskbb.hearth.theme.LayerSpec
import io.github.jameskbb.hearth.theme.Sway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeMotionTest {
    private val eps = 1e-3f

    @Test fun swayIsZeroAtTheStartAndFullAQuarterPeriodIn() {
        val sway = Sway(10f, 90f)
        assertEquals(0f, ThemeMotion.swayOffset(0.0, sway, 0.5f), eps)
        assertEquals(5f, ThemeMotion.swayOffset(22.5, sway, 0.5f), eps)
        assertEquals(-5f, ThemeMotion.swayOffset(67.5, sway, 0.5f), eps)
        assertEquals(0f, ThemeMotion.swayOffset(22.5, sway, 0f), eps)
    }

    @Test fun swayStaysExactAfterDaysOfUptime() {
        val sway = Sway(10f, 90f)
        val days = 30.0 * 24 * 3600 // a whole number of periods
        assertEquals(10f, ThemeMotion.swayOffset(days + 22.5, sway, 1f), eps)
    }

    @Test fun driftWithoutWrapGoesToAndFroBySine() {
        val drift = Drift(24f, 160f)
        assertEquals(0f, ThemeMotion.driftOffset(0.0, drift, wrap = false), eps)
        assertEquals(24f, ThemeMotion.driftOffset(40.0, drift, wrap = false), eps)
        assertEquals(-24f, ThemeMotion.driftOffset(120.0, drift, wrap = false), eps)
    }

    @Test fun wrapDriftMovesContinuouslyAndWrapsAtOneImageWidth() {
        val drift = Drift(80f, 150f) // 80 dp per 150 s
        assertEquals(40f, ThemeMotion.driftOffset(75.0, drift, wrap = true), eps)
        val wrapAt = 150.0 * 960 / 80 // one canvas width travelled
        assertEquals(0f, ThemeMotion.driftOffset(wrapAt, drift, wrap = true), 0.01f)
        assertEquals(40f, ThemeMotion.driftOffset(wrapAt + 75.0, drift, wrap = true), 0.01f)
        // Leftward drift stays in range too.
        assertEquals(920f, ThemeMotion.driftOffset(75.0, Drift(-80f, 150f), wrap = true), eps)
    }

    @Test fun breathStartsAtTheMidpointAndPeaksAQuarterCycleOn() {
        val b = Breath(0.6f, 1f, 40f)
        assertEquals(0.8f, ThemeMotion.breath(0.0, b), eps)
        assertEquals(1f, ThemeMotion.breath(10.0, b), eps)
        assertEquals(0.6f, ThemeMotion.breath(30.0, b), eps)
    }

    @Test fun breathPhaseOffsetsTheCycle() {
        assertEquals(1f, ThemeMotion.breath(0.0, Breath(0.6f, 1f, 40f, phase = 0.25f)), eps)
        assertEquals(0.8f, ThemeMotion.breath(0.0, Breath(0.6f, 1f, 40f, phase = 0.5f)), eps)
    }

    @Test fun fadeHoldsThenSmoothstepsThenHolds() {
        val f = Fade(0f, 1f, delaySeconds = 20f, durationSeconds = 180f)
        assertEquals(0f, ThemeMotion.fade(0.0, f), eps)
        assertEquals(0f, ThemeMotion.fade(20.0, f), eps)
        assertEquals(0.5f, ThemeMotion.fade(110.0, f), eps)
        assertEquals(1f, ThemeMotion.fade(200.0, f), eps)
        assertEquals(1f, ThemeMotion.fade(10_000.0, f), eps)
        // Smoothstep: slow at the ends.
        assertTrue(ThemeMotion.fade(29.0, f) < 0.01f)
    }

    @Test fun zeroDurationFadeSwitchesAfterTheDelay() {
        val f = Fade(1f, 0f, delaySeconds = 5f, durationSeconds = 0f)
        assertEquals(1f, ThemeMotion.fade(5.0, f), eps)
        assertEquals(0f, ThemeMotion.fade(5.01, f), eps)
    }

    @Test fun alphaMultipliesBreathAndFade() {
        // At 20 s the breath is back at its midpoint and the fade is halfway.
        val spec = LayerSpec("s.webp", opacity = Breath(0.6f, 1f, 40f), fade = Fade(0f, 1f, 0f, 40f))
        assertEquals(0.8f * 0.5f, ThemeMotion.layerAlpha(20.0, spec), eps)
        assertEquals(1f, ThemeMotion.layerAlpha(3.0, LayerSpec("plain.webp")), eps)
    }

    @Test fun restStateIsMidpointTimesFadeEnd() {
        val spec = LayerSpec("s.webp", opacity = Breath(0.75f, 1f, 30f), fade = Fade(0f, 0.8f, 110f, 120f))
        assertEquals(0.875f * 0.8f, ThemeMotion.restAlpha(spec), eps)
        assertEquals(ThemeMotion.restAlpha(spec), ThemeMotion.layerAlpha(null, spec), eps)
        assertEquals(1f, ThemeMotion.restAlpha(LayerSpec("plain.webp")), eps)
    }

    @Test fun restFrameIsOverscannedLikeTheArt() {
        // A transparent overlay with travel is still drawn wider by its travel on each side at
        // rest: the art is authored with that margin, so it lines up with the base layer.
        val spec = LayerSpec("wisps.webp", drift = Drift(24f, 150f))
        val f = ThemeMotion.frame(spec, 1008, 540, 1920f, Sway(6f, 90f), null)
        assertEquals(-48f, f.left, eps)
        assertEquals(1920f + 96f, f.width, eps)
        assertEquals((1920f + 96f) * 540f / 1008f, f.height, eps)
        assertEquals(0f, f.top, eps)
    }

    @Test fun restFrameHasNoOffset() {
        val spec = LayerSpec("s.webp", parallax = 1f, drift = Drift(20f, 100f))
        val f = ThemeMotion.frame(spec, 1920, 1080, 1920f, Sway(10f, 90f), null)
        assertEquals(-30f * 2f, f.left, eps) // overscan only: (10 + 20) dp at 2 px per dp
    }

    @Test fun overscanCoversTheFurthestTravel() {
        val sway = Sway(10f, 90f)
        val spec = LayerSpec("s.webp", parallax = 0.5f, drift = Drift(-24f, 150f))
        assertEquals(29f, ThemeMotion.overscan(spec, sway), eps)
        assertEquals(0f, ThemeMotion.overscan(spec.copy(wrap = true), sway), eps)
        assertEquals(0f, ThemeMotion.overscan(LayerSpec("still.webp"), sway), eps)
    }

    @Test fun overscannedLayerNeverShowsAnEdge() {
        val sway = Sway(10f, 90f)
        val spec = LayerSpec("s.webp", parallax = 1f, drift = Drift(24f, 150f))
        var t = 0.0
        while (t < 900.0) {
            val f = ThemeMotion.frame(spec, 1920, 1080, 1920f, sway, t)
            assertTrue("left edge shows at $t", f.left <= 0.01f)
            assertTrue("right edge shows at $t", f.left + f.width >= 1919.99f)
            t += 0.37
        }
    }

    @Test fun staticBaseLayerFillsTheCanvasExactly() {
        val f = ThemeMotion.frame(LayerSpec("sky.webp"), 1920, 1080, 1920f, Sway(8f, 90f), 12.0)
        assertEquals(LayerFrame(0f, 0f, 1920f, 1080f, 1f, tiled = false), f)
    }

    @Test fun halfSizeLayerScalesUpAndYIsInCanvasDp() {
        // 960 px wide strip, 150 px tall, at y 300 dp, on a 1280 px canvas (4/3 px per dp).
        val f = ThemeMotion.frame(LayerSpec("strip.webp", yDp = 300f), 960, 150, 1280f, Sway(), null)
        assertEquals(400f, f.top, eps)
        assertEquals(1280f, f.width, eps)
        assertEquals(200f, f.height, eps)
    }

    @Test fun wrappingLayerTilesFromInsideTheFirstTile() {
        val spec = LayerSpec("fog.webp", wrap = true, drift = Drift(80f, 150f))
        val f = ThemeMotion.frame(spec, 960, 540, 1920f, Sway(), 75.0)
        assertTrue(f.tiled)
        assertEquals(1920f, f.width, eps)
        assertEquals(80f - 1920f, f.left, eps) // 40 dp moved right: the tile before shows its last 80 px
        val rest = ThemeMotion.frame(spec, 960, 540, 1920f, Sway(), null)
        assertEquals(0f, rest.left, eps)
    }

    @Test fun stillnessIsDetected() {
        assertTrue(ThemeMotion.isStill(Sway(), listOf(LayerSpec("a.webp", parallax = 1f))))
        assertTrue(ThemeMotion.isStill(Sway(10f), listOf(LayerSpec("a.webp"))))
        assertFalse(ThemeMotion.isStill(Sway(10f), listOf(LayerSpec("a.webp", parallax = 0.2f))))
        assertFalse(ThemeMotion.isStill(Sway(), listOf(LayerSpec("a.webp", opacity = Breath(0.5f, 1f, 30f)))))
        assertFalse(ThemeMotion.isStill(Sway(), listOf(LayerSpec("a.webp", fade = Fade(0f, 1f, 0f, 10f)))))
    }
}
