package io.github.jameskbb.hearth.ui.settings

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.jameskbb.hearth.R
import io.github.jameskbb.hearth.theme.ThemeFailure
import io.github.jameskbb.hearth.theme.ThemeId
import io.github.jameskbb.hearth.theme.ThemeInfo
import io.github.jameskbb.hearth.theme.ThemeMode
import io.github.jameskbb.hearth.theme.ThemePeriod
import io.github.jameskbb.hearth.theme.ThemeProblem
import io.github.jameskbb.hearth.prefs.HomeLabel
import io.github.jameskbb.hearth.theme.ThemeSchedule
import io.github.jameskbb.hearth.ui.Appearance
import io.github.jameskbb.hearth.ui.AppearancePage
import io.github.jameskbb.hearth.ui.HearthViewModel
import io.github.jameskbb.hearth.ui.components.FocusRegistry
import io.github.jameskbb.hearth.ui.components.LeadBringIntoViewSpec
import io.github.jameskbb.hearth.ui.components.TextAction
import io.github.jameskbb.hearth.ui.components.focusTarget
import io.github.jameskbb.hearth.ui.components.remoteClickable
import io.github.jameskbb.hearth.ui.state.FocusTarget
import io.github.jameskbb.hearth.ui.state.Screen
import io.github.jameskbb.hearth.ui.theme.Dimens
import io.github.jameskbb.hearth.ui.theme.Hearth
import io.github.jameskbb.hearth.ui.theme.UiTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val AppearanceColumnWidth = 864.dp
private val CardBleed = 14.dp

@Composable
internal fun themeRowValue(vm: HearthViewModel): String? {
    val mode by vm.themeMode.collectAsState()
    val activeId by vm.activeThemeId.collectAsState()
    val info by vm.activeThemeInfo.collectAsState()
    if (mode == null || activeId == null) return null
    val name = info?.name ?: stringResource(R.string.theme_plain)
    return when (mode) {
        ThemeMode.Single -> name
        ThemeMode.FollowTheDay -> stringResource(R.string.theme_follow_now, name)
        ThemeMode.SurpriseMe -> stringResource(R.string.theme_surprise_now, name)
        null -> null
    }
}

@Composable
internal fun appearanceRows(vm: HearthViewModel, panel: Appearance?): List<FocusTarget> {
    val themes by vm.themes.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val page = panel?.page ?: AppearancePage.Main
    return remember(themes, prefs, page, panel?.periodId) {
        when (page) {
            AppearancePage.Main -> buildList {
                add(FocusTarget.Row(HearthViewModel.ROW_MODE_SINGLE))
                add(FocusTarget.Row(HearthViewModel.ROW_MODE_FOLLOW))
                add(FocusTarget.Row(HearthViewModel.ROW_MODE_SURPRISE))
                val mode = prefs?.let {
                    if (it.theme.isAuto && it.themeMode == ThemeMode.Single) ThemeMode.FollowTheDay else it.themeMode
                }
                if (mode == ThemeMode.FollowTheDay) {
                    add(FocusTarget.Row(HearthViewModel.ROW_SCHEDULE_EDIT))
                } else {
                    addAll(themes.map { FocusTarget.Row(HearthViewModel.themeCardRowId(it.id)) })
                }
                add(FocusTarget.Row(HearthViewModel.ROW_HOME_LABEL))
                add(FocusTarget.Row(HearthViewModel.ROW_APPEARANCE_RELOAD))
            }
            AppearancePage.HomeLabel -> buildList {
                add(FocusTarget.Row(HearthViewModel.ROW_HOME_LABEL_ENABLED))
                add(FocusTarget.Row(HearthViewModel.ROW_HOME_LABEL_EDIT))
                if (prefs?.homeLabel?.isNotEmpty() == true) add(FocusTarget.Row(HearthViewModel.ROW_HOME_LABEL_CLEAR))
            }
            AppearancePage.HomeLabelText -> listOf(
                FocusTarget.Row(HearthViewModel.ROW_HOME_LABEL_FIELD),
                FocusTarget.Row(HearthViewModel.ROW_HOME_LABEL_SAVE),
            )
            AppearancePage.Schedule -> buildList {
                prefs?.themeSchedule?.orderedPeriods?.forEach {
                    add(FocusTarget.Row(HearthViewModel.schedulePeriodRowId(it.id)))
                }
                add(FocusTarget.Row(HearthViewModel.ROW_SCHEDULE_RESTORE))
            }
            AppearancePage.Period -> buildList {
                add(FocusTarget.Row(HearthViewModel.ROW_PERIOD_EARLIER))
                add(FocusTarget.Row(HearthViewModel.ROW_PERIOD_LATER))
                add(FocusTarget.Row(HearthViewModel.ROW_PERIOD_SHUFFLE))
                addAll(themes.map { FocusTarget.Row(HearthViewModel.themeCardRowId(it.id)) })
                add(FocusTarget.Row(HearthViewModel.ROW_PERIOD_RENAME))
            }
            AppearancePage.PeriodName -> buildList {
                add(FocusTarget.Row(HearthViewModel.ROW_PERIOD_NAME))
                add(FocusTarget.Row(HearthViewModel.ROW_PERIOD_NAME_SAVE))
            }
        }
    }
}

