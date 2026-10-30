package io.github.jameskbb.hearth.weather

import org.junit.Assert.assertEquals
import org.junit.Test

class TemperaturesTest {
    @Test fun convertsToFahrenheit() {
        assertEquals(32.0, Temperatures.convert(0.0, TemperatureUnit.Fahrenheit), 1e-9)
        assertEquals(212.0, Temperatures.convert(100.0, TemperatureUnit.Fahrenheit), 1e-9)
        assertEquals(-40.0, Temperatures.convert(-40.0, TemperatureUnit.Fahrenheit), 1e-9)
        assertEquals(17.8, Temperatures.convert(17.8, TemperatureUnit.Celsius), 0.0)
    }

    @Test fun convertsBeforeRounding() {
        // 21.4 C is 70.52 F, so 71; rounding to 21 C first would give 69.8 F, so 70.
        assertEquals(64, Temperatures.whole(17.8, TemperatureUnit.Fahrenheit))
        assertEquals(71, Temperatures.whole(21.4, TemperatureUnit.Fahrenheit))
        assertEquals(21, Temperatures.whole(21.4, TemperatureUnit.Celsius))
    }

    @Test fun halvesRoundUpAndThereIsNoNegativeZero() {
        assertEquals(1, Temperatures.whole(0.5, TemperatureUnit.Celsius))
        assertEquals(0, Temperatures.whole(-0.4, TemperatureUnit.Celsius))
        assertEquals(0, Temperatures.whole(-0.5, TemperatureUnit.Celsius))
        assertEquals(-1, Temperatures.whole(-0.6, TemperatureUnit.Celsius))
        assertEquals("0°", Temperatures.format(-0.4, TemperatureUnit.Celsius))
        assertEquals("-12°", Temperatures.format(-12.3, TemperatureUnit.Celsius))
        assertEquals("31°", Temperatures.format(-0.4, TemperatureUnit.Fahrenheit))
    }

    @Test fun defaultUnitFollowsTheCountry() {
        assertEquals(TemperatureUnit.Fahrenheit, Temperatures.defaultUnit("US"))
        assertEquals(TemperatureUnit.Fahrenheit, Temperatures.defaultUnit("us"))
        assertEquals(TemperatureUnit.Fahrenheit, Temperatures.defaultUnit("PR"))
        assertEquals(TemperatureUnit.Celsius, Temperatures.defaultUnit("GB"))
        assertEquals(TemperatureUnit.Celsius, Temperatures.defaultUnit("CA"))
        assertEquals(TemperatureUnit.Celsius, Temperatures.defaultUnit(""))
        assertEquals(TemperatureUnit.Celsius, Temperatures.defaultUnit(null))
    }

    @Test fun storedNamesRoundTrip() {
        for (u in TemperatureUnit.entries) assertEquals(u, TemperatureUnit.fromStored(u.storedName))
        assertEquals(null, TemperatureUnit.fromStored("kelvin"))
        assertEquals(null, TemperatureUnit.fromStored(null))
    }
}
