package io.github.jameskbb.hearth.weather

/**
 * When to fetch and what is recent enough to show. Pure: every decision takes the time as an
 * argument, so it is unit-tested without a clock.
 */
object WeatherPolicy {
    private const val MINUTE = 60_000L

    /** A good result is refreshed after this long, while Hearth is in front. */
    const val REFRESH_MILLIS = 30 * MINUTE

    /** Older than this, a result is not shown on Home: it would pass stale weather off as current. */
    const val MAX_AGE_MILLIS = 3 * 60 * MINUTE

    /** A result timestamped this far in the future (the device clock moved back) is not trusted. */
    const val CLOCK_SKEW_MILLIS = 5 * MINUTE

    /** Waits after the 1st, 2nd, 3rd and later consecutive failures. */
    val BACKOFF_MILLIS = listOf(5 * MINUTE, 15 * MINUTE, 30 * MINUTE, 60 * MINUTE)

    fun backoff(failures: Int): Long = BACKOFF_MILLIS[(failures - 1).coerceIn(0, BACKOFF_MILLIS.lastIndex)]

    /** True when a result fetched at [fetchedAt] may be shown as current at [now]. */
    fun isCurrent(fetchedAt: Long, now: Long): Boolean {
        val age = now - fetchedAt
        return age >= -CLOCK_SKEW_MILLIS && age <= MAX_AGE_MILLIS
    }

    /** When a result fetched at [fetchedAt] stops being shown. */
    fun expiresAt(fetchedAt: Long): Long = fetchedAt + MAX_AGE_MILLIS

    /**
     * When the next request should go out, given the last good result's time, the time of the last
     * failure and how many failures came in a row since the last success.
     *
     * - Never fetched: now.
     * - After failures: the last failure plus a growing backoff (5, 15, 30, then 60 minutes).
     * - Otherwise: 30 minutes after the last good result.
     *
     * A time further ahead than any wait Hearth would choose means the clock moved back; then now.
     */
    fun nextAttemptAt(lastSuccessAt: Long?, lastFailureAt: Long?, failures: Int, now: Long): Long {
        val due = when {
            failures > 0 && lastFailureAt != null -> lastFailureAt + backoff(failures)
            lastSuccessAt != null -> lastSuccessAt + REFRESH_MILLIS
            else -> now
        }
        val longestWait = maxOf(REFRESH_MILLIS, BACKOFF_MILLIS.last())
        return if (due - now > longestWait) now else due
    }

    /** What Home shows: the last good result for the chosen city, only while it is current. */
    fun glance(state: WeatherState, now: Long): WeatherGlance? {
        if (!state.enabled || state.place == null) return null
        val r = state.report ?: return null
        if (!r.isFor(state.place) || !isCurrent(r.fetchedAt, now)) return null
        return WeatherGlance(
            condition = WeatherCodes.condition(r.weatherCode),
            isDay = r.isDay,
            temperatureC = r.temperatureC,
            highC = r.highC,
            lowC = r.lowC,
            expiresAt = expiresAt(r.fetchedAt),
        )
    }
}

/** The weather on Home: current temperature and condition, with today's high and low. */
data class WeatherGlance(
    val condition: Condition?,
    val isDay: Boolean,
    val temperatureC: Double,
    val highC: Double?,
    val lowC: Double?,
    /** When this stops being current and must leave Home. */
    val expiresAt: Long,
)
