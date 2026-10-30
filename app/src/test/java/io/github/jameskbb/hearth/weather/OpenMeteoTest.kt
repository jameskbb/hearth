package io.github.jameskbb.hearth.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Parsing recorded Open-Meteo responses (test resources), and the request URLs. No network. */
class OpenMeteoTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/openmeteo/$name")) { "missing fixture $name" }.readText()

    private val springfield = Place("Springfield", "Illinois", "United States", 39.80172, -89.64371)

    @Test fun parsesCurrentConditionsAndTodaysRange() {
        val r = OpenMeteo.parseForecast(fixture("forecast.json"), springfield, fetchedAt = 1234L)
        assertEquals(17.8, r.temperatureC, 0.0)
        assertEquals(21.1, r.highC!!, 0.0)
        assertEquals(11.2, r.lowC!!, 0.0)
        assertEquals(2, r.weatherCode)
        assertFalse(r.isDay)
        assertEquals(1234L, r.fetchedAt)
    }

    @Test fun theReportKeepsThePlacesCoordinatesNotTheGridCells() {
        // Open-Meteo answers with its grid cell (39.80322, -89.64264); the report must still match
        // the stored city, or it would never be shown.
        val r = OpenMeteo.parseForecast(fixture("forecast.json"), springfield, fetchedAt = 0L)
        assertEquals(springfield.latitude, r.latitude, 0.0)
        assertEquals(springfield.longitude, r.longitude, 0.0)
        assertTrue(r.isFor(springfield))
    }

    @Test fun aMissingOrNullRangeIsNullNotZero() {
        val place = Place("Reykjavík", "Capital Region", "Iceland", 64.13548, -21.89541)
        val r = OpenMeteo.parseForecast(fixture("forecast_no_daily.json"), place, fetchedAt = 0L)
        assertEquals(-0.4, r.temperatureC, 0.0)
        assertNull(r.highC)
        assertNull(r.lowC)
        assertEquals(73, r.weatherCode)
        assertTrue(r.isDay)
    }

    @Test fun anErrorBodyIsABadResponse() {
        assertBadResponse { OpenMeteo.parseForecast(fixture("forecast_error.json"), springfield, 0L) }
        assertBadResponse { OpenMeteo.parseForecast("<html>gateway timeout</html>", springfield, 0L) }
        assertBadResponse { OpenMeteo.parseForecast("""{"current":{"temperature_2m":"warm","weather_code":1}}""", springfield, 0L) }
        assertBadResponse { OpenMeteo.parsePlaces("not json") }
    }

    @Test fun parsesSearchResultsAndSkipsUnusableOnes() {
        val places = OpenMeteo.parsePlaces(fixture("search_springfield.json"))
        assertEquals(4, places.size)
        assertEquals(springfield, places[0])
        assertEquals("Springfield, Missouri, United States", places[1].describe())
        assertEquals(Place("Springfield", null, "New Zealand", -43.33, 171.93), places[3])
        assertEquals("New Zealand", places[3].area())
    }

    @Test fun noResultsKeyMeansNoMatches() {
        assertEquals(emptyList<Place>(), OpenMeteo.parsePlaces(fixture("search_none.json")))
    }

    @Test fun keepsNonAsciiNames() {
        val p = OpenMeteo.parsePlaces(fixture("search_reykjavik.json")).single()
        assertEquals("Reykjavík", p.name)
        assertEquals("Reykjavík, Capital Region, Iceland", p.describe())
    }

    @Test fun forecastUrlIsHttpsAndSendsOnlyRoundedCoordinates() {
        val url = OpenMeteo.forecastUrl(39.801723456, -89.643719)
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue(url.contains("latitude=39.8017&longitude=-89.6437"))
        assertTrue(url.contains("current=temperature_2m,weather_code,is_day"))
        assertTrue(url.contains("daily=temperature_2m_max,temperature_2m_min"))
        assertTrue(url.contains("timezone=auto"))
    }

    @Test fun searchUrlEncodesTheQuery() {
        val url = OpenMeteo.searchUrl("  São Paulo&x ", "pt")
        assertTrue(url.startsWith("https://geocoding-api.open-meteo.com/v1/search?"))
        assertTrue(url.contains("name=S%C3%A3o+Paulo%26x&"))
        assertTrue(url.contains("language=pt"))
        assertTrue(url.contains("count=${OpenMeteo.SEARCH_COUNT}"))
        assertTrue(OpenMeteo.searchUrl("Oslo", "").contains("language=en"))
    }

    private fun assertBadResponse(block: () -> Unit) {
        try {
            block()
            fail("expected a bad response")
        } catch (e: WeatherException) {
            assertEquals(WeatherFailure.BadResponse, e.failure)
        }
    }
}