@Composable
internal fun AppearancePanel(vm: HearthViewModel, panel: Appearance, registry: FocusRegistry) {
    when (panel.page) {
        AppearancePage.Main -> AppearanceMain(vm, panel, registry)
        AppearancePage.HomeLabel, AppearancePage.HomeLabelText -> HomeLabelPanel(vm, panel, registry)
        AppearancePage.Schedule -> ThemeSchedulePanel(vm, registry)
        AppearancePage.Period -> ThemePeriodPanel(vm, panel, registry)
        AppearancePage.PeriodName -> RenamePeriodPanel(vm, panel, registry)
    }
}

@Composable
private fun AppearanceMain(vm: HearthViewModel, panel: Appearance, registry: FocusRegistry) {
    val themes by vm.themes.collectAsState()
    val failures by vm.themeFailures.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val mode by vm.themeMode.collectAsState()
    val activeId by vm.activeThemeId.collectAsState()
    val palette = Hearth.palette
    val focused = settingFocus(vm)

    Column(Modifier.padding(top = 8.dp)) {
        BasicText(
            stringResource(R.string.appearance_mode_prompt),
            style = Hearth.type.section.copy(color = palette.inkMuted),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(42.dp)) {
            ModeChoice(vm, registry, ThemeMode.Single, R.string.theme_mode_single, mode, focused)
            ModeChoice(vm, registry, ThemeMode.FollowTheDay, R.string.theme_mode_follow, mode, focused)
            ModeChoice(vm, registry, ThemeMode.SurpriseMe, R.string.theme_mode_surprise, mode, focused)
        }
        BasicText(
            stringResource(
                when (mode) {
                    ThemeMode.FollowTheDay -> R.string.theme_mode_follow_short_description
                    ThemeMode.SurpriseMe -> R.string.theme_mode_surprise_description
                    else -> R.string.theme_mode_single_description
                },
            ),
            style = Hearth.type.body.copy(color = palette.ink),
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
        if (mode == ThemeMode.FollowTheDay) {
            SettingRow(
                stringResource(R.string.appearance_customize_schedule),
                (prefs?.themeSchedule?.periods?.size ?: 0).let {
                    pluralStringResource(R.plurals.appearance_period_count, it, it)
                },
                vm::openThemeSchedule,
                focused(HearthViewModel.ROW_SCHEDULE_EDIT),
                Modifier.width(560.dp)
                    .focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_SCHEDULE_EDIT)),
            )
        }
        if (mode != ThemeMode.FollowTheDay) {
            BasicText(
                stringResource(if (mode == ThemeMode.SurpriseMe) R.string.appearance_enabled_themes else R.string.appearance_choose_theme),
                style = Hearth.type.section.copy(color = palette.inkMuted),
                modifier = Modifier.padding(top = 10.dp),
            )
            ThemeCards(
                vm,
                themes,
                selected = { id ->
                    when (mode) {
                        ThemeMode.Single -> id == prefs?.theme
                        ThemeMode.SurpriseMe -> id in ThemeSchedule.surprisePool(prefs?.surpriseThemeIds.orEmpty(), themes)
                        else -> id == activeId
                    }
                },
                onClick = vm::chooseTheme,
                registry = registry,
                initialId = panel.focused,
                selectedLabel = stringResource(
                    if (mode == ThemeMode.SurpriseMe) R.string.appearance_included else R.string.appearance_selected,
                ),
            )
        }
        if (mode == ThemeMode.Single && panel.previewing) {
            val name = themes.firstOrNull { it.id == panel.focused }?.name.orEmpty()
            BasicText(
                stringResource(R.string.appearance_single_focused_hint, name),
                style = Hearth.type.hint.copy(color = palette.ink),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        SettingRow(
            stringResource(R.string.home_label_title),
            stringResource(if (prefs?.homeLabelEnabled == true && !prefs?.homeLabel.isNullOrBlank()) R.string.settings_on else R.string.settings_off),
            vm::openHomeLabel, focused(HearthViewModel.ROW_HOME_LABEL),
            Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_HOME_LABEL)),
        )
        SettingRow(
            stringResource(R.string.appearance_reload), null, vm::reloadThemes,
            focused(HearthViewModel.ROW_APPEARANCE_RELOAD),
            Modifier.padding(top = 4.dp)
                .focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_APPEARANCE_RELOAD)),
        )
        failures.forEach { ThemeFailureLine(it) }
    }
}

