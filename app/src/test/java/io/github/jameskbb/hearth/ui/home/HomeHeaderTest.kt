package io.github.jameskbb.hearth.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeHeaderTest {
    @Test fun aNormalClockAndOptionalWeatherLeaveAQuietTitle() {
        assertEquals(300, homeLabelWidth(864, 180, 32, 300, 80))
        assertEquals(300, homeLabelWidth(864, 470, 32, 300, 80))
    }

    @Test fun wideWeatherConstrainsTheLabelThenHidesItBeforeACollision() {
        assertEquals(132, homeLabelWidth(864, 700, 32, 300, 80))
        assertEquals(80, homeLabelWidth(864, 752, 32, 300, 80))
        assertEquals(0, homeLabelWidth(864, 753, 32, 300, 80))
        assertEquals(0, homeLabelWidth(864, 864, 32, 300, 80))
        assertEquals(0, homeLabelWidth(400, 600, 32, 300, 80))
    }
}
