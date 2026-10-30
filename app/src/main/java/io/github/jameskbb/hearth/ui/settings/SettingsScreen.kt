package io.github.jameskbb.hearth.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jameskbb.hearth.R
import io.github.jameskbb.hearth.ui.HearthViewModel
import io.github.jameskbb.hearth.ui.AppearancePage
import io.github.jameskbb.hearth.ui.components.FocusRegistry
import io.github.jameskbb.hearth.ui.components.LeadBringIntoViewSpec
import io.github.jameskbb.hearth.ui.components.TextAction
import io.github.jameskbb.hearth.ui.components.focusTarget
import io.github.jameskbb.hearth.ui.components.messageText
import io.github.jameskbb.hearth.ui.components.temperatureUnit
import io.github.jameskbb.hearth.ui.state.FocusTarget
import io.github.jameskbb.hearth.ui.state.Screen
import io.github.jameskbb.hearth.ui.state.UiLogic
import io.github.jameskbb.hearth.ui.theme.Dimens
import io.github.jameskbb.hearth.ui.theme.Hearth
import io.github.jameskbb.hearth.weather.TemperatureUnit

/**
 * Settings is one calm, focusable list. Detail flows replace that list until an action completes
 * or Back is pressed. Appearance lives in AppearanceSettings.kt.
 */
