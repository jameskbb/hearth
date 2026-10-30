package io.github.jameskbb.hearth.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherPolicyTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val now = 1_800_000_000_000L
    private val place = Place("Springfield", "Illinois", "United States", 39.8017, -89.6437)

    private fun report(fetchedAt: Long, code: Int = 2) =
        WeatherReport(place.latitude, place.longitude, fetchedAt, 17.8, 21.1, 11.2, code, isDay = false)

    // region staleness

    @Test fun currentUpToThreeHours() {
        assertTrue(WeatherPolicy.isCurrent(now, now))
        assertTrue(WeatherPolicy.isCurrent(now - 3 * hour, now))
        assertFalse(WeatherPolicy.isCurrent(now - 3 * hour - 1, now))
    }

    @Test fun aResultFromTheFutureIsNotTrustedBeyondSmallSkew() {
        assertTrue(WeatherPolicy.isCurrent(now + 4 * minute, now))
        assertFalse(WeatherPolicy.isCurrent(now + 10 * minute, now))
    }

    @Test fun glanceNeedsWeatherOnACityAndACurrentResultForThatCity() {
        val on = WeatherState(enabled = true, place = place, report = report(now - hour))
        assertNotNull(WeatherPolicy.glance(on, now))
        assertNull(WeatherPolicy.glance(on.copy(enabled = false), now))
        assertNull(WeatherPolicy.glance(on.copy(place = null), now))
        assertNull(WeatherPolicy.glance(on.copy(report = null), now))
        assertNull(WeatherPolicy.glance(on.copy(report = report(now - 4 * hour)), now))
        val elsewhere = place.copy(latitude = 51.5, longitude = -0.12)
        assertNull(WeatherPolicy.glance(on.copy(place = elsewhere), now))
    }

    @Test fun glanceCarriesConditionRangeAndExpiry() {
        val g = WeatherPolicy.glance(WeatherState(enabled = true, place = place, report = report(now)), now)!!
        assertEquals(Condition.PartlyCloudy, g.condition)
        assertFalse(g.isDay)
        assertEquals(21.1, g.highC!!, 0.0)
        assertEquals(now + 3 * hour, g.expiresAt)
        val unknown = WeatherPolicy.glance(WeatherState(enabled = true, place = place, report = report(now, 42)), now)!!
        assertNull(unknown.condition)
    }

    // endregion

    // region refresh and backoff

    @Test fun neverFetchedIsDueNow() {
        assertEquals(now, WeatherPolicy.nextAttemptAt(null, null, 0, now))
    }

    @Test fun refreshesThirtyMinutesAfterTheLastGoodResult() {
        assertEquals(now + 10 * minute, WeatherPolicy.nextAttemptAt(now - 20 * minute, null, 0, now))
        // Stale on resume: due in the past, so the caller fetches at once.
        assertTrue(WeatherPolicy.nextAttemptAt(now - 2 * hour, null, 0, now) < now)
    }

    @Test fun backsOffAfterConsecutiveFailures() {
        assertEquals(5 * minute, WeatherPolicy.backoff(1))
        assertEquals(15 * minute, WeatherPolicy.backoff(2))
        assertEquals(30 * minute, WeatherPolicy.backoff(3))
        assertEquals(60 * minute, WeatherPolicy.backoff(4))
        assertEquals(60 * minute, WeatherPolicy.backoff(40))
        assertEquals(now + 15 * minute, WeatherPolicy.nextAttemptAt(now - hour, now, 2, now))
    }

    @Test fun failuresWinOverTheRefreshInterval() {
        // The last good result is old, but the last attempt just failed: wait out the backoff.
        assertEquals(now + 5 * minute, WeatherPolicy.nextAttemptAt(now - 5 * hour, now, 1, now))
    }

    @Test fun aClockThatMovedBackDoesNotStallRefreshing() {
        // The last result claims to be from tomorrow: refresh now rather than in a day.
        assertEquals(now, WeatherPolicy.nextAttemptAt(now + 24 * hour, null, 0, now))
        assertEquals(now, WeatherPolicy.nextAttemptAt(null, now + 24 * hour, 3, now))
    }

    // endregion
}
