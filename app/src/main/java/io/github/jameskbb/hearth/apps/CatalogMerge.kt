package io.github.jameskbb.hearth.apps

/** One activity returned by a launcher-category query, before merging. */
internal data class Launchable(
    val packageName: String,
    val activityName: String,
    val label: String,
    val lastUpdateTime: Long,
) {
    val key: AppKey get() = AppKey.of(packageName, activityName)
}

/**
 * Turns the two launcher-category query results into the catalog list. Pure, so the rules
 * are unit-tested on the JVM:
 * - An activity in the LEANBACK_LAUNCHER results is a TV app, even if it is also a
 *   LAUNCHER activity (merged by [AppKey]; it appears once).
 * - A LAUNCHER-only activity of a package that also has a TV activity is dropped, so an app
 *   with separate phone and TV entry points is listed once, as the TV app it ships for TV.
 * - Several activities of one package with the same label are one app: the first, in query
 *   order, is kept. Some TV apps export helper screens to the launcher category under the app's
 *   own name (one AirPlay receiver lists three), which would otherwise show as identical tiles.
 *   Activities with different labels are separate entry points and are all listed.
 * - [ownPackage] (Hearth) is excluded.
 * - A blank label falls back to the package name.
 * - Sorted by label with [labelOrder] (a locale Collator in production), then by key so
 *   the order is stable for equal labels.
 */
internal object CatalogMerge {
    fun merge(
        tv: List<Launchable>,
        other: List<Launchable>,
        ownPackage: String,
        labelOrder: Comparator<String>,
    ): List<AppEntry> {
        val byKey = LinkedHashMap<AppKey, AppEntry>()
        val seenLabels = HashSet<Pair<String, String>>()
        fun add(l: Launchable, isTvApp: Boolean) {
            if (l.key in byKey) return
            val entry = l.toEntry(isTvApp)
            if (!seenLabels.add(l.packageName to entry.label)) return
            byKey[l.key] = entry
        }
        for (l in tv) {
            if (l.packageName == ownPackage) continue
            add(l, isTvApp = true)
        }
        val tvPackages = byKey.keys.mapTo(HashSet()) { it.packageName }
        for (l in other) {
            if (l.packageName == ownPackage || l.packageName in tvPackages) continue
            add(l, isTvApp = false)
        }
        return byKey.values.sortedWith(
            compareBy<AppEntry, String>(labelOrder) { it.label }.thenBy { it.key.id },
        )
    }

    private fun Launchable.toEntry(isTvApp: Boolean) = AppEntry(
        key = key,
        label = label.trim().ifEmpty { packageName },
        isTvApp = isTvApp,
        lastUpdateTime = lastUpdateTime,
    )
}
