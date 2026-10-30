package io.github.jameskbb.hearth.prefs

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.jameskbb.hearth.apps.AppKey
import io.github.jameskbb.hearth.theme.ThemeId
import io.github.jameskbb.hearth.theme.ThemeMode
import io.github.jameskbb.hearth.theme.ThemePeriod
import io.github.jameskbb.hearth.theme.ThemeRotationState
import io.github.jameskbb.hearth.theme.ThemeScheduleConfig
import io.github.jameskbb.hearth.weather.Place
import io.github.jameskbb.hearth.weather.TemperatureUnit

/**
 * Maps [HearthPrefs] to and from DataStore [Preferences]. Pure (no Android framework
 * classes), so it runs in JVM unit tests.
 *
 * Storage layout:
 * - `favorites`: one string, keys joined by newline, which keeps the order. An [AppKey] is
 *   "package/activity" and neither part can contain a newline.
 * - `hidden`: a string set.
 * - `wallpaper` remains the legacy theme choice for downgrade compatibility. The current mode,
 *   single-theme choice, schedule, Surprise Me pool, and stable rotation choices have their own
 *   keys.
 * - `weather_enabled`, `temperature_unit` (absent when following the locale), and the chosen city
 *   as `weather_place_name`, `_region`, `_country`, `_lat`, `_lon` (all absent when none).
 * - `input_labels`: a string set of "<input id>\t<name>", one per renamed input.
 *
 * Reading is forgiving: a missing or malformed value falls back to the [HearthPrefs]
 * default, and malformed or duplicate keys are dropped rather than failing the whole read.
 */
object PrefsCodec {
    val FAVORITES = stringPreferencesKey("favorites")
    val HIDDEN = stringSetPreferencesKey("hidden")
    val THEME = stringPreferencesKey("wallpaper")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val SINGLE_THEME = stringPreferencesKey("single_theme")
    val THEME_PERIODS = stringSetPreferencesKey("theme_periods")
    val FOLLOW_DAY_SELECTIONS = stringSetPreferencesKey("follow_day_selections")
    val SURPRISE_THEME_IDS = stringSetPreferencesKey("surprise_theme_ids")
    val SURPRISE_SELECTION = stringPreferencesKey("surprise_selection")
    val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
    val SEEDED = booleanPreferencesKey("seeded")
    val WEATHER_ENABLED = booleanPreferencesKey("weather_enabled")
    val TEMPERATURE_UNIT = stringPreferencesKey("temperature_unit")
    val PLACE_NAME = stringPreferencesKey("weather_place_name")
    val PLACE_REGION = stringPreferencesKey("weather_place_region")
    val PLACE_COUNTRY = stringPreferencesKey("weather_place_country")
    val PLACE_LAT = doublePreferencesKey("weather_place_lat")
    val PLACE_LON = doublePreferencesKey("weather_place_lon")
    val INPUT_LABELS = stringSetPreferencesKey("input_labels")
    val HOME_LABEL_ENABLED = booleanPreferencesKey("home_label_enabled")
    val HOME_LABEL = stringPreferencesKey("home_label")

    private const val SEPARATOR = '\n'
    private const val LABEL_SEPARATOR = '\t'

    /** Longest input name Hearth stores; the rename field stops there too. */
    const val MAX_INPUT_LABEL = 40

