package io.github.jameskbb.hearth.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.jameskbb.hearth.theme.PaletteSpec

/** Colors that change with the theme. See docs/design.md for how each is used. */
data class HearthPalette(
    val ink: Color,        // primary text
    val inkMuted: Color,   // secondary text (hints, date)
    val accent: Color,     // focus ring, selected state
    val glow: Color,       // soft light under the focused tile
    val shade: Color,      // legacy pack shade token, retained for theme-format compatibility
    val shadeMid: Color,   // legacy pack shade token, never drawn over the scene
    val window: Color,     // solid fill shown before the theme decodes
)

/** A pack's palette as Compose colors. */
fun PaletteSpec.toHearthPalette() = HearthPalette(
    ink = Color(ink),
    inkMuted = Color(inkMuted),
    accent = Color(accent),
    glow = Color(glow),
    shade = Color(shade),
    shadeMid = Color(shadeMid),
    window = Color(window),
)

/**
 * Dusk's palette, the last resort: shown before the first theme is known, and with a solid
 * window color when no theme can be loaded at all (docs/theme-packs.md, Validation and fallback).
 * Matches assets/themes/dusk/theme.json.
 */
val DefaultPalette = HearthPalette(
    ink = Color(0xFFFFF3E4),
    inkMuted = Color(0xB8FFF3E4),
    accent = Color(0xFFFFB45C),
    glow = Color(0x73FF9646),
    shade = Color(0xE00A0E24),
    shadeMid = Color(0x730A0E24),
    window = Color(0xFF0A0E24),
)

/** Layout tokens, in dp on the 960x540 dp Fire TV canvas. */
object Dimens {
    val marginH = 48.dp
    val marginV = 32.dp
    val tileWidth = 184.dp
    val tileHeight = 104.dp
    val tileGap = 20.dp
    val tileRadius = 10.dp
    val focusScale = 1.08f
    val ringWidth = 2.dp
    val ringGap = 2.dp
}

/** UI tokens beyond the layout grid: motion and the smaller pieces of the focus treatment. */
object UiTokens {
    /** Focus and lift motion: 160 ms, standard easing, only in response to the user. */
    const val motionMillis = 160
    /** How far a tile rises while it is being moved in arrange mode. */
    val liftOffset = 8.dp
    /**
     * Room above the favorites row for a lifted tile: lift, ring (gap plus width), plus 4 dp so the ring stays clear of
     * the hint line's glyphs. A lifted tile is not also scaled (see AppTile). Room below is for the focus glow.
     */
    val rowPaddingTop = liftOffset + Dimens.ringGap + Dimens.ringWidth + 4.dp
    val rowPaddingBottom = 22.dp
    /** Gap between text actions in a row. */
    val actionGap = 28.dp
    /** Thickness of the accent underline on a focused text action. */
    val underline = 2.dp
    /** Banner aspect ratios within this tolerance of 16:9 fill the tile; others are fitted. */
    const val bannerAspectTolerance = 0.08f
    /** Icon size on icon-fallback tiles. */
    val tileIcon = 56.dp
    /** Weather beside the clock: the gap between the columns, the glyph and its gap to the temperature. */
    val clockAsideGap = 40.dp
    val weatherGlyph = 26.dp
    val weatherGlyphGap = 10.dp
    /** Number of columns in All apps. */
    const val gridColumns = 4
}
