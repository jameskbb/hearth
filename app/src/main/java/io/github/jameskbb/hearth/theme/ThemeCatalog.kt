package io.github.jameskbb.hearth.theme

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

/** What the gallery and Auto need to know about a pack, without its images. */
data class ThemeInfo(
    val id: ThemeId,
    val name: String,
    val author: String?,
    val description: String?,
    val dayparts: Set<Daypart>,
    val palette: PaletteSpec,
    val bundled: Boolean,
    /** Changes when any file used by an installed pack changes, even if its manifest does not. */
    val contentRevision: String = "",
    val autoRotationEligible: Boolean = true,
)

/** A pack that is not listed, where it was ([location]: its folder or zip name) and why. */
data class ThemeFailure(val location: String, val problem: ThemeProblem)

/**
 * The theme packs Hearth can show: bundled ones from the APK, then installed ones from
 * Hearth's app-specific storage (docs/theme-packs.md). Discovery reads manifests and image
 * headers only; images are decoded by [load] and [preview], off the main thread.
 */
interface ThemeCatalog {
    /** Usable packs, in gallery and Auto order: bundled first, then installed by id. */
    val themes: StateFlow<List<ThemeInfo>>

    /** Packs that were found but cannot be used, each with its reason. */
    val failures: StateFlow<List<ThemeFailure>>

    /** True once the first scan has finished, so an empty [themes] really means none. */
    val loaded: StateFlow<Boolean>

    /** Counts [release] calls: a theme loaded before the latest one is no longer cached. */
    val releases: StateFlow<Int>

    /** Scans again (at launch and each time Settings opens). */
    suspend fun refresh()

    /**
     * The decoded pack, or null when [id] is not listed or its images could not be decoded (it
     * then moves to [failures]). Holds one theme: loading another drops the previous one from
     * the cache once the new one is decoded. Cancellable between layers.
     */
    suspend fun load(id: ThemeId): LoadedTheme?

    /** A small image for the gallery card (`preview`, else the first layer scaled down). */
    suspend fun preview(id: ThemeId): ImageBitmap?

    /** Drops the gallery's preview images (the Appearance panel closed). */
    fun releasePreviews()

    /** Drops every decoded image (memory is short). */
    fun release()
}

/**
 * Ordering and de-duplication for discovery, kept pure for tests. [S] is whatever the caller
 * needs to load the pack later (its [ThemeSource] in the app).
 */
object ThemeOrder {
    /** One pack found by a scan: its parsed and validated manifest, or why it failed. */
    data class Scanned<S>(
        val location: String,
        val bundled: Boolean,
        val manifest: ThemeManifest?,
        val problem: ThemeProblem?,
        val source: S,
    )

    data class Arranged<S>(val accepted: List<Scanned<S>>, val failures: List<ThemeFailure>)

    /** Ids listed in `assets/themes/order.txt`: one per line, blank lines and `#` comments skipped. */
    fun parseOrder(text: String?): List<String> =
        text.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()

    /**
     * Bundled packs first, in [order] (ids it does not list follow alphabetically), then
     * installed packs by id. The first pack with an id wins; a later one with the same id
     * becomes a failure.
     */
    fun <S> arrange(scanned: List<Scanned<S>>, order: List<String>): Arranged<S> {
        val good = scanned.filter { it.manifest != null && it.problem == null }
        val rank = order.withIndex().associate { (i, id) -> id to i }
        val bundled = good.filter { it.bundled }
            .sortedWith(compareBy({ rank[it.manifest!!.id.name] ?: Int.MAX_VALUE }, { it.manifest!!.id.name }, { it.location }))
        val installed = good.filterNot { it.bundled }
            .sortedWith(compareBy({ it.manifest!!.id.name }, { it.location }))
        val accepted = ArrayList<Scanned<S>>()
        val winners = HashMap<ThemeId, Scanned<S>>()
        val duplicates = ArrayList<ThemeFailure>()
        for (s in bundled + installed) {
            val id = s.manifest!!.id
            val winner = winners[id]
            if (winner == null) {
                winners[id] = s
                accepted += s
            } else {
                duplicates += ThemeFailure(s.location, ThemeProblem.DuplicateId(id.name, byBundled = winner.bundled))
            }
        }
        val broken = scanned.filter { it.problem != null }
            .sortedWith(compareBy({ !it.bundled }, { it.location }))
            .map { ThemeFailure(it.location, it.problem!!) }
        return Arranged(accepted, broken + duplicates)
    }

    fun info(manifest: ThemeManifest, bundled: Boolean, contentRevision: String = "") = ThemeInfo(
        id = manifest.id,
        name = manifest.name,
        author = manifest.author,
        description = manifest.description,
        dayparts = manifest.dayparts,
        palette = manifest.palette,
        bundled = bundled,
        contentRevision = contentRevision,
        autoRotationEligible = manifest.autoRotationEligible,
    )
}