@Composable
private fun ModeChoice(
    vm: HearthViewModel,
    registry: FocusRegistry,
    rowMode: ThemeMode,
    label: Int,
    current: ThemeMode?,
    focused: (String) -> () -> Unit,
) {
    val id = HearthViewModel.modeRowId(rowMode)
    TextAction(
        stringResource(label),
        { vm.chooseThemeMode(rowMode) },
        Modifier.focusTarget(registry, FocusTarget.Row(id)),
        onFocused = focused(id),
        trailing = if (current == rowMode || rowMode == ThemeMode.FollowTheDay) { isFocused ->
            BasicText(
                stringResource(if (current == rowMode) R.string.appearance_selected else R.string.theme_mode_recommended),
                style = Hearth.type.hint.copy(
                    color = if (isFocused) Hearth.palette.accent else Hearth.palette.inkMuted,
                ),
                modifier = Modifier.padding(start = 10.dp, top = 4.dp),
            )
        } else null,
    )
}

@Composable
private fun ThemeSchedulePanel(vm: HearthViewModel, registry: FocusRegistry) {
    val prefs by vm.prefs.collectAsState()
    val themes by vm.themes.collectAsState()
    val schedule = prefs?.themeSchedule ?: return
    val ordered = schedule.orderedPeriods
    val focused = settingFocus(vm)
    Column(Modifier.padding(top = 12.dp)) {
        BasicText(
            stringResource(R.string.schedule_stability_hint),
            style = Hearth.type.hint.copy(color = Hearth.palette.ink),
            modifier = Modifier.padding(bottom = 4.dp),
        )
        ordered.forEachIndexed { index, period ->
            val id = HearthViewModel.schedulePeriodRowId(period.id)
            val end = ordered[(index + 1) % ordered.size].startMinute
            Column(Modifier.width(720.dp)) {
                SettingRow(
                    period.name, "${timeText(period.startMinute)} - ${timeText(end)}", { vm.openThemePeriod(period.id) },
                    focused(id), Modifier.focusTarget(registry, FocusTarget.Row(id)),
                )
                BasicText(
                    assignmentText(period, themes),
                    style = Hearth.type.body.copy(color = Hearth.palette.ink),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                )
            }
        }
        SettingRow(
            stringResource(R.string.schedule_restore), null, vm::restoreRecommendedSchedule,
            focused(HearthViewModel.ROW_SCHEDULE_RESTORE),
            Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_SCHEDULE_RESTORE)),
        )
    }
}

