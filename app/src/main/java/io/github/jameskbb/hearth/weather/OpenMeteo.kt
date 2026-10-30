package io.github.jameskbb.hearth.weather

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Open-Meteo's two endpoints: request URLs and response parsing. Pure (org.json only), so the
 * parsing is unit-tested against recorded responses in test resources.
 *
 * Forecast: https://open-meteo.com/en/docs. Geocoding: https://open-meteo.com/en/docs/geocoding-api.
 * Data is CC BY 4.0; attribution is in Settings, About.
 */
object OpenMeteo {
    const val FORECAST_HOST = "api.open-meteo.com"
    const val GEOCODING_HOST = "geocoding-api.open-meteo.com"
    const val SEARCH_COUNT = 8

    /** Current temperature, code and day/night, plus today's range in the place's own time zone. */
    fun forecastUrl(latitude: Double, longitude: Double): String =
        "https://$FORECAST_HOST/v1/forecast" +
            "?latitude=${coordinate(latitude)}&longitude=${coordinate(longitude)}" +
            "&current=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=1"

    fun searchUrl(query: String, language: String): String {
        val lang = language.lowercase(Locale.ROOT).filter { it in 'a'..'z' }.take(3).ifEmpty { "en" }
        return "https://$GEOCODING_HOST/v1/search" +
            "?name=${URLEncoder.encode(query.trim(), "UTF-8")}" +
            "&count=$SEARCH_COUNT&language=$lang&format=json"
    }

    /** Four decimals (about 11 m) is all the precision the request needs or sends. */
    private fun coordinate(v: Double) = String.format(Locale.ROOT, "%.4f", v)

    /**
     * Parses a forecast response for [place]. The report keeps the place's own coordinates: Open-Meteo
     * answers with the nearest grid cell's, which would never match the stored city. Throws
     * [WeatherException] ([WeatherFailure.BadResponse]) if the response is unusable.
     */
    fun parseForecast(body: String, place: Place, fetchedAt: Long): WeatherReport = readable {
        val root = JSONObject(body)
        val current = root.getJSONObject("current")
        val temperature = current.getDouble("temperature_2m")
        val code = current.getInt("weather_code")
        if (!temperature.isFinite()) throw JSONException("temperature is not a number")
        val daily = root.optJSONObject("daily")
        WeatherReport(
            latitude = place.latitude,
            longitude = place.longitude,
            fetchedAt = fetchedAt,
            temperatureC = temperature,
            highC = daily?.optJSONArray("temperature_2m_max")?.firstFinite(),
            lowC = daily?.optJSONArray("temperature_2m_min")?.firstFinite(),
            weatherCode = code,
            // Open-Meteo sends 1 or 0; a missing flag is read as day.
            isDay = current.optInt("is_day", 1) != 0,
        )
    }

    /**
     * Parses a geocoding response. No "results" key means no matches. Entries without a name or
     * with impossible coordinates are skipped, not fatal.
     */
    fun parsePlaces(body: String): List<Place> = readable {
        val results = JSONObject(body).optJSONArray("results") ?: return@readable emptyList()
        buildList {
            for (i in 0 until results.length()) {
                val o = results.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val lat = o.optDouble("latitude")
                val lon = o.optDouble("longitude")
                if (name.isEmpty() || !Place.isValid(lat, lon)) continue
                add(Place(name, o.optText("admin1"), o.optText("country"), lat, lon))
            }
        }.distinctBy { listOf(it.describe(), it.latitude, it.longitude) }
    }

    private fun JSONObject.optText(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

    private fun JSONArray.firstFinite(): Double? =
        if (length() == 0 || isNull(0)) null else optDouble(0).takeIf { it.isFinite() }

    private inline fun <T> readable(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw WeatherException(WeatherFailure.BadResponse, e)
    }
}
