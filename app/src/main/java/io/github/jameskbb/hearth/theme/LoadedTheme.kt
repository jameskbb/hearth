package io.github.jameskbb.hearth.theme

import androidx.compose.ui.graphics.ImageBitmap

/** One decoded layer: its image and how to place and move it. */
data class LoadedLayer(val bitmap: ImageBitmap, val spec: LayerSpec)

/**
 * A pack ready to draw: its info, the scene's sway and its decoded layers, first to last. With a
 * weather [variant] (its id; null for the plain scene), [layers] and [palette] are the variant's
 * resolved ones.
 */
data class LoadedTheme(
    val info: ThemeInfo,
    val sway: Sway,
    val layers: List<LoadedLayer>,
    val variant: String? = null,
    val palette: PaletteSpec = info.palette,
)