@Composable
private fun ThemePeriodPanel(vm: HearthViewModel, panel: Appearance, registry: FocusRegistry) {
    val prefs by vm.prefs.collectAsState()
    val themes by vm.themes.collectAsState()
    val period = prefs?.themeSchedule?.periods?.firstOrNull { it.id == panel.periodId } ?: return
    val focused = settingFocus(vm)
    Column(Modifier.padding(top = 12.dp)) {
        BasicText(
            stringResource(R.string.schedule_starts_at, timeText(period.startMinute)),
            style = Hearth.type.body.copy(color = Hearth.palette.ink),
            modifier = Modifier.padding(bottom = 4.dp),
        )
        SettingRow(
            stringResource(R.string.schedule_start_earlier),
            timeText(Math.floorMod(period.startMinute - 30, ThemePeriod.MINUTES_PER_DAY)),
            { vm.movePeriodStart(-30) },
            focused(HearthViewModel.ROW_PERIOD_EARLIER),
            Modifier.width(560.dp)
                .focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_PERIOD_EARLIER)),
        )
        SettingRow(
            stringResource(R.string.schedule_start_later),
            timeText(Math.floorMod(period.startMinute + 30, ThemePeriod.MINUTES_PER_DAY)),
            { vm.movePeriodStart(30) },
            focused(HearthViewModel.ROW_PERIOD_LATER),
            Modifier.width(560.dp)
                .focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_PERIOD_LATER)),
        )
        SettingRow(
            stringResource(R.string.schedule_theme_choice),
            stringResource(if (period.shuffle) R.string.schedule_choice_shuffle else R.string.schedule_choice_one),
            vm::togglePeriodShuffle,
            focused(HearthViewModel.ROW_PERIOD_SHUFFLE),
            Modifier.width(560.dp)
                .focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_PERIOD_SHUFFLE)),
        )
        BasicText(
            stringResource(if (period.shuffle) R.string.schedule_choose_shuffle_themes else R.string.schedule_choose_one_theme),
            style = Hearth.type.section.copy(color = Hearth.palette.inkMuted),
            modifier = Modifier.padding(top = 10.dp),
        )
        ThemeCards(
            vm,
            themes,
            { if (period.shuffle) it in period.themeIds else it == period.themeIds.firstOrNull() },
            vm::togglePeriodTheme,
            registry,
            selectedLabel = stringResource(if (period.shuffle) R.string.appearance_included else R.string.appearance_chosen),
        )
        SettingRow(
            stringResource(R.string.schedule_rename_period), period.name, vm::openPeriodRename,
            focused(HearthViewModel.ROW_PERIOD_RENAME),
            Modifier.width(560.dp)
                .focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_PERIOD_RENAME)),
        )
    }
}

@Composable
private fun RenamePeriodPanel(vm: HearthViewModel, panel: Appearance, registry: FocusRegistry) {
    val focused = settingFocus(vm)
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.width(560.dp).padding(top = 12.dp)) {
        UnderlineField(
            panel.periodName, stringResource(R.string.schedule_period_name), vm::onPeriodNameChange,
            { keyboard?.hide(); vm.savePeriodName() }, focused(HearthViewModel.ROW_PERIOD_NAME),
            Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_PERIOD_NAME)),
            ImeAction.Done, ThemePeriod.MAX_NAME_LENGTH,
        )
        SettingRow(
            stringResource(R.string.schedule_save_name), null, { keyboard?.hide(); vm.savePeriodName() },
            focused(HearthViewModel.ROW_PERIOD_NAME_SAVE),
            Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_PERIOD_NAME_SAVE)),
        )
    }
}

