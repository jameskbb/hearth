package io.github.jameskbb.hearth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import io.github.jameskbb.hearth.ui.HearthRoot
import io.github.jameskbb.hearth.ui.HearthViewModel
import io.github.jameskbb.hearth.system.registerClockChanges

/**
 * Single activity. Back never finishes it (leaving would reveal the system launcher): the view
 * model decides what Back means on each screen. A HOME intent returns to the Home root.
 */
class MainActivity : ComponentActivity() {
    private val vm: HearthViewModel by viewModels { HearthViewModel.Factory }
    private var clockReceiverRegistered = false
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            vm.onClockChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as HearthApp).container
        onBackPressedDispatcher.addCallback(this) { vm.onBack() }
        setContent { HearthRoot(vm, container.artwork) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Home button, the redirect app, or a relaunch from the launcher: back to the Home root,
        // with the last focused tile restored.
        if (intent.action == Intent.ACTION_MAIN || intent.hasCategory(Intent.CATEGORY_HOME)) {
            vm.onHomeIntent()
        }
    }

    // Weather refreshes only while Hearth is in front; see WeatherRepository.
    override fun onStart() {
        super.onStart()
        registerClockReceiver()
        vm.onForeground(true)
    }

    override fun onStop() {
        super.onStop()
        vm.onForeground(false)
        if (clockReceiverRegistered) {
            unregisterReceiver(clockReceiver)
            clockReceiverRegistered = false
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    /** Reschedules the single theme-boundary delay after clock, date, or timezone changes. */
    private fun registerClockReceiver() {
        if (clockReceiverRegistered) return
        registerClockChanges(clockReceiver)
        clockReceiverRegistered = true
    }
}
