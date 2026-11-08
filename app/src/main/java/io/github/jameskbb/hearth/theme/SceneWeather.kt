package io.github.jameskbb.hearth.theme

import io.github.jameskbb.hearth.weather.Condition
import io.github.jameskbb.hearth.weather.WeatherGlance

/** The sky part of a [SceneMood]: what the current weather looks like from the sofa. */
enum class SceneSky(val manifestName: String) {
    Clear("clear"),
    Cloudy("cloudy"),
    Fog("fog"),
    Rain("rain"),
    Snow("snow"),
    Storm("storm");

    companion object {
        fun fromManifest(s: String): SceneSky? = entries.firstOrNull { it.manifestName == s }
    }
}

/** The temperature part of a [SceneMood], in bands (thresholds in [SceneWeather]). */
enum class SceneTemp(val manifestName: String) {
    Freezing("freezing"),
    Cold("cold"),
    Mild("mild"),
    Warm("warm"),
    Hot("hot");

    companion object {
        fun fromManifest(s: String): SceneTemp? = entries.firstOrNull { it.manifestName == s }
    }
}

/** Precipitation in today's forecast that the current sky does not show yet. */
enum class SceneForecast(val manifestName: String) {
    Snow("snow"),
    Rain("rain");

    companion object {
        /** `none` in a manifest's `when.forecast` means "no forecast"; it is read separately. */
        fun fromManifest(s: String): SceneForecast? = entries.firstOrNull { it.manifestName == s }
    }
}

/**
 * The weather as a scene sees it (docs/decisions/0013-weather-scenes.md). A pack's weather
 * variants match on it; null everywhere means the plain scene.
 */
data class SceneMood(val sky: SceneSky, val temp: SceneTemp, val forecast: SceneForecast? = null) {
    companion object {
        /** Every possible mood, for computing which ones a pack has variants for. */
        val all: List<SceneMood> = SceneSky.entries.flatMap { sky ->
            SceneTemp.entries.flatMap { temp ->
                (listOf<SceneForecast?>(null) + SceneForecast.entries).map { SceneMood(sky, temp, it) }
            }
        }
    }
}

/** A mood and when it first appeared on screen (epoch millis), for the hold in [SceneWeather.hold]. */
data class ShownMood(val mood: SceneMood, val since: Long)

/**
 * Turns weather into a [SceneMood]. Pure: every input, including the previous mood and the
 * time, is an argument, so thresholds, hysteresis and the hold are unit-tested without a clock.
 */
object SceneWeather {
    /** A new mood replaces the one on screen only after that one has been shown this long. */
    const val HOLD_MILLIS = 45 * 60_000L

    /** Hysteresis around each temperature edge, in degrees Celsius, against the previous band. */
    const val HYSTERESIS_C = 1.0

    /** Band edges in Celsius: freezing at or below 0, cold below 7, mild below 18, warm below 27, hot above. */
    const val FREEZING_MAX_C = 0.0
    const val COLD_BELOW_C = 7.0
    const val MILD_BELOW_C = 18.0
    const val WARM_BELOW_C = 27.0

    fun sky(condition: Condition?): SceneSky = when (condition) {
        null, Condition.Clear, Condition.MostlyClear -> SceneSky.Clear
        Condition.PartlyCloudy, Condition.Cloudy -> SceneSky.Cloudy
        Condition.Fog -> SceneSky.Fog
        Condition.Drizzle, Condition.FreezingDrizzle, Condition.Rain, Condition.HeavyRain,
        Condition.FreezingRain, Condition.Showers, Condition.HeavyShowers -> SceneSky.Rain
        Condition.Snow, Condition.HeavySnow, Condition.SnowShowers -> SceneSky.Snow
        Condition.Thunderstorm, Condition.ThunderstormHail -> SceneSky.Storm
    }

    /** The band for [celsius] with no history. */
    fun rawTemp(celsius: Double): SceneTemp = when {
        celsius <= FREEZING_MAX_C -> SceneTemp.Freezing
        celsius < COLD_BELOW_C -> SceneTemp.Cold
        celsius < MILD_BELOW_C -> SceneTemp.Mild
        celsius < WARM_BELOW_C -> SceneTemp.Warm
        else -> SceneTemp.Hot
    }

    /**
     * The band for [celsius], staying in [previous] while the temperature is within
     * [HYSTERESIS_C] of that band's edges, so a reading that hovers on an edge does not flip
     * the scene back and forth.
     */
    fun temp(celsius: Double, previous: SceneTemp?): SceneTemp {
        val raw = rawTemp(celsius)
        if (previous == null || raw == previous) return raw
        val h = HYSTERESIS_C
        val keep = when (previous) {
            SceneTemp.Freezing -> celsius <= FREEZING_MAX_C + h
            SceneTemp.Cold -> celsius > FREEZING_MAX_C - h && celsius < COLD_BELOW_C + h
            SceneTemp.Mild -> celsius >= COLD_BELOW_C - h && celsius < MILD_BELOW_C + h
            SceneTemp.Warm -> celsius >= MILD_BELOW_C - h && celsius < WARM_BELOW_C + h
            SceneTemp.Hot -> celsius >= WARM_BELOW_C - h
        }
        return if (keep) previous else raw
    }

    /** Snow or rain in today's forecast, only when the sky does not already show it. */
    fun forecast(daily: Condition?, sky: SceneSky): SceneForecast? {
        val f = when (daily?.let(::sky)) {
            SceneSky.Snow -> SceneForecast.Snow
            SceneSky.Rain, SceneSky.Storm -> SceneForecast.Rain
            else -> null
        } ?: return null
        val shows = when (f) {
            SceneForecast.Snow -> sky == SceneSky.Snow
            SceneForecast.Rain -> sky == SceneSky.Rain || sky == SceneSky.Storm
        }
        return if (shows) null else f
    }

    /**
     * The mood for [glance] (what Home shows: null when weather is off, has no city or is
     * stale), or null when there is none or [scenesEnabled] is off. [previous] is the mood
     * on screen, for the temperature hysteresis.
     */
    fun mood(glance: WeatherGlance?, scenesEnabled: Boolean, previous: SceneMood?): SceneMood? {
        if (!scenesEnabled || glance == null || !glance.temperatureC.isFinite()) return null
        val sky = sky(glance.condition)
        return SceneMood(sky, temp(glance.temperatureC, previous?.temp), forecast(glance.forecast, sky))
    }

    /**
     * What to show given the mood on screen ([shown]) and a newly computed [candidate] at [now].
     * No mood is immediate (weather off, stale, scenes off). A first mood is immediate. A
     * different mood waits until [shown] has been up for [HOLD_MILLIS]; see [holdEndsAt].
     */
    fun hold(shown: ShownMood?, candidate: SceneMood?, now: Long): ShownMood? = when {
        candidate == null -> null
        shown == null -> ShownMood(candidate, now)
        candidate == shown.mood -> shown
        // A clock that moved back must not freeze the scene.
        now - shown.since >= HOLD_MILLIS || now < shown.since -> ShownMood(candidate, now)
        else -> shown
    }

    /** When a held-back [candidate] may replace [shown], or null when nothing is waiting. */
    fun holdEndsAt(shown: ShownMood?, candidate: SceneMood?): Long? =
        if (shown == null || candidate == null || candidate == shown.mood) null else shown.since + HOLD_MILLIS
}