    fun read(prefs: Preferences): HearthPrefs {
        val defaults = HearthPrefs()
        val legacyChoice = ThemeId.parseChoice(prefs.getOrNull(THEME))
        val explicitSingle = ThemeId.parseChoice(prefs.getOrNull(SINGLE_THEME))?.takeIf { !it.isAuto }
        val mode = ThemeMode.fromStored(prefs.getOrNull(THEME_MODE))
            ?: if (legacyChoice?.isAuto == true) ThemeMode.FollowTheDay else ThemeMode.Single
        return HearthPrefs(
            favorites = decodeFavorites(prefs.getOrNull(FAVORITES)),
            hidden = decodeHidden(prefs.getOrNull(HIDDEN)),
            theme = explicitSingle ?: legacyChoice?.takeIf { !it.isAuto } ?: defaults.theme,
            themeMode = mode,
            themeSchedule = decodeThemeSchedule(prefs.getOrNull(THEME_PERIODS)),
            followDaySelections = decodeFollowDaySelections(prefs.getOrNull(FOLLOW_DAY_SELECTIONS)),
            surpriseThemeIds = decodeThemeIds(prefs.getOrNull(SURPRISE_THEME_IDS)),
            surpriseSelection = decodeRotationState(prefs.getOrNull(SURPRISE_SELECTION)),
            reducedMotion = prefs.getOrNull(REDUCED_MOTION) ?: defaults.reducedMotion,
            seeded = prefs.getOrNull(SEEDED) ?: defaults.seeded,
            weatherEnabled = prefs.getOrNull(WEATHER_ENABLED) ?: defaults.weatherEnabled,
            weatherPlace = readPlace(prefs),
            temperatureUnit = TemperatureUnit.fromStored(prefs.getOrNull(TEMPERATURE_UNIT)),
            inputLabels = decodeInputLabels(prefs.getOrNull(INPUT_LABELS)),
            homeLabelEnabled = prefs.getOrNull(HOME_LABEL_ENABLED) ?: false,
            homeLabel = HomeLabel.clean(prefs.getOrNull(HOME_LABEL).orEmpty()),
        )
    }

    /** The stored city, or null when any required part is missing or the coordinates are impossible. */
    private fun readPlace(prefs: Preferences): Place? {
        val name = prefs.getOrNull(PLACE_NAME)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lat = prefs.getOrNull(PLACE_LAT) ?: return null
        val lon = prefs.getOrNull(PLACE_LON) ?: return null
        if (!Place.isValid(lat, lon)) return null
        return Place(
            name = name,
            region = prefs.getOrNull(PLACE_REGION)?.trim()?.takeIf { it.isNotEmpty() },
            country = prefs.getOrNull(PLACE_COUNTRY)?.trim()?.takeIf { it.isNotEmpty() },
            latitude = lat,
            longitude = lon,
        )
    }

    /** Writes every field of [value] into [prefs], replacing what was there. */
    fun write(prefs: MutablePreferences, value: HearthPrefs) {
        prefs[FAVORITES] = encodeFavorites(value.favorites)
        prefs[HIDDEN] = value.hidden.filter(::isValidKey).map { it.id }.toSet()
        val single = value.theme.takeIf { ThemeId.isValidPackId(it.name) } ?: ThemeId.Default
        // Old Hearth versions still understand Single Theme and Follow the Day after a downgrade.
        prefs[THEME] = if (value.themeMode == ThemeMode.FollowTheDay) ThemeId.Auto.name else single.name
        prefs[THEME_MODE] = value.themeMode.storedName
        prefs[SINGLE_THEME] = single.name
        prefs[THEME_PERIODS] = encodeThemeSchedule(value.themeSchedule)
        val periodIds = value.themeSchedule.periods.mapTo(HashSet()) { it.id }
        prefs[FOLLOW_DAY_SELECTIONS] = encodeFollowDaySelections(value.followDaySelections.filterKeys { it in periodIds })
        prefs[SURPRISE_THEME_IDS] = value.surpriseThemeIds
            .filter { ThemeId.isValidPackId(it.name) }.map { it.name }.toSet()
        val surprise = encodeRotationState(value.surpriseSelection)
        if (surprise == null) prefs.remove(SURPRISE_SELECTION) else prefs[SURPRISE_SELECTION] = surprise
        prefs[REDUCED_MOTION] = value.reducedMotion
        prefs[SEEDED] = value.seeded
        prefs[WEATHER_ENABLED] = value.weatherEnabled
        prefs[INPUT_LABELS] = encodeInputLabels(value.inputLabels)
        prefs[HOME_LABEL_ENABLED] = value.homeLabelEnabled
        prefs[HOME_LABEL] = HomeLabel.clean(value.homeLabel)
        val unit = value.temperatureUnit
        if (unit == null) prefs.remove(TEMPERATURE_UNIT) else prefs[TEMPERATURE_UNIT] = unit.storedName
        val place = value.weatherPlace?.takeIf { it.name.isNotBlank() && Place.isValid(it.latitude, it.longitude) }
        if (place == null) {
            listOf(PLACE_NAME, PLACE_REGION, PLACE_COUNTRY).forEach { prefs.remove(it) }
            prefs.remove(PLACE_LAT)
            prefs.remove(PLACE_LON)
        } else {
            prefs[PLACE_NAME] = place.name
            val region = place.region
            if (region == null) prefs.remove(PLACE_REGION) else prefs[PLACE_REGION] = region
            val country = place.country
            if (country == null) prefs.remove(PLACE_COUNTRY) else prefs[PLACE_COUNTRY] = country
            prefs[PLACE_LAT] = place.latitude
            prefs[PLACE_LON] = place.longitude
        }
    }

