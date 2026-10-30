package io.github.jameskbb.hearth.weather

import kotlinx.coroutines.flow.StateFlow

/*
 * Opt-in weather (docs/decisions/0005-opt-in-weather.md). Off by default; while it is off, nothing
 * in this package touches the network. The only service used is Open-Meteo, and the only thing
 * sent to it is the chosen city's coordinates (forecast) or the letters typed into the city search.
 */

/** A city the user picked from Open-Meteo's geocoding results. Stored in prefs, never looked up by IP. */
data class Place(
    val name: String,
    /** First-level region (state, province), when the geocoder gives one. */
    val region: String?,
    val country: String?,
    val latitude: Double,
    val longitude: Double,
) {
    /** "Springfield, Illinois, United States": every part that is present, without repeats. */
    fun describe(): String = listOf(name, region, country)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
        .joinToString(", ")

    /** The part after the name, for search results: "Illinois, United States". */
    fun area(): String = listOf(region, country)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .filter { it != name }
        .distinct()
        .joinToString(", ")

    /** True when [other] is the same spot (coordinates equal to about 10 m). */
    fun sameLocation(other: Place?): Boolean = other != null &&
        kotlin.math.abs(latitude - other.latitude) < 1e-4 && kotlin.math.abs(longitude - other.longitude) < 1e-4

    companion object {
        fun isValid(latitude: Double, longitude: Double): Boolean =
            latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
    }
}

enum class TemperatureUnit(val storedName: String) {
    Celsius("celsius"),
    Fahrenheit("fahrenheit");

    companion object {
        fun fromStored(name: String?): TemperatureUnit? = entries.firstOrNull { it.storedName == name }
    }
}

/**
 * One successful forecast, in Celsius as Open-Meteo sends it by default. Converted only for display,
 * so changing units needs no new request.
 */
data class WeatherReport(
    val latitude: Double,
    val longitude: Double,
    /** When Hearth received it (device clock, epoch millis). Drives staleness, not the API's own time. */
    val fetchedAt: Long,
    val temperatureC: Double,
    /** Today's high and low at that place, when the response has them. */
    val highC: Double?,
    val lowC: Double?,
    /** The WMO weather interpretation code; see [WeatherCodes]. */
    val weatherCode: Int,
    val isDay: Boolean,
) {
    fun isFor(place: Place?): Boolean =
        place != null && place.sameLocation(Place("", null, null, latitude, longitude))
}

/** Why a request did not produce weather. Each has its own copy in Settings. */
enum class WeatherFailure {
    /** No connection, DNS failure, timeout: Open-Meteo could not be reached. */
    Unreachable,
    /** Open-Meteo answered with an HTTP error. */
    ServerError,
    /** Open-Meteo answered, but not with something Hearth could read. */
    BadResponse,
}

class WeatherException(val failure: WeatherFailure, cause: Throwable? = null) : Exception(failure.name, cause)

/** What the user has set in Settings. */
data class WeatherSettings(val enabled: Boolean, val place: Place?)

/**
 * Everything the UI needs about weather. [report] is the last good result for [place] with its
 * timestamp; whether it is recent enough to show is decided against the clock by [WeatherPolicy].
 */
data class WeatherState(
    val enabled: Boolean = false,
    val place: Place? = null,
    val report: WeatherReport? = null,
    /** The most recent attempt failed this way; null after a success. */
    val failure: WeatherFailure? = null,
    /** After a failure: when Hearth will try again (while it is in front). */
    val retryAt: Long? = null,
    /** A request is in flight. */
    val fetching: Boolean = false,
)

/** The network calls. One implementation talks to Open-Meteo; tests use fakes. */
interface WeatherApi {
    /** Current conditions and today's range at [place]. Throws [WeatherException]. */
    suspend fun forecast(place: Place, fetchedAt: Long): WeatherReport

    /** Cities matching [query], best first. Throws [WeatherException]. */
    suspend fun searchPlaces(query: String, language: String): List<Place>
}

/** Weather as the rest of the app sees it, wired in AppContainer. */
interface WeatherService {
    val state: StateFlow<WeatherState>

    /**
     * Hearth is in front (true) or not. Refreshing is scheduled only while in front; going to the
     * background cancels the pending refresh, so there is no background work.
     */
    fun setForeground(foreground: Boolean)

    /** City search for Settings. Returns nothing (and sends nothing) while weather is off. */
    suspend fun searchPlaces(query: String, language: String): List<Place>
}
