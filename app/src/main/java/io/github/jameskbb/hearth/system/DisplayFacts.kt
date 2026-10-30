package io.github.jameskbb.hearth.system

/**
 * What the platform reports about the current display, shown in Settings, About.
 * Values are read at runtime; nothing here is assumed or hard-coded.
 */
data class DisplayFacts(
    /** UI surface size in pixels (what the launcher renders at). */
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val refreshRateHz: Float,
    /** Physical modes the display supports (width x height @ Hz), if reported. */
    val supportedModes: List<String>,
    val isWideColorGamut: Boolean,
    /** HDR types the display reports, e.g. "HDR10", "Dolby Vision", "HLG". Empty if none. */
    val hdrTypes: List<String>,
)

interface DisplayFactsReader {
    fun read(): DisplayFacts
}
