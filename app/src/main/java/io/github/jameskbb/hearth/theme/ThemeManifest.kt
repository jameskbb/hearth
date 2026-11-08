package io.github.jameskbb.hearth.theme

/**
 * Identifies a theme pack, or the legacy [Auto] choice. Pack ids are stored in preferences,
 * so they remain stable when a manifest's display name changes.
 */
@JvmInline
value class ThemeId(val name: String) {
    val isAuto: Boolean get() = this == Auto

    companion object {
        /** The bundled pack shown when nothing else can be: see docs/theme-packs.md, Validation and fallback. */
        val Default = ThemeId("dusk")

        /** Legacy name for Follow the Day. Reserved: no pack may use it. */
        val Auto = ThemeId("auto")

        private val PACK_ID = Regex("[a-z0-9-]{2,40}")

        /** A pack id: 2 to 40 characters of `a-z`, `0-9` and `-`, and not `auto`. */
        fun isValidPackId(s: String): Boolean = s != Auto.name && PACK_ID.matches(s)

        /** A stored choice: a pack id or `auto`; anything else is null. */
        fun parseChoice(s: String?): ThemeId? {
            val t = s?.trim() ?: return null
            return if (t == Auto.name || isValidPackId(t)) ThemeId(t) else null
        }
    }
}

/** A manifest's recommended part of the day. User schedules may assign the pack anywhere. */
enum class Daypart(val manifestName: String) {
    Morning("morning"),
    Day("day"),
    Evening("evening"),
    Night("night");

    companion object {
        fun fromManifest(s: String): Daypart? = entries.firstOrNull { it.manifestName == s }
    }
}

/** A pack's palette as ARGB ints, with the defaults from docs/theme-packs.md already applied. */
data class PaletteSpec(
    val window: Int,
    val accent: Int,
    val ink: Int,
    val inkMuted: Int,
    val glow: Int,
    val shade: Int,
    val shadeMid: Int,
)

/** Horizontal drift: [xDp] canvas dp per [periodSeconds] (wrapping layers) or to and fro by up to [xDp]. */
data class Drift(val xDp: Float, val periodSeconds: Float)

/** Opacity breathing on a sine between [min] and [max]; [phase] (0 to 1) offsets the cycle. */
data class Breath(val min: Float, val max: Float, val periodSeconds: Float, val phase: Float = 0f)

/** A one-time opacity change, smoothstep, counted from when the theme appears. */
data class Fade(val from: Float, val to: Float, val delaySeconds: Float, val durationSeconds: Float)

/**
 * Vertical fall: the layer tiles top to bottom and moves down [yDp] canvas dp every
 * [periodSeconds]. Its rest state is no offset.
 */
data class Fall(val yDp: Float, val periodSeconds: Float)

enum class LayerBlend { Normal, Screen }

/** One image layer. Distances are canvas dp: 1/960 of the screen width. */
data class LayerSpec(
    val image: String,
    val yDp: Float = 0f,
    val parallax: Float = 0f,
    val drift: Drift? = null,
    val wrap: Boolean = false,
    val opacity: Breath? = null,
    val fade: Fade? = null,
    val blend: LayerBlend = LayerBlend.Normal,
    val fall: Fall? = null,
)

/** The slow to-and-fro of the whole scene, which each layer follows by its parallax. */
data class Sway(val amplitudeDp: Float = 0f, val periodSeconds: Float = 90f)

/** A parsed and value-checked `theme.json`. Image files are checked separately ([ThemeValidation]). */
data class ThemeManifest(
    val format: Int,
    val id: ThemeId,
    val name: String,
    val author: String?,
    val description: String?,
    val dayparts: Set<Daypart>,
    val palette: PaletteSpec,
    val preview: String?,
    val sway: Sway,
    val layers: List<LayerSpec>,
    /** Opt out of implicit automatic pools; deliberate user assignments still work. */
    val autoRotationEligible: Boolean = true,
    /** Optional weather variants, in match order (docs/theme-packs.md, Weather). */
    val weather: List<WeatherVariant> = emptyList(),
)

/**
 * When a weather variant applies. A null part matches anything; a set matches a mood whose part
 * is in it. In [forecast], null stands for "no forecast" (`none` in the manifest).
 */
data class MoodRule(
    val sky: Set<SceneSky>? = null,
    val temp: Set<SceneTemp>? = null,
    val forecast: Set<SceneForecast?>? = null,
) {
    fun matches(mood: SceneMood): Boolean =
        (sky == null || mood.sky in sky) && (temp == null || mood.temp in temp) &&
            (forecast == null || mood.forecast in forecast)
}

