package io.github.jameskbb.hearth.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.jameskbb.hearth.R
import io.github.jameskbb.hearth.ui.InlineMessage

/** The copy for an inline message. */
@Composable
fun messageText(message: InlineMessage): String = when (message.kind) {
    InlineMessage.Kind.NotInstalled -> stringResource(R.string.launch_not_installed, message.label)
    InlineMessage.Kind.LaunchFailed -> stringResource(R.string.launch_failed, message.label)
    InlineMessage.Kind.DeviceSettingsFailed -> stringResource(R.string.settings_device_failed)
}
