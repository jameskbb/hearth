package io.github.jameskbb.hearth.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * [SystemRoutes] through documented platform intents only.
 *
 * [SystemRoute.Settings] uses Settings.ACTION_SETTINGS. On Fire OS the settings app is
 * Amazon's own package, and ACTION_SETTINGS is its supported entry point, so no vendor
 * component names are hard-coded. A route is offered only when the intent resolves, and
 * the resolution is cached, so [isAvailable] is cheap to call from composition.
 *
 * On API 30+ resolving an implicit intent needs package visibility: the manifest must list
 * the `android.settings.SETTINGS` action under `<queries>`, or the route reads as
 * unavailable even though it exists.
 */
class AndroidSystemRoutes(context: Context) : SystemRoutes {
    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager
    private val available = ConcurrentHashMap<SystemRoute, Boolean>()

    override fun isAvailable(route: SystemRoute): Boolean =
        available.getOrPut(route) { resolves(intentFor(route)) }

    override fun open(route: SystemRoute): Boolean {
        if (!isAvailable(route)) return false
        return try {
            appContext.startActivity(intentFor(route))
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Route $route no longer resolves", e)
            available[route] = false // stop offering it
            false
        } catch (e: Exception) {
            // SecurityException or a platform failure: report it, keep offering the route.
            Log.w(TAG, "Route $route refused", e)
            false
        }
    }

    private fun intentFor(route: SystemRoute): Intent = when (route) {
        SystemRoute.Settings -> Intent(Settings.ACTION_SETTINGS)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun resolves(intent: Intent): Boolean = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        info?.activityInfo != null
    } catch (e: Exception) {
        Log.w(TAG, "Could not resolve ${intent.action}", e)
        false
    }

    private companion object {
        const val TAG = "HearthRoutes"
    }
}
