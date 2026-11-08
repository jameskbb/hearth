package io.github.jameskbb.hearth.theme

import io.github.jameskbb.hearth.weather.Condition
import io.github.jameskbb.hearth.weather.Place
import io.github.jameskbb.hearth.weather.WeatherGlance
import io.github.jameskbb.hearth.weather.WeatherPolicy
import io.github.jameskbb.hearth.weather.WeatherReport
import io.github.jameskbb.hearth.weather.WeatherState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Decision 0013, section 1: sky, temperature bands with hysteresis, forecast, and the 45 minute hold. */
class SceneWeatherTest {
    private fun glance(c: Double, condition: Condition? = Condition.Clear, forecast: Condition? = null) =
        WeatherGlance(condition, isDay = true, temperatureC = c, highC = null, lowC = null, forecast = forecast, expiresAt = 0L)

    @Test fun everyConditionHasASky() {
        val expected = mapOf(
            Condition.Clear to SceneSky.Clear, Condition.MostlyClear to SceneSky.Clear,
            Condition.PartlyCloudy to SceneSky.Cloudy, Condition.Cloudy to SceneSky.Cloudy,
            Condition.Fog to SceneSky.Fog,
            Condition.Drizzle to SceneSky.Rain, Condition.FreezingDrizzle to SceneSky.Rain,
            Condition.Rain to SceneSky.Rain, Condition.HeavyRain to SceneSky.Rain,
            Condition.FreezingRain to SceneSky.Rain, Condition.Showers to SceneSky.Rain,
            Condition.HeavyShowers to SceneSky.Rain,
            Condition.Snow to SceneSky.Snow, Condition.HeavySnow to SceneSky.Snow, Condition.SnowShowers to SceneSky.Snow,
            Condition.Thunderstorm to SceneSky.Storm, Condition.ThunderstormHail to SceneSky.Storm,
        )
        assertEquals(Condition.entries.toSet(), expected.keys)
        for ((condition, sky) in expected) assertEquals(condition.name, sky, SceneWeather.sky(condition))
    }

    @Test fun anUnknownCodeIsAClearSkyButTheTemperatureStillCounts() {
        assertEquals(SceneMood(SceneSky.Clear, SceneTemp.Freezing), SceneWeather.mood(glance(-5.0, condition = null), true, null))
    }

    @Test fun temperatureBandsHaveTheirEdges() {
        val cases = listOf(
            -20.0 to SceneTemp.Freezing, 0.0 to SceneTemp.Freezing, 0.1 to SceneTemp.Cold,
            4.4 to SceneTemp.Cold, 6.9 to SceneTemp.Cold, 7.0 to SceneTemp.Mild,
            17.9 to SceneTemp.Mild, 18.0 to SceneTemp.Warm, 26.9 to SceneTemp.Warm,
            27.0 to SceneTemp.Hot, 40.0 to SceneTemp.Hot,
        )
        for ((c, band) in cases) assertEquals("$c C", band, SceneWeather.temp(c, previous = null))
    }

    @Test fun aFortyDegreeFahrenheitMorningIsCold() {
        assertEquals(SceneTemp.Cold, SceneWeather.rawTemp((40.0 - 32.0) * 5.0 / 9.0))
    }

    @Test fun oneDegreeOfHysteresisHoldsThePreviousBandAtEachEdge() {
        // Rising out of freezing needs more than 1 C; falling into it needs below -1 C.
        assertEquals(SceneTemp.Freezing, SceneWeather.temp(0.9, SceneTemp.Freezing))
        assertEquals(SceneTemp.Freezing, SceneWeather.temp(1.0, SceneTemp.Freezing))
        assertEquals(SceneTemp.Cold, SceneWeather.temp(1.1, SceneTemp.Freezing))
        assertEquals(SceneTemp.Cold, SceneWeather.temp(-0.9, SceneTemp.Cold))
        assertEquals(SceneTemp.Freezing, SceneWeather.temp(-1.0, SceneTemp.Cold))
        // Cold and mild meet at 7.
        assertEquals(SceneTemp.Cold, SceneWeather.temp(7.9, SceneTemp.Cold))
        assertEquals(SceneTemp.Mild, SceneWeather.temp(8.0, SceneTemp.Cold))
        assertEquals(SceneTemp.Mild, SceneWeather.temp(6.0, SceneTemp.Mild))
        assertEquals(SceneTemp.Cold, SceneWeather.temp(5.9, SceneTemp.Mild))
        // Mild and warm at 18, warm and hot at 27.
        assertEquals(SceneTemp.Mild, SceneWeather.temp(18.5, SceneTemp.Mild))
        assertEquals(SceneTemp.Warm, SceneWeather.temp(19.0, SceneTemp.Mild))
        assertEquals(SceneTemp.Warm, SceneWeather.temp(17.2, SceneTemp.Warm))
        assertEquals(SceneTemp.Warm, SceneWeather.temp(27.9, SceneTemp.Warm))
        assertEquals(SceneTemp.Hot, SceneWeather.temp(28.0, SceneTemp.Warm))
        assertEquals(SceneTemp.Hot, SceneWeather.temp(26.0, SceneTemp.Hot))
        assertEquals(SceneTemp.Warm, SceneWeather.temp(25.9, SceneTemp.Hot))
        // A jump across a whole band is never held.
        assertEquals(SceneTemp.Hot, SceneWeather.temp(30.0, SceneTemp.Cold))
    }

