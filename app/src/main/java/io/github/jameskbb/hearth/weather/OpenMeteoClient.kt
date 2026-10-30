package io.github.jameskbb.hearth.weather

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * [WeatherApi] over HTTPS with the platform's HttpsURLConnection; no HTTP library. Every request
 * runs on [io], never on the main thread, and is bounded by connect and read timeouts.
 */
class OpenMeteoClient(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Int = 10_000,
) : WeatherApi {

    override suspend fun forecast(place: Place, fetchedAt: Long): WeatherReport =
        OpenMeteo.parseForecast(get(OpenMeteo.forecastUrl(place.latitude, place.longitude)), place, fetchedAt)

    override suspend fun searchPlaces(query: String, language: String): List<Place> =
        OpenMeteo.parsePlaces(get(OpenMeteo.searchUrl(query, language)))

    private suspend fun get(url: String): String = withContext(io) {
        // HTTPS only; the manifest also forbids cleartext traffic.
        val connection = URL(url).openConnection() as? HttpsURLConnection
            ?: throw WeatherException(WeatherFailure.Unreachable)
        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status !in 200..299) throw WeatherException(WeatherFailure.ServerError)
            connection.inputStream.use { input ->
                val bytes = input.readNBytesCompat(MAX_BODY_BYTES + 1)
                if (bytes.size > MAX_BODY_BYTES) throw WeatherException(WeatherFailure.BadResponse)
                String(bytes, Charsets.UTF_8)
            }
        } catch (e: IOException) {
            throw WeatherException(WeatherFailure.Unreachable, e)
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        /** Both responses are a few kilobytes; anything far larger is not what was asked for. */
        const val MAX_BODY_BYTES = 256 * 1024
    }
}
