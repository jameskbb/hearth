package io.github.jameskbb.hearth.ui

import androidx.lifecycle.SavedStateHandle
import io.github.jameskbb.hearth.AppContainer
import io.github.jameskbb.hearth.apps.AppArtwork
import io.github.jameskbb.hearth.apps.AppCatalog
import io.github.jameskbb.hearth.apps.AppEntry
import io.github.jameskbb.hearth.apps.AppKey
import io.github.jameskbb.hearth.apps.AppLauncher
import io.github.jameskbb.hearth.apps.ArtworkLoader
import io.github.jameskbb.hearth.apps.EntryKind
import io.github.jameskbb.hearth.apps.LaunchResult
import io.github.jameskbb.hearth.prefs.HearthPrefs
import io.github.jameskbb.hearth.prefs.PrefsRepository
import io.github.jameskbb.hearth.system.DisplayFacts
import io.github.jameskbb.hearth.system.DisplayFactsReader
import io.github.jameskbb.hearth.system.SystemRoute
import io.github.jameskbb.hearth.system.SystemRoutes
import io.github.jameskbb.hearth.system.TvInput
import io.github.jameskbb.hearth.ui.state.FocusTarget
import io.github.jameskbb.hearth.ui.state.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HDMI inputs in the view model: they behave like apps (favorites, hide, focus follow, launch
 * feedback) and can be renamed in Settings. Inputs come from a [FakeTvInputs].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InputsViewModelTest {
    private val a = AppKey("a/A")
    private val b = AppKey("b/B")
    private val hdmi1 = TvInput("tv.example/.HdmiService/HW1", "HDMI 1", 1)
    private val hdmi2 = TvInput("tv.example/.HdmiService/HW2", "HDMI 2", 2)
    private val in1 = AppKey.forInput(hdmi1.id)
    private val in2 = AppKey.forInput(hdmi2.id)

    private fun app(key: AppKey) = AppEntry(key, key.packageName, true, 0L)

    private class FakeCatalog(initial: List<AppEntry>) : AppCatalog {
        override val apps = MutableStateFlow(initial)
        override val loaded = MutableStateFlow(true)
        override suspend fun refresh() = Unit
    }

    private class FakePrefs(initial: HearthPrefs) : PrefsRepository {
        val state = MutableStateFlow(initial)
        override val prefs = state
        override suspend fun update(transform: (HearthPrefs) -> HearthPrefs) {
            state.value = transform(state.value)
        }
    }

    private class FakeLauncher : AppLauncher {
        val launched = mutableListOf<AppKey>()
        override fun launch(key: AppKey): LaunchResult {
            launched += key
            return LaunchResult.Launched
        }
    }

    private class Container(
        override val catalog: FakeCatalog,
        override val prefs: FakePrefs,
        override val tvInputs: FakeTvInputs,
    ) : AppContainer {
        override val launcher = FakeLauncher()
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
    }

    private lateinit var prefs: FakePrefs
    private lateinit var tv: FakeTvInputs
    private lateinit var container: Container

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        favorites: List<AppKey> = listOf(a, in1, b),
        inputs: List<TvInput> = listOf(hdmi1, hdmi2),
        labels: Map<String, String> = emptyMap(),
    ): HearthViewModel {
        prefs = FakePrefs(HearthPrefs(favorites = favorites, seeded = true, inputLabels = labels))
        tv = FakeTvInputs(inputs)
        container = Container(FakeCatalog(listOf(app(a), app(b))), prefs, tv)
        return HearthViewModel(container, SavedStateHandle())
    }

    private fun HearthViewModel.order() = favorites.value.map { it.key }
    private fun HearthViewModel.requested() = focusRequest.value?.target

    // region inputs as entries

    @Test fun inputsAreListedFirstInAllAppsAndPinnedOnHome() {
        val vm = vm()
        val content = vm.allApps.value
        assertEquals(listOf(in1, in2), content.inputs.map { it.key })
        assertEquals(listOf(in1, in2, a, b), content.ordered.map { it.key })
        assertTrue(content.inputs.all { it.kind == EntryKind.Input })
        assertEquals(listOf(a, in1, b), vm.order())
    }

    @Test fun noInputsMeansNothingInputRelated() {
        val vm = vm(favorites = listOf(a, in1), inputs = emptyList())
        assertEquals(emptyList<AppEntry>(), vm.allApps.value.inputs)
        assertEquals(emptyList<AppEntry>(), vm.inputs.value)
        assertEquals(listOf(a), vm.order())
    }

    @Test fun selectingAnInputSwitchesToItNotTheAppLauncher() {
        val vm = vm()
        vm.onTileClick(Screen.AllApps, vm.allApps.value.inputs[1])
        assertEquals(listOf(hdmi2.id), tv.switched)
        assertEquals(emptyList<AppKey>(), container.launcher.launched)
        assertNull(vm.message.value)
    }

    @Test fun aFailedSwitchShowsTheLaunchFailedMessage() {
        val vm = vm()
        tv.result = LaunchResult.Failed("no player")
        val entry = vm.favorites.value[1]
        vm.onFocused(Screen.Home, FocusTarget.Tile(entry.key))
        vm.onTileClick(Screen.Home, entry)
        val m = vm.message.value
        assertEquals(InlineMessage.Kind.LaunchFailed, m?.kind)
        assertEquals("HDMI 1", m?.label)
        assertEquals(FocusTarget.Tile(in1), m?.origin)
    }

    @Test fun resumeRefreshesTheInputs() {
        val vm = vm()
        val before = tv.refreshes
        vm.onResume()
        assertEquals(before + 1, tv.refreshes)
    }

    @Test fun anInputCanBeHiddenAndShownAgain() {
        val vm = vm()
        vm.openOptions(Screen.AllApps, vm.allApps.value.inputs[0])
        vm.optionHide()
        assertEquals(listOf(in2), vm.allApps.value.inputs.map { it.key })
        assertEquals(listOf(a, b), vm.order())
        assertEquals(listOf(in1 to "HDMI 1"), vm.hiddenApps.value)
        vm.unhide(in1)
        assertEquals(listOf(in1, in2), vm.allApps.value.inputs.map { it.key })
    }

    // endregion

    // region an input disappearing (review focus 4)

    @Test fun aFocusedFavoriteInputThatDisappearsIsSkippedAndComesBack() {
        val vm = vm()
        vm.onFocused(Screen.Home, FocusTarget.Tile(in1))
        tv.inputs.value = emptyList()
        assertEquals(listOf(a, b), vm.order())
        assertEquals(FocusTarget.Tile(b), vm.requested())
        assertEquals(listOf(a, in1, b), prefs.state.value.favorites) // stored favorite kept
        tv.inputs.value = listOf(hdmi1, hdmi2)
        assertEquals(listOf(a, in1, b), vm.order())
    }

    @Test fun aFocusedAllAppsInputThatDisappearsMovesFocusToItsNeighbor() {
        val vm = vm()
        vm.open(Screen.AllApps)
        vm.onFocused(Screen.AllApps, FocusTarget.Tile(in2))
        tv.inputs.value = listOf(hdmi1)
        assertEquals(FocusTarget.Tile(a), vm.requested())
    }

    // endregion

    // region renaming

    @Test fun renameStoresATrimmedNameAndReturnsToTheRow() {
        val vm = vm()
        vm.open(Screen.Settings)
        vm.openRenameInput(hdmi1.id)
        assertEquals(InputRename(hdmi1.id, "HDMI 1", "HDMI 1", custom = false), vm.inputRename.value)
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_RENAME_FIELD), vm.requested())
        vm.onRenameChange("  Game console  ")
        vm.submitRename()
        assertNull(vm.inputRename.value)
        assertEquals(mapOf(hdmi1.id to "Game console"), prefs.state.value.inputLabels)
        assertEquals("Game console", vm.inputs.value[0].label)
        assertEquals("Game console", vm.favorites.value[1].label)
        assertEquals(FocusTarget.Row(HearthViewModel.inputRowId(hdmi1.id)), vm.requested())
    }

    @Test fun anEmptyNameOrTheTvsNameRestoresTheTvsName() {
        val vm = vm(labels = mapOf(hdmi1.id to "Console", hdmi2.id to "Blu-ray"))
        vm.openRenameInput(hdmi1.id)
        assertEquals(InputRename(hdmi1.id, "Console", "HDMI 1", custom = true), vm.inputRename.value)
        vm.onRenameChange("   ")
        vm.submitRename()
        vm.openRenameInput(hdmi2.id)
        vm.onRenameChange("HDMI 2")
        vm.submitRename()
        assertEquals(emptyMap<String, String>(), prefs.state.value.inputLabels)
        assertEquals(listOf("HDMI 1", "HDMI 2"), vm.inputs.value.map { it.label })
    }

    @Test fun useTheTvsNameDropsTheCustomName() {
        val vm = vm(labels = mapOf(hdmi1.id to "Console"))
        vm.openRenameInput(hdmi1.id)
        vm.resetRename()
        assertEquals(emptyMap<String, String>(), prefs.state.value.inputLabels)
        assertNull(vm.inputRename.value)
    }

    @Test fun backCancelsTheRenameThenLeavesSettings() {
        val vm = vm(labels = mapOf(hdmi1.id to "Console"))
        vm.open(Screen.Settings)
        vm.openRenameInput(hdmi1.id)
        vm.onRenameChange("Something else")
        vm.onBack()
        assertNull(vm.inputRename.value)
        assertEquals(Screen.Settings, vm.screen.value)
        assertEquals(FocusTarget.Row(HearthViewModel.inputRowId(hdmi1.id)), vm.requested())
        assertEquals(mapOf(hdmi1.id to "Console"), prefs.state.value.inputLabels)
        vm.onBack()
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test fun theHomeIntentClosesTheRename() {
        val vm = vm()
        vm.open(Screen.Settings)
        vm.openRenameInput(hdmi1.id)
        vm.onHomeIntent()
        assertNull(vm.inputRename.value)
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test fun renamingAnUnknownInputDoesNothing() {
        val vm = vm()
        vm.openRenameInput("gone")
        assertNull(vm.inputRename.value)
    }

    @Test fun namesStopAtTheLimit() {
        val vm = vm()
        vm.openRenameInput(hdmi1.id)
        vm.onRenameChange("x".repeat(100))
        assertEquals(40, vm.inputRename.value?.name?.length)
    }

    // endregion
}
