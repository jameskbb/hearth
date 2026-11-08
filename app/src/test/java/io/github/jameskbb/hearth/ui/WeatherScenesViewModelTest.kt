package io.github.jameskbb.hearth.ui

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
import io.github.jameskbb.hearth.theme.MoodRule
import io.github.jameskbb.hearth.theme.SceneForecast
import io.github.jameskbb.hearth.theme.SceneMood
import io.github.jameskbb.hearth.theme.SceneSky
import io.github.jameskbb.hearth.theme.SceneTemp
import io.github.jameskbb.hearth.theme.SceneWeather
import io.github.jameskbb.hearth.theme.ThemeId
import io.github.jameskbb.hearth.theme.ThemeMode
import io.github.jameskbb.hearth.theme.ThemePeriod
import io.github.jameskbb.hearth.theme.ThemeScheduleConfig
import io.github.jameskbb.hearth.theme.VariantRule
import io.github.jameskbb.hearth.ui.FakeThemeCatalog.Companion.info
import io.github.jameskbb.hearth.weather.Place
import io.github.jameskbb.hearth.weather.WeatherReport
import io.github.jameskbb.hearth.weather.WeatherState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Weather scenes in the view model (decision 0013): the mood, the hold, variants on screen and selection. */
@OptIn(ExperimentalCoroutinesApi::class)
class WeatherScenesViewModelTest {
    private class Prefs(initial: HearthPrefs) : PrefsRepository {
        val state = MutableStateFlow(initial)
        override val prefs = state
        override suspend fun update(transform: (HearthPrefs) -> HearthPrefs) {
            state.value = transform(state.value)
        }
    }

    private class Container(override val prefs: Prefs, override val themes: FakeThemeCatalog) : AppContainer {
        override val catalog: AppCatalog = object : AppCatalog {
            override val apps = MutableStateFlow<List<AppEntry>>(emptyList())
            override val loaded = MutableStateFlow(true)
            override suspend fun refresh() = Unit
        }
        override val artwork: ArtworkLoader = object : ArtworkLoader {
            override suspend fun load(entry: AppEntry): AppArtwork? = null
        }
        override val launcher: AppLauncher = object : AppLauncher {
            override fun launch(key: AppKey) = LaunchResult.Launched
        }
        override val systemRoutes: SystemRoutes = object : SystemRoutes {
            override fun isAvailable(route: SystemRoute) = false
            override fun open(route: SystemRoute) = false
        }
        override val displayFacts: DisplayFactsReader = object : DisplayFactsReader {
            override fun read() = DisplayFacts(1920, 1080, 320, 60f, emptyList(), false, emptyList())
        }
        override val updates = FakeUpdates()
        override val updateInstaller = FakeUpdateInstaller()
        override val weather = FakeWeather()
        override val tvInputs = FakeTvInputs()
    }

    private val place = Place("Example", null, null, 10.0, 20.0)
    private val start = LocalDateTime.of(2026, 11, 3, 18, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val scheduler = TestCoroutineScheduler()
    private fun now() = start + scheduler.currentTime
    private val minute = 60_000L

    private val dusk = ThemeId("dusk")
    private val desert = ThemeId("desert-twilight")
    private val rules = listOf(
        VariantRule("snow", MoodRule(sky = setOf(SceneSky.Snow))),
        VariantRule("rain", MoodRule(sky = setOf(SceneSky.Rain))),
    )
    private val packs = listOf(
        info("dusk").copy(weather = rules),
        info("desert-twilight").copy(weather = listOf(VariantRule("fog", MoodRule(sky = setOf(SceneSky.Fog))))),
    )

    private lateinit var prefs: Prefs
    private lateinit var themes: FakeThemeCatalog
    private lateinit var container: Container

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        initial: HearthPrefs = HearthPrefs(seeded = true, theme = dusk, weatherEnabled = true, weatherPlace = place),
        before: () -> Unit = {},
    ): HearthViewModel {
        prefs = Prefs(initial)
        themes = FakeThemeCatalog(packs)
        container = Container(prefs, themes)
        before()
        return HearthViewModel(container, SavedStateHandle(), now = ::now, zone = { ZoneOffset.UTC })
    }

