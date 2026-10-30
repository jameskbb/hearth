package io.github.jameskbb.hearth.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeValidationTest {
    private val full = ImageBounds(1920, 1080)
    private val half = ImageBounds(960, 540)

    private fun manifest(vararg layers: String, preview: String? = null) = ThemeManifest(
        format = 1,
        id = ThemeId("test-pack"),
        name = "Test",
        author = null,
        description = null,
        dayparts = emptySet(),
        palette = PaletteSpec(0, 0, 0, 0, 0, 0, 0),
        preview = preview,
        sway = Sway(),
        layers = layers.map { LayerSpec(it) },
    )

    /** A pack whose files are [files]; counts header reads so a test can see nothing else happened. */
    private class Pack(val files: Map<String, ImageBounds?>) {
        var boundsReads = 0
        val exists: (String) -> Boolean = { it in files }
        val bounds: (String) -> ImageBounds? = { boundsReads++; files[it] }
    }

    private fun check(m: ThemeManifest, pack: Pack) = ThemeValidation.check(m, pack.exists, pack.bounds)

    @Test fun aGoodPackPasses() {
        val pack = Pack(mapOf("sky.webp" to full, "fog.webp" to half, "pines.webp" to full, "preview.webp" to ImageBounds(480, 270)))
        assertNull(check(manifest("sky.webp", "fog.webp", "pines.webp", preview = "preview.webp"), pack))
    }

    @Test fun aManifestNamingAMissingImageFailsWithThatImage() {
        val pack = Pack(mapOf("sky.webp" to full))
        assertEquals(ThemeProblem.MissingImage("fog.webp"), check(manifest("sky.webp", "fog.webp"), pack))
    }

    @Test fun aMissingPreviewFailsToo() {
        val pack = Pack(mapOf("sky.webp" to full))
        assertEquals(ThemeProblem.MissingImage("preview.webp"), check(manifest("sky.webp", preview = "preview.webp"), pack))
    }

    @Test fun anImageWhoseHeaderCannotBeReadFails() {
        val pack = Pack(mapOf("sky.webp" to full, "junk.webp" to null))
        assertEquals(ThemeProblem.UnreadableImage("junk.webp"), check(manifest("sky.webp", "junk.webp"), pack))
    }

    @Test fun theBaseLayerMustBeFullSize() {
        val pack = Pack(mapOf("sky.webp" to half))
        assertEquals(ThemeProblem.BaseLayerSize("sky.webp", 960, 540), check(manifest("sky.webp"), pack))
    }

    @Test fun imagesWiderThan1920Fail() {
        val pack = Pack(mapOf("sky.webp" to full, "wide.webp" to ImageBounds(3840, 400)))
        assertEquals(ThemeProblem.ImageTooWide("wide.webp", 3840), check(manifest("sky.webp", "wide.webp"), pack))
    }

    @Test fun threeFullLayersFitTheBudget() {
        val pack = Pack(mapOf("a.webp" to full, "b.webp" to full, "c.webp" to full, "d.webp" to half))
        assertNull(check(manifest("a.webp", "b.webp", "c.webp", "d.webp"), pack))
    }

    @Test fun overBudgetPackIsRejectedFromHeadersAlone() {
        // Four full-size layers: 33.2 MB decoded. Only headers are read, one per image.
        val pack = Pack(mapOf("a.webp" to full, "b.webp" to full, "c.webp" to full, "d.webp" to full))
        assertEquals(
            ThemeProblem.TooLarge(4L * 1920 * 1080 * 4),
            check(manifest("a.webp", "b.webp", "c.webp", "d.webp"), pack),
        )
        assertEquals(4, pack.boundsReads)
    }

    @Test fun anImageUsedTwiceCountsOnce() {
        val pack = Pack(mapOf("a.webp" to full, "b.webp" to full, "c.webp" to full))
        assertNull(check(manifest("a.webp", "b.webp", "c.webp", "a.webp"), pack))
    }

    @Test fun stripsKeepALayeredPackUnderBudget() {
        // Desert Twilight's plan: two full skies, half-size stars and clouds, two 1920x600 strips.
        val strip = ImageBounds(1920, 600)
        val pack = Pack(
            mapOf("sky.webp" to full, "twilight.webp" to full, "stars.webp" to half, "clouds.webp" to half,
                "far.webp" to strip, "near.webp" to strip),
        )
        assertNull(check(manifest("sky.webp", "twilight.webp", "stars.webp", "clouds.webp", "far.webp", "near.webp"), pack))
    }
}
