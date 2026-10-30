package io.github.jameskbb.hearth.ui

import io.github.jameskbb.hearth.weather.Place
import io.github.jameskbb.hearth.weather.WeatherService
import io.github.jameskbb.hearth.weather.WeatherState
import kotlinx.coroutines.flow.MutableStateFlow

/** An in-memory [WeatherService]: state is set by the test, searches answer from [onSearch]. */
class FakeWeather : WeatherService {
    override val state = MutableStateFlow(WeatherState())
    val foregroundCalls = mutableListOf<Boolean>()
    val searches = mutableListOf<String>()
    var onSearch: (String) -> List<Place> = { emptyList() }

    override fun setForeground(foreground: Boolean) {
        foregroundCalls += foreground
    }

    override suspend fun searchPlaces(query: String, language: String): List<Place> {
        searches += query
        return onSearch(query)
    }
}