/** A variant's id and when it applies: all selection and matching need, without its art. */
data class VariantRule(val id: String, val rule: MoodRule)

/** The palette colors a variant may change. `accent` and `glow` are each pack's own and stay. */
data class PalettePatch(
    val window: Int? = null,
    val ink: Int? = null,
    val inkMuted: Int? = null,
    val shade: Int? = null,
    val shadeMid: Int? = null,
) {
    fun applyTo(p: PaletteSpec) = p.copy(
        window = window ?: p.window,
        ink = ink ?: p.ink,
        inkMuted = inkMuted ?: p.inkMuted,
        shade = shade ?: p.shade,
        shadeMid = shadeMid ?: p.shadeMid,
    )
}

/** A layer a variant adds: drawn just after the base layer whose image is [after], else on top. */
data class AddedLayer(val spec: LayerSpec, val after: String? = null)

/** One entry of a manifest's `weather` array, parsed and value-checked. */
data class WeatherVariant(
    val id: String,
    val rule: MoodRule,
    val palette: PalettePatch = PalettePatch(),
    /** Base layer image to the image drawn in its place. */
    val replace: Map<String, String> = emptyMap(),
    /** Base layer images left out. */
    val hide: Set<String> = emptySet(),
    val add: List<AddedLayer> = emptyList(),
) {
    val matchRule: VariantRule get() = VariantRule(id, rule)

    /** Every image this variant draws that the base may not: replacements and added layers. */
    val images: Set<String> get() = (replace.values + add.map { it.spec.image }).toCollection(LinkedHashSet())
}

/**
 * Why a pack is not shown. [message] is plain English for logs; Settings shows its own
 * translated text for each kind (ui/settings/AppearanceSettings.kt).
 */
sealed interface ThemeProblem {
    val message: String

    data object MissingManifest : ThemeProblem {
        override val message get() = "missing theme.json"
    }

    data object MalformedManifest : ThemeProblem {
        override val message get() = "theme.json is not valid JSON"
    }

    data class NeedsNewerHearth(val format: Int) : ThemeProblem {
        override val message get() = "format $format needs a newer Hearth"
    }

    data class MissingField(val field: String) : ThemeProblem {
        override val message get() = "theme.json has no $field"
    }

    data class BadValue(val field: String) : ThemeProblem {
        override val message get() = "theme.json has an invalid $field"
    }

    data class BadId(val id: String) : ThemeProblem {
        override val message get() = "id \"$id\" is not allowed"
    }

    data class LayerCount(val count: Int, val max: Int = ThemeManifestCodec.MAX_LAYERS) : ThemeProblem {
        override val message get() = "$count layers, 1 to $max allowed"
    }

    data class FullSizeReplacements(val count: Int) : ThemeProblem {
        override val message get() = "$count full-size replacement images, at most 1 allowed"
    }

    /** A weather variant ([variant] is its id) fails a check of its own; [problem] says which. */
    data class BadVariant(val variant: String, val problem: ThemeProblem) : ThemeProblem {
        override val message get() = "weather variant $variant: ${problem.message}"
    }

    data class MissingImage(val path: String) : ThemeProblem {
        override val message get() = "image $path not found"
    }

    data class UnreadableImage(val path: String) : ThemeProblem {
        override val message get() = "image $path could not be read"
    }

    data class BaseLayerSize(val path: String, val width: Int, val height: Int) : ThemeProblem {
        override val message get() = "first layer $path is ${width}x$height, not 1920x1080"
    }

    data class ImageTooWide(val path: String, val width: Int) : ThemeProblem {
        override val message get() = "image $path is $width px wide, over 1920"
    }

    data class TooLarge(val bytes: Long) : ThemeProblem {
        override val message get() = "decoded layers exceed 32 MB"
    }

    data class DuplicateId(val id: String, val byBundled: Boolean) : ThemeProblem {
        override val message get() =
            if (byBundled) "id $id already used by a bundled theme" else "id $id already used by another theme"
    }

    data object ArchiveTooLarge : ThemeProblem {
        override val message get() = "zip is too large"
    }

    data object UnreadableArchive : ThemeProblem {
        override val message get() = "zip could not be read"
    }
}

class ThemeException(val problem: ThemeProblem) : Exception(problem.message)
