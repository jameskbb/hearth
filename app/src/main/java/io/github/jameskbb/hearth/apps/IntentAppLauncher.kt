package io.github.jameskbb.hearth.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * The platform operations [IntentAppLauncher] needs, behind an interface so the result
 * mapping is unit-tested without a device.
 */
internal interface LaunchPlatform {
    /** Whether the package's application is enabled; null when it is not installed. */
    fun isAppEnabled(packageName: String): Boolean?

    /** The launcher category [key] is listed under (TV first), or null if neither. */
    fun launchCategory(key: AppKey): String?

    /** Whether the activity still exists in the installed package. */
    fun activityExists(key: AppKey): Boolean

    /** Starts [key] explicitly with ACTION_MAIN and [category]. Throws like startActivity. */
    fun start(key: AppKey, category: String)
}

/**
 * [AppLauncher] that starts the app's own activity explicitly, in its own task.
 *
 * The intent matches what the platform launcher sends (ACTION_MAIN plus the category the
 * activity is listed under, NEW_TASK and RESET_TASK_IF_NEEDED), so an app already running
 * comes back to where the user left it instead of starting a second copy.
 *
 * Results: not installed, or its activity is gone, is [LaunchResult.NotInstalled]; a
 * disabled app is `Failed("disabled")`; anything the platform refuses is [LaunchResult.Failed]
 * with the platform's message. Never throws.
 */
class IntentAppLauncher internal constructor(private val platform: LaunchPlatform) : AppLauncher {

    constructor(context: Context) : this(AndroidLaunchPlatform(context.applicationContext))

    override fun launch(key: AppKey): LaunchResult = try {
        launchOrThrow(key)
    } catch (e: Exception) {
        Log.w(TAG, "Launch failed for ${key.id}", e)
        LaunchResult.Failed(e.message ?: e.javaClass.simpleName)
    }

    private fun launchOrThrow(key: AppKey): LaunchResult {
        val enabled = platform.isAppEnabled(key.packageName) ?: return LaunchResult.NotInstalled
        if (!enabled) return LaunchResult.Failed(REASON_DISABLED)
        val category = platform.launchCategory(key)
            ?: if (platform.activityExists(key)) LaunchCategories.OTHER else return LaunchResult.NotInstalled
        return try {
            platform.start(key, category)
            LaunchResult.Launched
        } catch (e: ActivityNotFoundException) {
            // Uninstalled or updated without that activity between the checks and the start.
            if (platform.isAppEnabled(key.packageName) == null || !platform.activityExists(key)) {
                LaunchResult.NotInstalled
            } else {
                LaunchResult.Failed(e.message ?: "activity not found")
            }
        } catch (e: SecurityException) {
            LaunchResult.Failed(e.message ?: "permission denied")
        }
    }

    internal companion object {
        const val TAG = "HearthLauncher"
        const val REASON_DISABLED = "disabled"
    }
}

private class AndroidLaunchPlatform(private val context: Context) : LaunchPlatform {
    private val pm: PackageManager = context.packageManager

    override fun isAppEnabled(packageName: String): Boolean? = try {
        pm.applicationInfo(packageName).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    override fun launchCategory(key: AppKey): String? =
        listOf(LaunchCategories.TV, LaunchCategories.OTHER).firstOrNull { category ->
            pm.queryActivities(LaunchCategories.mainIntent(category).setPackage(key.packageName))
                .any { it.activityInfo?.name == key.activityName }
        }

    override fun activityExists(key: AppKey): Boolean = try {
        pm.activityInfo(key.component)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun start(key: AppKey, category: String) {
        val intent = LaunchCategories.mainIntent(category)
            .setComponent(key.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
    }
}
