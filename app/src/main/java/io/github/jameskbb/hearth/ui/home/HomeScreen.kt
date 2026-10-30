package io.github.jameskbb.hearth.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jameskbb.hearth.R
import io.github.jameskbb.hearth.apps.ArtworkLoader
import io.github.jameskbb.hearth.ui.HearthViewModel
import io.github.jameskbb.hearth.ui.InlineMessage
import io.github.jameskbb.hearth.ui.components.AppTile
import io.github.jameskbb.hearth.prefs.HomeLabel
import io.github.jameskbb.hearth.ui.components.FocusRegistry
import io.github.jameskbb.hearth.ui.components.LeadBringIntoViewSpec
import io.github.jameskbb.hearth.ui.components.TextAction
import io.github.jameskbb.hearth.ui.components.TileActions
import io.github.jameskbb.hearth.ui.components.focusTarget
import io.github.jameskbb.hearth.ui.components.messageText
import io.github.jameskbb.hearth.ui.components.reveal
import io.github.jameskbb.hearth.ui.components.temperatureUnit
import io.github.jameskbb.hearth.ui.components.weatherAside
import io.github.jameskbb.hearth.ui.state.ArrangeState
import io.github.jameskbb.hearth.ui.state.FocusTarget
import io.github.jameskbb.hearth.ui.state.HomeAction
import io.github.jameskbb.hearth.ui.state.Screen
import io.github.jameskbb.hearth.ui.state.UiLogic
import io.github.jameskbb.hearth.ui.theme.Dimens
import io.github.jameskbb.hearth.ui.theme.Hearth
import io.github.jameskbb.hearth.ui.theme.UiTokens
import kotlinx.coroutines.launch

/**
 * Home: clock top left, the art unobstructed above, and the dock at the bottom (focused app
 * name and hint, the favorites row, the action row), all on the 48 dp left margin.
 */
