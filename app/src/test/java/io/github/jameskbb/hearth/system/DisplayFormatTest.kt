package io.github.jameskbb.hearth.system

import io.github.jameskbb.hearth.system.DisplayFormat.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayFormatTest {
    @Test fun hzFormatting() {
        assertEquals("60", DisplayFormat.hz(60f))
        assertEquals("60", DisplayFormat.hz(60.0001f))
        assertEquals("59.94", DisplayFormat.hz(59.940002f))
        assertEquals("23.98", DisplayFormat.hz(23.976f))
        assertEquals("29.97", DisplayFormat.hz(29.97003f))
        assertEquals("47.5", DisplayFormat.hz(47.5f))
    }

    @Test fun modesSortedLargestThenFastestAndDeduplicated() {
        val modes = listOf(
            Mode(1920, 1080, 60f),
            Mode(3840, 2160, 30f),
            Mode(3840, 2160, 60f),
            Mode(1280, 720, 50f),
            Mode(1920, 1080, 59.94f),
            Mode(1920, 1080, 60f),
        )
        assertEquals(
            listOf(
                "3840x2160 @ 60 Hz",
                "3840x2160 @ 30 Hz",
                "1920x1080 @ 60 Hz",
                "1920x1080 @ 59.94 Hz",
                "1280x720 @ 50 Hz",
            ),
            DisplayFormat.modes(modes),
        )
    }

    @Test fun noModes() {
        assertEquals(emptyList<String>(), DisplayFormat.modes(emptyList()))
    }

    @Test fun hdrTypesNamedInFixedOrder() {
        // Platform constants: Dolby Vision 1, HDR10 2, HLG 3, HDR10+ 4.
        assertEquals(listOf("HDR10", "HLG", "HDR10+", "Dolby Vision"), DisplayFormat.hdrTypes(intArrayOf(4, 1, 3, 2)))
        assertEquals(listOf("HDR10"), DisplayFormat.hdrTypes(intArrayOf(2, 2)))
    }

    @Test fun hdrUnknownAndInvalid() {
        assertEquals(emptyList<String>(), DisplayFormat.hdrTypes(intArrayOf()))
        assertEquals(emptyList<String>(), DisplayFormat.hdrTypes(intArrayOf(-1)))
        assertEquals(listOf("HDR10", "HDR type 9"), DisplayFormat.hdrTypes(intArrayOf(9, 2)))
    }
}
