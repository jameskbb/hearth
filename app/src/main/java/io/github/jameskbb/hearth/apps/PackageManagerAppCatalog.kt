package io.github.jameskbb.hearth.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/**
 * [AppCatalog] built from PackageManager launcher-category queries.
 *
 * Lists activities that answer ACTION_MAIN with CATEGORY_LEANBACK_LAUNCHER (TV apps) or
 * CATEGORY_LAUNCHER (other apps); see [CatalogMerge] for how the two are combined. Internal
 * components with neither category are never listed. Package visibility on API 30+ comes
 * from the manifest's `<queries>` for exactly these two intents.
 *
 * Stays current by itself: after [start], package broadcasts (added, removed, changed,
 * replaced, suspended, external storage) trigger a debounced rescan. The UI also calls
 * [refresh] on resume to catch anything missed while Hearth was stopped.
 */
class PackageManagerAppCatalog(
    context: Context,
    private val scope: CoroutineScope,
) : AppCatalog {
    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    override val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    override val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val scanLock = Mutex()
    private val changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var started = false

    /** Registers the package receiver and runs the first scan. Call once, from the container. */
    fun start() {
        if (started) return
        started = true
        registerPackageReceiver()
        scope.launch {
            // An app update sends several broadcasts in a row; scan once after they settle.
            changes.collectLatest {
                delay(CHANGE_DEBOUNCE_MS)
                refresh()
            }
        }
        scope.launch { refresh() }
    }

    /**
     * Rescans installed apps on Dispatchers.IO. Concurrent calls run one after another. A
     * failed scan keeps the previous list (and still marks the catalog loaded, so the UI
     * never waits forever).
     */
    override suspend fun refresh() {
        scanLock.withLock {
            try {
                val list = withContext(Dispatchers.IO) { scan() }
                _apps.value = list
                Log.i(TAG, "Scanned ${list.size} launchable apps (${list.count { it.isTvApp }} TV)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "App scan failed; keeping the previous list", e)
            } finally {
                _loaded.value = true
            }
        }
    }

    private fun scan(): List<AppEntry> {
        val updateTimes = HashMap<String, Long?>()
        fun lastUpdate(pkg: String): Long? = updateTimes.getOrPut(pkg) {
            try {
                pm.packageInfo(pkg).lastUpdateTime
            } catch (_: PackageManager.NameNotFoundException) {
                null // uninstalled mid-scan
            }
        }

        fun query(category: String): List<Launchable> =
            pm.queryActivities(LaunchCategories.mainIntent(category)).mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                val updated = lastUpdate(ai.packageName) ?: return@mapNotNull null
                Launchable(
                    packageName = ai.packageName,
                    activityName = ai.name,
                    label = ri.loadLabel(pm)?.toString().orEmpty(),
                    lastUpdateTime = updated,
                )
            }

        val collator = Collator.getInstance(Locale.getDefault()).apply {
            strength = Collator.SECONDARY // case-insensitive, accent-aware
        }
        return CatalogMerge.merge(
            tv = query(LaunchCategories.TV),
            other = query(LaunchCategories.OTHER),
            ownPackage = appContext.packageName,
            labelOrder = { x, y -> collator.compare(x, y) },
        )
    }

    private fun registerPackageReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                changes.tryEmit(Unit)
            }
        }
        val perPackage = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // These carry package lists in extras, not a package: URI, so no data scheme.
        val batched = IntentFilter().apply {
            if (Build.VERSION.SDK_INT >= 24) {
                addAction(Intent.ACTION_PACKAGES_SUSPENDED)
                addAction(Intent.ACTION_PACKAGES_UNSUSPENDED)
            }
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
            addAction(Intent.ACTION_LOCALE_CHANGED) // labels and sort order follow the locale
        }
        // All of these are protected broadcasts only the system can send, so no export flag
        // is needed (or wanted) on API 33+. Registered for the life of the process.
        appContext.registerReceiver(receiver, perPackage)
        appContext.registerReceiver(receiver, batched)
    }

    private companion object {
        const val TAG = "HearthCatalog"
        const val CHANGE_DEBOUNCE_MS = 300L
    }
}
