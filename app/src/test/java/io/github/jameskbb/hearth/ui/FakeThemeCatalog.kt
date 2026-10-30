package io.github.jameskbb.hearth.ui

import androidx.compose.ui.graphics.ImageBitmap
import io.github.jameskbb.hearth.theme.Daypart
import io.github.jameskbb.hearth.theme.LoadedTheme
import io.github.jameskbb.hearth.theme.PaletteSpec
import io.github.jameskbb.hearth.theme.Sway
import io.github.jameskbb.hearth.theme.ThemeCatalog
import io.github.jameskbb.hearth.theme.ThemeFailure
import io.github.jameskbb.hearth.theme.ThemeId
import io.github.jameskbb.hearth.theme.ThemeInfo
import io.github.jameskbb.hearth.theme.ThemeProblem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * An in-memory [ThemeCatalog]. Packs are [ThemeInfo]s; a loaded theme has no layers (bitmaps need
 * Android), which is enough to see which pack the view model shows. A pack in [undecodable]
 * fails to load the way a broken image would: it moves from [themes] to [failures].
 */
class FakeThemeCatalog(initial: List<ThemeInfo> = listOf(info("dusk", Daypart.Evening), info("aurora", Daypart.Night))) :
    ThemeCatalog {
    override val themes = MutableStateFlow(initial)
    override val failures = MutableStateFlow<List<ThemeFailure>>(emptyList())
    override val loaded = MutableStateFlow(true)
    override val releases = MutableStateFlow(0)
    val undecodable = mutableSetOf<ThemeId>()
    val loads = mutableListOf<ThemeId>()
    var refreshes = 0
    var previewReleases = 0

    override suspend fun refresh() {
        refreshes++
    }

    override suspend fun load(id: ThemeId): LoadedTheme? {
        loads += id
        val info = themes.value.firstOrNull { it.id == id } ?: return null
        if (id in undecodable) {
            themes.update { list -> list.filterNot { it.id == id } }
            failures.update { it + ThemeFailure(id.name, ThemeProblem.UnreadableImage("sky.webp")) }
            return null
        }
        return LoadedTheme(info, Sway(), emptyList())
    }

    override suspend fun preview(id: ThemeId): ImageBitmap? = null

    override fun releasePreviews() {
        previewReleases++
    }

    override fun release() {
        releases.value++
    }

    companion object {
        fun info(id: String, vararg dayparts: Daypart, window: Int = 0xFF000000.toInt()) = ThemeInfo(
            id = ThemeId(id),
            name = id.replaceFirstChar { it.uppercase() },
            author = null,
            description = null,
            dayparts = dayparts.toSet(),
            palette = PaletteSpec(window, 0xFFFFFFFF.toInt(), -1, -1, -1, window, window),
            bundled = true,
        )
    }
}