@Composable
private fun HomeLabelPanel(vm: HearthViewModel, panel: Appearance, registry: FocusRegistry) {
    val prefs by vm.prefs.collectAsState()
    val focused = settingFocus(vm)
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.width(560.dp).padding(top = 12.dp)) {
        if (panel.page == AppearancePage.HomeLabelText) {
            UnderlineField(
                panel.homeLabelDraft, stringResource(R.string.home_label_placeholder), vm::onHomeLabelChange,
                { keyboard?.hide(); vm.saveHomeLabel() }, focused(HearthViewModel.ROW_HOME_LABEL_FIELD),
                Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_HOME_LABEL_FIELD)),
                ImeAction.Done, HomeLabel.MAX_LENGTH * 2,
            )
            BasicText(
                stringResource(R.string.home_label_hint),
                style = Hearth.type.hint.copy(color = Hearth.palette.inkMuted),
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
            SettingRow(
                stringResource(R.string.rename_input_save), null, { keyboard?.hide(); vm.saveHomeLabel() },
                focused(HearthViewModel.ROW_HOME_LABEL_SAVE),
                Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_HOME_LABEL_SAVE)),
            )
        } else {
            SettingRow(
                stringResource(R.string.settings_weather_show),
                stringResource(if (prefs?.homeLabelEnabled == true) R.string.settings_on else R.string.settings_off),
                vm::toggleHomeLabel, focused(HearthViewModel.ROW_HOME_LABEL_ENABLED),
                Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_HOME_LABEL_ENABLED)),
            )
            SettingRow(
                stringResource(R.string.home_label_edit), prefs?.homeLabel?.takeIf { it.isNotEmpty() },
                vm::editHomeLabel, focused(HearthViewModel.ROW_HOME_LABEL_EDIT),
                Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_HOME_LABEL_EDIT)),
            )
            if (!prefs?.homeLabel.isNullOrEmpty()) {
                SettingRow(
                    stringResource(R.string.home_label_clear), null, vm::clearHomeLabel,
                    focused(HearthViewModel.ROW_HOME_LABEL_CLEAR),
                    Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_HOME_LABEL_CLEAR)),
                )
            }
            BasicText(
                stringResource(R.string.home_label_description),
                style = Hearth.type.hint.copy(color = Hearth.palette.inkMuted),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun settingFocus(vm: HearthViewModel): (String) -> () -> Unit = { id ->
    { vm.onFocused(Screen.Settings, FocusTarget.Row(id)) }
}

@Composable
private fun ThemeCards(
    vm: HearthViewModel,
    themes: List<ThemeInfo>,
    selected: (ThemeId) -> Boolean,
    onClick: (ThemeId) -> Unit,
    registry: FocusRegistry,
    initialId: ThemeId? = null,
    selectedLabel: String,
) {
    val reducedMotion = Hearth.reducedMotion
    val bleedPx = with(LocalDensity.current) { CardBleed.toPx() }
    val spec = remember(bleedPx, reducedMotion) { LeadBringIntoViewSpec(bleedPx, bleedPx, reducedMotion) }
    @OptIn(ExperimentalFoundationApi::class)
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        LazyRow(
            state = rememberLazyListState(
                initialFirstVisibleItemIndex = themes.indexOfFirst { it.id == initialId }.coerceAtLeast(0),
            ),
            contentPadding = PaddingValues(start = CardBleed, end = CardBleed, top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimens.tileGap),
            modifier = Modifier.bleedHorizontally(CardBleed),
        ) {
            items(themes, key = { it.id.name }) { theme ->
                ThemeCard(
                    theme, selected(theme.id), selectedLabel, { vm.themePreview(theme.id) }, { vm.focusTheme(theme.id) },
                    { onClick(theme.id) }, Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.themeCardRowId(theme.id))),
                )
            }
        }
    }
}