@Composable
fun SettingsScreen(vm: HearthViewModel) {
    val registry = remember { FocusRegistry() }
    val prefs by vm.prefs.collectAsState()
    val hidden by vm.hiddenApps.collectAsState()
    val deviceAvailable by vm.deviceSettingsAvailable.collectAsState()
    val request by vm.focusRequest.collectAsState()
    val message by vm.message.collectAsState()
    val search by vm.citySearch.collectAsState()
    val appearance by vm.appearance.collectAsState()
    val aboutOpen by vm.aboutOpen.collectAsState()
    val appearanceRows = appearanceRows(vm, appearance)
    val inputs by vm.inputs.collectAsState()
    val rename by vm.inputRename.collectAsState()
    val palette = Hearth.palette
    val type = Hearth.type
    val reducedMotion = Hearth.reducedMotion
    val weatherOn = prefs?.weatherEnabled == true

    val currentSearch = search
    val currentAppearance = appearance
    val currentAbout = aboutOpen
    val currentRename = rename
    val rows = if (currentAppearance != null) {
        appearanceRows
    } else if (currentAbout) {
        listOf(FocusTarget.Row(HearthViewModel.ROW_ABOUT_BACK))
    } else if (currentRename != null) {
        renamePanelTargets(currentRename)
    } else if (currentSearch != null) {
        buildList {
            add(FocusTarget.Row(HearthViewModel.ROW_CITY_FIELD))
            currentSearch.results.indices.forEach { add(FocusTarget.Row(HearthViewModel.cityResultRowId(it))) }
        }
    } else {
        buildList {
            add(FocusTarget.Row(HearthViewModel.ROW_THEME))
            add(FocusTarget.Row(HearthViewModel.ROW_MOTION))
            if (deviceAvailable) add(FocusTarget.Row(HearthViewModel.ROW_DEVICE))
            add(FocusTarget.Row(HearthViewModel.ROW_WEATHER))
            if (weatherOn) {
                add(FocusTarget.Row(HearthViewModel.ROW_CITY))
                add(FocusTarget.Row(HearthViewModel.ROW_UNITS))
            }
            addAll(inputRowTargets(inputs))
            hidden.forEach { add(FocusTarget.Row(HearthViewModel.hiddenRowId(it.first))) }
            add(FocusTarget.Row(HearthViewModel.ROW_ABOUT))
        }
    }
    val currentRows by rememberUpdatedState(rows)
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        if (r.screen != Screen.Settings) return@LaunchedEffect
        UiLogic.resolveListFocus(r.target, currentRows)?.let { registry.focus(it) }
        vm.consumeFocus(r.seq)
    }

    val edgePx = with(LocalDensity.current) { 40.dp.toPx() }
    val spec = remember(edgePx, reducedMotion) { LeadBringIntoViewSpec(edgePx, edgePx, reducedMotion) }
    val focused: (String) -> () -> Unit = { id -> { vm.onFocused(Screen.Settings, FocusTarget.Row(id)) } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Dimens.marginH, vertical = Dimens.marginV)) {
            val columnModifier = if (currentAppearance != null || currentAbout) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.width(560.dp)
            }
            Column(columnModifier) {
                val title = when {
                    currentAppearance?.page == AppearancePage.HomeLabel ||
                        currentAppearance?.page == AppearancePage.HomeLabelText -> stringResource(R.string.home_label_title)
                    currentAppearance?.page == AppearancePage.Schedule -> stringResource(R.string.schedule_title)
                    currentAppearance?.page == AppearancePage.Period -> {
                        val name = prefs?.themeSchedule?.periods
                            ?.firstOrNull { it.id == currentAppearance.periodId }?.name
                            ?: stringResource(R.string.schedule_period_fallback)
                        stringResource(R.string.schedule_period_title, name)
                    }
                    currentAppearance?.page == AppearancePage.PeriodName -> stringResource(R.string.schedule_rename_period)
                    currentAppearance != null -> stringResource(R.string.appearance_title)
                    currentAbout -> stringResource(R.string.settings_about)
                    currentRename != null -> stringResource(R.string.rename_input_title)
                    currentSearch != null -> stringResource(R.string.city_search_title)
                    else -> stringResource(R.string.settings_title)
                }
                BasicText(
                    title,
                    style = type.title.copy(color = palette.ink),
                )
                @OptIn(ExperimentalFoundationApi::class)
                CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                    if (currentAppearance != null) {
                        AppearancePanel(vm, currentAppearance, registry)
                    } else if (currentAbout) {
                        AboutPanel(vm, registry, focused)
                    } else if (currentRename != null) {
                        RenameInputPanel(vm, currentRename, registry, focused)
                    } else if (currentSearch != null) {
                        CitySearchPanel(vm, currentSearch, registry, focused)
                    } else {
                        Column(Modifier.padding(top = 16.dp).verticalScroll(rememberScrollState())) {
                            SettingRow(
                                label = stringResource(R.string.settings_theme),
                                value = themeRowValue(vm),
                                onClick = vm::openAppearance,
                                onFocused = focused(HearthViewModel.ROW_THEME),
                                modifier = Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_THEME)),
                            )
                            SettingRow(
                                label = stringResource(R.string.settings_reduce_motion),
                                value = onOff(prefs?.reducedMotion == true),
                                onClick = vm::toggleReducedMotion,
                                onFocused = focused(HearthViewModel.ROW_MOTION),
                                modifier = Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_MOTION)),
                            )
                            if (deviceAvailable) {
                                SettingRow(
                                    label = stringResource(R.string.settings_device),
                                    value = null,
                                    onClick = vm::openDeviceSettings,
                                    onFocused = focused(HearthViewModel.ROW_DEVICE),
                                    modifier = Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_DEVICE)),
                                )
                            }
                            val m = message?.takeIf { it.screen == Screen.Settings }
                            if (m != null) {
                                BasicText(
                                    messageText(m),
                                    style = type.hint.copy(color = palette.ink),
                                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                                )
                            }

                            SectionLabel(stringResource(R.string.settings_weather))
                            SettingRow(
                                label = stringResource(R.string.settings_weather_show),
                                value = onOff(weatherOn),
                                onClick = vm::toggleWeather,
                                onFocused = focused(HearthViewModel.ROW_WEATHER),
                                modifier = Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_WEATHER)),
                            )
                            if (weatherOn) {
                                SettingRow(
                                    label = stringResource(R.string.settings_weather_city),
                                    value = prefs?.weatherPlace?.describe()
                                        ?: stringResource(R.string.settings_weather_choose_city),
                                    onClick = vm::openCitySearch,
                                    onFocused = focused(HearthViewModel.ROW_CITY),
                                    modifier = Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_CITY)),
                                )
                                val unit = temperatureUnit(prefs)
                                SettingRow(
                                    label = stringResource(R.string.settings_weather_units),
                                    value = stringResource(
                                        if (unit == TemperatureUnit.Fahrenheit) R.string.settings_units_fahrenheit
                                        else R.string.settings_units_celsius,
                                    ),
                                    onClick = {
                                        vm.setTemperatureUnit(
                                            if (unit == TemperatureUnit.Fahrenheit) TemperatureUnit.Celsius
                                            else TemperatureUnit.Fahrenheit,
                                        )
                                    },
                                    onFocused = focused(HearthViewModel.ROW_UNITS),
                                    modifier = Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_UNITS)),
                                )
                            }
                            WeatherStatus(vm, weatherOn, hasPlace = prefs?.weatherPlace != null)

                            InputsSection(vm, inputs, registry, focused)

                            SectionLabel(stringResource(R.string.settings_hidden_apps))
                            if (hidden.isEmpty()) {
                                BasicText(
                                    stringResource(R.string.settings_no_hidden_apps),
                                    style = type.body.copy(color = palette.inkMuted),
                                    modifier = Modifier.padding(vertical = 6.dp),
                                )
                            }
                            for ((appKey, label) in hidden) {
                                key(appKey.id) {
                                    val id = HearthViewModel.hiddenRowId(appKey)
                                    SettingRow(
                                        label = label,
                                        value = stringResource(R.string.settings_show),
                                        onClick = { vm.unhide(appKey) },
                                        onFocused = focused(id),
                                        modifier = Modifier.focusTarget(registry, FocusTarget.Row(id)),
                                    )
                                }
                            }
                            SettingRow(
                                label = stringResource(R.string.settings_about),
                                value = null,
                                onClick = vm::openAbout,
                                onFocused = focused(HearthViewModel.ROW_ABOUT),
                                modifier = Modifier.padding(top = 14.dp)
                                    .focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_ABOUT)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun onOff(on: Boolean) = stringResource(if (on) R.string.settings_on else R.string.settings_off)

