package io.github.jameskbb.hearth.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.jameskbb.hearth.R
import io.github.jameskbb.hearth.ui.HearthViewModel
import io.github.jameskbb.hearth.ui.components.FocusRegistry
import io.github.jameskbb.hearth.ui.components.focusTarget
import io.github.jameskbb.hearth.ui.state.FocusTarget
import io.github.jameskbb.hearth.ui.theme.Hearth
import io.github.jameskbb.hearth.updates.InstallResult
import io.github.jameskbb.hearth.updates.UpdateFailure
import io.github.jameskbb.hearth.updates.UpdateState

/** Manual updates use the same text rows and focus treatment as the rest of Settings. */
@Composable
internal fun UpdatesPanel(
    vm: HearthViewModel,
    registry: FocusRegistry,
    focused: (String) -> () -> Unit,
) {
    val state by vm.updateState.collectAsState()
    val installing by vm.updateInstalling.collectAsState()
    val installResult by vm.updateInstallResult.collectAsState()
    val permissionAvailable by vm.updatePermissionAvailable.collectAsState()
    val permissionSettingsFailed by vm.updatePermissionSettingsFailed.collectAsState()
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val current = state
    val action = when {
        installing -> R.string.updates_verifying
        installResult == InstallResult.VerificationFailed -> R.string.settings_check_updates
        current == UpdateState.Checking || current is UpdateState.Downloading -> R.string.updates_cancel
        current is UpdateState.Available -> R.string.updates_download
        current is UpdateState.Ready && !permissionAvailable -> R.string.updates_allow
        current is UpdateState.Ready -> R.string.updates_install
        current is UpdateState.Failed -> R.string.updates_retry
        else -> R.string.settings_check_updates
    }
    val status = when {
        installing -> stringResource(R.string.updates_verifying_status)
        permissionSettingsFailed -> stringResource(R.string.updates_permission_settings_failed)
        installResult == InstallResult.VerificationFailed -> stringResource(R.string.updates_verification_failed)
        installResult == InstallResult.Unavailable -> stringResource(R.string.updates_installer_unavailable)
        installResult == InstallResult.Started -> stringResource(R.string.updates_installer_opened)
        current is UpdateState.Ready && !permissionAvailable -> stringResource(R.string.updates_permission_needed)
        else -> updateStatus(current)
    }
    Column(Modifier.padding(top = 16.dp).verticalScroll(rememberScrollState())) {
        BasicText(
            stringResource(R.string.about_version, version),
            style = Hearth.type.body.copy(color = Hearth.palette.inkMuted),
        )
        BasicText(
            status,
            style = Hearth.type.body.copy(color = Hearth.palette.ink),
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        SettingRow(
            stringResource(action), null, vm::performUpdateAction,
            focused(HearthViewModel.ROW_UPDATE_ACTION),
            Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_UPDATE_ACTION)),
        )
        SettingRow(
            stringResource(R.string.settings_about_back), null, vm::closeUpdates,
            focused(HearthViewModel.ROW_UPDATES_BACK),
            Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_UPDATES_BACK)),
        )
        BasicText(
            stringResource(R.string.updates_privacy),
            style = Hearth.type.hint.copy(color = Hearth.palette.inkMuted),
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun updateStatus(state: UpdateState): String = when (state) {
    UpdateState.Idle -> stringResource(R.string.updates_idle)
    UpdateState.Checking -> stringResource(R.string.updates_checking)
    UpdateState.UpToDate -> stringResource(R.string.updates_current)
    UpdateState.NoRelease -> stringResource(R.string.updates_no_release)
    is UpdateState.Available -> stringResource(R.string.updates_available, state.release.versionName)
    is UpdateState.Downloading -> state.percent?.let { stringResource(R.string.updates_progress, it) }
        ?: stringResource(R.string.updates_downloading)
    is UpdateState.Ready -> stringResource(R.string.updates_ready, state.release.versionName)
    is UpdateState.Failed -> stringResource(when (state.reason) {
        UpdateFailure.Network -> R.string.updates_network_failed
        UpdateFailure.RateLimited -> R.string.updates_rate_limited
        UpdateFailure.InvalidRelease -> R.string.updates_invalid_release
        UpdateFailure.Incompatible -> R.string.updates_incompatible
        UpdateFailure.Download -> R.string.updates_download_failed
        UpdateFailure.Verification -> R.string.updates_verification_failed
    })
}
