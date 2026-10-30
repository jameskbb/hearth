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

    /** The first problem with [manifest]'s images, or null when the pack is usable. */
    fun check(
        manifest: ThemeManifest,
        exists: (String) -> Boolean,
        bounds: (String) -> ImageBounds?,
    ): ThemeProblem? {
        val seen = HashMap<String, ImageBounds>()
        manifest.layers.forEachIndexed { index, layer ->
            val path = layer.image
            val b = seen[path] ?: run {
                if (!exists(path)) return ThemeProblem.MissingImage(path)
                val read = bounds(path)?.takeIf { it.width > 0 && it.height > 0 }
                    ?: return ThemeProblem.UnreadableImage(path)
                read.also { seen[path] = it }
            }
            if (b.width > MAX_IMAGE_WIDTH) return ThemeProblem.ImageTooWide(path, b.width)
            if (index == 0 && (b.width != BASE_WIDTH || b.height != BASE_HEIGHT)) {
                return ThemeProblem.BaseLayerSize(path, b.width, b.height)
            }
        }
        // An image used by two layers is decoded once.
        val total = seen.values.sumOf(::decodedBytes)
        if (total > MAX_DECODED_BYTES) return ThemeProblem.TooLarge(total)
        manifest.preview?.let { path ->
            if (!exists(path)) return ThemeProblem.MissingImage(path)
            if (bounds(path)?.takeIf { it.width > 0 && it.height > 0 } == null) return ThemeProblem.UnreadableImage(path)
        }
        return null
    }
}
