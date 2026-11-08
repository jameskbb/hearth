package io.github.jameskbb.hearth.theme

/**
 * Weather variant matching and resolution (docs/theme-packs.md, Weather). Pure: it works on
 * parsed manifests and [VariantRule]s, so it needs no decoded art and runs in JVM tests.
 */
object ThemeVariants {
    /**
     * Skies tried, in order, when no variant matches the mood's own sky. Temperature and
     * forecast stay as they are. Clear and cloudy are not widened.
     */
    fun widenedSkies(sky: SceneSky): List<SceneSky> = when (sky) {
        SceneSky.Storm -> listOf(SceneSky.Storm, SceneSky.Rain, SceneSky.Cloudy)
        SceneSky.Fog, SceneSky.Rain, SceneSky.Snow -> listOf(sky, SceneSky.Cloudy)
        SceneSky.Clear, SceneSky.Cloudy -> listOf(sky)
    }

    /** The first rule, in order, that matches [mood] as it is, without widening. */
    fun exact(rules: List<VariantRule>, mood: SceneMood): VariantRule? = rules.firstOrNull { it.rule.matches(mood) }

    /**
     * The variant id to show for [mood], or null for the plain scene: the first exact match,
     * else the first match with the sky widened along [widenedSkies].
     */
    fun pick(rules: List<VariantRule>, mood: SceneMood?): String? {
        if (mood == null || rules.isEmpty()) return null
        for (sky in widenedSkies(mood.sky)) {
            exact(rules, mood.copy(sky = sky))?.let { return it.id }
        }
        return null
    }

    /** Every mood some variant matches exactly: what selection prefers a pack for. */
    fun moods(rules: List<VariantRule>): Set<SceneMood> =
        if (rules.isEmpty()) emptySet() else SceneMood.all.filterTo(LinkedHashSet()) { m -> rules.any { it.rule.matches(m) } }

    /**
     * The layers drawn for [variant] (null: the base layers), first to last:
     *
     * 1. Each base layer whose image is in `replace` draws the new image with the same
     *    placement and motion; if an `add` entry names that new image, its fields are used
     *    instead and it is not added a second time.
     * 2. Base layers in `hide` are left out.
     * 3. Each other `add` entry goes just after the base layer named by its `after` (in the
     *    order listed; also when that base layer is hidden), or on top without one.
     */
    fun layers(manifest: ThemeManifest, variant: WeatherVariant?): List<LayerSpec> {
        if (variant == null) return manifest.layers
        val fullReplacements = variant.add.filter { a -> a.spec.image in variant.replace.values }
            .associateBy { it.spec.image }
        val out = ArrayList<LayerSpec>()
        for (base in manifest.layers) {
            val replacement = variant.replace[base.image]
            if (base.image !in variant.hide) {
                out += when (replacement) {
                    null -> base
                    else -> fullReplacements[replacement]?.spec ?: base.copy(image = replacement)
                }
            }
            for (added in variant.add) {
                if (added.after == base.image && added.spec.image !in fullReplacements) out += added.spec
            }
        }
        for (added in variant.add) {
            if (added.after == null && added.spec.image !in fullReplacements) out += added.spec
        }
        return out
    }

    /** The palette for [variant] (null: the base palette). */
    fun palette(manifest: ThemeManifest, variant: WeatherVariant?): PaletteSpec =
        variant?.palette?.applyTo(manifest.palette) ?: manifest.palette

    fun variant(manifest: ThemeManifest, id: String?): WeatherVariant? =
        id?.let { wanted -> manifest.weather.firstOrNull { it.id == wanted } }
}
