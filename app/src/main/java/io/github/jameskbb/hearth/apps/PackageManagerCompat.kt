package io.github.jameskbb.hearth.apps

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

/*
 * Package queries and package/application getters use typed flags on API 33+.
 * Activity metadata keeps the stable integer-flags API. Each getter preserves
 * the platform's NameNotFoundException.
 */

internal fun PackageManager.queryActivities(intent: Intent): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= 33) {
        queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, 0)
    }

internal fun PackageManager.packageInfo(packageName: String): PackageInfo =
    if (Build.VERSION.SDK_INT >= 33) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, 0)
    }

internal fun PackageManager.applicationInfo(packageName: String): ApplicationInfo =
    if (Build.VERSION.SDK_INT >= 33) {
        getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getApplicationInfo(packageName, 0)
    }

// Zero flags need no new flag type. This long-standing overload also works with
// PackageManager implementations that do not implement the newer activity-info bridge.
@Suppress("DEPRECATION")
internal fun PackageManager.activityInfo(component: ComponentName): ActivityInfo = getActivityInfo(component, 0)

internal val AppKey.component: ComponentName get() = ComponentName(packageName, activityName)

/** The two launcher categories Hearth lists. */
internal object LaunchCategories {
    const val TV = Intent.CATEGORY_LEANBACK_LAUNCHER
    const val OTHER = Intent.CATEGORY_LAUNCHER

    fun mainIntent(category: String): Intent = Intent(Intent.ACTION_MAIN).addCategory(category)
}
