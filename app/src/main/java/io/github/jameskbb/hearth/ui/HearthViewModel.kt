package io.github.jameskbb.hearth.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jameskbb.hearth.AppContainer
import io.github.jameskbb.hearth.HearthApp
import io.github.jameskbb.hearth.apps.AppEntry
import io.github.jameskbb.hearth.apps.AppKey
import io.github.jameskbb.hearth.apps.EntryKind
import io.github.jameskbb.hearth.apps.LaunchResult
import io.github.jameskbb.hearth.prefs.FavoritesOps
import io.github.jameskbb.hearth.prefs.HearthPrefs
import io.github.jameskbb.hearth.prefs.HomeLabel
import io.github.jameskbb.hearth.prefs.PrefsCodec
import io.github.jameskbb.hearth.system.SystemRoute
import io.github.jameskbb.hearth.theme.LoadedTheme
import io.github.jameskbb.hearth.theme.ThemeFailure
import io.github.jameskbb.hearth.theme.ThemeId
import io.github.jameskbb.hearth.theme.ThemeInfo
import io.github.jameskbb.hearth.theme.ThemeMode
import io.github.jameskbb.hearth.theme.ThemePeriod
import io.github.jameskbb.hearth.theme.ThemeResolution
import io.github.jameskbb.hearth.theme.ThemeRotationState
import io.github.jameskbb.hearth.theme.ThemeSchedule
import io.github.jameskbb.hearth.theme.ThemeScheduleConfig
import io.github.jameskbb.hearth.ui.state.ArrangeState
import io.github.jameskbb.hearth.ui.state.FocusTarget
import io.github.jameskbb.hearth.ui.state.HomeAction
import io.github.jameskbb.hearth.ui.state.Screen
import io.github.jameskbb.hearth.ui.state.UiLogic
import io.github.jameskbb.hearth.ui.theme.DefaultPalette
import io.github.jameskbb.hearth.ui.theme.HearthPalette
import io.github.jameskbb.hearth.ui.theme.toHearthPalette
import io.github.jameskbb.hearth.weather.Place
import io.github.jameskbb.hearth.weather.TemperatureUnit
import io.github.jameskbb.hearth.weather.WeatherException
import io.github.jameskbb.hearth.weather.WeatherFailure
import io.github.jameskbb.hearth.weather.WeatherGlance
import io.github.jameskbb.hearth.weather.WeatherPolicy
import io.github.jameskbb.hearth.weather.WeatherState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale

/** A request to move focus on [screen]. [seq] makes repeated requests for the same target distinct. */
data class FocusRequest(val screen: Screen, val target: FocusTarget, val seq: Long)

/** An app the options dialog is open for, and where focus goes back to when it closes. */
data class OptionsState(val entry: AppEntry, val origin: Screen, val isFavorite: Boolean, val canMove: Boolean)

/**
 * Inline feedback shown in place of the hint. Cleared on the next focus move away from [origin].
 *
 * [Kind.NotInstalled] comes from a launch that found the app gone. The catalog rescan then drops
 * the tile, and focus moves to its neighbor; the message moves with it (its origin becomes the
 * neighbor) so it stays readable until the user moves focus. There is no advice to remove the
 * favorite: an uninstalled favorite is not shown, and it comes back in place if reinstalled.
 */
data class InlineMessage(val screen: Screen, val origin: FocusTarget, val kind: Kind, val label: String) {
    enum class Kind { NotInstalled, LaunchFailed, DeviceSettingsFailed }
}

/** What Home shows once everything has loaded. */
data class HomeContent(val favorites: List<AppEntry>, val noApps: Boolean)

/** All apps content: the TV's inputs first, then TV apps, then the rest. */
data class AllAppsContent(
    val inputs: List<AppEntry>,
    val tvApps: List<AppEntry>,
    val otherApps: List<AppEntry>,
    val loaded: Boolean,
) {
    val ordered: List<AppEntry> get() = inputs + tvApps + otherApps
}

/**
 * Renaming an input in Settings: the input's framework id, the name being typed, and the name the
 * TV reports ([tvLabel]), which an empty name goes back to. [custom] is true while a name of the
 * user's is stored.
 */
data class InputRename(val inputId: String, val name: String, val tvLabel: String, val custom: Boolean)

/**
 * The city search in Settings: what has been typed, and what the last search found.
 * [searchedFor] is the query the [results] (or [failure]) belong to.
 */
data class CitySearch(
    val query: String = "",
    val status: Status = Status.Idle,
    val results: List<Place> = emptyList(),
    val searchedFor: String = "",
    val failure: WeatherFailure? = null,
) {
    enum class Status { Idle, Searching, Results, NoMatches, Failed }
}

/**
 * Appearance navigation and its live theme-card preview. Mode and schedule actions save at once;
 * Back only leaves the current page and never discards an action the user selected.
 */
data class Appearance(
    val focused: ThemeId,
    val saved: ThemeId,
    val page: AppearancePage = AppearancePage.Main,
    val periodId: String? = null,
    val periodName: String = "",
    val previewing: Boolean = false,
    val homeLabelDraft: String = "",
)

enum class AppearancePage { Main, Schedule, Period, PeriodName, HomeLabel, HomeLabelText }

