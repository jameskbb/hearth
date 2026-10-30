package io.github.jameskbb.hearth.weather

/** The conditions Hearth names. Each maps to one line of copy and one glyph in the UI. */
enum class Condition {
    Clear,
    MostlyClear,
    PartlyCloudy,
    Cloudy,
    Fog,
    Drizzle,
    FreezingDrizzle,
    Rain,
    HeavyRain,
    FreezingRain,
    Showers,
    HeavyShowers,
    Snow,
    HeavySnow,
    SnowShowers,
    Thunderstorm,
    ThunderstormHail,
}

/**
 * WMO weather interpretation codes (WMO 4677, the subset Open-Meteo documents) to [Condition].
 * Intensities that read the same from the sofa are merged: light and moderate rain are both "Rain".
 */
object WeatherCodes {
    fun condition(code: Int): Condition? = when (code) {
        0 -> Condition.Clear
        1 -> Condition.MostlyClear
        2 -> Condition.PartlyCloudy
        3 -> Condition.Cloudy
        45, 48 -> Condition.Fog
        51, 53, 55 -> Condition.Drizzle
        56, 57 -> Condition.FreezingDrizzle
        61, 63 -> Condition.Rain
        65 -> Condition.HeavyRain
        66, 67 -> Condition.FreezingRain
        71, 73, 77 -> Condition.Snow
        75 -> Condition.HeavySnow
        80, 81 -> Condition.Showers
        82 -> Condition.HeavyShowers
        85, 86 -> Condition.SnowShowers
        95 -> Condition.Thunderstorm
        96, 99 -> Condition.ThunderstormHail
        // An unknown code is not guessed at: Home shows the temperature without a condition.
        else -> null
    }
}