@Composable
fun HomeScreen(vm: HearthViewModel, artwork: ArtworkLoader, listState: LazyListState) {
    val registry = remember { FocusRegistry() }
    val home by vm.home.collectAsState()
    val arrange by vm.arrange.collectAsState()
    val request by vm.focusRequest.collectAsState()
    val reducedMotion = Hearth.reducedMotion

    val favorites = home?.favorites.orEmpty()
    val canArrange = favorites.size >= 2
    val actions = remember(canArrange) {
        if (canArrange) listOf(HomeAction.AllApps, HomeAction.Arrange, HomeAction.Settings)
        else listOf(HomeAction.AllApps, HomeAction.Settings)
    }

    val density = LocalDensity.current
    val leadPx = with(density) { Dimens.marginH.toPx() }

    // Focus requests from the view model: resolve against the latest content (not a copy from an
    // earlier composition), scroll the target into view so it is composed, then focus it.
    val currentActions by rememberUpdatedState(actions)
    val ready = home != null
    LaunchedEffect(request, ready) {
        val r = request ?: return@LaunchedEffect
        val content = vm.home.value
        if (r.screen != Screen.Home || content == null) return@LaunchedEffect
        val keys = content.favorites.map { it.key }
        val target = UiLogic.resolveHomeFocus(r.target, keys, currentActions)
        if (target is FocusTarget.Tile) {
            listState.reveal(keys.indexOf(target.key), leadPx, leadPx, animate = false, keys.size, target.key.id)
        }
        registry.focus(target)
        vm.consumeFocus(r.seq)
    }

    // The Arrange action disappears when fewer than two favorites remain; never strand focus there.
    LaunchedEffect(actions) {
        val f = vm.homeFocus.value
        if (f is FocusTarget.Action && f.action !in actions) vm.requestFocus(Screen.Home, FocusTarget.Default)
    }

    // Keep the focused (or lifted) tile fully in view when the row changes under it: while a tile
    // moves in arrange mode, and when favorites appear or vanish before it (an app reinstalled,
    // re-enabled or removed). The lazy row keeps its first visible item in place, so without this
    // an insertion pushes the focused tile toward or past the trailing edge.
    val moving = arrange as? ArrangeState.Moving
    val favoriteKeys = remember(favorites) { favorites.map { it.key } }
    LaunchedEffect(moving?.key, favoriteKeys) {
        val key = moving?.key ?: (vm.homeFocus.value as? FocusTarget.Tile)?.key ?: return@LaunchedEffect
        val index = favoriteKeys.indexOf(key)
        if (index >= 0) listState.reveal(index, leadPx, leadPx, animate = !reducedMotion, favoriteKeys.size, key.id)
    }

    val tileActions = remember(vm) {
        TileActions(
            onClick = { vm.onTileClick(Screen.Home, it) },
            onOptions = { vm.openOptions(Screen.Home, it) },
            onFocused = { vm.onFocused(Screen.Home, FocusTarget.Tile(it.key)) },
        )
    }
    val scope = rememberCoroutineScope()
    val currentArrange by rememberUpdatedState(arrange)
    val selectGate = remember { BooleanArray(1) }

    Box(Modifier.fillMaxSize()) {
        val weather by vm.homeWeather.collectAsState()
        val prefs by vm.prefs.collectAsState()
        val unit = temperatureUnit(prefs)
        val aside = weather?.let { weatherAside(it, unit) }
        // Not focusable: the weather is a glance, never a stop for the D-pad.
        HomeHeader(
            label = HomeLabel.visible(prefs),
            aside = aside,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.marginH).padding(top = Dimens.marginV),
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(bottom = Dimens.marginV)
                // Arrange mode: Left/Right move the lifted tile, Select places it, Up/Down stay put.
                .onPreviewKeyEvent { event ->
                    if (currentArrange !is ArrangeState.Moving) return@onPreviewKeyEvent false
                    val down = event.type == KeyEventType.KeyDown
                    when (event.key) {
                        Key.DirectionLeft -> { if (down) vm.arrangeMove(-1); true }
                        Key.DirectionRight -> { if (down) vm.arrangeMove(1); true }
                        Key.DirectionUp, Key.DirectionDown -> true
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (down && event.nativeKeyEvent.repeatCount == 0) selectGate[0] = true
                            if (event.type == KeyEventType.KeyUp && selectGate[0]) {
                                selectGate[0] = false
                                vm.arrangePlace()
                            }
                            true
                        }
                        else -> false
                    }
                },
        ) {
            NameArea(
                vm,
                Modifier.padding(start = Dimens.marginH),
            )

            // No favorites: no empty band. The name area and its hint sit right above the actions.
            if (favorites.isNotEmpty()) {
                val spec = remember(leadPx, reducedMotion) { LeadBringIntoViewSpec(leadPx, leadPx, reducedMotion) }
                @OptIn(ExperimentalFoundationApi::class)
                CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                    LazyRow(
                        state = listState,
                        contentPadding = PaddingValues(
                            start = Dimens.marginH,
                            end = Dimens.marginH,
                            top = UiTokens.rowPaddingTop,
                            bottom = UiTokens.rowPaddingBottom,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.tileGap),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Dimens.tileHeight + UiTokens.rowPaddingTop + UiTokens.rowPaddingBottom),
                    ) {
                        items(favorites, key = { it.key.id }) { entry ->
                            AppTile(
                                entry = entry,
                                artwork = artwork,
                                actions = tileActions,
                                lifted = moving?.key == entry.key,
                                modifier = Modifier
                                    .animateItem(
                                        fadeInSpec = null,
                                        fadeOutSpec = null,
                                        placementSpec = if (reducedMotion) null
                                        else tween(UiTokens.motionMillis, easing = FastOutSlowInEasing),
                                    )
                                    .focusTarget(registry, FocusTarget.Tile(entry.key))
                                    .onPreviewKeyEvent { event ->
                                        // Down from any tile lands on the first action: predictable.
                                        if (event.key == Key.DirectionDown && currentArrange !is ArrangeState.Moving) {
                                            if (event.type == KeyEventType.KeyDown) {
                                                scope.launch { registry.focus(FocusTarget.Action(HomeAction.AllApps)) }
                                            }
                                            true
                                        } else {
                                            false
                                        }
                                    },
                            )
                        }
                    }
                }
            } else {
                Box(Modifier.height(UiTokens.rowPaddingTop))
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(UiTokens.actionGap),
                modifier = Modifier.padding(start = Dimens.marginH),
            ) {
                for (action in actions) {
                    key(action) {
                        TextAction(
                            text = stringResource(action.label()),
                            onClick = { vm.onHomeAction(action) },
                            onFocused = { vm.onFocused(Screen.Home, FocusTarget.Action(action)) },
                            modifier = Modifier
                                .focusTarget(registry, FocusTarget.Action(action))
                                .onPreviewKeyEvent { event ->
                                    // Up returns to the favorite that last had focus.
                                    if (event.key == Key.DirectionUp && vm.favorites.value.isNotEmpty()) {
                                        if (event.type == KeyEventType.KeyDown) {
                                            val last = vm.lastHomeTile.value
                                            val target = last?.let { FocusTarget.Tile(it) } ?: FocusTarget.Default
                                            vm.requestFocus(Screen.Home, target)
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

private fun HomeAction.label() = when (this) {
    HomeAction.AllApps -> R.string.action_all_apps
    HomeAction.Arrange -> R.string.action_arrange
    HomeAction.Settings -> R.string.action_settings
}

private fun HomeAction.hint() = when (this) {
    HomeAction.AllApps -> R.string.home_hint_all_apps
    HomeAction.Arrange -> R.string.home_hint_arrange
    HomeAction.Settings -> R.string.home_hint_settings
}

/**
 * The focused app's name and the hint beneath it. Reads focus state itself so a focus move
 * recomposes only this, not the row.
 */
@Composable
private fun NameArea(vm: HearthViewModel, modifier: Modifier = Modifier) {
    val home by vm.home.collectAsState()
    val focus by vm.homeFocus.collectAsState()
    val lastTile by vm.lastHomeTile.collectAsState()
    val arrange by vm.arrange.collectAsState()
    val message by vm.message.collectAsState()
    val palette = Hearth.palette
    val type = Hearth.type

    val content = home
    val f = focus
    val name = when {
        content == null -> ""
        content.noApps -> stringResource(R.string.home_no_apps)
        content.favorites.isEmpty() -> stringResource(R.string.home_empty_favorites)
        // An action has focus: no app is selected, so name none. The line keeps its height.
        f is FocusTarget.Action -> ""
        else -> {
            val key = (f as? FocusTarget.Tile)?.key ?: lastTile
            (content.favorites.firstOrNull { it.key == key } ?: content.favorites.first()).label
        }
    }
    val m = message?.takeIf { it.screen == Screen.Home }
    val hint = when {
        m != null -> messageText(m)
        arrange is ArrangeState.Moving -> stringResource(R.string.arrange_move_hint)
        arrange == ArrangeState.Picking -> stringResource(R.string.arrange_pick_hint)
        content != null && !content.noApps && content.favorites.isEmpty() -> stringResource(R.string.home_empty_hint)
        f is FocusTarget.Tile -> stringResource(R.string.home_hint_options)
        f is FocusTarget.Action && content?.noApps != true -> stringResource(f.action.hint())
        else -> ""
    }

    Column(modifier) {
        BasicText(
            name,
            style = type.focusedName.copy(color = palette.ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = Dimens.marginH),
        )
        BasicText(
            hint,
            style = type.hint.copy(color = if (m != null) palette.ink else palette.inkMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, end = Dimens.marginH),
        )
    }
}