@Composable
private fun ThemeCard(
    info: ThemeInfo,
    selected: Boolean,
    selectedLabel: String,
    preview: suspend () -> ImageBitmap?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = Hearth.palette
    var focused by remember { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, info.id, info.contentRevision) {
        value = withContext(Dispatchers.IO) { runCatching { preview() }.getOrNull() }
    }
    val scale by animateFloatAsState(
        if (focused && !Hearth.reducedMotion) Dimens.focusScale else 1f,
        if (Hearth.reducedMotion) snap() else tween(UiTokens.motionMillis, easing = FastOutSlowInEasing),
        label = "cardScale",
    )
    val shape = remember { RoundedCornerShape(Dimens.tileRadius) }
    Column(Modifier.width(Dimens.tileWidth).zIndex(if (focused) 1f else 0f)) {
        Box(
            modifier.size(Dimens.tileWidth, Dimens.tileHeight)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .focusRing(focused)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
                .remoteClickable(onClick = onClick),
        ) {
            Box(Modifier.fillMaxSize().clip(shape).background(Color(info.palette.window))) {
                image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                if (selected) {
                    BasicText(
                        selectedLabel,
                        style = Hearth.type.tileLabel.copy(color = Color.White),
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        BasicText(
            info.name,
            style = Hearth.type.action.copy(color = if (selected) palette.accent else if (focused) palette.ink else palette.inkMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun assignmentText(period: ThemePeriod, themes: List<ThemeInfo>): String {
    val byId = themes.associateBy { it.id }
    val names = period.themeIds.mapNotNull { byId[it]?.name }
    if (names.isEmpty()) return stringResource(R.string.schedule_no_themes)
    return if (period.shuffle && names.size > 1) stringResource(R.string.schedule_shuffle_value, names.joinToString(", "))
    else names.first()
}

@Composable
private fun timeText(minute: Int): String {
    val context = LocalContext.current
    val use24Hour = android.text.format.DateFormat.is24HourFormat(context)
    val locale = Locale.getDefault()
    val formatter = remember(use24Hour, locale) {
        DateTimeFormatter.ofPattern(if (use24Hour) "H:mm" else "h:mm a", locale)
    }
    return remember(minute, formatter) { LocalTime.of(minute / 60, minute % 60).format(formatter) }
}

@Composable
private fun ThemeFailureLine(failure: ThemeFailure) {
    BasicText(
        stringResource(R.string.appearance_failure, failure.location, problemText(failure)),
        style = Hearth.type.hint.copy(color = Hearth.palette.inkMuted),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 3.dp).width(AppearanceColumnWidth),
    )
}

@Composable
private fun problemText(f: ThemeFailure): String = when (val p = f.problem) {
    ThemeProblem.MissingManifest -> stringResource(R.string.theme_problem_missing_manifest)
    ThemeProblem.MalformedManifest -> stringResource(R.string.theme_problem_malformed)
    is ThemeProblem.NeedsNewerHearth -> stringResource(R.string.theme_problem_newer)
    is ThemeProblem.MissingField -> stringResource(R.string.theme_problem_missing_field, p.field)
    is ThemeProblem.BadValue -> stringResource(R.string.theme_problem_bad_value, p.field)
    is ThemeProblem.BadId -> stringResource(R.string.theme_problem_bad_id, p.id)
    is ThemeProblem.LayerCount -> stringResource(R.string.theme_problem_layer_count, p.count)
    is ThemeProblem.MissingImage -> stringResource(R.string.theme_problem_missing_image, p.path)
    is ThemeProblem.UnreadableImage -> stringResource(R.string.theme_problem_unreadable_image, p.path)
    is ThemeProblem.BaseLayerSize -> stringResource(R.string.theme_problem_base_size, p.path, "${p.width} x ${p.height}")
    is ThemeProblem.ImageTooWide -> stringResource(R.string.theme_problem_too_wide, p.path)
    is ThemeProblem.TooLarge -> stringResource(R.string.theme_problem_too_large)
    is ThemeProblem.DuplicateId -> stringResource(
        if (p.byBundled) R.string.theme_problem_duplicate_bundled else R.string.theme_problem_duplicate, p.id,
    )
    ThemeProblem.ArchiveTooLarge -> stringResource(R.string.theme_problem_archive_too_large)
    ThemeProblem.UnreadableArchive -> stringResource(R.string.theme_problem_archive_unreadable)
}

@Composable
private fun Modifier.focusRing(show: Boolean): Modifier {
    val palette = Hearth.palette
    return drawBehind {
        if (!show) return@drawBehind
        val width = size.width * 0.86f
        val height = 30.dp.toPx()
        val center = Offset(size.width / 2, size.height + 6.dp.toPx())
        scale(width / height, 1f, center) {
            drawCircle(Brush.radialGradient(listOf(palette.glow, Color.Transparent), center, height / 2), height / 2, center)
        }
    }.drawWithContent {
        drawContent()
        if (!show) return@drawWithContent
        val gap = Dimens.ringGap.toPx()
        val ring = Dimens.ringWidth.toPx()
        val radius = Dimens.tileRadius.toPx()
        drawRoundRect(
            Color.Black.copy(alpha = 0.55f), Offset(-gap / 2, -gap / 2), Size(size.width + gap, size.height + gap),
            CornerRadius(radius + gap / 2), style = Stroke(gap),
        )
        val offset = gap + ring / 2
        drawRoundRect(
            palette.accent, Offset(-offset, -offset), Size(size.width + 2 * offset, size.height + 2 * offset),
            CornerRadius(radius + offset), style = Stroke(ring),
        )
    }
}

private fun Modifier.bleedHorizontally(bleed: Dp): Modifier = layout { measurable, constraints ->
    val extra = bleed.roundToPx() * 2
    val wide = if (constraints.hasBoundedWidth) {
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra)
    } else constraints
    val placeable = measurable.measure(wide)
    layout((placeable.width - extra).coerceAtLeast(0), placeable.height) { placeable.place(-extra / 2, 0) }
}
