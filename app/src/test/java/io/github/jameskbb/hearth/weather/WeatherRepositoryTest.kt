package io.github.jameskbb.hearth.weather

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refresh schedule against a fake API on virtual time: no network, no real clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeatherRepositoryTest {
    private val minute = 60_000L
    private val base = 1_800_000_000_000L
    private val place = Place("Springfield", "Illinois", "United States", 39.8017, -89.6437)

    private class FakeApi(private val clockBase: Long) : WeatherApi {
        val calls = mutableListOf<Long>()
        val searches = mutableListOf<String>()
        var fail: WeatherFailure? = null
        override suspend fun forecast(place: Place, fetchedAt: Long): WeatherReport {
            calls += fetchedAt - clockBase
            fail?.let { throw WeatherException(it) }
            return WeatherReport(place.latitude, place.longitude, fetchedAt, 17.8, 21.1, 11.2, 2, true)
        }
        override suspend fun searchPlaces(query: String, language: String): List<Place> {
            searches += query
            return emptyList()
        }
    }

    private class MemoryCache(var value: WeatherReport? = null) : WeatherCache {
        override suspend fun read() = value
        override suspend fun write(report: WeatherReport) {
            value = report
        }
    }

    private class Setup(val api: FakeApi, val cache: MemoryCache, val settings: MutableStateFlow<WeatherSettings>, val repo: WeatherRepository)

    private fun TestScope.setup(
        settings: WeatherSettings = WeatherSettings(enabled = true, place = place),
        cached: WeatherReport? = null,
    ): Setup {
        val api = FakeApi(base)
        val cache = MemoryCache(cached)
        val flow = MutableStateFlow(settings)
        val repo = WeatherRepository(api, cache, flow, backgroundScope, now = { base + testScheduler.currentTime })
        runCurrent()
        return Setup(api, cache, flow, repo)
    }

    @Test fun offMeansNoRequestsEvenInFront() = runTest {
        val s = setup(WeatherSettings(enabled = false, place = place))
        s.repo.setForeground(true)
        advanceTimeBy(24 * 60 * minute)
        assertEquals(emptyList<Long>(), s.api.calls)
        assertEquals(emptyList<Place>(), s.repo.searchPlaces("Spr", "en"))
        assertEquals(emptyList<String>(), s.api.searches)
    }

    @Test fun noCityMeansNoRequests() = runTest {
        val s = setup(WeatherSettings(enabled = true, place = null))
        s.repo.setForeground(true)
        advanceTimeBy(60 * minute)
        assertEquals(emptyList<Long>(), s.api.calls)
    }

    @Test fun nothingHappensInTheBackground() = runTest {
        val s = setup()
        advanceTimeBy(5 * 60 * minute)
        assertEquals(emptyList<Long>(), s.api.calls)
    }

    @Test fun fetchesOnComingToTheFrontThenEveryThirtyMinutes() = runTest {
        val s = setup()
        s.repo.setForeground(true)
        runCurrent()
        assertEquals(listOf(0L), s.api.calls)
        assertEquals(base, s.repo.state.value.report?.fetchedAt)
        assertEquals(s.repo.state.value.report, s.cache.value)
        advanceTimeBy(30 * minute + 1)
        assertEquals(listOf(0L, 30 * minute), s.api.calls)
    }

    @Test fun goingToTheBackgroundCancelsThePendingRefresh() = runTest {
        val s = setup()
        s.repo.setForeground(true)
        runCurrent()
        s.repo.setForeground(false)
        advanceTimeBy(3 * 60 * minute)
        assertEquals(listOf(0L), s.api.calls)
        // Back in front after three hours: stale, so it fetches at once.
        s.repo.setForeground(true)
        runCurrent()
        assertEquals(listOf(0L, 3 * 60 * minute), s.api.calls)
    }

    @Test fun aFreshCachedResultIsShownWithoutARequestOnLaunch() = runTest {
        val cached = WeatherReport(place.latitude, place.longitude, base - 10 * minute, 5.0, 8.0, 1.0, 0, true)
        val s = setup(cached = cached)
        assertEquals(cached, s.repo.state.value.report)
        s.repo.setForeground(true)
        runCurrent()
        assertEquals(emptyList<Long>(), s.api.calls)
        advanceTimeBy(20 * minute + 1)
        assertEquals(listOf(20 * minute), s.api.calls)
    }

    @Test fun aCachedResultForAnotherCityIsIgnored() = runTest {
        val cached = WeatherReport(51.5, -0.12, base, 5.0, 8.0, 1.0, 0, true)
        val s = setup(cached = cached)
        assertNull(s.repo.state.value.report)
    }

    @Test fun failuresBackOffAndKeepTheLastGoodResult() = runTest {
        val s = setup()
        s.repo.setForeground(true)
        runCurrent()
        val good = s.repo.state.value.report
        s.api.fail = WeatherFailure.Unreachable
        advanceTimeBy(30 * minute + 1)
        assertEquals(WeatherFailure.Unreachable, s.repo.state.value.failure)
        assertEquals(base + 35 * minute, s.repo.state.value.retryAt)
        assertEquals(good, s.repo.state.value.report)
        advanceTimeBy(5 * minute)
        assertEquals(listOf(0L, 30 * minute, 35 * minute), s.api.calls)
        advanceTimeBy(15 * minute)
        assertEquals(listOf(0L, 30 * minute, 35 * minute, 50 * minute), s.api.calls)
        s.api.fail = null
        advanceTimeBy(30 * minute)
        assertEquals(80 * minute, s.api.calls.last())
        assertNull(s.repo.state.value.failure)
        assertNull(s.repo.state.value.retryAt)
    }

    @Test fun changingTheCityDropsTheOldResultAndFetchesAtOnce() = runTest {
        val s = setup()
        s.repo.setForeground(true)
        runCurrent()
        val other = Place("Reykjavík", "Capital Region", "Iceland", 64.1355, -21.8954)
        advanceTimeBy(minute)
        s.settings.value = WeatherSettings(enabled = true, place = other)
        runCurrent()
        assertEquals(listOf(0L, minute), s.api.calls)
        assertTrue(s.repo.state.value.report!!.isFor(other))
    }

    @Test fun turningWeatherOffStopsRefreshing() = runTest {
        val s = setup()
        s.repo.setForeground(true)
        runCurrent()
        s.settings.value = WeatherSettings(enabled = false, place = place)
        advanceTimeBy(5 * 60 * minute)
        assertEquals(listOf(0L), s.api.calls)
    }
}
