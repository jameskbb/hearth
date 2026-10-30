package io.github.jameskbb.hearth.theme

import androidx.compose.ui.graphics.ImageBitmap

/** One decoded layer: its image and how to place and move it. */
data class LoadedLayer(val bitmap: ImageBitmap, val spec: LayerSpec)

/** A pack ready to draw: its info, the scene's sway and its decoded layers, first to last. */
data class LoadedTheme(val info: ThemeInfo, val sway: Sway, val layers: List<LoadedLayer>)
