package io.github.jameskbb.hearth.ui.allapps

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jameskbb.hearth.R
import io.github.jameskbb.hearth.apps.AppEntry
import io.github.jameskbb.hearth.apps.ArtworkLoader
import io.github.jameskbb.hearth.ui.AllAppsContent
import io.github.jameskbb.hearth.ui.HearthViewModel
import io.github.jameskbb.hearth.ui.components.AppTile
import io.github.jameskbb.hearth.ui.components.FocusRegistry
import io.github.jameskbb.hearth.ui.components.LeadBringIntoViewSpec
import io.github.jameskbb.hearth.ui.components.TileActions
import io.github.jameskbb.hearth.ui.components.focusTarget
import io.github.jameskbb.hearth.ui.components.messageText
import io.github.jameskbb.hearth.ui.components.reveal
import io.github.jameskbb.hearth.ui.state.FocusTarget
import io.github.jameskbb.hearth.ui.state.Screen
import io.github.jameskbb.hearth.ui.state.UiLogic
import io.github.jameskbb.hearth.ui.theme.Dimens
import io.github.jameskbb.hearth.ui.theme.Hearth
import io.github.jameskbb.hearth.ui.theme.UiTokens

/** All apps: a 4-column grid, the TV's inputs, TV apps, then other apps, hidden entries excluded. */
@Composable
fun AllAppsScreen(vm: HearthViewModel, artwork: ArtworkLoader) {
    val registry = remember { FocusRegistry() }
    val content by vm.allApps.collectAsState()
    val request by vm.focusRequest.collectAsState()
    val message by vm.message.collectAsState()
    val gridState = rememberLazyGridState()
    val palette = Hearth.palette
    val type = Hearth.type
    val reducedMotion = Hearth.reducedMotion

    LaunchedEffect(request, content.loaded) {
        val r = request ?: return@LaunchedEffect
        val c = vm.allApps.value
        if (r.screen != Screen.AllApps || !c.loaded) return@LaunchedEffect
        val ordered = c.ordered
        val target = UiLogic.resolveListFocus(r.target, ordered.map { FocusTarget.Tile(it.key) })
        if (target is FocusTarget.Tile) {
            val index = gridIndexOf(c, target)
            if (index >= 0) gridState.reveal(index)
            registry.focus(target)
        }
        vm.consumeFocus(r.seq)
    }

    val tileActions = remember(vm) {
        TileActions(
            onClick = { vm.onTileClick(Screen.AllApps, it) },
            onOptions = { vm.openOptions(Screen.AllApps, it) },
            onFocused = { vm.onFocused(Screen.AllApps, FocusTarget.Tile(it.key)) },
        )
    }

    val gridWidth = Dimens.tileWidth * UiTokens.gridColumns + Dimens.tileGap * (UiTokens.gridColumns - 1) +
        Dimens.marginH * 2
    val edgePx = with(LocalDensity.current) { 40.dp.toPx() }
    val spec = remember(edgePx, reducedMotion) { LeadBringIntoViewSpec(edgePx, edgePx, reducedMotion) }

    var gridFocused by remember { mutableStateOf(false) }

    // Type sits directly on the scene; only the glyphs carry their own contrast treatment.
    Column(Modifier.fillMaxSize().padding(top = Dimens.marginV)) {
        Column(Modifier.padding(start = Dimens.marginH)) {
            BasicText(stringResource(R.string.all_apps_title), style = type.title.copy(color = palette.ink))
            val m = message?.takeIf { it.screen == Screen.AllApps }
            val (status, statusColor) = when {
                m != null -> messageText(m) to palette.ink
                content.loaded && content.ordered.isEmpty() -> stringResource(R.string.home_no_apps) to palette.ink
                gridFocused -> stringResource(R.string.all_apps_hint_options) to palette.inkMuted
                else -> "" to palette.inkMuted
            }
            BasicText(
                status,
                style = type.hint.copy(color = statusColor),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).widthIn(max = gridWidth - Dimens.marginH * 2),
            )
        }
        @OptIn(ExperimentalFoundationApi::class)
        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(UiTokens.gridColumns),
                state = gridState,
                contentPadding = PaddingValues(
                    start = Dimens.marginH,
                    end = Dimens.marginH,
                    // The first section label (10 dp of its own padding) starts just past the fade.
                    top = TopFade - 10.dp,
                    bottom = 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(Dimens.tileGap),
                verticalArrangement = Arrangement.spacedBy(Dimens.tileGap),
                modifier = Modifier
                    .width(gridWidth)
                    .weight(1f)
                    // The grid ends at the bottom safe margin; rows scrolling past either end
                    // fade out rather than being cut hard or running to the screen edge.
                    .padding(bottom = Dimens.marginV)
                    .fadeEdges(TopFade, BottomFade)
                    .onFocusChanged { gridFocused = it.hasFocus },
            ) {
                section(R.string.all_apps_inputs, content.inputs, artwork, tileActions, registry)
                section(R.string.all_apps_tv, content.tvApps, artwork, tileActions, registry)
                section(R.string.all_apps_other, content.otherApps, artwork, tileActions, registry)
            }
        }
    }
}

/**
 * Depth of the soft edges where rows scroll out of the grid. The top one is deep enough to
 * swallow a whole section label, so a half-scrolled label never sits under the hint line.
 */
private val TopFade = 28.dp
private val BottomFade = 16.dp

/** Fades the first [top] and last [bottom] of the content to transparent, so clipped rows dissolve. */
private fun Modifier.fadeEdges(top: Dp, bottom: Dp): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val t = (top.toPx() / size.height).coerceIn(0f, 0.5f)
        val b = (bottom.toPx() / size.height).coerceIn(0f, 0.5f)
        // At the top the first part of the depth is fully clear and the rest eases in, so the
        // sliver of a label scrolled past it is gone rather than ghosted.
        val mask = Brush.verticalGradient(
            0f to Color.Transparent,
            t * 0.45f to Color.Transparent,
            t * 0.8f to Color.Black.copy(alpha = 0.4f),
            t to Color.Black,
            1f - b to Color.Black,
            1f to Color.Transparent,
        )
        onDrawWithContent {
            drawContent()
            drawRect(mask, blendMode = BlendMode.DstIn)
        }
    }

private fun LazyGridScope.section(
    title: Int,
    apps: List<AppEntry>,
    artwork: ArtworkLoader,
    actions: TileActions,
    registry: FocusRegistry,
) {
    if (apps.isEmpty()) return
    item(key = "section:$title", span = { GridItemSpan(maxLineSpan) }, contentType = "section") {
        BasicText(
            stringResource(title),
            style = Hearth.type.section.copy(color = Hearth.palette.inkMuted),
            modifier = Modifier
                .padding(top = 10.dp)
                .wrapContentWidth(Alignment.Start),
        )
    }
    items(apps, key = { it.key.id }, contentType = { "tile" }) { entry ->
        AppTile(
            entry = entry,
            artwork = artwork,
            actions = actions,
            modifier = Modifier.focusTarget(registry, FocusTarget.Tile(entry.key)),
        )
    }
}

/** Index of a tile in the grid, counting the labels of the sections shown (empty ones are skipped). */
private fun gridIndexOf(content: AllAppsContent, target: FocusTarget.Tile): Int {
    var start = 0
    for (section in listOf(content.inputs, content.tvApps, content.otherApps)) {
        if (section.isEmpty()) continue
        val index = section.indexOfFirst { it.key == target.key }
        if (index >= 0) return start + 1 + index
        start += 1 + section.size
    }
    return -1
}
