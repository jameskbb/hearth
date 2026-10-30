package io.github.jameskbb.hearth.ui.state

import io.github.jameskbb.hearth.apps.AppEntry
import io.github.jameskbb.hearth.apps.AppKey
import io.github.jameskbb.hearth.apps.EntryKind
import io.github.jameskbb.hearth.prefs.HearthPrefs
import io.github.jameskbb.hearth.system.DisplayFacts
import io.github.jameskbb.hearth.system.TvInput
import java.util.Locale
import kotlin.math.roundToInt

/** Top-level screens. Overlays (options dialog, arrange mode) live beside this, not in it. */
enum class Screen { Home, AllApps, Settings }

/** The actions under the favorites row, in on-screen order. */
enum class HomeAction { AllApps, Arrange, Settings }

/** Something that can hold focus. Each screen keeps its own registry of these. */
sealed interface FocusTarget {
    /** The screen's defined initial focus (first favorite, else All apps; first tile; first row). */
    data object Default : FocusTarget
    data class Tile(val key: AppKey) : FocusTarget
    data class Action(val action: HomeAction) : FocusTarget
    data class Row(val id: String) : FocusTarget
}

/** Arrange mode on Home. */
sealed interface ArrangeState {
    /** Entered from the Arrange action: the user is choosing which favorite to move. */
    data object Picking : ArrangeState

    /**
     * A favorite is lifted and follows Left/Right.
     * [originalIndex] is its index in the full stored favorites list, for Back to restore.
     * [returnToPicking] is true when entered from the Arrange action, so placing a tile lets the
     * user pick another one.
     */
    data class Moving(val key: AppKey, val originalIndex: Int, val returnToPicking: Boolean) : ArrangeState
}

/** All apps, in on-screen order: the TV's inputs, TV apps, then other launchable apps. */
data class AllAppsSections(val inputs: List<AppEntry>, val tvApps: List<AppEntry>, val otherApps: List<AppEntry>)

/** Pure helpers behind the UI. No Android or Compose types so they run as JVM unit tests. */
object UiLogic {

    /**
     * Favorites in stored order that are installed (or, for inputs, currently listed) and not
     * hidden. A favorite whose app or input is gone is skipped, and comes back in place if it returns.
     */
    fun visibleFavorites(prefs: HearthPrefs, apps: List<AppEntry>, inputs: List<AppEntry> = emptyList()): List<AppEntry> {
        val byKey = (apps + inputs).associateBy { it.key }
        return prefs.favorites.mapNotNull { key ->
            if (key in prefs.hidden) null else byKey[key]
        }.distinctBy { it.key }
    }

    /** All apps sections: inputs, TV apps, then other launchable apps. Hidden entries excluded. */
    fun allAppsSections(apps: List<AppEntry>, hidden: Set<AppKey>, inputs: List<AppEntry> = emptyList()): AllAppsSections {
        val shown = apps.filter { it.key !in hidden && it.kind == EntryKind.App }
        return AllAppsSections(
            inputs = inputs.filter { it.key !in hidden },
            tvApps = shown.filter { it.isTvApp },
            otherApps = shown.filterNot { it.isTvApp },
        )
    }

    /**
     * The TV's inputs as catalog entries, in the order given (port order). The user's name for an
     * input ([labels], by input id) wins over the TV's.
     */
    fun inputEntries(inputs: List<TvInput>, labels: Map<String, String>): List<AppEntry> = inputs.map {
        AppEntry(
            key = AppKey.forInput(it.id),
            label = labels[it.id]?.trim()?.takeIf { l -> l.isNotEmpty() } ?: it.label,
            isTvApp = false,
            lastUpdateTime = 0L,
            kind = EntryKind.Input,
        )
    }

    /**
     * The name Settings shows for a hidden entry: its current label, else (gone) the user's name for
     * an input, else the input id or the package name.
     */
    fun hiddenLabel(key: AppKey, current: AppEntry?, inputLabels: Map<String, String>): String {
        current?.let { return it.label }
        val inputId = key.inputId ?: return key.packageName
        return inputLabels[inputId] ?: inputId
    }

    /** Keys used to seed favorites on first run: installed TV apps in catalog order. Never inputs. */
    fun seedCandidates(apps: List<AppEntry>): List<AppKey> =
        apps.filter { it.isTvApp && it.kind == EntryKind.App }.map { it.key }