    /** A fresh report, as the weather service publishes one. WMO 71 is snow, 61 rain, 45 fog, 0 clear. */
    private fun report(code: Int, celsius: Double = 2.0, daily: Int? = null, enabled: Boolean = true) {
        container.weather.state.value = WeatherState(
            enabled = enabled,
            place = place,
            report = WeatherReport(place.latitude, place.longitude, now(), celsius, null, null, code, true, daily),
        )
    }

    private fun HearthViewModel.variant() = activeTheme.value?.variant

    @Test fun theSceneFollowsTheWeatherWhileWeatherIsOn() {
        val vm = vm()
        assertNull(vm.sceneMood.value)
        assertNull(vm.variant())
        report(71, celsius = -3.0, daily = 71)
        assertEquals(SceneMood(SceneSky.Snow, SceneTemp.Freezing), vm.sceneMood.value)
        assertEquals("snow", vm.variant())
        assertEquals(dusk to "snow", themes.variantLoads.last())
    }

    @Test fun turningScenesOffOrWeatherOffReturnsToThePlainSceneAtOnce() {
        val vm = vm()
        report(61)
        assertEquals("rain", vm.variant())
        prefs.state.value = prefs.state.value.copy(weatherScenes = false)
        assertNull(vm.sceneMood.value)
        assertNull(vm.variant())
        vm.toggleWeatherScenes()
        assertEquals("rain", vm.variant())
        report(61, enabled = false)
        assertNull(vm.variant())
    }

    @Test fun aStaleReportReturnsToThePlainScene() {
        val vm = vm()
        report(61)
        assertEquals("rain", vm.variant())
        scheduler.advanceTimeBy(3 * 60 * minute + 2)
        scheduler.runCurrent()
        assertNull(vm.sceneMood.value)
        assertNull(vm.variant())
    }

    @Test fun aNewMoodWaitsFortyFiveMinutesThenShowsWithoutAnotherReport() {
        val vm = vm()
        report(61)
        assertEquals("rain", vm.variant())
        scheduler.advanceTimeBy(30 * minute)
        scheduler.runCurrent()
        report(71, celsius = -1.0)
        assertEquals("rain", vm.variant())
        scheduler.advanceTimeBy(15 * minute - 2)
        scheduler.runCurrent()
        assertEquals("rain", vm.variant())
        scheduler.advanceTimeBy(5)
        scheduler.runCurrent()
        assertEquals("snow", vm.variant())
    }

    @Test fun aMoodThatPicksNoVariantShowsThePlainScene() {
        val vm = vm()
        report(0, celsius = 12.0)
        assertEquals(SceneMood(SceneSky.Clear, SceneTemp.Mild), vm.sceneMood.value)
        assertNull(vm.variant())
        // Snow forecast with a clear sky: Dusk has no entry for it, and clear never widens.
        assertEquals(null, packs[0].variantFor(SceneMood(SceneSky.Clear, SceneTemp.Cold, SceneForecast.Snow)))
    }

    @Test fun aShuffledPickPrefersAMatchAndWeatherNeverRerollsIt() {
        val evening = ThemeScheduleConfig(listOf(ThemePeriod("evening", "Evening", 0, listOf(dusk, desert), shuffle = true)))
        val follow = HearthPrefs(
            seeded = true, themeMode = ThemeMode.FollowTheDay, themeSchedule = evening,
            weatherEnabled = true, weatherPlace = place,
        )
        for ((code, preferred) in listOf(45 to desert, 61 to dusk)) {
            val vm = vm(follow) { report(code, celsius = 5.0) }
            assertEquals(preferred, vm.activeThemeId.value)
            assertEquals(preferred, prefs.state.value.followDaySelections["evening"]?.themeId)
            // Later weather changes the look of the chosen pack only.
            scheduler.advanceTimeBy(SceneWeather.HOLD_MILLIS)
            scheduler.runCurrent()
            report(if (code == 45) 61 else 45, celsius = 5.0)
            assertEquals(preferred, vm.activeThemeId.value)
        }
    }
}