@Composable
internal fun SectionLabel(text: String) {
    BasicText(
        text,
        style = Hearth.type.section.copy(color = Hearth.palette.inkMuted),
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
internal fun SettingRow(
    label: String,
    value: String?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = Hearth.palette
    val type = Hearth.type
    TextAction(
        text = label,
        onClick = onClick,
        onFocused = onFocused,
        style = type.dialogOption,
        modifier = modifier.padding(vertical = 2.dp),
        trailing = if (value == null) null else { focused ->
            BasicText(
                value,
                style = type.dialogOption.copy(color = if (focused) palette.accent else palette.inkMuted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp),
            )
        },
    )
}

@Composable
private fun AboutPanel(
    vm: HearthViewModel,
    registry: FocusRegistry,
    focused: (String) -> () -> Unit,
) {
    val context = LocalContext.current
    val palette = Hearth.palette
    val type = Hearth.type
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val facts = remember { vm.displayFacts() }
    val yes = stringResource(R.string.about_yes)
    val no = stringResource(R.string.about_no)
    val none = stringResource(R.string.about_none_reported)
    val lines = remember(facts, yes, no, none) { UiLogic.displayFactLines(facts, yes, no, none) }

    Column(Modifier.width(AppearanceColumnWidth).padding(top = 12.dp)) {
        SettingRow(
            stringResource(R.string.settings_about_back), null, vm::closeAbout,
            focused(HearthViewModel.ROW_ABOUT_BACK),
            Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_ABOUT_BACK)),
        )
        BasicText(
            stringResource(R.string.about_version, version),
            style = type.body.copy(color = palette.ink),
            modifier = Modifier.padding(top = 8.dp),
        )
        BasicText(
            stringResource(R.string.about_fonts),
            style = type.body.copy(color = palette.inkMuted),
            modifier = Modifier.padding(top = 4.dp),
        )
        BasicText(
            stringResource(R.string.about_weather_data),
            style = type.body.copy(color = palette.inkMuted),
            modifier = Modifier.padding(top = 4.dp),
        )
        BasicText(
            stringResource(R.string.about_display),
            style = type.section.copy(color = palette.inkMuted),
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        )
        for ((label, value) in lines) {
            Row(Modifier.padding(vertical = 3.dp)) {
                BasicText(
                    stringResource(label.res()),
                    style = type.body.copy(color = palette.inkMuted),
                    modifier = Modifier.width(140.dp),
                )
                BasicText(value, style = type.body.copy(color = palette.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        BasicText(
            stringResource(R.string.about_made_by),
            style = type.hint.copy(color = palette.inkMuted),
            modifier = Modifier.padding(top = 20.dp),
        )
    }
}

private fun UiLogic.DisplayFactLabel.res() = when (this) {
    UiLogic.DisplayFactLabel.Resolution -> R.string.about_resolution
    UiLogic.DisplayFactLabel.RefreshRate -> R.string.about_refresh_rate
    UiLogic.DisplayFactLabel.WideColor -> R.string.about_wide_color
    UiLogic.DisplayFactLabel.Hdr -> R.string.about_hdr
    UiLogic.DisplayFactLabel.Modes -> R.string.about_modes
}
