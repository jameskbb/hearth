package io.github.jameskbb.hearth.apps

import io.github.jameskbb.hearth.apps.ArtworkSizing.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkMathTest {
    // Sizing

    @Test fun fireTv1080pBannerBoxIs368x208() {
        assertEquals(Size(368, 208), ArtworkSizing.bannerBox(320))
        assertEquals(Size(112, 112), ArtworkSizing.iconBox(320))
    }

    @Test fun otherDensities() {
        assertEquals(Size(184, 104), ArtworkSizing.bannerBox(160))
        assertEquals(Size(276, 156), ArtworkSizing.bannerBox(240))
        assertEquals(1, ArtworkSizing.dpToPx(0, 320))
    }

    private val box = Size(368, 208)

    @Test fun sixteenByNineBannerFillsTheTile() {
        assertEquals(box, ArtworkSizing.fit(320, 180, box))
        assertEquals(box, ArtworkSizing.fit(1280, 720, box))
    }

    @Test fun wideBannerKeepsAspect() {
        // 2:1 banner: full width, shorter.
        assertEquals(Size(368, 184), ArtworkSizing.fit(400, 200, box))
    }

    @Test fun squareArtKeepsAspect() {
        assertEquals(Size(208, 208), ArtworkSizing.fit(192, 192, box))
    }

    @Test fun tallArtKeepsAspect() {
        assertEquals(Size(104, 208), ArtworkSizing.fit(100, 200, box))
    }

    @Test fun noIntrinsicSizeFillsTheBox() {
        assertEquals(box, ArtworkSizing.fit(-1, -1, box))
        assertEquals(box, ArtworkSizing.fit(0, 50, box))
    }

    @Test fun iconFitsSquareBox() {
        val icon = Size(112, 112)
        assertEquals(icon, ArtworkSizing.fit(432, 432, icon))
        assertEquals(Size(112, 56), ArtworkSizing.fit(200, 100, icon))
    }

    // Colors

    @Test fun nullCandidatesGiveFallback() {
        assertEquals(ArtColors.FALLBACK, ArtColors.backdrop(listOf(null, null), 0.4f))
        assertEquals(ArtColors.FALLBACK, ArtColors.backdrop(emptyList(), 0.4f))
    }

    @Test fun firstNonNullWins() {
        val navy = 0xFF102040.toInt()
        assertEquals(navy, ArtColors.backdrop(listOf(null, navy, 0xFF00FF00.toInt()), 0.5f))
    }

    @Test fun whiteIsDarkened() {
        val out = ArtColors.backdrop(listOf(0xFFFFFFFF.toInt()), ArtColors.MAX_LIGHTNESS_ICON)
        assertTrue(ArtColors.toHsl(out)[2] <= ArtColors.MAX_LIGHTNESS_ICON + 0.01f)
    }

    @Test fun brightRedKeepsHueButDarkens() {
        val out = ArtColors.backdrop(listOf(0xFFFF4040.toInt()), 0.3f)
        val hsl = ArtColors.toHsl(out)
        assertEquals(0f, hsl[0], 1f)
        assertEquals(0.3f, hsl[2], 0.01f)
    }

    @Test fun resultIsOpaque() {
        val out = ArtColors.backdrop(listOf(0x40112233), 0.5f)
        assertEquals(0xFF, out ushr 24)
    }

    @Test fun hslRoundTrip() {
        val colors = listOf(0xFF000000, 0xFFFFFFFF, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF336699, 0xFFC08040, 0xFF7F7F7F, 0xFFFF00FF)
        for (c in colors.map { it.toInt() }) {
            val h = ArtColors.toHsl(c)
            val back = ArtColors.fromHsl(h[0], h[1], h[2])
            for (shift in listOf(16, 8, 0)) {
                val diff = (c shr shift and 0xFF) - (back shr shift and 0xFF)
                assertTrue("channel off for ${Integer.toHexString(c)}", diff in -1..1)
            }
        }
    }
}
