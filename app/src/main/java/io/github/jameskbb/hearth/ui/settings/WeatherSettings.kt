package io.github.jameskbb.hearth.ui.settings

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jameskbb.hearth.R
import io.github.jameskbb.hearth.system.primaryLocale
import io.github.jameskbb.hearth.ui.CitySearch
import io.github.jameskbb.hearth.ui.HearthViewModel
import io.github.jameskbb.hearth.ui.components.FocusRegistry
import io.github.jameskbb.hearth.ui.components.focusTarget
import io.github.jameskbb.hearth.ui.state.FocusTarget
import io.github.jameskbb.hearth.ui.theme.Hearth
import io.github.jameskbb.hearth.weather.WeatherFailure
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/** Longest city name the search field accepts. */
private const val MAX_QUERY = 60

/**
 * The line (or two) under the weather rows: what Hearth is doing with the network and how fresh
 * the weather is. A failure says what happened and when Hearth tries again; it is drawn in full
 * ink so it reads as news, the rest in muted ink. Plain text, never focusable.
 */
@Composable
internal fun WeatherStatus(vm: HearthViewModel, weatherOn: Boolean, hasPlace: Boolean) {
    val state by vm.weather.collectAsState()
    // The same value Home shows, so "current" here and on Home always agree (and this recomposes
    // the moment a result grows too old for Home).
    val glance by vm.homeWeather.collectAsState()
    val context = LocalContext.current
    val now = System.currentTimeMillis()

    val lines = buildList {
        when {
            !weatherOn -> add(stringResource(R.string.weather_status_off) to false)
            !hasPlace -> add(stringResource(R.string.weather_status_no_city) to false)
            else -> {
                val failure = state.failure
                val retry = state.retryAt
                if (failure != null && retry != null) add(stringResource(failure.statusText(), clockTime(context, retry)) to true)
                val report = state.report?.takeIf { it.isFor(state.place) }
                when {
                    report != null && glance != null ->
                        add(stringResource(R.string.weather_status_updated, clockTime(context, report.fetchedAt)) to false)
                    report != null ->
                        add(stringResource(R.string.weather_status_too_old, whenText(context, report.fetchedAt, now)) to false)
                    failure == null -> add(stringResource(R.string.weather_status_fetching) to false)
                }
            }
        }
    }
    val palette = Hearth.palette
    val type = Hearth.type
    Column(Modifier.padding(top = 4.dp)) {
        for ((text, emphasis) in lines) {
            BasicText(
                text,
                style = type.hint.copy(color = if (emphasis) palette.ink else palette.inkMuted),
                modifier = Modifier.padding(vertical = 2.dp).width(360.dp),
            )
        }
    }
}

private fun WeatherFailure.statusText(): Int = when (this) {
    WeatherFailure.Unreachable -> R.string.weather_failed_unreachable
    WeatherFailure.ServerError -> R.string.weather_failed_server
    WeatherFailure.BadResponse -> R.string.weather_failed_response
}

/** The time in the user's 12/24-hour format: "9:05 PM" or "21:05". */
private fun clockTime(context: Context, millis: Long): String = DateFormat.getTimeFormat(context).format(Date(millis))

/** "at 6:12 PM" today, "on Oct 19 at 6:12 PM" on another day. */
@Composable
private fun whenText(context: Context, millis: Long, now: Long): String {
    val time = clockTime(context, millis)
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val sameDay = then.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return stringResource(R.string.weather_status_time_today, time)
    val locale = LocalConfiguration.current.primaryLocale()
    val day = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale).format(Date(millis))
    return stringResource(R.string.weather_status_time_other_day, day, time)
}

/**
 * The city search, in place of the Settings rows. The field takes focus when it opens, which
 * brings up the system keyboard. Matches appear as the user types (after a short pause); the
 * keyboard's Search key searches at once, closes the keyboard and focuses the first match. Down
 * moves from the field to the matches; Select on a match chooses it; Back closes the keyboard
 * first, then the search.
 */
@Composable
internal fun CitySearchPanel(
    vm: HearthViewModel,
    search: CitySearch,
    registry: FocusRegistry,
    focused: (String) -> () -> Unit,
) {
    val palette = Hearth.palette
    val type = Hearth.type
    val keyboard = LocalSoftwareKeyboardController.current

    Column(Modifier.padding(top = 14.dp)) {
        UnderlineField(
            value = search.query,
            placeholder = stringResource(R.string.city_search_placeholder),
            onValueChange = vm::onCityQueryChange,
            onSubmit = {
                keyboard?.hide()
                vm.submitCitySearch()
            },
            onFocused = focused(HearthViewModel.ROW_CITY_FIELD),
            modifier = Modifier.focusTarget(registry, FocusTarget.Row(HearthViewModel.ROW_CITY_FIELD)),
        )
        val failed = search.status == CitySearch.Status.Failed
        val note = when (search.status) {
            CitySearch.Status.Idle -> stringResource(R.string.city_search_idle)
            CitySearch.Status.Searching -> stringResource(R.string.city_search_searching)
            CitySearch.Status.Results -> stringResource(R.string.city_search_results)
            CitySearch.Status.NoMatches -> stringResource(R.string.city_search_no_matches, search.searchedFor)
            CitySearch.Status.Failed -> stringResource(
                if (search.failure == WeatherFailure.Unreachable) R.string.city_search_unreachable
                else R.string.city_search_failed,
            )
        }
        BasicText(
            note,
            style = type.hint.copy(color = if (failed) palette.ink else palette.inkMuted),
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).width(360.dp),
        )
        Column(Modifier.verticalScroll(rememberScrollState())) {
            search.results.forEachIndexed { index, place ->
                key(index, place) {
                    val id = HearthViewModel.cityResultRowId(index)
                    SettingRow(
                        label = place.name,
                        value = place.area().ifEmpty { null },
                        onClick = { vm.choosePlace(place) },
                        onFocused = focused(id),
                        modifier = Modifier.focusTarget(registry, FocusTarget.Row(id)),
                    )
                }
            }
        }
    }
}

/**
 * A one-line text field with Hearth's own underline (accent when focused) and no caret, no boxed
 * input: the city search, and renaming an input. The keyboard's action key calls [onSubmit];
 * [imeAction] picks which key that is. Input stops at [maxLength] characters.
 */
@Composable
internal fun UnderlineField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Search,
    maxLength: Int = MAX_QUERY,
) {
    val palette = Hearth.palette
    val type = Hearth.type
    var isFocused by remember { mutableStateOf(false) }
    val submit = KeyboardActions(
        onSearch = { onSubmit() },
        onDone = { onSubmit() },
        onGo = { onSubmit() },
        onNext = { onSubmit() },
        onSend = { onSubmit() },
    )
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.replace("\n", "").take(maxLength)) },
        singleLine = true,
        textStyle = type.dialogOption.copy(color = palette.ink),
        // No caret: a blinking caret would animate for as long as the field sat focused, and text
        // is only ever added at the end from the keyboard. The accent underline marks the field.
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = imeAction,
        ),
        keyboardActions = submit,
        modifier = modifier
            .width(340.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val stroke = (if (isFocused) 2.dp else 1.dp).toPx()
                        val y = size.height - stroke / 2
                        drawLine(
                            color = if (isFocused) palette.accent else palette.inkMuted,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = stroke,
                        )
                    }
                    .padding(top = 6.dp, bottom = 8.dp),
            ) {
                if (value.isEmpty()) {
                    BasicText(placeholder, style = type.dialogOption.copy(color = palette.inkMuted))
                }
                inner()
            }
        },
    )
}
