package io.github.jameskbb.hearth.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtCoverageTest {
    private fun pixels(count: Int, visible: Int, visibleArgb: Int = 0xFF336699.toInt()) =
        IntArray(count) { if (it < visible) visibleArgb else 0x00000000 }

    @Test fun fullyTransparentIsEmpty() {
        assertTrue(ArtCoverage.isEmpty(pixels(4096, 0)))
        assertEquals(0f, ArtCoverage.coverage(pixels(4096, 0)), 0f)
    }

    @Test fun noPixelsIsEmpty() = assertTrue(ArtCoverage.isEmpty(IntArray(0)))

    @Test fun nearlyInvisibleAlphaDoesNotCount() {
        // A faint wash at alpha 10 everywhere still reads as a blank tile.
        val faint = IntArray(1000) { 0x0AFFFFFF }
        assertTrue(ArtCoverage.isEmpty(faint))
    }

    @Test fun aFewStrayPixelsAreStillEmpty() {
        // 10 of 4096 sampled pixels (0.24%) is below the 0.5% threshold.
        assertTrue(ArtCoverage.isEmpty(pixels(4096, 10)))
    }

    @Test fun smallButRealGlyphIsArtwork() {
        // A thin outline icon covering about 3% of its box is real artwork.
        assertFalse(ArtCoverage.isEmpty(pixels(4096, 123)))
    }

    @Test fun opaqueIconIsArtwork() {
        assertFalse(ArtCoverage.isEmpty(pixels(4096, 4096)))
        assertEquals(1f, ArtCoverage.coverage(pixels(100, 100)), 0f)
    }

    @Test fun alphaThresholdIsInclusive() {
        val atThreshold = IntArray(10) { (ArtCoverage.VISIBLE_ALPHA shl 24) or 0xFFFFFF }
        assertEquals(1f, ArtCoverage.coverage(atThreshold), 0f)
        val below = IntArray(10) { ((ArtCoverage.VISIBLE_ALPHA - 1) shl 24) or 0xFFFFFF }
        assertEquals(0f, ArtCoverage.coverage(below), 0f)
    }

    @Test fun sampleStepKeepsSmallRastersWhole() {
        assertEquals(1, ArtCoverage.sampleStep(56, 56))
        assertEquals(1, ArtCoverage.sampleStep(64, 64))
    }

    @Test fun sampleStepBoundsTheSampleCount() {
        for ((w, h) in listOf(368 to 208, 179 to 179, 1920 to 1080)) {
            val step = ArtCoverage.sampleStep(w, h)
            val samples = ((w + step - 1) / step) * ((h + step - 1) / step)
            assertTrue("$w x $h sampled $samples", samples <= ArtCoverage.SAMPLE_TARGET * 2)
            assertTrue("$w x $h sampled $samples", samples >= ArtCoverage.SAMPLE_TARGET / 2)
        }
    }
}