    /**
     * Where focus goes when [removed] leaves a list that used to be [before] and is now [after]:
     * the next surviving item, else the previous one, else null (the caller falls back to the
     * screen's first action).
     */
    fun neighborAfterRemoval(before: List<AppKey>, after: List<AppKey>, removed: AppKey): AppKey? {
        val survivors = after.toSet()
        val index = before.indexOf(removed)
        if (index < 0) return after.firstOrNull()
        for (i in index + 1 until before.size) if (before[i] in survivors) return before[i]
        for (i in index - 1 downTo 0) if (before[i] in survivors) return before[i]
        return null
    }

    /**
     * The delta to pass to FavoritesOps.move so that [key] swaps places with its next visible
     * neighbor in [direction] (-1 left, +1 right). Favorites that are stored but not shown
     * (uninstalled apps) are stepped over so every press moves the tile on screen.
     * Returns 0 when the tile is already at that end.
     */
    fun arrangeDelta(full: List<AppKey>, isVisible: (AppKey) -> Boolean, key: AppKey, direction: Int): Int {
        val from = full.indexOf(key)
        if (from < 0 || direction == 0) return 0
        val step = if (direction < 0) -1 else 1
        var i = from + step
        while (i in full.indices) {
            if (isVisible(full[i])) return i - from
            i += step
        }
        return 0
    }

    /** Resolves a Home focus request against what is on screen now. */
    fun resolveHomeFocus(requested: FocusTarget, favorites: List<AppKey>, actions: List<HomeAction>): FocusTarget {
        val fallback: FocusTarget = favorites.firstOrNull()?.let { FocusTarget.Tile(it) }
            ?: FocusTarget.Action(actions.firstOrNull() ?: HomeAction.AllApps)
        return when (requested) {
            is FocusTarget.Tile -> if (requested.key in favorites) requested else fallback
            is FocusTarget.Action -> if (requested.action in actions) requested else fallback
            else -> fallback
        }
    }

    /** Resolves a focus request on a list screen whose targets are [available], in order. */
    fun resolveListFocus(requested: FocusTarget, available: List<FocusTarget>): FocusTarget? =
        if (requested in available) requested else available.firstOrNull()

    /**
     * How far a scrolling row should move so an item at [offset] with [size] (both relative to
     * the container's leading edge) sits inside the container with at least [lead] before it and
     * [trail] after it. Negative scrolls back. Used for the favorites row and the grids.
     */
    fun scrollDistanceToReveal(offset: Float, size: Float, containerSize: Float, lead: Float, trail: Float): Float {
        val end = offset + size
        val usable = containerSize - lead - trail
        return when {
            size > usable -> offset - lead
            offset < lead -> offset - lead
            end > containerSize - trail -> end - (containerSize - trail)
            else -> 0f
        }
    }

    /** "60 Hz", "59.94 Hz". */
    fun formatHz(hz: Float): String {
        val hundredths = (hz * 100).roundToInt()
        if (hundredths % 100 == 0) return "${hundredths / 100} Hz"
        val text = String.format(Locale.US, "%d.%02d", hundredths / 100, hundredths % 100).trimEnd('0')
        return "$text Hz"
    }

    /** "1920 x 1080". */
    fun formatResolution(widthPx: Int, heightPx: Int): String = "$widthPx x $heightPx"

    /** Plain lines describing the display, label to value, as the device reports them. */
    fun displayFactLines(
        facts: DisplayFacts,
        yes: String,
        no: String,
        noneReported: String,
    ): List<Pair<DisplayFactLabel, String>> = buildList {
        add(DisplayFactLabel.Resolution to "${formatResolution(facts.widthPx, facts.heightPx)}, ${facts.densityDpi} dpi")
        add(DisplayFactLabel.RefreshRate to formatHz(facts.refreshRateHz))
        add(DisplayFactLabel.WideColor to if (facts.isWideColorGamut) yes else no)
        add(DisplayFactLabel.Hdr to if (facts.hdrTypes.isEmpty()) noneReported else facts.hdrTypes.joinToString(", "))
        if (facts.supportedModes.isNotEmpty()) {
            add(DisplayFactLabel.Modes to facts.supportedModes.joinToString(", "))
        }
    }

    enum class DisplayFactLabel { Resolution, RefreshRate, WideColor, Hdr, Modes }

    /**
     * Removes the AM/PM marker (and the space around it) from a best-fit 12-hour pattern, so the
     * clock reads "8:42" as in the concept. 24-hour patterns pass through unchanged.
     */
    fun stripDayPeriod(pattern: String): String {
        val out = StringBuilder()
        var inQuote = false
        for (c in pattern) {
            if (c == '\'') inQuote = !inQuote
            if (!inQuote && (c == 'a' || c == 'b' || c == 'B')) continue
            out.append(c)
        }
        return out.toString().replace(' ', ' ').replace(' ', ' ').trim()
    }
}
