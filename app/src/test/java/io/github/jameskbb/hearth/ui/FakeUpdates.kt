package io.github.jameskbb.hearth.ui

import io.github.jameskbb.hearth.updates.ApkVerification
import io.github.jameskbb.hearth.updates.InstallResult
import io.github.jameskbb.hearth.updates.UpdateInstaller
import io.github.jameskbb.hearth.updates.UpdateService
import io.github.jameskbb.hearth.updates.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

internal class FakeUpdates : UpdateService {
    override val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    var checks = 0
    var downloads = 0
    var cancels = 0
    override fun check() { checks++; state.value = UpdateState.Checking }
    override fun download() {
        val release = (state.value as? UpdateState.Available)?.release ?: return
        downloads++
        state.value = UpdateState.Downloading(release, null)
    }
    override fun cancel() { cancels++; state.value = UpdateState.Idle }
}

internal class FakeUpdateInstaller : UpdateInstaller {
    var permitted = true
    var settingsAvailable = true
    var settingsOpened = 0
    var installs = 0
    var result = InstallResult.Started
    var install: suspend () -> InstallResult = { result }
    override suspend fun verify(file: File, expectedVersionCode: Long) = ApkVerification.Valid
    override suspend fun install(file: File, expectedVersionCode: Long): InstallResult {
        installs++
        return install()
    }
    override fun openPermissionSettings(): Boolean { settingsOpened++; return settingsAvailable }
    override fun canRequestInstalls() = permitted
}