    fun encodeFavorites(favorites: List<AppKey>): String =
        favorites.filter(::isValidKey).distinct().joinToString(SEPARATOR.toString()) { it.id }

    fun decodeFavorites(raw: String?): List<AppKey> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(SEPARATOR)
            .map { AppKey(it.trim()) }
            .filter(::isValidKey)
            .distinct()
    }

    /** A name as Hearth stores it: one line, trimmed, at most [MAX_INPUT_LABEL] characters; null if empty. */
    fun cleanInputLabel(raw: String): String? =
        raw.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(MAX_INPUT_LABEL).trim()
            .takeIf { it.isNotEmpty() }

    /** An input id Hearth can store: non-empty, no whitespace or control characters. */
    fun isValidInputId(id: String): Boolean = id.isNotEmpty() && id.none { it.isWhitespace() || it.isISOControl() }

    fun encodeInputLabels(labels: Map<String, String>): Set<String> =
        labels.mapNotNullTo(HashSet()) { (id, label) ->
            val clean = cleanInputLabel(label)
            if (!isValidInputId(id) || clean == null) null else "$id$LABEL_SEPARATOR$clean"
        }

    fun decodeInputLabels(raw: Set<String>?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (item in raw.orEmpty().sorted()) {
            val tab = item.indexOf(LABEL_SEPARATOR)
            if (tab <= 0) continue
            val id = item.substring(0, tab)
            val label = cleanInputLabel(item.substring(tab + 1)) ?: continue
            if (isValidInputId(id) && id !in out) out[id] = label
        }
        return out
    }

    fun decodeHidden(raw: Set<String>?): Set<AppKey> =
        raw.orEmpty().map { AppKey(it.trim()) }.filter(::isValidKey).toSet()

    /** A versionless row format whose fields cannot contain tabs after validation. */
    fun encodeThemeSchedule(schedule: ThemeScheduleConfig): Set<String> =
        schedule.periods.mapIndexedTo(LinkedHashSet()) { index, period ->
            listOf(
                index.toString(),
                period.id,
                period.startMinute.toString(),
                if (period.shuffle) "1" else "0",
                period.name,
                period.themeIds.joinToString(",") { it.name },
            ).joinToString(LABEL_SEPARATOR.toString())
        }

    fun decodeThemeSchedule(raw: Set<String>?): ThemeScheduleConfig {
        if (raw.isNullOrEmpty()) return ThemeScheduleConfig.Recommended
        val decoded = raw.mapNotNull { row ->
            val fields = row.split(LABEL_SEPARATOR)
            if (fields.size != 6) return@mapNotNull null
            val order = fields[0].toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            val id = fields[1].takeIf(ThemePeriod::isValidId) ?: return@mapNotNull null
            val start = fields[2].toIntOrNull()?.takeIf { it in 0 until ThemePeriod.MINUTES_PER_DAY }
                ?: return@mapNotNull null
            val shuffle = when (fields[3]) {
                "0" -> false
                "1" -> true
                else -> return@mapNotNull null
            }
            val name = ThemePeriod.cleanName(fields[4]).takeIf { it.isNotEmpty() && it == fields[4] }
                ?: return@mapNotNull null
            val themes = fields[5].split(',').filter { it.isNotEmpty() }
                .mapNotNull { ThemeId.parseChoice(it)?.takeIf { parsed -> !parsed.isAuto } }
                .distinct()
            order to runCatching { ThemePeriod(id, name, start, themes, shuffle) }.getOrNull()
        }.filter { it.second != null }.sortedBy { it.first }.map { it.second!! }
        return runCatching { ThemeScheduleConfig(decoded) }.getOrDefault(ThemeScheduleConfig.Recommended)
    }

    fun encodeFollowDaySelections(selections: Map<String, ThemeRotationState>): Set<String> =
        selections.mapNotNullTo(HashSet()) { (periodId, state) ->
            val encoded = encodeRotationState(state)
            if (!ThemePeriod.isValidId(periodId) || encoded == null) null
            else "$periodId$LABEL_SEPARATOR$encoded"
        }

    fun decodeFollowDaySelections(raw: Set<String>?): Map<String, ThemeRotationState> {
        val out = LinkedHashMap<String, ThemeRotationState>()
        raw.orEmpty().sorted().forEach { row ->
            val tab = row.indexOf(LABEL_SEPARATOR)
            if (tab <= 0) return@forEach
            val periodId = row.substring(0, tab)
            val state = decodeRotationState(row.substring(tab + 1))
            if (ThemePeriod.isValidId(periodId) && state != null && periodId !in out) out[periodId] = state
        }
        return out
    }

    fun encodeRotationState(state: ThemeRotationState?): String? {
        if (state == null || !isValidOccurrenceKey(state.occurrenceKey) ||
            !ThemeId.isValidPackId(state.themeId.name)
        ) return null
        return "${state.occurrenceKey}$LABEL_SEPARATOR${state.themeId.name}"
    }

    fun decodeRotationState(raw: String?): ThemeRotationState? {
        if (raw == null) return null
        val tab = raw.lastIndexOf(LABEL_SEPARATOR)
        if (tab <= 0) return null
        val key = raw.substring(0, tab)
        val id = ThemeId.parseChoice(raw.substring(tab + 1))?.takeIf { !it.isAuto } ?: return null
        return key.takeIf(::isValidOccurrenceKey)?.let { ThemeRotationState(it, id) }
    }

    private fun decodeThemeIds(raw: Set<String>?): Set<ThemeId> =
        raw.orEmpty().mapNotNull { ThemeId.parseChoice(it)?.takeIf { id -> !id.isAuto } }.toSet()

    private fun isValidOccurrenceKey(value: String): Boolean =
        value.isNotEmpty() && value.length <= 160 && value.none { it.isISOControl() }

    /**
     * "package/activity" with both parts non-empty and no separator characters, or an input key,
     * "input:" and a non-empty framework id (which may contain slashes) with no whitespace.
     */
    fun isValidKey(key: AppKey): Boolean {
        val id = key.id
        if (key.isInput) {
            return id.length > AppKey.INPUT_PREFIX.length && id.none { it == SEPARATOR || it == '\r' || it.isWhitespace() }
        }
        val slash = id.indexOf('/')
        return slash > 0 && slash < id.length - 1 && id.indexOf('/', slash + 1) < 0 &&
            id.none { it == SEPARATOR || it == '\r' || it.isWhitespace() }
    }

    /**
     * A value stored under a key with an unexpected type (for example from a future or
     * hand-edited file) would throw ClassCastException where it is used; treat it as missing.
     */
    private inline fun <reified T> Preferences.getOrNull(key: Preferences.Key<T>): T? =
        asMap()[key] as? T
}