    @Test fun theForecastShowsSnowOrRainOnTheWayOnly() {
        assertEquals(SceneForecast.Snow, SceneWeather.forecast(Condition.Snow, SceneSky.Cloudy))
        assertEquals(SceneForecast.Snow, SceneWeather.forecast(Condition.SnowShowers, SceneSky.Clear))
        assertEquals(SceneForecast.Rain, SceneWeather.forecast(Condition.Showers, SceneSky.Clear))
        assertEquals(SceneForecast.Rain, SceneWeather.forecast(Condition.Thunderstorm, SceneSky.Cloudy))
        assertEquals(SceneForecast.Snow, SceneWeather.forecast(Condition.Snow, SceneSky.Rain))
        assertNull("already snowing", SceneWeather.forecast(Condition.HeavySnow, SceneSky.Snow))
        assertNull("already raining", SceneWeather.forecast(Condition.Rain, SceneSky.Rain))
        assertNull("a storm already shows rain", SceneWeather.forecast(Condition.Rain, SceneSky.Storm))
        assertNull(SceneWeather.forecast(Condition.Cloudy, SceneSky.Clear))
        assertNull(SceneWeather.forecast(Condition.Fog, SceneSky.Clear))
        assertNull(SceneWeather.forecast(null, SceneSky.Clear))
    }

    @Test fun noMoodWithoutWeatherOrWithScenesOff() {
        assertNull(SceneWeather.mood(null, true, null))
        assertNull(SceneWeather.mood(glance(5.0), false, null))
        assertNull(SceneWeather.mood(glance(Double.NaN), true, null))
    }

    @Test fun theMoodComesFromWhatHomeShowsIncludingTheDailyCode() {
        val place = Place("Example", null, null, 10.0, 20.0)
        val report = WeatherReport(10.0, 20.0, 1_000L, -2.0, 1.0, -4.0, 3, true, dailyWeatherCode = 73)
        val shown = WeatherPolicy.glance(WeatherState(enabled = true, place = place, report = report), 1_000L)
        assertEquals(
            SceneMood(SceneSky.Cloudy, SceneTemp.Freezing, SceneForecast.Snow),
            SceneWeather.mood(shown, scenesEnabled = true, previous = null),
        )
        // Stale or switched off: what Home does not show, scenes do not use.
        val stale = WeatherPolicy.glance(WeatherState(enabled = true, place = place, report = report), 1_000L + WeatherPolicy.MAX_AGE_MILLIS + 1)
        assertNull(SceneWeather.mood(stale, true, null))
        assertNull(SceneWeather.mood(WeatherPolicy.glance(WeatherState(enabled = false, place = place, report = report), 1_000L), true, null))
    }

    private val rain = SceneMood(SceneSky.Rain, SceneTemp.Mild)
    private val clear = SceneMood(SceneSky.Clear, SceneTemp.Mild)
    private val minute = 60_000L

    @Test fun theFirstMoodAndNoMoodAreImmediate() {
        assertEquals(ShownMood(rain, 5L), SceneWeather.hold(null, rain, 5L))
        assertNull(SceneWeather.hold(ShownMood(rain, 0L), null, 1L))
    }

    @Test fun aNewMoodWaitsUntilTheOldOneHasBeenUpFortyFiveMinutes() {
        val shown = ShownMood(rain, 0L)
        assertSame(shown, SceneWeather.hold(shown, clear, 30 * minute))
        assertEquals(45 * minute, SceneWeather.holdEndsAt(shown, clear))
        assertSame(shown, SceneWeather.hold(shown, clear, 45 * minute - 1))
        assertEquals(ShownMood(clear, 45 * minute), SceneWeather.hold(shown, clear, 45 * minute))
        // The same mood again keeps its original time, and nothing is waiting.
        assertSame(shown, SceneWeather.hold(shown, rain, 90 * minute))
        assertNull(SceneWeather.holdEndsAt(shown, rain))
        assertNull(SceneWeather.holdEndsAt(shown, null))
    }

    @Test fun aClockThatMovedBackDoesNotFreezeTheScene() {
        assertEquals(ShownMood(clear, 10L), SceneWeather.hold(ShownMood(rain, 50 * minute), clear, 10L))
    }

    @Test fun everyMoodIsListedOnce() {
        assertEquals(6 * 5 * 3, SceneMood.all.size)
        assertEquals(SceneMood.all.size, SceneMood.all.toSet().size)
    }
}
