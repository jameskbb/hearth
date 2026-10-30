package io.github.jameskbb.hearth.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherCodesTest {
    @Test fun everyDocumentedCodeHasACondition() {
        val documented = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)
        for (code in documented) {
            requireNotNull(WeatherCodes.condition(code)) { "code $code has no condition" }
        }
    }

    @Test fun mapsEachFamily() {
        assertEquals(Condition.Clear, WeatherCodes.condition(0))
        assertEquals(Condition.MostlyClear, WeatherCodes.condition(1))
        assertEquals(Condition.PartlyCloudy, WeatherCodes.condition(2))
        assertEquals(Condition.Cloudy, WeatherCodes.condition(3))
        assertEquals(Condition.Fog, WeatherCodes.condition(48))
        assertEquals(Condition.Drizzle, WeatherCodes.condition(53))
        assertEquals(Condition.FreezingDrizzle, WeatherCodes.condition(57))
        assertEquals(Condition.Rain, WeatherCodes.condition(61))
        assertEquals(Condition.Rain, WeatherCodes.condition(63))
        assertEquals(Condition.HeavyRain, WeatherCodes.condition(65))
        assertEquals(Condition.FreezingRain, WeatherCodes.condition(66))
        assertEquals(Condition.Snow, WeatherCodes.condition(77))
        assertEquals(Condition.HeavySnow, WeatherCodes.condition(75))
        assertEquals(Condition.Showers, WeatherCodes.condition(80))
        assertEquals(Condition.HeavyShowers, WeatherCodes.condition(82))
        assertEquals(Condition.SnowShowers, WeatherCodes.condition(86))
        assertEquals(Condition.Thunderstorm, WeatherCodes.condition(95))
        assertEquals(Condition.ThunderstormHail, WeatherCodes.condition(99))
    }

    @Test fun unknownCodesAreNotGuessed() {
        for (code in listOf(-1, 4, 44, 50, 100, 1000)) assertNull(WeatherCodes.condition(code))
    }
}
