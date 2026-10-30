package io.github.jameskbb.hearth.apps

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.palette.graphics.Palette
import io.github.jameskbb.hearth.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ArtworkLoader] that rasterizes each app's banner (TV apps) or icon at the size the tile
 * draws it, samples a backdrop color, and keeps the result in a memory cache.
 *
 * - Source: for a TV app, the activity banner, then the application banner; otherwise, or
 *   when there is no banner, the activity icon (which falls back to the application icon).
 * - Size: banners fit a 184x104 dp box and icons a 56 dp box, converted with the display
 *   density (a 1080p Fire TV at 320 dpi gives 368x208 px banners). The drawable's own
 *   aspect is kept, so a non-16:9 banner comes back narrower or shorter than the box and
 *   [AppArtwork.aspectRatio] tells the UI how to fit it.
 * - Adaptive icons are drawn through Drawable.draw with bounds set first, which applies the
 *   system mask on API 28 as it does on later versions.
 * - Cache: an LruCache of about 1/16 of the heap, counted in bitmap bytes and keyed by
 *   AppKey plus lastUpdateTime, so an updated app gets fresh art. Concurrent loads of one
 *   key share a single decode ([SingleFlight]).
 * - Threads: PackageManager and resource reads on Dispatchers.IO, drawing and palette work
 *   on Dispatchers.Default.
 * - No artwork: an app that ships no icon at all (the platform would substitute its generic
 *   icon) and artwork that rasterizes to (almost) nothing ([ArtCoverage]) both count as none,
 *   so the tile shows its monogram. A blank banner falls through to the icon first.
 * - TV inputs ([EntryKind.Input]) have no package to read art from: they get Hearth's own HDMI
 *   glyph (`res/drawable/input_hdmi.xml`) as an icon on a fixed slate backdrop, so the tile draws
 *   like an icon-only app with its label.
 * - Never throws for a missing app or a bad drawable: [load] returns null instead.
 */
