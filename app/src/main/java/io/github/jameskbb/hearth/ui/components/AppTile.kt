package io.github.jameskbb.hearth.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.jameskbb.hearth.apps.AppArtwork
import io.github.jameskbb.hearth.apps.AppEntry
import io.github.jameskbb.hearth.apps.ArtKind
import io.github.jameskbb.hearth.apps.ArtworkLoader
import io.github.jameskbb.hearth.ui.theme.Dimens
import io.github.jameskbb.hearth.ui.theme.Hearth
import io.github.jameskbb.hearth.ui.theme.UiTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Artwork state for one tile. */
private sealed interface Art {
    data object Loading : Art
    data object None : Art
    data class Ready(val art: AppArtwork) : Art
}

/** Callbacks shared by every tile on a screen; one instance per screen keeps tiles skippable. */
@Stable
class TileActions(
    val onClick: (AppEntry) -> Unit,
    val onOptions: (AppEntry) -> Unit,
    val onFocused: (AppEntry) -> Unit,
)

/**
 * One app tile, 184x104 dp. Draws the banner, the icon fallback, or the monogram fallback, and the
 * focus treatment: scale 1.08, a 2 dp accent ring outside a 2 dp dark gap, and a small glow
 * beneath. [lifted] marks the tile being moved in arrange mode: ring and a small upward offset,
 * without the scale.
 */
@Composable
fun AppTile(
    entry: AppEntry,
    artwork: ArtworkLoader,
    actions: TileActions,
    modifier: Modifier = Modifier,
    lifted: Boolean = false,
) {
    val palette = Hearth.palette
    val reducedMotion = Hearth.reducedMotion
    var focused by remember { mutableStateOf(false) }

    val art by produceState<Art>(Art.Loading, entry.key, entry.lastUpdateTime) {
        val loaded = withContext(Dispatchers.IO) { runCatching { artwork.load(entry) }.getOrNull() }
        value = if (loaded == null) Art.None else Art.Ready(loaded)
    }

    val motion = if (reducedMotion) snap<Float>() else tween(UiTokens.motionMillis, easing = FastOutSlowInEasing)
    // A lifted tile shows the ring and the lift, not the focus scale: lift plus scale would reach
    // past the room above the favorites row and into the hint line.
    val scale by animateFloatAsState(
        targetValue = if (focused && !lifted && !reducedMotion) Dimens.focusScale else 1f,
        animationSpec = motion,
        label = "tileScale",
    )
    val liftPx = with(androidx.compose.ui.platform.LocalDensity.current) { UiTokens.liftOffset.toPx() }
    val lift by animateFloatAsState(
        targetValue = if (lifted) -liftPx else 0f,
        animationSpec = motion,
        label = "tileLift",
    )
    val showRing = focused || lifted
    val ringGap = Dimens.ringGap
    val ringWidth = Dimens.ringWidth
    val radius = Dimens.tileRadius
    val shape = remember { RoundedCornerShape(Dimens.tileRadius) }

    Box(
        modifier = modifier
            .zIndex(if (showRing) 1f else 0f)
            .size(Dimens.tileWidth, Dimens.tileHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = lift
            }
            .drawBehind {
                if (showRing) {
                    // Soft light pooled under the tile, offset downward: never a halo all round.
                    val w = size.width * 0.86f
                    val h = 30.dp.toPx()
                    val center = Offset(size.width / 2, size.height + 6.dp.toPx())
                    scale(scaleX = w / h, scaleY = 1f, pivot = center) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(palette.glow, Color.Transparent),
                                center = center,
                                radius = h / 2,
                            ),
                            radius = h / 2,
                            center = center,
                        )
                    }
                }
            }
            .drawWithContent {
                drawContent()
                if (showRing) {
                    val gap = ringGap.toPx()
                    val ring = ringWidth.toPx()
                    val r = radius.toPx()
                    // Dark gap hugging the artwork.
                    val g = gap / 2
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.55f),
                        topLeft = Offset(-g, -g),
                        size = Size(size.width + gap, size.height + gap),
                        cornerRadius = CornerRadius(r + g),
                        style = Stroke(gap),
                    )
                    // Accent ring outside it.
                    val o = gap + ring / 2
                    drawRoundRect(
                        color = palette.accent,
                        topLeft = Offset(-o, -o),
                        size = Size(size.width + 2 * o, size.height + 2 * o),
                        cornerRadius = CornerRadius(r + o),
                        style = Stroke(ring),
                    )
                }
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) actions.onFocused(entry)
            }
            .remoteClickable(
                onClick = { actions.onClick(entry) },
                onLongClick = { actions.onOptions(entry) },
                onMenu = { actions.onOptions(entry) },
            ),
    ) {
        Box(Modifier.matchParentSize().clip(shape)) {
            TileArt(entry, art, reducedMotion)
        }
    }
}

