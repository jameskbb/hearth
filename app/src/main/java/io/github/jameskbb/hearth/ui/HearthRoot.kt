package io.github.jameskbb.hearth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.jameskbb.hearth.apps.ArtworkLoader
import io.github.jameskbb.hearth.ui.allapps.AllAppsScreen
import io.github.jameskbb.hearth.ui.components.OptionsCallbacks
import io.github.jameskbb.hearth.ui.components.OptionsDialog
import io.github.jameskbb.hearth.ui.components.ThemeBackdrop
import io.github.jameskbb.hearth.ui.home.HomeScreen
import io.github.jameskbb.hearth.ui.settings.SettingsScreen
import io.github.jameskbb.hearth.ui.state.Screen
import io.github.jameskbb.hearth.ui.theme.HearthTheme

/** The whole UI: the theme's palette and art, the current screen, the dialog. */
@Composable
fun HearthRoot(vm: HearthViewModel, artwork: ArtworkLoader) {
    val reducedMotion by vm.reducedMotion.collectAsState()
    val foreground by vm.foreground.collectAsState()
    // Until the theme is known and decoded, only the window color shows, never a wrong image.
    val palette by vm.activePalette.collectAsState()
    val theme by vm.activeTheme.collectAsState()

    HearthTheme(palette, reducedMotion) {
        Box(Modifier.fillMaxSize().background(palette.window)) {
            // The art moves only while Hearth is in front and Reduce motion is off.
            ThemeBackdrop(theme, animate = foreground && !reducedMotion)

            // Hoisted so the favorites row keeps its scroll position across screens and launches.
            val homeListState = rememberLazyListState()
            val screen by vm.screen.collectAsState()
            when (screen) {
                Screen.Home -> HomeScreen(vm, artwork, homeListState)
                Screen.AllApps -> AllAppsScreen(vm, artwork)
                Screen.Settings -> SettingsScreen(vm)
            }

            val options by vm.options.collectAsState()
            val callbacks = remember(vm) {
                OptionsCallbacks(
                    onOpen = vm::optionOpen,
                    onToggleFavorite = vm::optionToggleFavorite,
                    onMove = vm::optionMove,
                    onHide = vm::optionHide,
                    onDismiss = vm::closeOptions,
                )
            }
            options?.let { OptionsDialog(it, callbacks) }
        }
    }
}
