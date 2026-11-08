package io.github.jameskbb.hearth.theme

/** An image's pixel size, read from its header without decoding it. */
data class ImageBounds(val width: Int, val height: Int)

/**
 * The checks a parsed manifest's images must pass before a pack is listed (docs/theme-packs.md,
 * Layers). Pure: it sees the pack only through [exists] and [bounds], which read headers, so a
 * pack that would not fit the memory budget is rejected before anything is decoded.
 */
object ThemeValidation {
    const val BASE_WIDTH = 1920
    const val BASE_HEIGHT = 1080
    const val MAX_IMAGE_WIDTH = 1920

    /**
     * The decoded budget for all layers together, in bytes (4 per pixel). Decimal megabytes:
     * three full-size layers (24.9 MB) fit and a fourth (33.2 MB) does not, which is what the
     * spec's "at most three full-size layers" means.
     */
    const val MAX_DECODED_BYTES = 32_000_000L

    /** Decoded size of one image as Hearth holds it (ARGB_8888). */
    fun decodedBytes(b: ImageBounds): Long = b.width.toLong() * b.height.toLong() * 4L

    /**
     * The first problem with [manifest]'s images, or null when the pack is usable. The base
     * layers are checked first, then each weather variant's resolved layer set the same way
     * (its own 32 MB budget, since only one set is decoded at a time); a variant's problem is
     * wrapped in [ThemeProblem.BadVariant] so it names the variant.
     */
    fun check(
        manifest: ThemeManifest,
        exists: (String) -> Boolean,
        bounds: (String) -> ImageBounds?,
    ): ThemeProblem? {
        val seen = HashMap<String, ImageBounds>()
        fun read(path: String): Pair<ImageBounds?, ThemeProblem?> {
            seen[path]?.let { return it to null }
            if (!exists(path)) return null to ThemeProblem.MissingImage(path)
            val b = bounds(path)?.takeIf { it.width > 0 && it.height > 0 }
                ?: return null to ThemeProblem.UnreadableImage(path)
            seen[path] = b
            return b to null
        }
        checkLayers(manifest.layers, ::read)?.let { return it }
        manifest.preview?.let { path -> read(path).second?.let { return it } }
        for (variant in manifest.weather) {
            checkVariant(manifest, variant, ::read)?.let { return ThemeProblem.BadVariant(variant.id, it) }
        }
        return null
    }

    /** One layer set as the renderer would decode it: sizes, base layer and budget. */
    private fun checkLayers(
        layers: List<LayerSpec>,
        read: (String) -> Pair<ImageBounds?, ThemeProblem?>,
    ): ThemeProblem? {
        val used = LinkedHashMap<String, ImageBounds>()
        layers.forEachIndexed { index, layer ->
            val path = layer.image
            val (b, problem) = read(path)
            if (problem != null) return problem
            b!!
            if (b.width > MAX_IMAGE_WIDTH) return ThemeProblem.ImageTooWide(path, b.width)
            if (index == 0 && (b.width != BASE_WIDTH || b.height != BASE_HEIGHT)) {
                return ThemeProblem.BaseLayerSize(path, b.width, b.height)
            }
            used[path] = b
        }
        // An image used by two layers is decoded once.
        val total = used.values.sumOf(::decodedBytes)
        if (total > MAX_DECODED_BYTES) return ThemeProblem.TooLarge(total)
        return null
    }

    private fun checkVariant(
        manifest: ThemeManifest,
        variant: WeatherVariant,
        read: (String) -> Pair<ImageBounds?, ThemeProblem?>,
    ): ThemeProblem? {
        // Every image the variant names, even one a full replacement makes unused.
        for (path in variant.images) {
            val (b, problem) = read(path)
            if (problem != null) return problem
            if (b!!.width > MAX_IMAGE_WIDTH) return ThemeProblem.ImageTooWide(path, b.width)
        }
        val fullSizeReplacements = variant.replace.values.toSet().count { read(it).first?.width == MAX_IMAGE_WIDTH }
        if (fullSizeReplacements > MAX_FULL_SIZE_REPLACEMENTS) return ThemeProblem.FullSizeReplacements(fullSizeReplacements)
        val layers = ThemeVariants.layers(manifest, variant)
        if (layers.isEmpty() || layers.size > ThemeManifestCodec.MAX_VARIANT_LAYERS) {
            return ThemeProblem.LayerCount(layers.size, ThemeManifestCodec.MAX_VARIANT_LAYERS)
        }
        return checkLayers(layers, read)
    }

    /** A variant replaces at most this many images with full-size (1920 px wide) ones. */
    const val MAX_FULL_SIZE_REPLACEMENTS = 1
}
