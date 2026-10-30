package io.github.jameskbb.hearth

import io.github.jameskbb.hearth.apps.AppCatalog
import io.github.jameskbb.hearth.apps.AppLauncher
import io.github.jameskbb.hearth.apps.ArtworkLoader
import io.github.jameskbb.hearth.prefs.PrefsRepository
import io.github.jameskbb.hearth.system.DisplayFactsReader
import io.github.jameskbb.hearth.system.SystemRoutes
import io.github.jameskbb.hearth.theme.ThemeCatalog
import io.github.jameskbb.hearth.system.TvInputs
import io.github.jameskbb.hearth.weather.WeatherService

/** Hand-wired dependencies; no DI framework. Built once in [HearthApp]. */
interface AppContainer {
    val catalog: AppCatalog
    val artwork: ArtworkLoader
    val launcher: AppLauncher
    val prefs: PrefsRepository
    val systemRoutes: SystemRoutes
    val displayFacts: DisplayFactsReader
    val weather: WeatherService
    val themes: ThemeCatalog
    val tvInputs: TvInputs
}