/**
 * The whole UI state machine: which screen is showing, the overlays above it, where focus should
 * be, and every user action. No navigation library; screens are an enum.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HearthViewModel(
    private val container: AppContainer,
    private val saved: SavedStateHandle,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {

    val prefs: StateFlow<HearthPrefs?> = container.prefs.prefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val apps: StateFlow<List<AppEntry>> = container.catalog.apps
    val catalogLoaded: StateFlow<Boolean> = container.catalog.loaded

    /**
     * The catalog's current list. [AppCatalog.apps] and [AppCatalog.loaded] are separate flows, so
     * combine can see "loaded" before the list it belongs to; reading the value directly when
     * either changes always pairs "loaded" with the list that was published before it.
     */
    private fun latestApps(): List<AppEntry> = container.catalog.apps.value

    /**
     * Home content, or null until prefs are read, the first scan is done and first-run seeding has
     * happened. One flow, so "ready" and the favorites list always arrive together. Seeding is the
     * container's job ([io.github.jameskbb.hearth.seedFavoritesOnce]); it waits for a non-empty
     * catalog, so an empty first scan shows "No apps found" rather than nothing.
     */
    /**
     * The TV's HDMI inputs as entries, named with the user's names where given. Empty on devices
     * without inputs, which makes everything input-related disappear.
     */
    val inputs: StateFlow<List<AppEntry>> = combine(container.tvInputs.inputs, prefs) { list, p ->
        UiLogic.inputEntries(list, p?.inputLabels.orEmpty())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val home: StateFlow<HomeContent?> = combine(prefs, apps, catalogLoaded, inputs) { p, _, loaded, i ->
        val a = latestApps()
        if (p == null || !loaded || (!p.seeded && a.isNotEmpty())) null
        else HomeContent(UiLogic.visibleFavorites(p, a, i), noApps = a.isEmpty() && i.isEmpty())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val favorites: StateFlow<List<AppEntry>> = home.map { it?.favorites ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allApps: StateFlow<AllAppsContent> = combine(prefs.filterNotNull(), apps, catalogLoaded, inputs) { p, _, loaded, i ->
        val a = latestApps()
        val s = UiLogic.allAppsSections(a, p.hidden, i)
        AllAppsContent(s.inputs, s.tvApps, s.otherApps, loaded)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AllAppsContent(emptyList(), emptyList(), emptyList(), false))

    /** Hidden apps and inputs with a label when still present, in label order. */
    val hiddenApps: StateFlow<List<Pair<AppKey, String>>> = combine(prefs.filterNotNull(), apps, inputs) { p, a, i ->
        val byKey = (a + i).associateBy { it.key }
        p.hidden.map { it to UiLogic.hiddenLabel(it, byKey[it], p.inputLabels) }.sortedBy { it.second.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val reducedMotion: StateFlow<Boolean> = prefs.map { it?.reducedMotion ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // region theme state

    /** Usable theme packs in gallery order, and the packs that failed, with their reasons. */
    val themes: StateFlow<List<ThemeInfo>> = container.themes.themes
    val themeFailures: StateFlow<List<ThemeFailure>> = container.themes.failures

    /** The pack stored for Single Theme, or null until prefs are read. */
    val themeChoice: StateFlow<ThemeId?> = prefs.map { it?.theme }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val themeMode: StateFlow<ThemeMode?> = prefs.map { it?.let(::effectiveThemeMode) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _foreground = MutableStateFlow(false)

    /** True between the activity's onStart and onStop: the backdrop animates only then. */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    /** Changes at a relevant local-time boundary, or after a clock or timezone change. */
    private val scheduleTick = MutableStateFlow(0L)
    private val clockGeneration = MutableStateFlow(0L)
    private val pendingFollowSelections = mutableMapOf<String, ThemeRotationState>()
    private var pendingSurpriseSelection: ThemeRotationState? = null

    /** A choice just made in the panel, until prefs report it, so the backdrop never flips back. */
    private val pendingChoice = MutableStateFlow<ThemeId?>(null)

    private val _appearance = MutableStateFlow<Appearance?>(null)
    val appearance: StateFlow<Appearance?> = _appearance.asStateFlow()

    private val _aboutOpen = MutableStateFlow(false)
    val aboutOpen: StateFlow<Boolean> = _aboutOpen.asStateFlow()

    /** False after the catalog released memory while Hearth was in the background, until it is back. */
    private val themeHeld = MutableStateFlow(true)

    private val themeResolution: StateFlow<ThemeResolution?> = combine(
        prefs, themes, container.themes.loaded, scheduleTick,
    ) { p, list, loaded, _ ->
        if (p == null || !loaded) return@combine null
        val available = list.map { it.id }
        val autoEligible = list.filter { it.autoRotationEligible }.map { it.id }
        when (effectiveThemeMode(p)) {
            ThemeMode.Single -> ThemeSchedule.resolveSingle(pendingChoice.value ?: p.theme, available)
            ThemeMode.FollowTheDay -> {
                val occurrence = ThemeSchedule.currentOccurrence(p.themeSchedule, now(), zone())
                ThemeSchedule.resolveFollowDay(
                    p.themeSchedule,
                    p.followDaySelections[occurrence.period.id] ?: pendingFollowSelections[occurrence.period.id],
                    available,
                    now(),
                    zone(),
                    autoEligible = autoEligible,
                    chooseIndex = { size -> Math.floorMod(occurrence.key.hashCode(), size) },
                )
            }
            ThemeMode.SurpriseMe -> ThemeSchedule.resolveSurprise(
                p.surpriseThemeIds,
                p.surpriseSelection ?: pendingSurpriseSelection,
                available,
                now(),
                zone(),
                autoEligible = autoEligible,
                chooseIndex = { size ->
                    val localDay = java.time.Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate()
                    Math.floorMod("${zone().id}|$localDay".hashCode(), size)
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Compatibility name used by previews: the theme Follow the Day would show now. */
    val autoThemeId: StateFlow<ThemeId> = combine(prefs, themes, scheduleTick) { p, list, _ ->
        if (p == null) ThemeId.Default else {
            val occurrence = ThemeSchedule.currentOccurrence(p.themeSchedule, now(), zone())
            ThemeSchedule.resolveFollowDay(
                p.themeSchedule,
                p.followDaySelections[occurrence.period.id],
                list.map { it.id },
                now(),
                zone(),
                autoEligible = list.filter { it.autoRotationEligible }.map { it.id },
                chooseIndex = { size -> Math.floorMod(occurrence.key.hashCode(), size) },
            ).themeId
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeId.Default)

    /**
     * The pack the saved choice shows: Auto resolved for the time of day, a pack that is no
     * longer listed replaced by Dusk. Null until prefs are read and the first scan is done.
     */
    val activeThemeId: StateFlow<ThemeId?> = combine(
        themeResolution, pendingChoice, themes,
    ) { resolution, pending, list ->
        pending?.takeIf { candidate -> list.any { it.id == candidate } } ?: resolution?.themeId
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The listed pack [activeThemeId] names: the Theme row's value. Null when not even Dusk is listed. */
    val activeThemeInfo: StateFlow<ThemeInfo?> = combine(activeThemeId, themes) { id, list -> list.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** What the backdrop should show: the focused card while the Appearance panel is open. */
    private val displayThemeId: StateFlow<ThemeId?> = combine(
        activeThemeId, _appearance, themes, container.themes.loaded,
    ) { active, panel, list, loaded ->
        val focused = panel?.focused?.takeIf { panel.previewing }
        if (focused != null && loaded && list.any { it.id == focused }) focused else active
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The decoded theme on screen. Switching keeps the old one until the new one is decoded (the
     * backdrop then crossfades); a pack that fails to decode falls back to Dusk, and if Dusk fails
     * too, to null: the window color with Dusk's palette.
     */
    val activeTheme: StateFlow<LoadedTheme?> = combine(displayThemeId, themeHeld, themes) { id, held, list ->
        Triple(id, held, list.firstOrNull { it.id == id }?.contentRevision)
    }
        .transformLatest { (id, held, _) ->
            if (!held) emit(null) else if (id != null) emit(loadTheme(id))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The palette for the UI: the theme on screen, else the one about to be, else Dusk's. */
    val activePalette: StateFlow<HearthPalette> = combine(activeTheme, displayThemeId, themes) { theme, id, list ->
        (theme?.info?.palette ?: list.firstOrNull { it.id == id }?.palette)?.toHearthPalette() ?: DefaultPalette
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DefaultPalette)

    private suspend fun loadTheme(id: ThemeId): LoadedTheme? =
        container.themes.load(id) ?: if (id != ThemeId.Default) container.themes.load(ThemeId.Default) else null

    // endregion

    /** Weather as the service reports it (Settings shows its status). */
    val weather: StateFlow<WeatherState> = container.weather.state

    /**
     * What Home shows: the current weather, or null (weather off, no city, never fetched, or older
     * than WeatherPolicy.MAX_AGE_MILLIS). A result leaves Home when it expires even if nothing else
     * happens: one delay until that moment, no polling.
     */
    val homeWeather: StateFlow<WeatherGlance?> = weather.transformLatest { s ->
        var glance = WeatherPolicy.glance(s, now())
        emit(glance)
        while (glance != null) {
            delay((glance.expiresAt - now()).coerceAtLeast(0) + 1)
            glance = WeatherPolicy.glance(s, now())
            emit(glance)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _citySearch = MutableStateFlow<CitySearch?>(null)
    val citySearch: StateFlow<CitySearch?> = _citySearch.asStateFlow()
    private var searchJob: Job? = null

    /** Set by an explicit search (the keyboard's Search key): focus the first match when it lands. */
    private var focusFirstResult = false

    private val _inputRename = MutableStateFlow<InputRename?>(null)
    val inputRename: StateFlow<InputRename?> = _inputRename.asStateFlow()

    private val _screen = MutableStateFlow(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _options = MutableStateFlow<OptionsState?>(null)
    val options: StateFlow<OptionsState?> = _options.asStateFlow()

    private val _arrange = MutableStateFlow<ArrangeState?>(null)
    val arrange: StateFlow<ArrangeState?> = _arrange.asStateFlow()

    private val _message = MutableStateFlow<InlineMessage?>(null)
    val message: StateFlow<InlineMessage?> = _message.asStateFlow()

    private val _focusRequest = MutableStateFlow<FocusRequest?>(null)
    val focusRequest: StateFlow<FocusRequest?> = _focusRequest.asStateFlow()
    private var focusSeq = 0L

    /** What currently has focus on Home (tile or action); drives the name area. */
    private val _homeFocus = MutableStateFlow<FocusTarget?>(null)
    val homeFocus: StateFlow<FocusTarget?> = _homeFocus.asStateFlow()

    /** The last favorite that had focus on Home, kept across app launches and process death. */
    private val _lastHomeTile = MutableStateFlow(saved.get<String>(KEY_LAST_TILE)?.let { AppKey(it) })
    val lastHomeTile: StateFlow<AppKey?> = _lastHomeTile.asStateFlow()

    private val lastFocus = mutableMapOf<Screen, FocusTarget>()

    private val _deviceSettingsAvailable = MutableStateFlow(false)
    val deviceSettingsAvailable: StateFlow<Boolean> = _deviceSettingsAvailable.asStateFlow()

    init {
        _homeFocus.value = _lastHomeTile.value?.let { FocusTarget.Tile(it) }
        lastFocus[Screen.Home] = _homeFocus.value ?: FocusTarget.Default
        requestFocus(Screen.Home, lastFocus.getValue(Screen.Home))
        followRemovals()
        refreshDeviceSettings()
    }

    init {
        followThemes()
    }

    // region lifecycle and intents

    fun onResume() {
        viewModelScope.launch { container.catalog.refresh() }
        container.tvInputs.refresh()
        refreshDeviceSettings()
        invalidateThemeSchedule()
        if (_options.value == null) {
            val s = _screen.value
            requestFocus(s, lastFocus[s] ?: FocusTarget.Default)
        }
    }

    /** The activity started (true) or stopped (false): weather refreshes and themes move only in between. */
    fun onForeground(foreground: Boolean) {
        container.weather.setForeground(foreground)
        _foreground.value = foreground
        if (foreground) themeHeld.value = true
    }

    /** The platform clock, date or timezone changed. Re-resolve now and replace the boundary delay. */
    fun onClockChanged() = invalidateThemeSchedule()

    /** The HOME intent arrived (Home button or redirect app): close everything and show Home. */
    fun onHomeIntent() {
        _options.value = null
        dropCitySearch()
        dropAppearance()
        _aboutOpen.value = false
        _inputRename.value = null
        cancelArrangeFully()
        goHome()
    }

    /** Back, from the activity's back dispatcher. Never finishes the activity. */
    fun onBack() {
        when {
            _options.value != null -> closeOptions()
            _arrange.value is ArrangeState.Moving -> cancelMove()
            _arrange.value == ArrangeState.Picking -> exitArrange()
            _appearance.value != null -> closeAppearancePage()
            _citySearch.value != null -> closeCitySearch()
            _inputRename.value != null -> closeRename()
            _aboutOpen.value -> closeAbout()
            _screen.value != Screen.Home -> goHome()
            else -> {
                val first = favorites.value.firstOrNull()
                val target = first?.let { FocusTarget.Tile(it.key) } ?: FocusTarget.Action(HomeAction.AllApps)
                if (_homeFocus.value != target) requestFocus(Screen.Home, target)
                // Otherwise: already at the root with the first item focused. Do nothing.
            }
        }
    }

    // endregion

    // region focus

    fun requestFocus(screen: Screen, target: FocusTarget) {
        _focusRequest.value = FocusRequest(screen, target, ++focusSeq)
    }

    fun consumeFocus(seq: Long) {
        if (_focusRequest.value?.seq == seq) _focusRequest.value = null
    }

    fun onFocused(screen: Screen, target: FocusTarget) {
        lastFocus[screen] = target
        if (screen == Screen.Home) {
            _homeFocus.value = target
            if (target is FocusTarget.Tile) {
                _lastHomeTile.value = target.key
                saved[KEY_LAST_TILE] = target.key.id
            }
        }
        val m = _message.value
        if (m != null && (m.screen != screen || m.origin != target)) _message.value = null
    }

    /** Keeps focus on a neighbor when the focused tile disappears (hidden, removed, uninstalled). */
    private fun followRemovals() {
        viewModelScope.launch {
            var before = emptyList<AppKey>()
            favorites.collect { list ->
                val after = list.map { it.key }
                val focused = lastFocus[Screen.Home]
                if (focused is FocusTarget.Tile && focused.key in before && focused.key !in after) {
                    val target = UiLogic.neighborAfterRemoval(before, after, focused.key)
                        ?.let { FocusTarget.Tile(it) } ?: FocusTarget.Action(HomeAction.AllApps)
                    lastFocus[Screen.Home] = target
                    carryMessage(Screen.Home, focused, target)
                    if (_screen.value == Screen.Home && _options.value == null) requestFocus(Screen.Home, target)
                }
                before = after
            }
        }
        viewModelScope.launch {
            var before = emptyList<AppKey>()
            allApps.collect { content ->
                val after = content.ordered.map { it.key }
                val focused = lastFocus[Screen.AllApps]
                if (focused is FocusTarget.Tile && focused.key in before && focused.key !in after) {
                    val target = UiLogic.neighborAfterRemoval(before, after, focused.key)
                        ?.let { FocusTarget.Tile(it) } ?: FocusTarget.Default
                    lastFocus[Screen.AllApps] = target
                    carryMessage(Screen.AllApps, focused, target)
                    if (_screen.value == Screen.AllApps && _options.value == null) requestFocus(Screen.AllApps, target)
                }
                before = after
            }
        }
    }

    /** A message about a tile that just disappeared follows focus to the neighbor. */
    private fun carryMessage(screen: Screen, from: FocusTarget, to: FocusTarget) {
        val m = _message.value ?: return
        if (m.screen == screen && m.origin == from) _message.value = m.copy(origin = to)
    }

    // endregion

    // region navigation

    fun open(screen: Screen) {
        if (screen == Screen.Home) return goHome()
        exitArrange()
        dropCitySearch()
        dropAppearance()
        if (screen == Screen.Settings) viewModelScope.launch { container.themes.refresh() }
        _inputRename.value = null
        _message.value = null
        _screen.value = screen
        lastFocus[screen] = FocusTarget.Default
        requestFocus(screen, FocusTarget.Default)
    }

    /**
     * Back to Home from a sub-screen or on a HOME intent. Focus lands on the favorite that last
     * had focus (the first favorite if it is gone, All apps when there are none), not on the
     * action that opened the sub-screen: pressing Home should show what is selected.
     */
    private fun goHome() {
        _message.value = null
        _screen.value = Screen.Home
        val target = _lastHomeTile.value?.let { FocusTarget.Tile(it) } ?: FocusTarget.Default
        lastFocus[Screen.Home] = target
        requestFocus(Screen.Home, target)
    }

    fun onHomeAction(action: HomeAction) {
        when (action) {
            HomeAction.AllApps -> open(Screen.AllApps)
            HomeAction.Settings -> open(Screen.Settings)
            HomeAction.Arrange -> startPicking()
        }
    }

    // endregion

    // region tiles and launching

    fun onTileClick(screen: Screen, entry: AppEntry) {
        val arrange = _arrange.value
        if (screen == Screen.Home && arrange == ArrangeState.Picking) {
            pickUp(entry.key, returnToPicking = true)
            return
        }
        if (arrange != null) return
        launch(screen, entry)
    }

    fun launch(screen: Screen, entry: AppEntry) {
        val origin = FocusTarget.Tile(entry.key)
        val inputId = entry.key.inputId
        val result = if (entry.kind == EntryKind.Input && inputId != null) {
            container.tvInputs.switchTo(inputId)
        } else {
            container.launcher.launch(entry.key)
        }
        when (result) {
            LaunchResult.Launched -> _message.value = null
            LaunchResult.NotInstalled -> {
                _message.value = InlineMessage(screen, origin, InlineMessage.Kind.NotInstalled, entry.label)
                viewModelScope.launch { container.catalog.refresh() }
            }
            is LaunchResult.Failed -> {
                _message.value = InlineMessage(screen, origin, InlineMessage.Kind.LaunchFailed, entry.label)
            }
        }
    }

    // endregion

    // region options dialog

    fun openOptions(screen: Screen, entry: AppEntry) {
        if (_arrange.value != null) return
        val p = prefs.value ?: return
        val isFavorite = entry.key in p.favorites
        val canMove = isFavorite && favorites.value.size >= 2
        _options.value = OptionsState(entry, screen, isFavorite, canMove)
    }

    fun closeOptions() {
        val o = _options.value ?: return
        _options.value = null
        requestFocus(o.origin, FocusTarget.Tile(o.entry.key))
    }

    fun optionOpen() {
        val o = _options.value ?: return
        closeOptions()
        launch(o.origin, o.entry)
    }

    fun optionToggleFavorite() {
        val o = _options.value ?: return
        closeOptions()
        val key = o.entry.key
        edit { if (o.isFavorite) FavoritesOps.removeFavorite(it, key) else FavoritesOps.addFavorite(it, key) }
    }

    fun optionMove() {
        val o = _options.value ?: return
        _options.value = null
        if (o.origin != Screen.Home) {
            _screen.value = Screen.Home
            _message.value = null
        }
        pickUp(o.entry.key, returnToPicking = false)
    }

    fun optionHide() {
        val o = _options.value ?: return
        closeOptions()
        edit { FavoritesOps.hide(it, o.entry.key) }
    }

    // endregion

    // region arrange mode

    private fun startPicking() {
        if (favorites.value.size < 2) return
        _arrange.value = ArrangeState.Picking
        val target = _lastHomeTile.value?.let { FocusTarget.Tile(it) } ?: FocusTarget.Default
        requestFocus(Screen.Home, target)
    }

    private fun pickUp(key: AppKey, returnToPicking: Boolean) {
        val full = prefs.value?.favorites ?: return
        val index = full.indexOf(key)
        if (index < 0) return
        _arrange.value = ArrangeState.Moving(key, index, returnToPicking)
        requestFocus(Screen.Home, FocusTarget.Tile(key))
    }

    /** Left (-1) or Right (+1) while a tile is lifted. Focus stays on the moved tile. */
    fun arrangeMove(direction: Int) {
        val moving = _arrange.value as? ArrangeState.Moving ?: return
        val installed = (apps.value + inputs.value).mapTo(HashSet()) { it.key }
        edit { p ->
            val delta = UiLogic.arrangeDelta(p.favorites, { it in installed && it !in p.hidden }, moving.key, direction)
            if (delta == 0) p else FavoritesOps.move(p, moving.key, delta)
        }
    }

    /** Select while a tile is lifted: keep the new order. */
    fun arrangePlace() {
        val moving = _arrange.value as? ArrangeState.Moving ?: return
        _arrange.value = if (moving.returnToPicking) ArrangeState.Picking else null
        requestFocus(Screen.Home, FocusTarget.Tile(moving.key))
    }

    /** Back while a tile is lifted: put it back where it started. */
    private fun cancelMove() {
        val moving = _arrange.value as? ArrangeState.Moving ?: return
        edit { FavoritesOps.moveTo(it, moving.key, moving.originalIndex) }
        _arrange.value = if (moving.returnToPicking) ArrangeState.Picking else null
        requestFocus(Screen.Home, FocusTarget.Tile(moving.key))
    }

    private fun exitArrange() {
        if (_arrange.value is ArrangeState.Moving) cancelMove()
        _arrange.value = null
    }

    private fun cancelArrangeFully() = exitArrange()

    // endregion

    // region settings

    fun toggleReducedMotion() = edit { it.copy(reducedMotion = !it.reducedMotion) }

    /** Restores a hidden app and moves focus to the next row so it never disappears. */
    fun unhide(key: AppKey) {
        val before = hiddenApps.value.map { it.first }
        val neighbor = UiLogic.neighborAfterRemoval(before, before - key, key)
        val target = neighbor?.let { FocusTarget.Row(hiddenRowId(it)) } ?: FocusTarget.Default
        lastFocus[Screen.Settings] = target
        requestFocus(Screen.Settings, target)
        edit { FavoritesOps.unhide(it, key) }
    }

    fun openDeviceSettings() {
        val ok = container.systemRoutes.open(SystemRoute.Settings)
        if (!ok) {
            _message.value = InlineMessage(
                Screen.Settings, FocusTarget.Row(ROW_DEVICE), InlineMessage.Kind.DeviceSettingsFailed, "",
            )
        }
    }

    private fun refreshDeviceSettings() {
        _deviceSettingsAvailable.value = container.systemRoutes.isAvailable(SystemRoute.Settings)
    }

    fun displayFacts() = container.displayFacts.read()

    // endregion

    // region weather settings

    /**
     * Show on Home: on or off. Turning it on without a city goes straight to the city search, since
     * there is nothing to show until one is chosen.
     */
    fun toggleWeather() {
        val p = prefs.value ?: return
        val on = !p.weatherEnabled
        edit { it.copy(weatherEnabled = on) }
        if (on && p.weatherPlace == null) openCitySearch()
    }

    fun setTemperatureUnit(unit: TemperatureUnit) = edit { it.copy(temperatureUnit = unit) }

    fun openCitySearch() {
        _message.value = null
        _citySearch.value = CitySearch()
        val target = FocusTarget.Row(ROW_CITY_FIELD)
        lastFocus[Screen.Settings] = target
        requestFocus(Screen.Settings, target)
    }

    /** Back from the search, or a city chosen: return to the City row. */
    fun closeCitySearch() {
        dropCitySearch()
        val target = FocusTarget.Row(ROW_CITY)
        lastFocus[Screen.Settings] = target
        requestFocus(Screen.Settings, target)
    }

    private fun dropCitySearch() {
        searchJob?.cancel()
        searchJob = null
        focusFirstResult = false
        _citySearch.value = null
    }

    /** Every edit of the search field. Searches after a short pause in typing. */
    fun onCityQueryChange(query: String) {
        val current = _citySearch.value ?: return
        if (query == current.query) return
        _citySearch.value = current.copy(query = query)
        search(query, debounce = true)
    }

    /** The keyboard's Search (or Done) key: search now, then put focus on the first match. */
    fun submitCitySearch() {
        val current = _citySearch.value ?: return
        focusFirstResult = true
        search(current.query, debounce = false)
    }

    fun choosePlace(place: Place) {
        edit { it.copy(weatherPlace = place, weatherEnabled = true) }
        closeCitySearch()
    }

    private fun search(query: String, debounce: Boolean) {
        searchJob?.cancel()
        val term = query.trim()
        if (term.length < MIN_SEARCH_LENGTH) {
            focusFirstResult = false
            _citySearch.update { it?.copy(status = CitySearch.Status.Idle, results = emptyList(), failure = null) }
            return
        }
        searchJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MILLIS)
            _citySearch.update { it?.copy(status = CitySearch.Status.Searching) }
            val found = try {
                container.weather.searchPlaces(term, Locale.getDefault().language)
            } catch (e: WeatherException) {
                focusFirstResult = false
                _citySearch.update {
                    it?.copy(status = CitySearch.Status.Failed, results = emptyList(), searchedFor = term, failure = e.failure)
                }
                return@launch
            }
            _citySearch.update {
                it?.copy(
                    status = if (found.isEmpty()) CitySearch.Status.NoMatches else CitySearch.Status.Results,
                    results = found,
                    searchedFor = term,
                    failure = null,
                )
            }
            if (focusFirstResult && found.isNotEmpty()) requestFocus(Screen.Settings, FocusTarget.Row(cityResultRowId(0)))
            focusFirstResult = false
        }
    }

    // endregion

    // region appearance

    fun openAbout() {
        if (_screen.value != Screen.Settings) return
        _aboutOpen.value = true
        requestFocus(Screen.Settings, FocusTarget.Row(ROW_ABOUT_BACK))
    }

    fun closeAbout() {
        if (!_aboutOpen.value) return
        _aboutOpen.value = false
        val target = FocusTarget.Row(ROW_ABOUT)
        lastFocus[Screen.Settings] = target
        requestFocus(Screen.Settings, target)
    }

    /** Opens Appearance on the current mode. The active theme remains the live preview. */
    fun openAppearance() {
        val saved = themeChoice.value ?: return
        _message.value = null
        viewModelScope.launch { container.themes.refresh() }
        val inUse = activeThemeId.value ?: saved
        _appearance.value = Appearance(focused = inUse, saved = saved)
        val target = FocusTarget.Row(modeRowId(themeMode.value ?: ThemeMode.Single))
        lastFocus[Screen.Settings] = target
        requestFocus(Screen.Settings, target)
    }

    /** A card gained focus: the backdrop shows that theme (decoded off the main thread). */
    fun focusTheme(id: ThemeId) {
        val panel = _appearance.value ?: return
        onFocused(Screen.Settings, FocusTarget.Row(themeCardRowId(id)))
        if (panel.focused != id || !panel.previewing) _appearance.value = panel.copy(focused = id, previewing = true)
    }

    /** Select on a card: keep that theme, close the panel and return to the Theme row. */
    fun chooseTheme(id: ThemeId) {
        if (_appearance.value == null) return
        val mode = prefs.value?.let(::effectiveThemeMode) ?: return
        if (mode == ThemeMode.Single) {
            if (themeChoice.value != id) {
                pendingChoice.value = id
                edit { it.copy(theme = id, themeMode = ThemeMode.Single) }
            }
            dropAppearance()
            focusThemeRow()
        } else if (mode == ThemeMode.SurpriseMe) {
            toggleSurpriseTheme(id)
        }
    }

    fun chooseThemeMode(mode: ThemeMode) {
        val panel = _appearance.value ?: return
        val focusAfterSave = when (mode) {
            ThemeMode.Single -> {
                val id = themeChoice.value?.takeIf { choice -> themes.value.any { it.id == choice } }
                    ?: activeThemeId.value
                    ?: themes.value.firstOrNull()?.id
                    ?: ThemeId.Default
                _appearance.value = panel.copy(focused = id, page = AppearancePage.Main, previewing = false)
                themeCardRowId(id)
            }
            ThemeMode.FollowTheDay -> {
                _appearance.value = panel.copy(page = AppearancePage.Main, previewing = false)
                ROW_SCHEDULE_EDIT
            }
            ThemeMode.SurpriseMe -> {
                val id = activeThemeId.value?.takeIf { active -> themes.value.any { it.id == active } }
                    ?: themes.value.firstOrNull()?.id
                    ?: ThemeId.Default
                _appearance.value = panel.copy(
                    focused = id,
                    page = AppearancePage.Main,
                    previewing = false,
                )
                themeCardRowId(id)
            }
        }
        // DataStore writes are asynchronous. Request card focus only after the mode flow has
        // exposed that card to Compose, otherwise a fast request can be consumed by the old page.
        viewModelScope.launch {
            container.prefs.update {
                it.copy(themeMode = mode, theme = if (it.theme.isAuto) ThemeId.Default else it.theme)
            }
            invalidateThemeSchedule()
            if (_appearance.value?.page == AppearancePage.Main) {
                requestAppearanceFocus(focusAfterSave)
            }
        }
    }

    fun openThemeSchedule() {
        val panel = _appearance.value ?: return
        _appearance.value = panel.copy(page = AppearancePage.Schedule, periodId = null, previewing = false)
        val first = prefs.value?.themeSchedule?.orderedPeriods?.firstOrNull()?.id
        requestAppearanceFocus(first?.let(::schedulePeriodRowId) ?: ROW_SCHEDULE_RESTORE)
    }

    fun openThemePeriod(periodId: String) {
        val period = prefs.value?.themeSchedule?.periods?.firstOrNull { it.id == periodId } ?: return
        val panel = _appearance.value ?: return
        _appearance.value = panel.copy(page = AppearancePage.Period, periodId = period.id, periodName = period.name)
        requestAppearanceFocus(ROW_PERIOD_EARLIER)
    }

    fun openPeriodRename() {
        val panel = _appearance.value ?: return
        if (panel.page != AppearancePage.Period || panel.periodId == null) return
        _appearance.value = panel.copy(page = AppearancePage.PeriodName, previewing = false)
        requestAppearanceFocus(ROW_PERIOD_NAME)
    }

    fun closeAppearancePage() {
        val panel = _appearance.value ?: return
        when (panel.page) {
            AppearancePage.Main -> closeAppearance()
            AppearancePage.HomeLabel -> {
                _appearance.value = panel.copy(page = AppearancePage.Main, previewing = false)
                requestAppearanceFocus(ROW_HOME_LABEL)
            }
            AppearancePage.HomeLabelText -> {
                _appearance.value = panel.copy(page = AppearancePage.HomeLabel)
                requestAppearanceFocus(ROW_HOME_LABEL_EDIT)
            }
            AppearancePage.Schedule -> {
                _appearance.value = panel.copy(page = AppearancePage.Main, previewing = false)
                requestAppearanceFocus(ROW_SCHEDULE_EDIT)
            }
            AppearancePage.Period -> {
                val id = panel.periodId
                _appearance.value = panel.copy(page = AppearancePage.Schedule, periodId = null, previewing = false)
                requestAppearanceFocus(id?.let(::schedulePeriodRowId) ?: ROW_SCHEDULE_RESTORE)
            }
            AppearancePage.PeriodName -> {
                _appearance.value = panel.copy(page = AppearancePage.Period, previewing = false)
                requestAppearanceFocus(ROW_PERIOD_RENAME)
            }
        }
    }

    fun openHomeLabel() {
        _appearance.update { it?.copy(page = AppearancePage.HomeLabel, previewing = false) }
        requestAppearanceFocus(ROW_HOME_LABEL_ENABLED)
    }

    fun toggleHomeLabel() = edit { it.copy(homeLabelEnabled = !it.homeLabelEnabled) }

    fun editHomeLabel() {
        _appearance.update { it?.copy(page = AppearancePage.HomeLabelText, homeLabelDraft = prefs.value?.homeLabel.orEmpty()) }
        requestAppearanceFocus(ROW_HOME_LABEL_FIELD)
    }

    fun onHomeLabelChange(text: String) {
        // Preserve spaces while typing; trim only when saving.
        _appearance.update { it?.copy(homeLabelDraft = HomeLabel.sanitize(text)) }
    }

    fun saveHomeLabel() {
        val panel = _appearance.value ?: return
        if (panel.page != AppearancePage.HomeLabelText) return
        edit { it.copy(homeLabel = HomeLabel.clean(panel.homeLabelDraft)) }
        closeAppearancePage()
    }

    fun clearHomeLabel() {
        edit { it.copy(homeLabel = "", homeLabelEnabled = false) }
        requestAppearanceFocus(ROW_HOME_LABEL_EDIT)
    }

    fun onPeriodNameChange(name: String) {
        _appearance.update { panel -> panel?.copy(periodName = name.replace("\n", "").take(ThemePeriod.MAX_NAME_LENGTH)) }
    }

    fun savePeriodName() {
        val panel = _appearance.value ?: return
        val id = panel.periodId ?: return
        val name = ThemePeriod.cleanName(panel.periodName)
        if (name.isNotEmpty()) updatePeriod(id, invalidateSelection = false) { it.copy(name = name) }
        _appearance.update {
            it?.copy(
                page = AppearancePage.Period,
                periodName = name.ifEmpty { currentPeriod(id)?.name.orEmpty() },
                previewing = false,
            )
        }
        requestAppearanceFocus(ROW_PERIOD_RENAME)
    }

    fun movePeriodStart(minutes: Int) {
        val id = _appearance.value?.periodId ?: return
        val schedule = prefs.value?.themeSchedule ?: return
        val ordered = schedule.orderedPeriods
        val index = ordered.indexOfFirst { it.id == id }
        if (index < 0) return
        val period = ordered[index]
        val candidate = Math.floorMod(period.startMinute + minutes, ThemePeriod.MINUTES_PER_DAY)
        if (ordered.size > 1) {
            val previous = ordered[Math.floorMod(index - 1, ordered.size)]
            val next = ordered[(index + 1) % ordered.size]
            fun distance(from: Int, to: Int) = Math.floorMod(to - from, ThemePeriod.MINUTES_PER_DAY)
            val span = distance(previous.startMinute, next.startMinute)
            val before = distance(previous.startMinute, candidate)
            val after = distance(candidate, next.startMinute)
            if (before < 30 || after < 30 || before + after != span) return
        }
        updatePeriod(id) { it.copy(startMinute = candidate) }
    }

    fun togglePeriodShuffle() {
        val id = _appearance.value?.periodId ?: return
        updatePeriod(id) { period -> period.copy(shuffle = !period.shuffle) }
    }

    fun togglePeriodTheme(themeId: ThemeId) {
        val id = _appearance.value?.periodId ?: return
        updatePeriod(id) { period ->
            if (!period.shuffle) {
                period.copy(themeIds = listOf(themeId) + period.themeIds.filterNot { it == themeId })
            } else {
                period.copy(themeIds = if (themeId in period.themeIds) period.themeIds - themeId else period.themeIds + themeId)
            }
        }
    }

    fun restoreRecommendedSchedule() {
        pendingFollowSelections.clear()
        edit { it.copy(themeSchedule = ThemeScheduleConfig.Recommended, followDaySelections = emptyMap()) }
        invalidateThemeSchedule()
        requestAppearanceFocus(schedulePeriodRowId(ThemeScheduleConfig.Recommended.orderedPeriods.first().id))
    }

    fun reloadThemes() {
        viewModelScope.launch { container.themes.refresh() }
    }

    private fun toggleSurpriseTheme(id: ThemeId) {
        val packs = themes.value
        pendingSurpriseSelection = null
        edit { p ->
            val current = ThemeSchedule.surprisePool(p.surpriseThemeIds, packs).toMutableSet()
            if (id in current && current.size == 1) p else {
                if (!current.remove(id)) current += id
                // A full explicit pool remains explicit when packs are installed later.
                p.copy(surpriseThemeIds = current, surpriseSelection = null)
            }
        }
        invalidateThemeSchedule()
    }

    private fun updatePeriod(
        id: String,
        invalidateSelection: Boolean = true,
        change: (ThemePeriod) -> ThemePeriod,
    ) {
        if (invalidateSelection) pendingFollowSelections.remove(id)
        edit { p ->
            val periods = p.themeSchedule.periods.map { if (it.id == id) change(it) else it }
            p.copy(
                themeSchedule = ThemeScheduleConfig(periods),
                followDaySelections = if (invalidateSelection) p.followDaySelections - id else p.followDaySelections,
            )
        }
        invalidateThemeSchedule()
    }

    private fun currentPeriod(id: String): ThemePeriod? =
        prefs.value?.themeSchedule?.periods?.firstOrNull { it.id == id }

    private fun requestAppearanceFocus(id: String) {
        val target = FocusTarget.Row(id)
        lastFocus[Screen.Settings] = target
        requestFocus(Screen.Settings, target)
    }

    /** Back from the panel: the saved theme comes back. */
    fun closeAppearance() {
        if (_appearance.value == null) return
        dropAppearance()
        focusThemeRow()
    }

    private fun dropAppearance() {
        if (_appearance.value == null) return
        _appearance.value = null
        container.themes.releasePreviews()
    }

    private fun focusThemeRow() {
        val target = FocusTarget.Row(ROW_THEME)
        lastFocus[Screen.Settings] = target
        requestFocus(Screen.Settings, target)
    }

    /** A gallery card's image, decoded off the main thread. */
    suspend fun themePreview(id: ThemeId): ImageBitmap? =
        container.themes.preview(if (id.isAuto) autoThemeId.value else id)

    /**
     * Theme bookkeeping: scheduling follows the clock while Hearth is in front (one delay to the
     * next boundary, no polling); a choice made in the panel stops overriding prefs once they
     * report it; memory released in the background is let go here too; and when the focused card
     * disappears (its pack failed to decode), focus moves to its neighbor.
     */
    private fun followThemes() {
        viewModelScope.launch {
            combine(_foreground, clockGeneration) { fg, _ -> fg }.collectLatest { fg ->
                while (fg) {
                    scheduleTick.value += 1
                    val p = prefs.value
                    val next = when {
                        p == null -> now() + 60_000
                        effectiveThemeMode(p) == ThemeMode.FollowTheDay ->
                            ThemeSchedule.nextBoundaryMillis(p.themeSchedule, now(), zone())
                        effectiveThemeMode(p) == ThemeMode.SurpriseMe -> ThemeSchedule.resolveSurprise(
                            p.surpriseThemeIds, p.surpriseSelection, themes.value.map { it.id }, now(), zone(),
                        ).nextChangeMillis
                        else -> Long.MAX_VALUE
                    }
                    delay((next - now()).coerceAtLeast(0) + 1)
                }
            }
        }
        viewModelScope.launch {
            themeResolution.filterNotNull().collect { resolution ->
                val state = resolution.rotationState ?: return@collect
                val p = prefs.value ?: return@collect
                when (effectiveThemeMode(p)) {
                    ThemeMode.FollowTheDay -> {
                        val id = resolution.period?.id ?: return@collect
                        pendingFollowSelections[id] = state
                        if (p.followDaySelections[id] != state) {
                            edit { it.copy(followDaySelections = it.followDaySelections + (id to state)) }
                        }
                    }
                    ThemeMode.SurpriseMe -> {
                        pendingSurpriseSelection = state
                        if (p.surpriseSelection != state) edit { it.copy(surpriseSelection = state) }
                    }
                    ThemeMode.Single -> Unit
                }
            }
        }
        viewModelScope.launch {
            themeChoice.collect { stored -> if (stored != null && stored == pendingChoice.value) pendingChoice.value = null }
        }
        viewModelScope.launch {
            var seen = container.themes.releases.value
            container.themes.releases.collect { n ->
                if (n != seen && !_foreground.value) themeHeld.value = false
                seen = n
            }
        }
        viewModelScope.launch {
            var before = emptyList<ThemeId>()
            themes.collect { list ->
                val after = list.map { it.id }
                val panel = _appearance.value
                if (panel != null && panel.focused in before && panel.focused !in after) {
                    val next = neighborAfterRemoval(before, after, panel.focused) ?: ThemeId.Default
                    _appearance.value = panel.copy(focused = next)
                    val target = FocusTarget.Row(themeCardRowId(next))
                    lastFocus[Screen.Settings] = target
                    if (_screen.value == Screen.Settings) requestFocus(Screen.Settings, target)
                }
                before = after
            }
        }
    }

    private fun invalidateThemeSchedule() {
        clockGeneration.value += 1
        scheduleTick.value += 1
    }

    private fun effectiveThemeMode(p: HearthPrefs): ThemeMode =
        if (p.theme.isAuto && p.themeMode == ThemeMode.Single) ThemeMode.FollowTheDay else p.themeMode

    // endregion

    // region input names

    /** Opens the rename panel for input [inputId], with its current name ready to edit. */
    fun openRenameInput(inputId: String) {
        val input = container.tvInputs.inputs.value.firstOrNull { it.id == inputId } ?: return
        val custom = prefs.value?.inputLabels?.get(inputId)
        _message.value = null
        _inputRename.value = InputRename(inputId, custom ?: input.label, input.label, custom != null)
        val target = FocusTarget.Row(ROW_RENAME_FIELD)
        lastFocus[Screen.Settings] = target
        requestFocus(Screen.Settings, target)
    }

    fun onRenameChange(name: String) {
        _inputRename.update { it?.copy(name = name.replace("\n", "").take(PrefsCodec.MAX_INPUT_LABEL)) }
    }

    /**
     * The keyboard's Done, or Save: stores the trimmed name. An empty name, or the TV's own name,
     * removes the user's name so the input follows the TV again.
     */
    fun submitRename() {
        val r = _inputRename.value ?: return
        val name = PrefsCodec.cleanInputLabel(r.name)
        setInputLabel(r.inputId, name?.takeIf { it != r.tvLabel })
        closeRename()
    }

    /** "Use the TV's name": drops the user's name for the input being renamed. */
    fun resetRename() {
        val r = _inputRename.value ?: return
        setInputLabel(r.inputId, null)
        closeRename()
    }

    /** Back from the rename panel, or after saving: return to that input's row. */
    fun closeRename() {
        val r = _inputRename.value ?: return
        _inputRename.value = null
        val target = FocusTarget.Row(inputRowId(r.inputId))
        lastFocus[Screen.Settings] = target
        requestFocus(Screen.Settings, target)
    }

    private fun setInputLabel(inputId: String, label: String?) = edit { p ->
        val current = p.inputLabels[inputId]
        when {
            label == current -> p
            label == null -> p.copy(inputLabels = p.inputLabels - inputId)
            else -> p.copy(inputLabels = p.inputLabels + (inputId to label))
        }
    }

    // endregion

    private fun edit(transform: (HearthPrefs) -> HearthPrefs) {
        viewModelScope.launch { container.prefs.update(transform) }
    }

    companion object {
        private const val KEY_LAST_TILE = "last_home_tile"
        const val ROW_HOME_LABEL = "home_label"
        const val ROW_HOME_LABEL_ENABLED = "home_label_enabled"
        const val ROW_HOME_LABEL_EDIT = "home_label_edit"
        const val ROW_HOME_LABEL_FIELD = "home_label_field"
        const val ROW_HOME_LABEL_SAVE = "home_label_save"
        const val ROW_HOME_LABEL_CLEAR = "home_label_clear"
        const val ROW_THEME = "theme"
        const val ROW_MOTION = "motion"
        const val ROW_ABOUT = "about"
        const val ROW_ABOUT_BACK = "about_back"
        const val ROW_DEVICE = "device"
        const val ROW_WEATHER = "weather"
        const val ROW_CITY = "city"
        const val ROW_UNITS = "units"
        const val ROW_CITY_FIELD = "city_field"
        const val ROW_MODE_SINGLE = "mode_single"
        const val ROW_MODE_FOLLOW = "mode_follow"
        const val ROW_MODE_SURPRISE = "mode_surprise"
        const val ROW_SCHEDULE_EDIT = "schedule_edit"
        const val ROW_APPEARANCE_RELOAD = "appearance_reload"
        const val ROW_SCHEDULE_RESTORE = "schedule_restore"
        const val ROW_PERIOD_NAME = "period_name"
        const val ROW_PERIOD_NAME_SAVE = "period_name_save"
        const val ROW_PERIOD_RENAME = "period_rename"
        const val ROW_PERIOD_EARLIER = "period_earlier"
        const val ROW_PERIOD_LATER = "period_later"
        const val ROW_PERIOD_SHUFFLE = "period_shuffle"
        fun hiddenRowId(key: AppKey) = "hidden:${key.id}"
        fun cityResultRowId(index: Int) = "city_result:$index"
        fun themeCardRowId(id: ThemeId) = "theme_card:${id.name}"
        fun modeRowId(mode: ThemeMode) = when (mode) {
            ThemeMode.Single -> ROW_MODE_SINGLE
            ThemeMode.FollowTheDay -> ROW_MODE_FOLLOW
            ThemeMode.SurpriseMe -> ROW_MODE_SURPRISE
        }
        fun schedulePeriodRowId(id: String) = "schedule_period:$id"

        /** The next surviving item after [removed], else the previous one (as UiLogic.neighborAfterRemoval). */
        private fun <T> neighborAfterRemoval(before: List<T>, after: List<T>, removed: T): T? {
            val index = before.indexOf(removed)
            for (i in index + 1 until before.size) if (before[i] in after) return before[i]
            for (i in index - 1 downTo 0) if (before[i] in after) return before[i]
            return null
        }
        const val ROW_RENAME_FIELD = "rename_field"
        const val ROW_RENAME_SAVE = "rename_save"
        const val ROW_RENAME_RESET = "rename_reset"
        fun inputRowId(inputId: String) = "input:$inputId"

        /** City search starts at two letters, and waits this long after the last keystroke. */
        const val MIN_SEARCH_LENGTH = 2
        const val SEARCH_DEBOUNCE_MILLIS = 450L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as HearthApp
                HearthViewModel(app.container, createSavedStateHandle())
            }
        }
    }
}
