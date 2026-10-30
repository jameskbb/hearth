package io.github.jameskbb.hearth.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.SavedStateHandle
import io.github.jameskbb.hearth.AppContainer
import io.github.jameskbb.hearth.apps.AppArtwork
import io.github.jameskbb.hearth.apps.AppCatalog
import io.github.jameskbb.hearth.apps.AppEntry
import io.github.jameskbb.hearth.apps.AppKey
import io.github.jameskbb.hearth.apps.AppLauncher
import io.github.jameskbb.hearth.apps.ArtworkLoader
import io.github.jameskbb.hearth.apps.LaunchResult
import io.github.jameskbb.hearth.prefs.HearthPrefs
import io.github.jameskbb.hearth.prefs.PrefsRepository
import io.github.jameskbb.hearth.system.DisplayFacts
import io.github.jameskbb.hearth.system.DisplayFactsReader
import io.github.jameskbb.hearth.system.SystemRoute
import io.github.jameskbb.hearth.system.SystemRoutes
import io.github.jameskbb.hearth.theme.LoadedTheme
import io.github.jameskbb.hearth.theme.ThemeCatalog
import io.github.jameskbb.hearth.theme.ThemeFailure
import io.github.jameskbb.hearth.theme.ThemeId
import io.github.jameskbb.hearth.theme.ThemeInfo
import io.github.jameskbb.hearth.system.TvInput
import io.github.jameskbb.hearth.system.TvInputs
import io.github.jameskbb.hearth.ui.HearthRoot
import io.github.jameskbb.hearth.ui.HearthViewModel
import io.github.jameskbb.hearth.weather.Place
import io.github.jameskbb.hearth.weather.WeatherService
import io.github.jameskbb.hearth.weather.WeatherState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Fakes for @Preview only. Nothing here is reachable from the running app: MainActivity always
 * uses the real container from HearthApp. The labels below exist only to fill preview tiles.
 */

private fun previewApp(label: String, tv: Boolean = true) =
    AppEntry(AppKey.of("preview.${label.lowercase().filter { it.isLetter() }}", "Main"), label, tv, 0L)

private val previewApps = listOf(
    previewApp("Cinema"), previewApp("Music"), previewApp("News"), previewApp("Photos"),
    previewApp("Sports"), previewApp("Files", tv = false), previewApp("Browser", tv = false),
)

private class PreviewCatalog : AppCatalog {
    override val apps: StateFlow<List<AppEntry>> = MutableStateFlow(previewApps)
    override val loaded: StateFlow<Boolean> = MutableStateFlow(true)
    override suspend fun refresh() = Unit
}

private object PreviewArtwork : ArtworkLoader {
    override suspend fun load(entry: AppEntry): AppArtwork? = null
}

private object PreviewLauncher : AppLauncher {
    override fun launch(key: AppKey): LaunchResult = LaunchResult.NotInstalled
}

private class PreviewPrefs : PrefsRepository {
    private val state = MutableStateFlow(
        HearthPrefs(favorites = previewApps.filter { it.isTvApp }.map { it.key }, seeded = true),
    )
    override val prefs = state
    override suspend fun update(transform: (HearthPrefs) -> HearthPrefs) {
        state.value = transform(state.value)
    }
}

private object PreviewRoutes : SystemRoutes {
    override fun isAvailable(route: SystemRoute) = true
    override fun open(route: SystemRoute) = false
}

private object PreviewDisplay : DisplayFactsReader {
    override fun read() = DisplayFacts(1920, 1080, 320, 60f, emptyList(), false, emptyList())
}

/** Weather off, as it is by default: previews show Home exactly as it looks without weather. */
private object PreviewWeather : WeatherService {
    override val state: StateFlow<WeatherState> = MutableStateFlow(WeatherState())
    override fun setForeground(foreground: Boolean) = Unit
    override suspend fun searchPlaces(query: String, language: String): List<Place> = emptyList()
}

/** No theme packs: previews show the window color with Dusk's palette. */
private object PreviewThemes : ThemeCatalog {
    override val themes: StateFlow<List<ThemeInfo>> = MutableStateFlow(emptyList())
    override val failures: StateFlow<List<ThemeFailure>> = MutableStateFlow(emptyList())
    override val loaded: StateFlow<Boolean> = MutableStateFlow(true)
    override val releases: StateFlow<Int> = MutableStateFlow(0)
    override suspend fun refresh() = Unit
    override suspend fun load(id: ThemeId): LoadedTheme? = null
    override suspend fun preview(id: ThemeId): ImageBitmap? = null
    override fun releasePreviews() = Unit
    override fun release() = Unit
}

/** No inputs, as on a Fire TV stick: previews show nothing input-related. */
private object PreviewTvInputs : TvInputs {
    override val inputs: StateFlow<List<TvInput>> = MutableStateFlow(emptyList())
    override fun refresh() = Unit
    override fun switchTo(id: String): LaunchResult = LaunchResult.Failed("preview")
}

private class PreviewContainer : AppContainer {
    override val catalog: AppCatalog = PreviewCatalog()
    override val artwork: ArtworkLoader = PreviewArtwork
    override val launcher: AppLauncher = PreviewLauncher
    override val prefs: PrefsRepository = PreviewPrefs()
    override val systemRoutes: SystemRoutes = PreviewRoutes
    override val displayFacts: DisplayFactsReader = PreviewDisplay
    override val weather: WeatherService = PreviewWeather
    override val themes: ThemeCatalog = PreviewThemes
    override val tvInputs: TvInputs = PreviewTvInputs
}

@Preview(name = "Home", widthDp = 960, heightDp = 540)
@Composable
private fun HomePreview() {
    val container = remember { PreviewContainer() }
    val vm = remember { HearthViewModel(container, SavedStateHandle()) }
    HearthRoot(vm, container.artwork)
}
