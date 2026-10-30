package io.github.jameskbb.hearth.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.jameskbb.hearth.R
import io.github.jameskbb.hearth.apps.AppEntry
import io.github.jameskbb.hearth.prefs.PrefsCodec
import io.github.jameskbb.hearth.ui.HearthViewModel
import io.github.jameskbb.hearth.ui.InputRename
import io.github.jameskbb.hearth.ui.components.FocusRegistry
import io.github.jameskbb.hearth.ui.components.focusTarget
import io.github.jameskbb.hearth.ui.state.FocusTarget
import io.github.jameskbb.hearth.ui.theme.Hearth

/** The Inputs section's rows, in on-screen order (none when the TV has no inputs). */
internal fun inputRowTargets(inputs: List<AppEntry>): List<FocusTarget> =
    inputs.mapNotNull { e -> e.key.inputId?.let { FocusTarget.Row(HearthViewModel.inputRowId(it)) } }

/** The rename panel's focus targets, in on-screen order. */
internal fun renamePanelTargets(rename: InputRename): List<FocusTarget> = buildList {
    add(FocusTarget.Row(HearthViewModel.ROW_RENAME_FIELD))
    add(FocusTarget.Row(HearthViewModel.ROW_RENAME_SAVE))
    if (rename.custom) add(FocusTarget.Row(HearthViewModel.ROW_RENAME_RESET))
}

/**
 * Settings, Inputs: one row per HDMI input, its name (the user's, else the TV's) and "Rename".
 * Shown only when the TV has inputs Hearth can switch to.
 */
@Composable
internal fun InputsSection(
    vm: HearthViewModel,
    inputs: List<AppEntry>,
    registry: FocusRegistry,
    focused: (String) -> () -> Unit,
) {
    if (inputs.isEmpty()) return
    SectionLabel(stringResource(R.string.settings_inputs))
    for (entry in inputs) {
        val inputId = entry.key.inputId ?: continue
        key(entry.key.id) {
            val id = HearthViewModel.inputRowId(inputId)
            SettingRow(
                label = entry.label,
                value = stringResource(R.string.settings_rename),
                onClick = { vm.openRenameInput(inputId) },
                onFocused = focused(id),
                modifier = Modifier.focusTarget(registry, FocusTarget.Row(id)),
            )
        }
    }
}

/**
 * Renaming an input, in place of the Settings rows (title "Rename input"; About stays on the
 * right). The field takes focus when the panel opens, which brings up the system keyboard, with
 * the current name ready to edit. The keyboard's Done, or Save, stores the name and returns to the
 * input's row; "Use the TV's name" (shown while a name of the user's is stored) drops it; Back
 * closes the keyboard first, then the panel, keeping the old name.
 */
@Composable
internal fun RenameInputPanel(
    vm: HearthViewModel,
    rename: InputRename,
    registry: FocusRegistry,
    focused: (String) -> () -> Unit,
) {
    val palette = Hearth.palette
    val type = Hearth.type
    val keyboard = LocalSoftwareKeyboardController.current

    Column(Modifier.padding(top = 14.dp)) {
        UnderlineField(
            value = rename.name,
            placeholder = stringResource(R.string.rename_input_placeholder),
            onValueChange = vm::onRenameChange,
            onSubmit = {
                keyboard?.hide()
                vm.submitRename()
            },
            onFocused = focused(HearthViewModel.ROW_RENAME_FIELD),
            modifier = Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_RENAME_FIELD)),
            imeAction = ImeAction.Done,
            maxLength = PrefsCodec.MAX_INPUT_LABEL,
        )
        BasicText(
            stringResource(R.string.rename_input_hint),
            style = type.hint.copy(color = palette.inkMuted),
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).width(360.dp),
        )
        SettingRow(
            label = stringResource(R.string.rename_input_save),
            value = null,
            onClick = vm::submitRename,
            onFocused = focused(HearthViewModel.ROW_RENAME_SAVE),
            modifier = Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_RENAME_SAVE)),
        )
        if (rename.custom) {
            SettingRow(
                label = stringResource(R.string.rename_input_reset),
                value = rename.tvLabel,
                onClick = vm::resetRename,
                onFocused = focused(HearthViewModel.ROW_RENAME_RESET),
                modifier = Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_RENAME_RESET)),
            )
        }
    }
}
