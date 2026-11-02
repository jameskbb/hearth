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
import io.github.jameskbb.hearth.ui.state.FocusTarget
import io.github.jameskbb.hearth.ui.state.Screen
import io.github.jameskbb.hearth.updates.InstallResult
import io.github.jameskbb.hearth.updates.UpdateRelease
import io.github.jameskbb.hearth.updates.UpdateState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesViewModelTest {
    private val release = UpdateRelease(9, "0.2.0", "https://github.com/jameskbb/hearth/releases/download/v0.2.0/app-release.apk", "a".repeat(64), 42, 21)
    private lateinit var container: Container

    private class Container : AppContainer {
        override val updates = FakeUpdates()
        override val updateInstaller = FakeUpdateInstaller()
        override val catalog = object : AppCatalog {
            override val apps = MutableStateFlow<List<AppEntry>>(emptyList())
            override val loaded = MutableStateFlow(true)
            override suspend fun refresh() = Unit
        }
        override val prefs = object : PrefsRepository {
            override val prefs = MutableStateFlow(HearthPrefs(seeded = true))
            override suspend fun update(transform: (HearthPrefs) -> HearthPrefs) { prefs.value = transform(prefs.value) }
        }
        override val artwork = object : ArtworkLoader {
            override suspend fun load(entry: AppEntry): AppArtwork? = null
        }
        override val launcher = object : AppLauncher {
            override fun launch(key: AppKey) = LaunchResult.Launched
        }
        override val systemRoutes = object : SystemRoutes {
            override fun isAvailable(route: SystemRoute) = false
            override fun open(route: SystemRoute) = false
        }
        override val displayFacts = object : DisplayFactsReader {
            override fun read() = DisplayFacts(1920, 1080, 320, 60f, emptyList(), false, emptyList())
        }
        override val weather = FakeWeather()
        override val themes = FakeThemeCatalog()
        override val tvInputs = FakeTvInputs()
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(): HearthViewModel {
        container = Container()
        return HearthViewModel(container, SavedStateHandle())
    }

    private fun ready() { container.updates.state.value = UpdateState.Ready(release, File("update.apk")) }

    @Test fun lifecycleAndOpeningSettingsNeverCheckForUpdates() {
        val vm = vm()
        vm.onResume()
        vm.onForeground(true)
        vm.open(Screen.Settings)
        vm.onResume()
        assertEquals(0, container.updates.checks)
        assertEquals(0, container.updateInstaller.installs)
    }

    @Test fun explicitCheckOpensUpdatesAndBackCancelsWithFocusOnItsSettingsRow() {
        val vm = vm()
        vm.open(Screen.Settings)
        vm.openUpdates()
        assertTrue(vm.updatesOpen.value)
        assertEquals(1, container.updates.checks)
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_UPDATE_ACTION), vm.focusRequest.value?.target)
        vm.onBack()
        assertFalse(vm.updatesOpen.value)
        assertEquals(Screen.Settings, vm.screen.value)
        assertEquals(1, container.updates.cancels)
        assertEquals(FocusTarget.Row(HearthViewModel.ROW_UPDATES), vm.focusRequest.value?.target)
    }

    @Test fun availableUpdateWaitsForDownloadAndReadyWaitsForInstall() {
        val vm = vm()
        vm.open(Screen.Settings)
        vm.openUpdates()
        container.updates.state.value = UpdateState.Available(release)
        assertEquals(0, container.updates.downloads)
        vm.performUpdateAction()
        assertEquals(1, container.updates.downloads)
        ready()
        vm.onResume()
        assertEquals(0, container.updateInstaller.installs)
        vm.performUpdateAction()
        assertEquals(1, container.updateInstaller.installs)
        assertEquals(InstallResult.Started, vm.updateInstallResult.value)
    }

    @Test fun returnFromPermissionSettingsRequiresExplicitInstallSelection() {
        val vm = vm()
        container.updateInstaller.permitted = false
        vm.open(Screen.Settings)
        vm.openUpdates()
        ready()
        vm.performUpdateAction()
        assertEquals(1, container.updateInstaller.settingsOpened)
        assertEquals(0, container.updateInstaller.installs)
        container.updateInstaller.permitted = true
        vm.onResume()
        assertTrue(vm.updatePermissionAvailable.value)
        assertEquals(0, container.updateInstaller.installs)
        vm.performUpdateAction()
        assertEquals(1, container.updateInstaller.installs)
    }

    @Test fun missingPermissionRouteShowsRecoveryAndRetainsTheDownload() {
        val vm = vm()
        container.updateInstaller.permitted = false
        container.updateInstaller.settingsAvailable = false
        vm.open(Screen.Settings)
        vm.openUpdates()
        ready()
        vm.performUpdateAction()
        assertTrue(vm.updatePermissionSettingsFailed.value)
        assertTrue(vm.updateState.value is UpdateState.Ready)
        assertEquals(0, container.updateInstaller.installs)
        container.updateInstaller.permitted = true
        vm.onResume()
        assertFalse(vm.updatePermissionSettingsFailed.value)
    }

    @Test fun leavingForHomeCancelsTheDownloadAndClosesUpdates() {
        val vm = vm()
        vm.open(Screen.Settings)
        vm.openUpdates()
        container.updates.state.value = UpdateState.Downloading(release, 20)
        vm.onHomeIntent()
        assertFalse(vm.updatesOpen.value)
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(UpdateState.Idle, vm.updateState.value)
    }

    @Test fun backgroundCancelsTransfersWithoutStartingANewCheckOnResume() {
        val vm = vm()
        vm.open(Screen.Settings)
        vm.openUpdates()
        vm.onForeground(false)
        vm.onForeground(true)
        vm.onResume()
        assertEquals(1, container.updates.checks)
        assertEquals(1, container.updates.cancels)
        assertEquals(UpdateState.Idle, vm.updateState.value)
    }

    @Test fun cancelledInstallerCanBeReopenedWithoutDownloadingAgain() {
        val vm = vm()
        vm.open(Screen.Settings)
        vm.openUpdates()
        ready()
        vm.performUpdateAction()
        vm.onResume()
        assertEquals(1, container.updates.checks)
        vm.performUpdateAction()
        assertEquals(2, container.updateInstaller.installs)
        assertEquals(0, container.updates.downloads)
    }

    @Test fun choosingCheckForUpdatesAgainReplacesAnOlderDownload() {
        val vm = vm()
        vm.open(Screen.Settings)
        vm.openUpdates()
        ready()
        vm.closeUpdates()
        vm.openUpdates()
        assertEquals(2, container.updates.checks)
        assertEquals(UpdateState.Checking, vm.updateState.value)
    }

    @Test fun incompatibleSignatureOffersANewCheckInsteadOfRepeatingInstallation() {
        val vm = vm()
        container.updateInstaller.result = InstallResult.VerificationFailed
        vm.open(Screen.Settings)
        vm.openUpdates()
        ready()
        vm.performUpdateAction()
        assertEquals(InstallResult.VerificationFailed, vm.updateInstallResult.value)
        vm.performUpdateAction()
        assertEquals(2, container.updates.checks)
        assertEquals(1, container.updateInstaller.installs)
    }

    @Test fun repeatedSelectCannotStartTwoInstallersAndHomeCancelsPreparation() {
        val vm = vm()
        val pending = CompletableDeferred<InstallResult>()
        container.updateInstaller.install = { pending.await() }
        vm.open(Screen.Settings)
        vm.openUpdates()
        ready()
        vm.performUpdateAction()
        assertTrue(vm.updateInstalling.value)
        vm.performUpdateAction()
        assertEquals(1, container.updateInstaller.installs)
        vm.onHomeIntent()
        assertFalse(vm.updateInstalling.value)
        pending.complete(InstallResult.Started)
        assertEquals(null, vm.updateInstallResult.value)
    }
}