@Composable
private fun TileArt(entry: AppEntry, art: Art, reducedMotion: Boolean) {
    val palette = Hearth.palette
    val type = Hearth.type
    // Crossfade the artwork in once it arrives; reduced motion shows it at once.
    val alpha = remember(entry.key) { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(art) {
        if (art is Art.Ready) {
            if (reducedMotion) alpha.snapTo(1f) else alpha.animateTo(1f, tween(UiTokens.motionMillis))
        }
    }

    when (art) {
        Art.Loading -> {
            val brush = remember(palette.window, palette.ink) { neutralBrush(palette.window, palette.ink) }
            Box(Modifier.fillMaxSize().background(brush))
        }
        Art.None -> MonogramTile(entry, palette.window, palette.ink, type)
        is Art.Ready -> {
            val a = art.art
            val backdrop = Color(a.backdropArgb)
            val bitmap = a.bitmap
            val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
            when (a.kind) {
                ArtKind.Banner -> {
                    val fills = abs(ratio / (16f / 9f) - 1f) <= UiTokens.bannerAspectTolerance
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(backdrop)
                            .graphicsLayer { this.alpha = alpha.value },
                    ) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = if (fills) ContentScale.Crop else ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                ArtKind.Icon -> {
                    val brush = remember(a.backdropArgb) { tintedBrush(backdrop) }
                    Box(Modifier.fillMaxSize().background(brush)) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(y = (-8).dp)
                                .size(UiTokens.tileIcon)
                                .graphicsLayer { this.alpha = alpha.value },
                        )
                        TileLabel(entry.label, Modifier.align(Alignment.BottomStart))
                    }
                }
            }
        }
    }
}

@Composable
private fun MonogramTile(entry: AppEntry, window: Color, ink: Color, type: io.github.jameskbb.hearth.ui.theme.HearthType) {
    val brush = remember(window, ink) { neutralBrush(window, ink) }
    val letter = remember(entry.label) {
        entry.label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    }
    Box(Modifier.size(Dimens.tileWidth, Dimens.tileHeight).background(brush)) {
        BasicText(
            letter,
            style = type.monogram.copy(color = Color.White.copy(alpha = 0.22f)),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp),
        )
        TileLabel(entry.label, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun TileLabel(label: String, modifier: Modifier) {
    BasicText(
        label,
        style = Hearth.type.tileLabel.copy(color = Color.White),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
    )
}

/** A surface tinted from the artwork's dominant color, darkened toward the corner like the concept. */
private fun tintedBrush(base: Color): Brush {
    val top = lerp(base, Color.Black, 0.25f)
    val bottom = lerp(base, Color.Black, 0.62f)
    return Brush.linearGradient(listOf(top, bottom), start = Offset.Zero, end = Offset.Infinite)
}

/** A neutral surface derived from the wallpaper palette, for tiles with no artwork. */
private fun neutralBrush(window: Color, ink: Color): Brush {
    val top = lerp(window, ink, 0.16f)
    val bottom = lerp(window, ink, 0.05f)
    return Brush.linearGradient(listOf(top, bottom), start = Offset.Zero, end = Offset.Infinite)
}
