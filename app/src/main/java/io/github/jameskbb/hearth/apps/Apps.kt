package io.github.jameskbb.hearth.apps

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

/**
 * Stable identity of a catalog entry: "package/activityClass" for an app, "input:<id>" for a TV
 * input (the TV Input Framework's id, which itself contains slashes).
 * Favorites and hidden apps are stored by key, so a reinstalled app keeps its place.
 */
@JvmInline
value class AppKey(val id: String) {
    val packageName: String get() = id.substringBefore('/')
    val activityName: String get() = id.substringAfter('/')

    /** True for a TV input's key; [packageName] and [activityName] mean nothing then. */
    val isInput: Boolean get() = id.startsWith(INPUT_PREFIX)

    /** The framework's input id for an input key, else null. */
    val inputId: String? get() = if (isInput) id.substring(INPUT_PREFIX.length) else null

    companion object {
        const val INPUT_PREFIX = "input:"

        fun of(packageName: String, activityName: String) = AppKey("$packageName/$activityName")

        fun forInput(inputId: String) = AppKey(INPUT_PREFIX + inputId)
    }
}

/** What an entry opens: an app's activity, or one of the TV's own inputs (HDMI). */
enum class EntryKind { App, Input }

/** One launchable app as discovered on the device, or a TV input listed beside the apps. */
data class AppEntry(
    val key: AppKey,
    val label: String,
    /** Declares LEANBACK_LAUNCHER: built for TV. Non-TV apps go to a secondary section. */
    val isTvApp: Boolean,
    /** Changes when the app is updated; part of the artwork cache key. */
    val lastUpdateTime: Long,
    /** [EntryKind.Input] entries come from system.TvInputs, never from the [AppCatalog]. */
    val kind: EntryKind = EntryKind.App,
)

/** Installed launchable apps, kept current as packages are added, removed, or updated. */
interface AppCatalog {
    /** Every launchable app except Hearth itself, sorted by label. Empty until first load. */
    val apps: StateFlow<List<AppEntry>>
    /** True once the first scan has completed (lets the UI tell "loading" from "no apps"). */
    val loaded: StateFlow<Boolean>
    suspend fun refresh()
}

enum class ArtKind {
    /** 16:9 TV banner if the app ships one. */
    Banner,
    /** Square launcher icon. */
    Icon,
}

/** Artwork for a tile, decoded off the UI thread and cached. */
data class AppArtwork(
    val bitmap: ImageBitmap,
    val kind: ArtKind,
    /** A background color sampled from the artwork, for letterboxing and fallback tiles. */
    val backdropArgb: Int,
) {
    /**
     * Width over height of [bitmap], which keeps the artwork's native aspect (it is never
     * stretched to the tile). Fit it inside the tile on [backdropArgb] when it differs from
     * the tile's own aspect.
     */
    val aspectRatio: Float get() = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
}

interface ArtworkLoader {
    /** Banner if available, else icon; null when the app has neither or is gone. */
    suspend fun load(entry: AppEntry): AppArtwork?
}

sealed interface LaunchResult {
    data object Launched : LaunchResult
    /** The app was uninstalled or its activity no longer exists. */
    data object NotInstalled : LaunchResult
    /** The platform refused (disabled app, permission, crash on start). */
    data class Failed(val reason: String) : LaunchResult
}

interface AppLauncher {
    fun launch(key: AppKey): LaunchResult
}
