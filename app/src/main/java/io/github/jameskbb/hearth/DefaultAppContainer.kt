package io.github.jameskbb.hearth

import android.app.Application
import android.content.ComponentCallbacks2
import io.github.jameskbb.hearth.apps.AppCatalog
import io.github.jameskbb.hearth.apps.AppLauncher
import io.github.jameskbb.hearth.apps.ArtworkLoader
import io.github.jameskbb.hearth.apps.CachingArtworkLoader
import io.github.jameskbb.hearth.apps.IntentAppLauncher
import io.github.jameskbb.hearth.apps.PackageManagerAppCatalog
import io.github.jameskbb.hearth.prefs.DataStorePrefsRepository
import io.github.jameskbb.hearth.prefs.FavoritesOps
import io.github.jameskbb.hearth.prefs.PrefsRepository
import io.github.jameskbb.hearth.system.AndroidDisplayFactsReader
import io.github.jameskbb.hearth.system.AndroidSystemRoutes
import io.github.jameskbb.hearth.system.AndroidTvInputs
import io.github.jameskbb.hearth.system.DisplayFactsReader
import io.github.jameskbb.hearth.system.SystemRoutes
import io.github.jameskbb.hearth.theme.AndroidThemeCatalog
import io.github.jameskbb.hearth.theme.ThemeCatalog
import io.github.jameskbb.hearth.system.TvInputs
import io.github.jameskbb.hearth.ui.state.UiLogic
import io.github.jameskbb.hearth.weather.FileWeatherCache
import io.github.jameskbb.hearth.weather.OpenMeteoClient
import io.github.jameskbb.hearth.weather.WeatherRepository
import io.github.jameskbb.hearth.weather.WeatherService
import io.github.jameskbb.hearth.weather.WeatherSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import kotlinx.coroutines.launch

/**
 * The production [AppContainer]: one instance per process, built in [HearthApp.onCreate].
 * Every dependency is a hand-wired singleton sharing one application [scope], which lives
 * as long as the process (a failing child does not cancel its siblings).
 *
 * On creation it starts the catalog (first scan plus package broadcasts) and runs
 * [seedFavoritesOnce]. The container is the only place that seeds; the view model just waits
 * for [io.github.jameskbb.hearth.prefs.HearthPrefs.seeded].
 */
class DefaultAppContainer(app: Application) : AppContainer {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val catalogImpl = PackageManagerAppCatalog(app, scope)
    private val artworkImpl = CachingArtworkLoader(app, scope)

    override val catalog: AppCatalog get() = catalogImpl
    override val artwork: ArtworkLoader get() = artworkImpl
    override val launcher: AppLauncher = IntentAppLauncher(app)
    override val prefs: PrefsRepository = DataStorePrefsRepository.create(app, scope)
    override val systemRoutes: SystemRoutes = AndroidSystemRoutes(app)
    override val displayFacts: DisplayFactsReader = AndroidDisplayFactsReader(app)

    /** Opt-in weather. Makes no request unless the user turned it on and chose a city. */
    override val weather: WeatherService = WeatherRepository(
        api = OpenMeteoClient(),
        cache = FileWeatherCache(File(app.cacheDir, "weather.json")),
        settings = prefs.prefs.map { WeatherSettings(it.weatherEnabled, it.weatherPlace) },
        scope = scope,
    )

    /** Theme packs: bundled and installed; the first scan starts now. */
    override val themes: ThemeCatalog = AndroidThemeCatalog(app, scope)
    /** HDMI inputs; empty on devices without them (decision 0007). */
    private val tvInputsImpl = AndroidTvInputs(app, scope)
    override val tvInputs: TvInputs get() = tvInputsImpl

    init {
        catalogImpl.start()
        tvInputsImpl.start()
        scope.launch { seedFavoritesOnce(prefs, catalog) }
    }

    fun onTrimMemory(level: Int) {
        artworkImpl.onTrimMemory(level)
        // In the background the theme's decoded layers (up to 32 MB) are the largest thing Hearth holds.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) themes.release()
    }
}

/**
 * First-run seeding, owned by the container: the first time the catalog lists any app, the
 * favorites become its TV apps in label order and prefs are marked seeded. Waiting for a
 * non-empty catalog means a first scan that finds nothing (or fails) does not lock in an empty
 * list; Home shows "No apps found" meanwhile (see HearthViewModel.home). [FavoritesOps.seed] is
 * a no-op once seeded, so this is safe on every start.
 */
internal suspend fun seedFavoritesOnce(prefs: PrefsRepository, catalog: AppCatalog) {
    if (prefs.prefs.first().seeded) return
    val apps = catalog.apps.first { it.isNotEmpty() }
    prefs.update { p -> FavoritesOps.seed(p, UiLogic.seedCandidates(apps)) }
}
