package io.github.jameskbb.hearth.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import io.github.jameskbb.hearth.R
import io.github.jameskbb.hearth.prefs.HearthPrefs
import io.github.jameskbb.hearth.weather.Condition
import io.github.jameskbb.hearth.weather.Temperatures
import io.github.jameskbb.hearth.weather.TemperatureUnit
import io.github.jameskbb.hearth.weather.WeatherGlance
import io.github.jameskbb.hearth.system.primaryLocale

/** The unit to show: the one chosen in Settings, else the locale's (Fahrenheit in the US). */
@Composable
fun temperatureUnit(prefs: HearthPrefs?): TemperatureUnit {
    prefs?.temperatureUnit?.let { return it }
    val locale = LocalConfiguration.current.primaryLocale()
    return Temperatures.defaultUnit(locale.country)
}

@StringRes
fun Condition.label(): Int = when (this) {
    Condition.Clear -> R.string.weather_clear
    Condition.MostlyClear -> R.string.weather_mostly_clear
    Condition.PartlyCloudy -> R.string.weather_partly_cloudy
    Condition.Cloudy -> R.string.weather_cloudy
    Condition.Fog -> R.string.weather_fog
    Condition.Drizzle -> R.string.weather_drizzle
    Condition.FreezingDrizzle -> R.string.weather_freezing_drizzle
    Condition.Rain -> R.string.weather_rain
    Condition.HeavyRain -> R.string.weather_heavy_rain
    Condition.FreezingRain -> R.string.weather_freezing_rain
    Condition.Showers -> R.string.weather_showers
    Condition.HeavyShowers -> R.string.weather_heavy_showers
    Condition.Snow -> R.string.weather_snow
    Condition.HeavySnow -> R.string.weather_heavy_snow
    Condition.SnowShowers -> R.string.weather_snow_showers
    Condition.Thunderstorm -> R.string.weather_thunderstorm
    Condition.ThunderstormHail -> R.string.weather_thunderstorm_hail
}

/**
 * Hearth's own glyph for a condition (res/drawable/weather_*.xml, from scripts/weather-glyphs.py).
 * Clear and partly cloudy skies show the moon at night; intensities share one glyph.
 */
@DrawableRes
fun Condition.glyph(isDay: Boolean): Int = when (this) {
    Condition.Clear, Condition.MostlyClear -> if (isDay) R.drawable.weather_clear_day else R.drawable.weather_clear_night
    Condition.PartlyCloudy -> if (isDay) R.drawable.weather_partly_cloudy_day else R.drawable.weather_partly_cloudy_night
    Condition.Cloudy -> R.drawable.weather_cloudy
    Condition.Fog -> R.drawable.weather_fog
    Condition.Drizzle -> R.drawable.weather_drizzle
    Condition.FreezingDrizzle, Condition.FreezingRain -> R.drawable.weather_sleet
    Condition.Rain, Condition.HeavyRain, Condition.Showers, Condition.HeavyShowers -> R.drawable.weather_rain
    Condition.Snow, Condition.HeavySnow, Condition.SnowShowers -> R.drawable.weather_snow
    Condition.Thunderstorm, Condition.ThunderstormHail -> R.drawable.weather_thunderstorm
}

/** The weather column beside the clock: "64°" with a glyph, over "Partly cloudy, high 70°, low 52°". */
@Composable
fun weatherAside(glance: WeatherGlance, unit: TemperatureUnit): ClockAside {
    val temperature = Temperatures.format(glance.temperatureC, unit)
    val condition = glance.condition?.let { stringResource(it.label()) }
    val high = glance.highC?.let { Temperatures.format(it, unit) }
    val low = glance.lowC?.let { Temperatures.format(it, unit) }
    val caption = when {
        condition != null && high != null && low != null -> stringResource(R.string.weather_caption_full, condition, high, low)
        condition != null -> condition
        high != null && low != null -> stringResource(R.string.weather_caption_range, high, low)
        else -> ""
    }
    return ClockAside(
        icon = glance.condition?.glyph(glance.isDay),
        value = temperature,
        caption = caption,
        description = listOf(temperature, caption).filter { it.isNotEmpty() }.joinToString(", "),
    )
}
