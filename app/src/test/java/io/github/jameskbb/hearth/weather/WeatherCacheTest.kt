package io.github.jameskbb.hearth.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WeatherCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val report = WeatherReport(39.8017, -89.6437, 1_800_000_000_000L, 17.8, 21.1, null, 2, isDay = false)

    @Test fun codecRoundTrips() {
        assertEquals(report, WeatherCacheCodec.decode(WeatherCacheCodec.encode(report)))
    }

    @Test fun codecRejectsGarbageAndOtherVersions() {
        assertNull(WeatherCacheCodec.decode(""))
        assertNull(WeatherCacheCodec.decode("{"))
        assertNull(WeatherCacheCodec.decode("""{"v":99,"lat":1,"lon":1,"at":1,"t":1,"code":1,"day":true}"""))
        assertNull(WeatherCacheCodec.decode("""{"v":1,"lat":120,"lon":1,"at":1,"t":1,"code":1,"day":true}"""))
    }

    @Test fun fileCacheWritesAndReads() = runBlocking {
        val cache = FileWeatherCache(tmp.root.resolve("weather.json"), Dispatchers.Unconfined)
        assertNull(cache.read())
        cache.write(report)
        assertEquals(report, cache.read())
        assertEquals(report, FileWeatherCache(tmp.root.resolve("weather.json"), Dispatchers.Unconfined).read())
    }

    @Test fun aCorruptFileReadsAsNoCache() = runBlocking {
        val file = tmp.root.resolve("weather.json").apply { writeText("half a json {") }
        assertNull(FileWeatherCache(file, Dispatchers.Unconfined).read())
    }
}
