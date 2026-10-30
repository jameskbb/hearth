package io.github.jameskbb.hearth.weather

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** The last good result, kept across restarts so a fresh one shows at once on launch. */
interface WeatherCache {
    suspend fun read(): WeatherReport?
    suspend fun write(report: WeatherReport)
}

/**
 * [WeatherCache] in one small JSON file in the app's private cache directory. Written to a
 * temporary file and renamed, so a crash mid-write never leaves a half-written cache. A missing
 * or unreadable file reads as no cache.
 */
class FileWeatherCache(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : WeatherCache {
    override suspend fun read(): WeatherReport? = withContext(io) {
        try {
            if (file.isFile) WeatherCacheCodec.decode(file.readText()) else null
        } catch (_: IOException) {
            null
        }
    }

    override suspend fun write(report: WeatherReport) = withContext(io) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(WeatherCacheCodec.encode(report))
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (_: IOException) {
            // Only a cache: the next launch fetches again.
        }
        Unit
    }
}

/** Pure JSON encoding of [WeatherReport] for the cache file. */
object WeatherCacheCodec {
    private const val VERSION = 1

    fun encode(r: WeatherReport): String = JSONObject().apply {
        put("v", VERSION)
        put("lat", r.latitude)
        put("lon", r.longitude)
        put("at", r.fetchedAt)
        put("t", r.temperatureC)
        r.highC?.let { put("hi", it) }
        r.lowC?.let { put("lo", it) }
        put("code", r.weatherCode)
        put("day", r.isDay)
    }.toString()

    fun decode(text: String): WeatherReport? = try {
        val o = JSONObject(text)
        if (o.optInt("v") != VERSION) {
            null
        } else {
            WeatherReport(
                latitude = o.getDouble("lat"),
                longitude = o.getDouble("lon"),
                fetchedAt = o.getLong("at"),
                temperatureC = o.getDouble("t"),
                highC = if (o.has("hi")) o.getDouble("hi") else null,
                lowC = if (o.has("lo")) o.getDouble("lo") else null,
                weatherCode = o.getInt("code"),
                isDay = o.getBoolean("day"),
            ).takeIf { Place.isValid(it.latitude, it.longitude) && it.temperatureC.isFinite() }
        }
    } catch (_: JSONException) {
        null
    }
}
