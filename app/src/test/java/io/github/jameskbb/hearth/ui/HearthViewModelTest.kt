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
import io.github.jameskbb.hearth.ui.state.ArrangeState
import io.github.jameskbb.hearth.ui.state.FocusTarget
import io.github.jameskbb.hearth.ui.state.HomeAction
import io.github.jameskbb.hearth.ui.state.Screen
import io.github.jameskbb.hearth.weather.Place
import io.github.jameskbb.hearth.weather.TemperatureUnit
import io.github.jameskbb.hearth.weather.WeatherException
import io.github.jameskbb.hearth.weather.WeatherFailure
import io.github.jameskbb.hearth.weather.WeatherPolicy
import io.github.jameskbb.hearth.weather.WeatherReport
import io.github.jameskbb.hearth.weather.WeatherState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The view model's state machine against in-memory fakes: Back rules, screen transitions, focus
 * after removals, arrange mode, options and launch feedback. The UI is simulated by calling
 * [HearthViewModel.onFocused] where a real focus change would.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HearthViewModelTest {
    private val a = AppKey("a/A")
    private val b = AppKey("b/B")
    private val c = AppKey("c/C")
    private val d = AppKey("d/D")

    private fun entry(key: AppKey, tv: Boolean = true) = AppEntry(key, key.packageName, tv, 0L)

    private class FakeCatalog(initial: List<AppEntry>) : AppCatalog {
        override val apps = MutableStateFlow(initial)
        override val loaded = MutableStateFlow(true)
        var onRefresh: () -> Unit = {}
        override suspend fun refresh() = onRefresh()
    }

    private class FakePrefs(initial: HearthPrefs) : PrefsRepository {
        val state = MutableStateFlow(initial)
        override val prefs = state
        override suspend fun update(transform: (HearthPrefs) -> HearthPrefs) {
            state.value = transform(state.value)
        }
    }

    private class FakeLauncher : AppLauncher {
        var result: LaunchResult = LaunchResult.Launched
        val launched = mutableListOf<AppKey>()
        override fun launch(key: AppKey): LaunchResult {
            launched += key
            return result
        }
    }

    private class FakeContainer(
        override val catalog: FakeCatalog,
        override val prefs: FakePrefs,
        override val launcher: FakeLauncher = FakeLauncher(),
    ) : AppContainer {
        override val artwork: ArtworkLoader = object : ArtworkLoader {
            override suspend fun load(entry: AppEntry): AppArtwork? = null
        }
        override val systemRoutes: SystemRoutes = object : SystemRoutes {
            override fun isAvailable(route: SystemRoute) = false
            override fun open(route: SystemRoute) = false
        }
        override val displayFacts: DisplayFactsReader = object : DisplayFactsReader {
            override fun read() = DisplayFacts(1920, 1080, 320, 60f, emptyList(), false, emptyList())
        }
        override val weather = FakeWeather()
        override val themes = FakeThemeCatalog()
        override val tvInputs = FakeTvInputs()
    }

    private lateinit var catalog: FakeCatalog
    private lateinit var prefs: FakePrefs
    private lateinit var container: FakeContainer

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        favorites: List<AppKey> = listOf(a, b, c, d),
        apps: List<AppEntry> = listOf(entry(a), entry(b), entry(c), entry(d)),
        seeded: Boolean = true,
        saved: SavedStateHandle = SavedStateHandle(),
    ): HearthViewModel {
        catalog = FakeCatalog(apps)
        prefs = FakePrefs(HearthPrefs(favorites = favorites, seeded = seeded))
        container = FakeContainer(catalog, prefs)
        return HearthViewModel(container, saved)
    }

    private fun HearthViewModel.focusTile(key: AppKey) = onFocused(Screen.Home, FocusTarget.Tile(key))
    private fun HearthViewModel.requested() = focusRequest.value?.target
    private fun HearthViewModel.order() = favorites.value.map { it.key }

    // region initial focus and Back at the Home root

    @Test fun initialFocusIsTheDefaultWithoutASavedTile() {
        assertEquals(FocusTarget.Default, vm().requested())
    }

    @Test fun initialFocusRestoresTheSavedTileAfterProcessDeath() {
        val vm = vm(saved = SavedStateHandle(mapOf("last_home_tile" to c.id)))
        assertEquals(FocusTarget.Tile(c), vm.requested())
        assertEquals(c, vm.lastHomeTile.value)
    }

    @Test fun backAtRootMovesFocusToTheFirstFavorite() {
        val vm = vm()
        vm.focusTile(c)
        vm.onBack()
        assertEquals(FocusTarget.Tile(a), vm.requested())
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test fun backOnTheFirstFavoriteDoesNothing() {
        val vm = vm()
        vm.focusTile(a)
        val before = vm.focusRequest.value
        vm.onBack()
        assertEquals(before, vm.focusRequest.value)
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test fun backWithNoFavoritesTargetsAllApps() {
        val vm = vm(favorites = emptyList())
        vm.onFocused(Screen.Home, FocusTarget.Action(HomeAction.Settings))
        vm.onBack()
        assertEquals(FocusTarget.Action(HomeAction.AllApps), vm.requested())
    }

    // endregion

    // region sub-screens and the HOME intent

    @Test fun backFromASubScreenReturnsToTheLastTileNotTheAction() {
        val vm = vm()
        vm.focusTile(c)
        vm.onFocused(Screen.Home, FocusTarget.Action(HomeAction.Settings))
        vm.onHomeAction(HomeAction.Settings)
        assertEquals(Screen.Settings, vm.screen.value)
        vm.onBack()
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(FocusTarget.Tile(c), vm.requested())
    }

    @Test fun homeIntentFromAllAppsReturnsToTheLastTile() {
        val vm = vm()
        vm.focusTile(b)
        vm.onFocused(Screen.Home, FocusTarget.Action(HomeAction.AllApps))
        vm.onHomeAction(HomeAction.AllApps)
        vm.onFocused(Screen.AllApps, FocusTarget.Tile(d))
        vm.onHomeIntent()
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(FocusTarget.Tile(b), vm.requested())
        // onResume after the intent asks for the same tile, not the action.
        vm.onResume()
        assertEquals(FocusTarget.Tile(b), vm.requested())
    }

    @Test fun homeIntentClosesTheDialogAndCancelsAMove() {
        val vm = vm()
        vm.focusTile(a)
        vm.openOptions(Screen.Home, entry(a))
        vm.optionMove()
        vm.arrangeMove(1)
        assertEquals(listOf(b, a, c, d), vm.order())
        vm.openOptions(Screen.Home, entry(a)) // ignored while arranging
        assertNull(vm.options.value)
        vm.onHomeIntent()
        assertNull(vm.arrange.value)
        assertEquals(listOf(a, b, c, d), vm.order())
    }

    @Test fun backFromSettingsWithNoFavoritesLandsOnTheDefault() {
        val vm = vm(favorites = emptyList())
        vm.onHomeAction(HomeAction.Settings)
        vm.onBack()
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(FocusTarget.Default, vm.requested())
    }

    // endregion

    // region options dialog

    @Test fun backClosesTheDialogAndReturnsFocusToItsTile() {
        val vm = vm()
        vm.focusTile(c)
        vm.openOptions(Screen.Home, entry(c))
        assertEquals(c, vm.options.value?.entry?.key)
        vm.onBack()
        assertNull(vm.options.value)
        assertEquals(FocusTarget.Tile(c), vm.requested())
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test fun moveIsOfferedOnlyWithTwoOrMoreFavorites() {
        assertTrue(vm().also { it.openOptions(Screen.Home, entry(a)) }.options.value!!.canMove)
        val single = vm(favorites = listOf(a))
        single.openOptions(Screen.Home, entry(a))
        assertEquals(false, single.options.value!!.canMove)
    }

    @Test fun removeFromFavoritesMovesFocusToTheNextTile() {
        val vm = vm()
        vm.focusTile(b)
        vm.openOptions(Screen.Home, entry(b))
        vm.optionToggleFavorite()
        assertEquals(listOf(a, c, d), vm.order())
        assertEquals(FocusTarget.Tile(c), vm.requested())
    }

    @Test fun addToFavoritesFromAllApps() {
        val vm = vm(favorites = listOf(a))
        vm.onHomeAction(HomeAction.AllApps)
        vm.openOptions(Screen.AllApps, entry(c))
        assertEquals(false, vm.options.value!!.isFavorite)
        vm.optionToggleFavorite()
        assertEquals(listOf(a, c), vm.order())
        assertEquals(FocusRequest(Screen.AllApps, FocusTarget.Tile(c), vm.focusRequest.value!!.seq), vm.focusRequest.value)
    }

    // endregion

    // region focus after hide, removal and uninstall

    @Test fun hidingTheLastTileMovesFocusToThePreviousOne() {
        val vm = vm()
        vm.focusTile(d)
        vm.openOptions(Screen.Home, entry(d))
        vm.optionHide()
        assertEquals(listOf(a, b, c), vm.order())
        assertEquals(FocusTarget.Tile(c), vm.requested())
    }

    @Test fun removingTheOnlyFavoriteMovesFocusToAllApps() {
        val vm = vm(favorites = listOf(a))
        vm.focusTile(a)
        vm.openOptions(Screen.Home, entry(a))
        vm.optionToggleFavorite()
        assertTrue(vm.order().isEmpty())
        assertEquals(FocusTarget.Action(HomeAction.AllApps), vm.requested())
    }

    @Test fun uninstallingTheFocusedFavoriteMovesFocusToTheNextTile() {
        val vm = vm()
        vm.focusTile(b)
        catalog.apps.value = listOf(entry(a), entry(c), entry(d))
        assertEquals(FocusTarget.Tile(c), vm.requested())
        // The stored favorite is kept so a reinstall brings it back in place.
        assertEquals(listOf(a, b, c, d), prefs.state.value.favorites)
    }

    @Test fun hidingTheFocusedAllAppsTileMovesToItsNeighbor() {
        val vm = vm(favorites = listOf(a))
        vm.onHomeAction(HomeAction.AllApps)
        vm.onFocused(Screen.AllApps, FocusTarget.Tile(d))
        vm.openOptions(Screen.AllApps, entry(d))
        vm.optionHide()
        assertEquals(FocusRequest(Screen.AllApps, FocusTarget.Tile(c), vm.focusRequest.value!!.seq), vm.focusRequest.value)
    }

    @Test fun unhideMovesFocusToTheNextHiddenRow() {
        val vm = vm()
        prefs.state.value = prefs.state.value.copy(hidden = setOf(b, c))
        vm.onHomeAction(HomeAction.Settings)
        vm.unhide(b)
        assertEquals(FocusTarget.Row(HearthViewModel.hiddenRowId(c)), vm.requested())
        assertEquals(setOf(c), prefs.state.value.hidden)
    }

    // endregion

    // region arrange mode

    @Test fun arrangeNeedsTwoFavorites() {
        val vm = vm(favorites = listOf(a))
        vm.onHomeAction(HomeAction.Arrange)
        assertNull(vm.arrange.value)
    }

    @Test fun pickMoveAndPlaceKeepsTheNewOrderAndReturnsToPicking() {
        val vm = vm()
        vm.focusTile(b)
        vm.onHomeAction(HomeAction.Arrange)
        assertEquals(ArrangeState.Picking, vm.arrange.value)
        assertEquals(FocusTarget.Tile(b), vm.requested())
        vm.onTileClick(Screen.Home, entry(b))
        assertEquals(ArrangeState.Moving(b, 1, returnToPicking = true), vm.arrange.value)
        vm.arrangeMove(1)
        vm.arrangeMove(1)
        assertEquals(listOf(a, c, d, b), vm.order())
        vm.arrangeMove(1) // already last: no change
        assertEquals(listOf(a, c, d, b), vm.order())
        vm.arrangePlace()
        assertEquals(ArrangeState.Picking, vm.arrange.value)
        assertEquals(FocusTarget.Tile(b), vm.requested())
        vm.onBack()
        assertNull(vm.arrange.value)
        assertEquals(listOf(a, c, d, b), vm.order())
    }

    @Test fun backWhileMovingRestoresTheOriginalOrder() {
        val vm = vm()
        vm.focusTile(c)
        vm.openOptions(Screen.Home, entry(c))
        vm.optionMove()
        assertEquals(ArrangeState.Moving(c, 2, returnToPicking = false), vm.arrange.value)
        vm.arrangeMove(-1)
        vm.arrangeMove(-1)
        assertEquals(listOf(c, a, b, d), vm.order())
        vm.onBack()
        assertNull(vm.arrange.value)
        assertEquals(listOf(a, b, c, d), vm.order())
        assertEquals(FocusTarget.Tile(c), vm.requested())
    }

    @Test fun moveStepsOverStoredFavoritesThatAreNotShown() {
        // b is a stored favorite that is not installed; moving c left lands it before a.
        val vm = vm(apps = listOf(entry(a), entry(c), entry(d)))
        vm.openOptions(Screen.Home, entry(c))
        vm.optionMove()
        vm.arrangeMove(-1)
        assertEquals(listOf(c, a, d), vm.order())
        assertEquals(listOf(c, a, b, d), prefs.state.value.favorites)
    }

    @Test fun moveFromAllAppsLandsOnHomeInMovingMode() {
        val vm = vm()
        vm.onHomeAction(HomeAction.AllApps)
        vm.openOptions(Screen.AllApps, entry(b))
        vm.optionMove()
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(ArrangeState.Moving(b, 1, returnToPicking = false), vm.arrange.value)
        assertEquals(FocusTarget.Tile(b), vm.requested())
    }

    @Test fun tilesDoNotLaunchWhileArranging() {
        val vm = vm()
        vm.openOptions(Screen.Home, entry(a))
        vm.optionMove()
        vm.onTileClick(Screen.Home, entry(b))
        assertTrue(container.launcher.launched.isEmpty())
    }

    // endregion

    // region launching and inline messages

    @Test fun launchFailureShowsAMessageThatClearsOnTheNextFocusMove() {
        val vm = vm()
        container.launcher.result = LaunchResult.Failed("refused")
        vm.focusTile(a)
        vm.onTileClick(Screen.Home, entry(a))
        assertEquals(InlineMessage.Kind.LaunchFailed, vm.message.value?.kind)
        vm.focusTile(a) // same tile: stays
        assertEquals(InlineMessage.Kind.LaunchFailed, vm.message.value?.kind)
        vm.focusTile(b)
        assertNull(vm.message.value)
    }

    @Test fun notInstalledMessageFollowsFocusWhenTheTileDisappears() {
        val vm = vm()
        container.launcher.result = LaunchResult.NotInstalled
        catalog.onRefresh = { catalog.apps.value = listOf(entry(a), entry(c), entry(d)) }
        vm.focusTile(b)
        vm.onTileClick(Screen.Home, entry(b))
        // The rescan dropped b; focus moved to c and the message is still readable there.
        assertEquals(FocusTarget.Tile(c), vm.requested())
        vm.focusTile(c)
        val m = vm.message.value
        assertEquals(InlineMessage.Kind.NotInstalled, m?.kind)
        assertEquals("b", m?.label)
        // The next move clears it.
        vm.focusTile(d)
        assertNull(vm.message.value)
    }

    @Test fun successfulLaunchClearsAnEarlierMessage() {
        val vm = vm()
        container.launcher.result = LaunchResult.Failed("refused")
        vm.onTileClick(Screen.Home, entry(a))
        container.launcher.result = LaunchResult.Launched
        vm.onTileClick(Screen.Home, entry(a))
        assertNull(vm.message.value)
        assertEquals(listOf(a, a), container.launcher.launched)
    }

    // endregion

    // region readiness and seeding

    @Test fun homeWaitsForSeedingWhenAppsExist() {
        val vm = vm(favorites = emptyList(), seeded = false)
        assertNull(vm.home.value)
        prefs.state.value = HearthPrefs(favorites = listOf(a, b), seeded = true)
        assertEquals(listOf(a, b), vm.order())
    }

    @Test fun anEmptyCatalogShowsNoAppsInsteadOfWaitingForever() {
        val vm = vm(favorites = emptyList(), apps = emptyList(), seeded = false)
        assertEquals(HomeContent(emptyList(), noApps = true), vm.home.value)
    }

    @Test fun theViewModelNeverSeeds() {
        vm(favorites = emptyList(), seeded = false)
        assertEquals(HearthPrefs(seeded = false), prefs.state.value)
    }

    // endregion

    // region weather settings and the city search

    private val springfield = Place("Springfield", "Illinois", "United States", 39.8017, -89.6437)

    private fun HearthViewModel.openSettings() = onHomeAction(HomeAction.Settings)

    @Test fun weatherIsOffByDefault() {
        vm()
        assertFalse(prefs.state.value.weatherEnabled)
    }

    @Test fun foregroundIsPassedToTheWeatherService() {
        val vm = vm()
        vm.onForeground(true)
        vm.onForeground(false)
        assertEquals(listOf(true, false), container.weather.foregroundCalls)
    }

    @Test fun turningWeatherOnWithoutACityOpensTheSearchOnTheField() {
        val vm = vm()
        vm.openSettings()
        vm.toggleWeather()
        assertTrue(prefs.state.value.weatherEnabled)
        assertEquals(CitySearch(), vm.citySearch.value)
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_CITY_FIELD), vm.requested())
    }

    @Test fun turningWeatherOnWithACityDoesNotOpenTheSearch() {
        val vm = vm()
        prefs.state.value = prefs.state.value.copy(weatherPlace = springfield)
        vm.openSettings()
        vm.toggleWeather()
        assertTrue(prefs.state.value.weatherEnabled)
        assertNull(vm.citySearch.value)
        vm.toggleWeather()
        assertFalse(prefs.state.value.weatherEnabled)
        assertEquals(springfield, prefs.state.value.weatherPlace)
    }

    @Test fun backClosesTheSearchAndStaysInSettingsOnTheCityRow() {
        val vm = vm()
        vm.openSettings()
        vm.openCitySearch()
        vm.onBack()
        assertNull(vm.citySearch.value)
        assertEquals(Screen.Settings, vm.screen.value)
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_CITY), vm.requested())
        vm.onBack()
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test fun theHomeIntentDropsTheSearch() {
        val vm = vm()
        vm.openSettings()
        vm.openCitySearch()
        vm.onHomeIntent()
        assertNull(vm.citySearch.value)
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test fun oneLetterDoesNotSearch() {
        val vm = vm()
        vm.openSettings()
        vm.openCitySearch()
        vm.onCityQueryChange("S")
        vm.submitCitySearch()
        assertEquals(emptyList<String>(), container.weather.searches)
        assertEquals(CitySearch.Status.Idle, vm.citySearch.value?.status)
    }

    @Test fun submittingSearchesAndFocusesTheFirstMatch() {
        val vm = vm()
        container.weather.onSearch = { listOf(springfield) }
        vm.openSettings()
        vm.openCitySearch()
        vm.onCityQueryChange("Springf")
        vm.submitCitySearch()
        assertEquals(listOf("Springf"), container.weather.searches)
        val s = vm.citySearch.value!!
        assertEquals(CitySearch.Status.Results, s.status)
        assertEquals(listOf(springfield), s.results)
        assertEquals(FocusTarget.Row(HearthViewModel.cityResultRowId(0)), vm.requested())
    }

    @Test fun noMatchesAndFailuresAreReported() {
        val vm = vm()
        vm.openSettings()
        vm.openCitySearch()
        vm.onCityQueryChange("Zzxq")
        vm.submitCitySearch()
        assertEquals(CitySearch.Status.NoMatches, vm.citySearch.value?.status)
        assertEquals("Zzxq", vm.citySearch.value?.searchedFor)

        container.weather.onSearch = { throw WeatherException(WeatherFailure.Unreachable) }
        vm.onCityQueryChange("Oslo")
        vm.submitCitySearch()
        assertEquals(CitySearch.Status.Failed, vm.citySearch.value?.status)
        assertEquals(WeatherFailure.Unreachable, vm.citySearch.value?.failure)
    }

    @Test fun choosingACityStoresItTurnsWeatherOnAndReturnsToTheCityRow() {
        val vm = vm()
        vm.openSettings()
        vm.openCitySearch()
        vm.choosePlace(springfield)
        assertEquals(springfield, prefs.state.value.weatherPlace)
        assertTrue(prefs.state.value.weatherEnabled)
        assertNull(vm.citySearch.value)
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_CITY), vm.requested())
    }

    @Test fun unitsCanBeSet() {
        val vm = vm()
        vm.setTemperatureUnit(TemperatureUnit.Celsius)
        assertEquals(TemperatureUnit.Celsius, prefs.state.value.temperatureUnit)
    }

    @Test fun homeShowsOnlyCurrentWeather() {
        val vm = vm()
        val now = System.currentTimeMillis()
        val report = WeatherReport(springfield.latitude, springfield.longitude, now, 18.0, 21.0, 9.0, 2, true)
        container.weather.state.value = WeatherState(enabled = true, place = springfield, report = report)
        assertEquals(18.0, vm.homeWeather.value!!.temperatureC, 0.0)
        container.weather.state.value = WeatherState(
            enabled = true, place = springfield, report = report.copy(fetchedAt = now - WeatherPolicy.MAX_AGE_MILLIS - 60_000),
        )
        assertNull(vm.homeWeather.value)
        container.weather.state.value = WeatherState(enabled = false, place = springfield, report = report)
        assertNull(vm.homeWeather.value)
    }

    // endregion
}