class CachingArtworkLoader(
    context: Context,
    scope: CoroutineScope,
) : ArtworkLoader {
    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager
    private val flights = SingleFlight<String, AppArtwork?>(scope)

    private val cache = object : LruCache<String, AppArtwork>(cacheSizeKb()) {
        override fun sizeOf(key: String, value: AppArtwork): Int =
            (value.bitmap.asAndroidBitmap().allocationByteCount / 1024).coerceAtLeast(1)
    }

    override suspend fun load(entry: AppEntry): AppArtwork? {
        val key = cacheKey(entry)
        cache.get(key)?.let { return it }
        return try {
            flights.run(key) {
                cache.get(key) ?: decode(entry)?.also { cache.put(key, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Includes OutOfMemoryError from one oversized drawable: skip that tile's art.
            Log.w(TAG, "Artwork failed for ${entry.key.id}", e)
            null
        }
    }

    /** Called from Application.onTrimMemory: drop cached art when the system needs memory. */
    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> cache.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> cache.trimToSize(cache.maxSize() / 2)
        }
    }

    private suspend fun decode(entry: AppEntry): AppArtwork? {
        if (entry.kind == EntryKind.Input) return withContext(Dispatchers.Default) { renderInputGlyph() }
        val info = withContext(Dispatchers.IO) { activityInfo(entry) } ?: return null
        if (entry.isTvApp) {
            val banner = withContext(Dispatchers.IO) { info.loadBanner(pm) ?: info.applicationInfo?.loadBanner(pm) }
            if (banner != null) withContext(Dispatchers.Default) { render(Source(banner, ArtKind.Banner)) }?.let { return it }
        }
        // getIconResource() is the activity's icon, else the application's; 0 means the app ships
        // none and loadIcon would hand back the platform's generic icon, which is not its artwork.
        if (info.iconResource == 0) return null
        val icon = withContext(Dispatchers.IO) { info.loadIcon(pm) } ?: return null
        return withContext(Dispatchers.Default) { render(Source(icon, ArtKind.Icon)) }
    }

    private class Source(val drawable: Drawable, val kind: ArtKind)

    private fun activityInfo(entry: AppEntry): ActivityInfo? = try {
        pm.activityInfo(entry.key.component)
    } catch (_: PackageManager.NameNotFoundException) {
        null // uninstalled, or the activity is gone or disabled
    }

    /** Rasterizes [source] at tile size; null when the result is effectively transparent. */
    private fun render(source: Source): AppArtwork? {
        val density = appContext.resources.displayMetrics.densityDpi
        val box = when (source.kind) {
            ArtKind.Banner -> ArtworkSizing.bannerBox(density)
            ArtKind.Icon -> ArtworkSizing.iconBox(density)
        }
        val d = source.drawable
        val size = ArtworkSizing.fit(d.intrinsicWidth, d.intrinsicHeight, box)
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        // The drawable is a fresh instance from PackageManager, but restore bounds anyway.
        val oldBounds = d.copyBounds()
        d.setBounds(0, 0, size.width, size.height)
        d.draw(Canvas(bitmap))
        d.bounds = oldBounds
        if (isBlank(bitmap)) {
            bitmap.recycle()
            return null
        }
        bitmap.prepareToDraw()

        val palette = Palette.from(bitmap).maximumColorCount(16).generate()
        val backdrop = when (source.kind) {
            ArtKind.Banner -> ArtColors.backdrop(
                listOf(palette.dominantSwatch?.rgb, palette.darkVibrantSwatch?.rgb, palette.darkMutedSwatch?.rgb),
                ArtColors.MAX_LIGHTNESS_BANNER,
            )
            ArtKind.Icon -> ArtColors.backdrop(
                listOf(palette.dominantSwatch?.rgb, palette.vibrantSwatch?.rgb, palette.darkVibrantSwatch?.rgb),
                ArtColors.MAX_LIGHTNESS_ICON,
            )
        }
        return AppArtwork(bitmap = bitmap.asImageBitmap(), kind = source.kind, backdropArgb = backdrop)
    }

    /** Hearth's HDMI glyph at icon size, white, on [INPUT_BACKDROP]. */
    private fun renderInputGlyph(): AppArtwork? {
        val d = appContext.getDrawable(R.drawable.input_hdmi) ?: return null
        val box = ArtworkSizing.iconBox(appContext.resources.displayMetrics.densityDpi)
        val size = ArtworkSizing.fit(d.intrinsicWidth, d.intrinsicHeight, box)
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size.width, size.height)
        d.draw(Canvas(bitmap))
        bitmap.prepareToDraw()
        return AppArtwork(bitmap = bitmap.asImageBitmap(), kind = ArtKind.Icon, backdropArgb = INPUT_BACKDROP)
    }

    private fun isBlank(bitmap: Bitmap): Boolean {
        val step = ArtCoverage.sampleStep(bitmap.width, bitmap.height)
        val cols = (bitmap.width + step - 1) / step
        val rows = (bitmap.height + step - 1) / step
        val samples = IntArray(cols * rows)
        var i = 0
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) samples[i++] = bitmap.getPixel(x, y)
        }
        return ArtCoverage.isEmpty(samples)
    }

    private companion object {
        const val TAG = "HearthArtwork"

        /**
         * The surface behind the HDMI glyph: a muted slate that the tile's tint darkens to a calm
         * blue-grey, distinct from both the wallpapers' skies and the monogram tiles.
         */
        const val INPUT_BACKDROP = 0xFF3A4660.toInt()

        fun cacheKey(entry: AppEntry) = "${entry.key.id}@${entry.lastUpdateTime}"

        /** About 1/16 of the heap: on a 2 GB TV that is a few dozen banners. */
        fun cacheSizeKb(): Int = (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceAtLeast(1024)
    }
}
