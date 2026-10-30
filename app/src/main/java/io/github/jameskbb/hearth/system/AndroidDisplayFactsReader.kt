package io.github.jameskbb.hearth.system

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Reads [DisplayFacts] from the default display.
 *
 * Size: reports Display.getRealSize, the full logical size the launcher's window is
 * composed at (no system bars are subtracted; a TV has none). This is deliberately not
 * the current mode's physical size: on a 4K TV the platform often renders the UI at
 * 1920x1080 and scales it to a 3840x2160 mode, and "what Hearth renders at" is the former.
 * The panel's modes, including the current one, are listed in supportedModes.
 */
class AndroidDisplayFactsReader(context: Context) : DisplayFactsReader {
    private val appContext = context.applicationContext

    override fun read(): DisplayFacts {
        val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return DisplayFacts(0, 0, appContext.resources.displayMetrics.densityDpi, 0f, emptyList(), false, emptyList())

        val size = Point()
        @Suppress("DEPRECATION") // Still the logical full-display size on API 30+.
        display.getRealSize(size)

        val modes = if (Build.VERSION.SDK_INT >= 23) {
            display.supportedModes.orEmpty()
                .map { DisplayFormat.Mode(it.physicalWidth, it.physicalHeight, it.refreshRate) }
        } else {
            // Older displays still report logical size and refresh rate, but no mode list.
            emptyList()
        }

        @Suppress("DEPRECATION") // Display.Mode.getSupportedHdrTypes needs API 34.
        val hdr = if (Build.VERSION.SDK_INT >= 24) {
            display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
        } else {
            IntArray(0)
        }

        return DisplayFacts(
            widthPx = size.x,
            heightPx = size.y,
            densityDpi = appContext.resources.displayMetrics.densityDpi,
            refreshRateHz = display.refreshRate,
            supportedModes = DisplayFormat.modes(modes),
            isWideColorGamut = Build.VERSION.SDK_INT >= 26 && display.isWideColorGamut,
            hdrTypes = DisplayFormat.hdrTypes(hdr),
        )
    }
}

/** Formatting for [DisplayFacts]. Pure, so it is unit-tested. */
internal object DisplayFormat {
    data class Mode(val width: Int, val height: Int, val refreshRateHz: Float)

    /** "3840x2160 @ 60 Hz"; largest first, then fastest; duplicates removed. */
    fun modes(modes: List<Mode>): List<String> =
        modes.sortedWith(
            compareByDescending<Mode> { it.width.toLong() * it.height }
                .thenByDescending { it.width }
                .thenByDescending { it.refreshRateHz },
        ).map { "${it.width}x${it.height} @ ${hz(it.refreshRateHz)} Hz" }.distinct()

    /** 60f -> "60", 59.94f -> "59.94", 23.976f -> "23.98". */
    fun hz(rate: Float): String {
        val hundredths = (rate * 100.0).roundToLong()
        return when {
            hundredths % 100 == 0L -> (hundredths / 100).toString()
            hundredths % 10 == 0L -> String.format(Locale.ROOT, "%.1f", hundredths / 100.0)
            else -> String.format(Locale.ROOT, "%.2f", hundredths / 100.0)
        }
    }

    /**
     * Platform HDR type constants to names, in a fixed order, unknown types by number.
     * HDR_TYPE_HDR10_PLUS is an API 29 constant inlined at compile time; on API 28 the
     * platform simply never reports it.
     */
    @SuppressLint("InlinedApi")
    fun hdrTypes(types: IntArray): List<String> {
        val known = listOf(
            Display.HdrCapabilities.HDR_TYPE_HDR10 to "HDR10",
            Display.HdrCapabilities.HDR_TYPE_HLG to "HLG",
            Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS to "HDR10+",
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION to "Dolby Vision",
        )
        val present = types.filter { it > 0 }.toSet() // HDR_TYPE_INVALID is -1
        val names = known.filter { it.first in present }.map { it.second }
        val unknown = present.filter { t -> known.none { it.first == t } }.sorted().map { "HDR type $it" }
        return names + unknown
    }
}
