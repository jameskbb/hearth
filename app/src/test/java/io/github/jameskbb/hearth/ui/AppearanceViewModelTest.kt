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
import io.github.jameskbb.hearth.theme.Daypart
import io.github.jameskbb.hearth.theme.ThemeId
import io.github.jameskbb.hearth.theme.ThemeMode
import io.github.jameskbb.hearth.theme.ThemeProblem
import io.github.jameskbb.hearth.theme.ThemeRotationState
import io.github.jameskbb.hearth.ui.FakeThemeCatalog.Companion.info
import io.github.jameskbb.hearth.ui.state.FocusTarget
import io.github.jameskbb.hearth.ui.state.Screen
import io.github.jameskbb.hearth.ui.theme.DefaultPalette
import io.github.jameskbb.hearth.ui.theme.toHearthPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Themes in the view model: what the backdrop shows for each choice, the Appearance panel's
 * live preview, Select and Back, theme modes, and every fallback down to the plain window color.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceViewModelTest {
    private val dusk = ThemeId("dusk")
    private val aurora = ThemeId("aurora")
    private val misty = ThemeId("misty-forest")

    private class Prefs(initial: HearthPrefs, var holdUpdates: Boolean = false) : PrefsRepository {
        val state = MutableStateFlow(initial)
        override val prefs = state
        override suspend fun update(transform: (HearthPrefs) -> HearthPrefs) {
            if (!holdUpdates) state.value = transform(state.value)
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
        override val weather = FakeWeather()
        override val tvInputs = FakeTvInputs()
    }

    private lateinit var prefs: Prefs
    private lateinit var themes: FakeThemeCatalog
    private var clock = at(18, 0) // evening

    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 10, 22, h, m).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        choice: ThemeId = dusk,
        packs: FakeThemeCatalog = FakeThemeCatalog(
            listOf(info("dusk", Daypart.Evening, window = 0xFF0A0E24.toInt()), info("aurora", Daypart.Night, window = 0xFF050A18.toInt())),
        ),
    ): HearthViewModel {
        prefs = Prefs(HearthPrefs(seeded = true, theme = choice))
        themes = packs
        return HearthViewModel(Container(prefs, themes), SavedStateHandle(), now = { clock }, zone = { ZoneOffset.UTC })
    }

    private fun HearthViewModel.shown() = activeTheme.value?.info?.id
    private fun HearthViewModel.requested() = focusRequest.value?.target
    private fun card(id: ThemeId) = FocusTarget.Row(HearthViewModel.themeCardRowId(id))
    private fun HearthViewModel.inSettings() = apply { open(Screen.Settings) }

    // region what the backdrop shows

    @Test fun theSavedThemeIsShownWithItsPalette() {
        val vm = vm(choice = aurora)
        assertEquals(aurora, vm.shown())
        assertEquals(aurora, vm.activeThemeId.value)
        assertEquals(themes.themes.value[1].palette.toHearthPalette(), vm.activePalette.value)
    }

    @Test fun anUninstalledChoiceShowsDuskAndTheRowSaysDusk() {
        val vm = vm(choice = ThemeId("removed-pack")).inSettings()
        assertEquals(dusk, vm.shown())
        assertEquals("Dusk", vm.activeThemeInfo.value?.name)
        // The panel opens on Dusk's card, the theme actually shown.
        vm.openAppearance()
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_MODE_SINGLE), vm.requested())
        // Choosing again stores a real id.
        vm.focusTheme(aurora)
        vm.chooseTheme(aurora)
        assertEquals(aurora, prefs.state.value.theme)
        assertEquals(aurora, vm.shown())
    }

    @Test fun anUninstalledChoiceComesBackWhenThePackDoes() {
        val vm = vm(choice = misty)
        assertEquals(dusk, vm.shown())
        themes.themes.value = themes.themes.value + info("misty-forest", Daypart.Morning)
        assertEquals(misty, vm.shown())
    }

    @Test fun aPackThatFailsToDecodeFallsBackToDusk() {
        val packs = FakeThemeCatalog().apply { undecodable += aurora }
        val vm = vm(choice = aurora, packs = packs)
        assertEquals(dusk, vm.shown())
        assertEquals(listOf(aurora), themes.failures.value.map { ThemeId(it.location) })
        assertEquals(ThemeProblem.UnreadableImage("sky.webp"), themes.failures.value.single().problem)
    }

    @Test fun whenDuskFailsTooOnlyTheWindowColorShows() {
        val packs = FakeThemeCatalog().apply { undecodable += setOf(aurora, dusk) }
        val vm = vm(choice = aurora, packs = packs)
        assertNull(vm.activeTheme.value)
        assertEquals(DefaultPalette, vm.activePalette.value)
    }

    @Test fun nothingLoadsBeforeTheFirstScan() {
        val packs = FakeThemeCatalog().apply { loaded.value = false }
        val vm = vm(choice = ThemeId.Auto, packs = packs)
        assertNull(vm.activeThemeId.value)
        assertNull(vm.activeTheme.value)
        assertEquals(DefaultPalette, vm.activePalette.value)
        packs.loaded.value = true
        assertEquals(dusk, vm.shown())
    }

    // endregion

    // region Auto

    @Test fun autoFollowsTheDaypart() {
        clock = at(21, 0)
        val vm = vm(choice = ThemeId.Auto)
        assertEquals(aurora, vm.shown())
        assertEquals(aurora, vm.autoThemeId.value)
    }

    @Test fun autoWithNoPackForTheDaypartKeepsDusk() {
        clock = at(10, 0) // day: no bundled pack here lists it
        val vm = vm(choice = ThemeId.Auto)
        assertEquals(dusk, vm.shown())
    }

    @Test fun autoIsReevaluatedOnResume() {
        clock = at(20, 29)
        val vm = vm(choice = ThemeId.Auto)
        assertEquals(dusk, vm.shown())
        clock = at(20, 30)
        vm.onResume()
        assertEquals(aurora, vm.shown())
    }

    // endregion

    // region the Appearance panel

    @Test fun openingSettingsRescansThemes() {
        val vm = vm()
        val before = themes.refreshes
        vm.open(Screen.Settings)
        assertEquals(before + 1, themes.refreshes)
    }

    @Test fun thePanelOpensOnTheThemeInUse() {
        val vm = vm(choice = aurora).inSettings()
        vm.openAppearance()
        assertEquals(Appearance(focused = aurora, saved = aurora), vm.appearance.value)
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_MODE_SINGLE), vm.requested())
    }

    @Test fun withAutoSavedThePanelOpensOnFollowTheDay() {
        val vm = vm(choice = ThemeId.Auto).inSettings()
        vm.openAppearance()
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_MODE_FOLLOW), vm.requested())
    }

    @Test fun focusingACardShowsItLiveWithoutSavingIt() {
        val vm = vm().inSettings()
        vm.openAppearance()
        vm.focusTheme(aurora)
        assertEquals(aurora, vm.shown())
        assertEquals(dusk, vm.activeThemeId.value)
        assertEquals(dusk, prefs.state.value.theme)
    }

    @Test fun selectingFollowTheDayKeepsAppearanceSimpleUntilScheduleIsOpened() {
        val vm = vm().inSettings()
        vm.openAppearance()
        vm.chooseThemeMode(ThemeMode.FollowTheDay)
        assertEquals(AppearancePage.Main, vm.appearance.value?.page)
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_SCHEDULE_EDIT), vm.requested())
        vm.openThemeSchedule()
        assertEquals(AppearancePage.Schedule, vm.appearance.value?.page)
        assertEquals(FocusTarget.Row(HearthViewModel.schedulePeriodRowId("morning")), vm.requested())
    }

    @Test fun backRestoresTheSavedThemeAndStaysInSettings() {
        val vm = vm().inSettings()
        vm.openAppearance()
        vm.focusTheme(aurora)
        vm.onBack()
        assertNull(vm.appearance.value)
        assertEquals(Screen.Settings, vm.screen.value)
        assertEquals(dusk, vm.shown())
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_THEME), vm.requested())
        assertEquals(1, themes.previewReleases)
        // A second Back leaves Settings as usual.
        vm.onBack()
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test fun selectKeepsTheThemeAndReturnsToTheThemeRow() {
        val vm = vm().inSettings()
        vm.openAppearance()
        vm.focusTheme(aurora)
        val loadsBefore = themes.loads.size
        vm.chooseTheme(aurora)
        assertNull(vm.appearance.value)
        assertEquals(aurora, prefs.state.value.theme)
        assertEquals(aurora, vm.shown())
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_THEME), vm.requested())
        // The backdrop never flipped back to Dusk on the way.
        assertFalse(themes.loads.drop(loadsBefore).contains(dusk))
    }

    @Test fun choosingFollowTheDayStoresTheMode() {
        val vm = vm().inSettings()
        vm.openAppearance()
        vm.chooseThemeMode(ThemeMode.FollowTheDay)
        assertEquals(ThemeMode.FollowTheDay, prefs.state.value.themeMode)
    }

    @Test fun theHomeButtonClosesThePanelAndRestoresTheSavedTheme() {
        val vm = vm().inSettings()
        vm.openAppearance()
        vm.focusTheme(aurora)
        vm.onHomeIntent()
        assertNull(vm.appearance.value)
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(dusk, vm.shown())
    }

    @Test fun whenTheFocusedPackFailsFocusMovesToItsNeighbor() {
        val packs = FakeThemeCatalog().apply { undecodable += aurora }
        val vm = vm(packs = packs).inSettings()
        vm.openAppearance()
        vm.focusTheme(aurora)
        // Aurora could not be decoded: the backdrop keeps Dusk and the card is gone.
        assertEquals(dusk, vm.shown())
        assertEquals(listOf(dusk), themes.themes.value.map { it.id })
        assertEquals(card(dusk), vm.requested())
        assertEquals(dusk, vm.appearance.value?.focused)
    }

    @Test fun backWalksFromPeriodToScheduleToAppearanceToSettings() {
        val vm = vm().inSettings()
        vm.openAppearance()
        vm.chooseThemeMode(ThemeMode.FollowTheDay)
        vm.openThemeSchedule()
        vm.openThemePeriod("evening")
        vm.openPeriodRename()
        assertEquals(AppearancePage.PeriodName, vm.appearance.value?.page)
        vm.onBack()
        assertEquals(AppearancePage.Period, vm.appearance.value?.page)
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_PERIOD_RENAME), vm.requested())
        vm.onBack()
        assertEquals(AppearancePage.Schedule, vm.appearance.value?.page)
        assertEquals(FocusTarget.Row(HearthViewModel.schedulePeriodRowId("evening")), vm.requested())
        vm.onBack()
        assertEquals(AppearancePage.Main, vm.appearance.value?.page)
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_SCHEDULE_EDIT), vm.requested())
        vm.onBack()
        assertNull(vm.appearance.value)
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_THEME), vm.requested())
    }

    @Test fun aPeriodCanBeRenamedMovedShuffledAndAssignedThemes() {
        val vm = vm().inSettings()
        vm.openAppearance()
        vm.chooseThemeMode(ThemeMode.FollowTheDay)
        vm.openThemeSchedule()
        vm.openThemePeriod("evening")
        val selections = prefs.state.value.followDaySelections
        vm.openPeriodRename()
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_PERIOD_NAME), vm.requested())
        vm.onPeriodNameChange("Late afternoon")
        vm.savePeriodName()
        assertEquals(AppearancePage.Period, vm.appearance.value?.page)
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_PERIOD_RENAME), vm.requested())
        assertEquals("Late afternoon", prefs.state.value.themeSchedule.periods.first { it.id == "evening" }.name)
        assertEquals(selections, prefs.state.value.followDaySelections)
        vm.movePeriodStart(30)
        vm.togglePeriodShuffle()
        vm.togglePeriodTheme(aurora)
        val period = prefs.state.value.themeSchedule.periods.first { it.id == "evening" }
        assertEquals(17 * 60 + 30, period.startMinute)
        assertFalse(period.shuffle)
        assertEquals(aurora, period.themeIds.first())
        assertTrue(period.themeIds.size > 1)
    }

    @Test fun restoreRecommendedScheduleReplacesEdits() {
        val vm = vm().inSettings()
        vm.openAppearance()
        vm.chooseThemeMode(ThemeMode.FollowTheDay)
        vm.openThemeSchedule()
        vm.openThemePeriod("evening")
        vm.movePeriodStart(30)
        vm.restoreRecommendedSchedule()
        assertEquals(17 * 60, prefs.state.value.themeSchedule.periods.first { it.id == "evening" }.startMinute)
        assertEquals(setOf("evening"), prefs.state.value.followDaySelections.keys)
    }

    @Test fun aPeriodCannotMovePastTheNextBoundaryOrBecomeShorterThanThirtyMinutes() {
        val vm = vm().inSettings()
        vm.openAppearance()
        vm.chooseThemeMode(ThemeMode.FollowTheDay)
        vm.openThemeSchedule()
        vm.openThemePeriod("evening")
        repeat(7) { vm.movePeriodStart(30) }
        val evening = prefs.state.value.themeSchedule.periods.first { it.id == "evening" }
        assertEquals(20 * 60, evening.startMinute)
        assertEquals(20 * 60 + 30, prefs.state.value.themeSchedule.periods.first { it.id == "night" }.startMinute)
    }

    @Test fun turningShuffleOffAndOnPreservesTheCuratedPool() {
        val vm = vm().inSettings()
        vm.openAppearance()
        vm.chooseThemeMode(ThemeMode.FollowTheDay)
        vm.openThemeSchedule()
        vm.openThemePeriod("evening")
        val original = prefs.state.value.themeSchedule.periods.first { it.id == "evening" }.themeIds
        vm.togglePeriodShuffle()
        vm.togglePeriodShuffle()
        val restored = prefs.state.value.themeSchedule.periods.first { it.id == "evening" }
        assertTrue(restored.shuffle)
        assertEquals(original, restored.themeIds)
    }

    @Test fun surpriseMeThemeChoicesKeepThePanelOpen() {
        val vm = vm().inSettings()
        vm.openAppearance()
        vm.chooseThemeMode(ThemeMode.SurpriseMe)
        vm.chooseTheme(aurora)
        assertEquals(setOf(dusk), prefs.state.value.surpriseThemeIds)
        assertEquals(AppearancePage.Main, vm.appearance.value?.page)
    }

    @Test fun manualThemeRequiresAnExplicitSurpriseToggleAndFullPoolsRemainExplicit() {
        val bay = ThemeId("open-bay")
        val vm = vm(packs = FakeThemeCatalog(listOf(info("dusk"), info("aurora"),
            info("open-bay").copy(autoRotationEligible = false)))).inSettings()
        vm.openAppearance()
        vm.chooseThemeMode(ThemeMode.SurpriseMe)
        assertTrue(prefs.state.value.surpriseThemeIds.isEmpty())
        assertTrue(vm.activeThemeId.value != bay)
        vm.chooseTheme(bay)
        assertEquals(setOf(dusk, aurora, bay), prefs.state.value.surpriseThemeIds)
        themes.themes.value += info("new-pack").copy(autoRotationEligible = false)
        assertFalse(ThemeId("new-pack") in prefs.state.value.surpriseThemeIds)
        vm.chooseTheme(dusk)
        vm.chooseTheme(aurora)
        assertEquals(setOf(bay), prefs.state.value.surpriseThemeIds)
        assertEquals(bay, vm.activeThemeId.value)
        vm.chooseTheme(bay) // Keep at least one intentional selection.
        assertEquals(setOf(bay), prefs.state.value.surpriseThemeIds)
    }

    @Test fun labelCanBeEnabledEditedDisabledAndClearedWithFocusRestored() {
        val vm = vm().inSettings()
        vm.openAppearance()
        vm.openHomeLabel()
        assertFalse(prefs.state.value.homeLabelEnabled)
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_HOME_LABEL_ENABLED), vm.requested())
        vm.toggleHomeLabel()
        vm.editHomeLabel()
        vm.onHomeLabelChange(" My\nWorkshop ")
        vm.saveHomeLabel()
        assertEquals("My Workshop", prefs.state.value.homeLabel)
        assertTrue(prefs.state.value.homeLabelEnabled)
        assertEquals(AppearancePage.HomeLabel, vm.appearance.value?.page)
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_HOME_LABEL_EDIT), vm.requested())
        vm.toggleHomeLabel()
        assertFalse(prefs.state.value.homeLabelEnabled)
        assertEquals("My Workshop", prefs.state.value.homeLabel)
        vm.editHomeLabel()
        vm.onHomeLabelChange("Unsaved")
        vm.onBack()
        assertEquals("My Workshop", prefs.state.value.homeLabel)
        vm.clearHomeLabel()
        assertEquals("", prefs.state.value.homeLabel)
        assertFalse(prefs.state.value.homeLabelEnabled)
        vm.onBack()
        assertEquals(AppearancePage.Main, vm.appearance.value?.page)
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_HOME_LABEL), vm.requested())
    }

    @Test fun reloadThemesKeepsAppearanceOpen() {
        val vm = vm().inSettings()
        vm.openAppearance()
        val before = themes.refreshes
        vm.reloadThemes()
        assertEquals(before + 1, themes.refreshes)
        assertNotNull(vm.appearance.value)
    }

    @Test fun aboutIsADedicatedSettingsPageAndBackRestoresItsRow() {
        val vm = vm().inSettings()
        vm.openAbout()
        assertTrue(vm.aboutOpen.value)
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_ABOUT_BACK), vm.requested())
        vm.onBack()
        assertFalse(vm.aboutOpen.value)
        assertEquals(Screen.Settings, vm.screen.value)
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_ABOUT), vm.requested())
    }

    @Test fun aChangedContentRevisionReloadsTheActiveTheme() {
        val vm = vm()
        val before = themes.loads.size
        themes.themes.value = themes.themes.value.map {
            if (it.id == dusk) it.copy(contentRevision = "new-artwork") else it
        }
        assertEquals(before + 1, themes.loads.size)
        assertEquals(dusk, vm.shown())
    }

    @Test fun unresolvedRotationDoesNotRerollAcrossUpstreamEmissions() {
        clock = at(18, 0)
        prefs = Prefs(HearthPrefs(seeded = true, themeMode = ThemeMode.FollowTheDay), holdUpdates = true)
        themes = FakeThemeCatalog(
            listOf(
                info("dusk", Daypart.Evening),
                info("desert-twilight", Daypart.Evening),
                info("aurora", Daypart.Night),
            ),
        )
        val vm = HearthViewModel(Container(prefs, themes), SavedStateHandle(), now = { clock }, zone = { ZoneOffset.UTC })
        val first = vm.activeThemeId.value
        vm.onClockChanged()
        themes.themes.value = themes.themes.value.map { it.copy(contentRevision = "changed") }
        assertEquals(first, vm.activeThemeId.value)
        assertTrue(prefs.state.value.followDaySelections.isEmpty())
    }

    // endregion

    // region memory and foreground

    @Test fun foregroundFollowsTheActivity() {
        val vm = vm()
        assertFalse(vm.foreground.value)
        vm.onForeground(true)
        assertTrue(vm.foreground.value)
        vm.onForeground(false)
        assertFalse(vm.foreground.value)
    }

    @Test fun memoryReleasedInTheBackgroundIsLetGoUntilHearthIsBack() {
        val vm = vm()
        vm.onForeground(true)
        vm.onForeground(false)
        assertNotNull(vm.activeTheme.value)
        themes.release()
        assertNull(vm.activeTheme.value)
        val loads = themes.loads.size
        vm.onForeground(true)
        assertEquals(dusk, vm.shown())
        assertEquals(loads + 1, themes.loads.size)
    }

    @Test fun goingToTheBackgroundAloneKeepsTheTheme() {
        val vm = vm()
        vm.onForeground(true)
        val loads = themes.loads.size
        vm.onForeground(false)
        vm.onForeground(true)
        assertEquals(dusk, vm.shown())
        assertEquals(loads, themes.loads.size)
    }

    // endregion
}
