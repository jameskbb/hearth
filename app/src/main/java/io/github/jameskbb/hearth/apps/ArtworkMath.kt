package io.github.jameskbb.hearth.apps

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pixel sizes for tile artwork. Pure, so the sizing rules are unit-tested. */
internal object ArtworkSizing {
    /** Banner tile, matching ui.theme.Dimens.tileWidth x tileHeight. */
    const val BANNER_WIDTH_DP = 184
    const val BANNER_HEIGHT_DP = 104

    /** Icon fallback is drawn centered on the tile at about this size (docs/design.md). */
    const val ICON_DP = 56

    /** Banners this close to the tile's aspect fill it exactly instead of letterboxing. */
    const val SNAP_TOLERANCE = 0.02f

    data class Size(val width: Int, val height: Int)

    fun dpToPx(dp: Int, densityDpi: Int): Int = (dp * densityDpi / 160f).roundToInt().coerceAtLeast(1)

    fun bannerBox(densityDpi: Int) = Size(dpToPx(BANNER_WIDTH_DP, densityDpi), dpToPx(BANNER_HEIGHT_DP, densityDpi))

    fun iconBox(densityDpi: Int) = Size(dpToPx(ICON_DP, densityDpi), dpToPx(ICON_DP, densityDpi))

    /**
     * The largest size with the drawable's own aspect that fits in [box], so nothing is
     * stretched or cropped. A drawable without an intrinsic size (a plain color, say) fills
     * the box. An aspect within [SNAP_TOLERANCE] of the box fills it: a 320x180 banner is
     * 16:9 and the 184x104 dp tile is a hair narrower, and that sliver is not worth bars.
     */
    fun fit(intrinsicWidth: Int, intrinsicHeight: Int, box: Size): Size {
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) return box
        val aspect = intrinsicWidth.toFloat() / intrinsicHeight
        val boxAspect = box.width.toFloat() / box.height
        if (abs(aspect / boxAspect - 1f) <= SNAP_TOLERANCE) return box
        return if (aspect > boxAspect) {
            Size(box.width, (box.width / aspect).roundToInt().coerceAtLeast(1))
        } else {
            Size((box.height * aspect).roundToInt().coerceAtLeast(1), box.height)
        }
    }
}

/**
 * Backdrop colors for tiles. Pure ARGB/HSL math (no android.graphics), so it is unit-tested.
 * Tiles sit in a dark room on a dark wallpaper, so a sampled color is capped in lightness:
 * a white icon background must not become a bright white tile.
 */
internal object ArtColors {
    /** Deep blue-gray used when the artwork yields no usable color. */
    const val FALLBACK: Int = 0xFF1B2033.toInt()

    /** Letterbox bars beside a banner may stay closer to the banner's own color. */
    const val MAX_LIGHTNESS_BANNER = 0.42f

    /** The icon tile surface is a tint, kept darker. */
    const val MAX_LIGHTNESS_ICON = 0.30f

    /** The first non-null candidate, made opaque and capped at [maxLightness]; else [FALLBACK]. */
    fun backdrop(candidates: List<Int?>, maxLightness: Float): Int {
        val argb = candidates.firstOrNull { it != null } ?: return FALLBACK
        val hsl = toHsl(argb)
        val l = min(hsl[2], maxLightness)
        return fromHsl(hsl[0], hsl[1], l)
    }

    /** [h] in degrees 0..360, [s] and [l] in 0..1. Alpha is ignored. */
    fun toHsl(argb: Int): FloatArray {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        if (d == 0f) return floatArrayOf(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return floatArrayOf(h, s.coerceIn(0f, 1f), l)
    }

    /** Opaque ARGB from HSL. */
    fun fromHsl(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = (h % 360f) / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        fun ch(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }
}

/**
 * Decides whether rasterized artwork actually shows anything. An app can ship a fully (or almost
 * fully) transparent icon or banner; drawing it would leave a blank tile, so such artwork counts as
 * none and the tile falls back to the monogram. Pure, so it is unit-tested.
 */
internal object ArtCoverage {
    /** A pixel counts as drawn when its alpha is at least this (out of 255). */
    const val VISIBLE_ALPHA = 24

    /** Artwork covering less than this fraction of its box is treated as no artwork. */
    const val MIN_COVERAGE = 0.005f

    /** Roughly how many pixels to inspect; a tile raster is sampled on a grid, not read whole. */
    const val SAMPLE_TARGET = 4096

    /** Distance between sampled pixels along each axis for a [width] x [height] raster. */
    fun sampleStep(width: Int, height: Int): Int {
        val total = width.toLong() * height
        if (total <= SAMPLE_TARGET) return 1
        return kotlin.math.sqrt(total.toDouble() / SAMPLE_TARGET).toInt().coerceAtLeast(1)
    }

    /** Fraction of [argb] pixels (ARGB_8888 ints) whose alpha is at least [VISIBLE_ALPHA]. */
    fun coverage(argb: IntArray): Float {
        if (argb.isEmpty()) return 0f
        var visible = 0
        for (p in argb) if ((p ushr 24) >= VISIBLE_ALPHA) visible++
        return visible.toFloat() / argb.size
    }

    /** True when the sampled pixels show (effectively) nothing. */
    fun isEmpty(argb: IntArray): Boolean = coverage(argb) < MIN_COVERAGE
}
