package io.github.jameskbb.hearth.weather

import kotlin.math.roundToInt

/** Unit conversion, rounding and the locale default. Pure. */
object Temperatures {
    /**
     * Countries and territories that use Fahrenheit day to day (ISO 3166 codes). Everywhere else
     * defaults to Celsius. Settings overrides either way.
     */
    private val fahrenheitRegions = setOf(
        "US", "PR", "GU", "VI", "AS", "MP", "UM", // United States and its territories
        "LR", "MM", "BS", "KY", "PW", "FM", "MH",
    )

    fun defaultUnit(countryCode: String?): TemperatureUnit =
        if (countryCode?.uppercase() in fahrenheitRegions) TemperatureUnit.Fahrenheit else TemperatureUnit.Celsius

    fun convert(celsius: Double, unit: TemperatureUnit): Double = when (unit) {
        TemperatureUnit.Celsius -> celsius
        TemperatureUnit.Fahrenheit -> celsius * 9.0 / 5.0 + 32.0
    }

    /**
     * Whole degrees in [unit], converting before rounding so no precision is lost twice. Halves round
     * up (0.5 to 1, -0.5 to 0), and the result is an Int, so there is never a "-0".
     */
    fun whole(celsius: Double, unit: TemperatureUnit): Int = convert(celsius, unit).roundToInt()

    /** "64°". No unit letter: the household chose the unit, and the glance stays short. */
    fun format(celsius: Double, unit: TemperatureUnit): String = "${whole(celsius, unit)}°"
}
