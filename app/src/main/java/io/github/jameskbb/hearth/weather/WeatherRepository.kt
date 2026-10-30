package io.github.jameskbb.hearth.weather

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The weather service: follows the user's settings, keeps the last good result (memory and
 * [cache]), and refreshes on a schedule from [WeatherPolicy].
 *
 * Scheduling is one coroutine that waits for the next due time with a single delay, fetches, and
 * computes the next due time; there is no ticking loop. It runs only while weather is on, a city
 * is chosen and Hearth is in front ([setForeground]). Any change to those cancels it and starts a
 * fresh one, so coming back to Hearth fetches at once when the result is due (stale, or never
 * fetched), going to the background stops all weather work, and turning weather off means no
 * request is ever made.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeatherRepository(
    private val api: WeatherApi,
    private val cache: WeatherCache,
    settings: Flow<WeatherSettings>,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : WeatherService {

    private val _state = MutableStateFlow(WeatherState())
    override val state: StateFlow<WeatherState> = _state.asStateFlow()

    private val foreground = MutableStateFlow(false)

    /** Consecutive failures since the last success, and when the last one happened. */
    private var failures = 0
    private var lastFailureAt: Long? = null

    /** The last good result for any place, as read from or written to [cache]. */
    private var cached: WeatherReport? = null

    init {
        scope.launch {
            cached = cache.read()
            combine(settings.distinctUntilChanged(), foreground) { s, fg -> s to fg }
                .collectLatest { (s, fg) ->
                    apply(s)
                    val place = s.place
                    if (s.enabled && place != null && fg) refreshWhileInFront(place)
                }
        }
    }

    override fun setForeground(foreground: Boolean) {
        this.foreground.value = foreground
    }

    override suspend fun searchPlaces(query: String, language: String): List<Place> {
        // Weather off means no network at all, search included.
        if (!_state.value.enabled) return emptyList()
        return api.searchPlaces(query, language)
    }

    /** Takes in new settings. A different city drops the old city's result and failure history. */
    private fun apply(s: WeatherSettings) {
        // Runs on the one collector coroutine; collectLatest has already cancelled and joined the
        // previous refresh, so nothing else writes the state meanwhile.
        val prev = _state.value
        val samePlace = if (s.place == null) prev.place == null else s.place.sameLocation(prev.place)
        if (!samePlace) {
            failures = 0
            lastFailureAt = null
        }
        _state.value = prev.copy(
            enabled = s.enabled,
            place = s.place,
            report = if (samePlace && prev.report != null) prev.report else cached?.takeIf { it.isFor(s.place) },
            failure = if (samePlace) prev.failure else null,
            retryAt = if (samePlace) prev.retryAt else null,
            fetching = false,
        )
    }

    private suspend fun refreshWhileInFront(place: Place) {
        while (true) {
            val t = now()
            val due = WeatherPolicy.nextAttemptAt(_state.value.report?.fetchedAt, lastFailureAt, failures, t)
            if (due > t) delay(due - t)
            fetch(place)
        }
    }

    private suspend fun fetch(place: Place) {
        _state.update { it.copy(fetching = true) }
        try {
            val report = api.forecast(place, now())
            failures = 0
            lastFailureAt = null
            cached = report
            _state.update { it.copy(report = report, failure = null, retryAt = null, fetching = false) }
            cache.write(report)
        } catch (e: WeatherException) {
            failures++
            val at = now()
            lastFailureAt = at
            val retry = WeatherPolicy.nextAttemptAt(_state.value.report?.fetchedAt, at, failures, at)
            _state.update { it.copy(failure = e.failure, retryAt = retry, fetching = false) }
        } finally {
            // Cancelled mid-request (background, setting changed): nothing is in flight any more.
            _state.update { if (it.fetching) it.copy(fetching = false) else it }
        }
    }
}
