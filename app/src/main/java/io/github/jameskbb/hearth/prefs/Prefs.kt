package io.github.jameskbb.hearth.prefs

import io.github.jameskbb.hearth.apps.AppKey
import io.github.jameskbb.hearth.theme.ThemeId
import io.github.jameskbb.hearth.theme.ThemeMode
import io.github.jameskbb.hearth.theme.ThemeRotationState
import io.github.jameskbb.hearth.theme.ThemeScheduleConfig
import io.github.jameskbb.hearth.weather.Place
import io.github.jameskbb.hearth.weather.TemperatureUnit
import kotlinx.coroutines.flow.Flow

/** Everything Hearth persists. Survives app restarts and reboots (DataStore on disk). */
data class HearthPrefs(
    /** Ordered favorites shown on the home screen. May reference apps not currently installed. */
    val favorites: List<AppKey> = emptyList(),
    /** Apps hidden from All apps and Home. Restorable from Settings. */
    val hidden: Set<AppKey> = emptySet(),
    /** The pack used by [ThemeMode.Single]. May name a pack that is no longer installed. */
    val theme: ThemeId = ThemeId.Default,
    /** How Hearth chooses a theme. Existing `auto` choices migrate to Follow the Day. */
    val themeMode: ThemeMode = ThemeMode.Single,
    /** User-editable periods and their assigned theme pools. */
    val themeSchedule: ThemeScheduleConfig = ThemeScheduleConfig.Recommended,
    /** Stable choices by period id, retained across launches and device restarts. */
    val followDaySelections: Map<String, ThemeRotationState> = emptyMap(),
    /** Explicit Surprise Me pool. Empty means all usable automatically eligible themes. */
    val surpriseThemeIds: Set<ThemeId> = emptySet(),
    /** Today's stable Surprise Me choice. */
    val surpriseSelection: ThemeRotationState? = null,
    /** Disables scale and crossfade animations; focus is shown by the ring alone. */
    val reducedMotion: Boolean = false,
    /** False until favorites have been seeded once from the installed TV apps. */
    val seeded: Boolean = false,
    /** Opt-in weather on Home. Off by default; while off, Hearth makes no network requests. */
    val weatherEnabled: Boolean = false,
    /** The city chosen in Settings. Kept when weather is turned off, so turning it back on is one step. */
    val weatherPlace: Place? = null,
    /** The unit chosen in Settings; null follows the locale (see Temperatures.defaultUnit). */
    val temperatureUnit: TemperatureUnit? = null,
    /**
     * Names the user gave the TV's inputs in Settings, by framework input id. An input without one
     * shows the name the TV reports. Hearth never renames anything in the system.
     */
    val inputLabels: Map<String, String> = emptyMap(),
    /** Optional title on Home only. Disabling retains the text. */
    val homeLabelEnabled: Boolean = false,
    val homeLabel: String = "",
)

interface PrefsRepository {
    val prefs: Flow<HearthPrefs>
    /** Atomically read-modify-write. Use the pure operations in [FavoritesOps] inside [transform]. */
    suspend fun update(transform: (HearthPrefs) -> HearthPrefs)
}
