package io.github.jameskbb.hearth.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.jameskbb.hearth.R
import io.github.jameskbb.hearth.apps.LaunchResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The platform calls [AndroidTvInputs] needs, behind an interface so its rules are unit-tested. */
internal interface TvInputPlatform {
    /** Every input the framework lists, or null when there is no TV input service. Throws like the framework. */
    fun inputs(): List<RawTvInput>?

    /** Whether the passthrough ACTION_VIEW intent for [id] resolves to an activity. */
    fun canView(id: String): Boolean

    /** Starts the passthrough ACTION_VIEW intent for [id]. Throws like startActivity. */
    fun view(id: String)

    /** Calls [onChange] whenever an input is added, removed or updated. */
    fun watch(onChange: () -> Unit)

    fun numbered(port: Int): String
}

/**
 * [TvInputs] through TvInputManager and the documented passthrough intent
 * (`ACTION_VIEW` on `TvContract.buildChannelUriForPassthroughInput`), which on a Fire OS TV
 * resolves to the platform's Live TV player. Amazon's private input routes are not used.
 *
 * Reads run on [io], one at a time; every framework failure reads as "no inputs" rather than an
 * error, so the feature simply does not appear. [switchTo] maps failures to [LaunchResult.Failed]
 * and never throws.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidTvInputs internal constructor(
    private val platform: TvInputPlatform,
    private val scope: CoroutineScope,
    /** Where reads run; one at a time, so a slow read never overwrites a newer one. */
    private val reads: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : TvInputs {

    constructor(context: Context, scope: CoroutineScope) : this(AndroidTvInputPlatform(context.applicationContext), scope)

    private val _inputs = MutableStateFlow<List<TvInput>>(emptyList())
    override val inputs: StateFlow<List<TvInput>> = _inputs.asStateFlow()

    /** Starts watching for input changes and reads the inputs once. Call once. */
    fun start() {
        try {
            platform.watch(::refresh)
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch TV inputs", e)
        }
        refresh()
    }

    override fun refresh() {
        scope.launch(reads) { _inputs.value = read() }
    }

    internal fun read(): List<TvInput> = try {
        val raw = platform.inputs().orEmpty()
        TvInputListing.list(raw, platform::canView, platform::numbered)
    } catch (e: Exception) {
        Log.w(TAG, "Could not list TV inputs", e)
        emptyList()
    }

    override fun switchTo(id: String): LaunchResult = try {
        platform.view(id)
        LaunchResult.Launched
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No player for input $id", e)
        refresh()
        LaunchResult.Failed(e.message ?: "activity not found")
    } catch (e: Exception) {
        // SecurityException or a platform failure.
        Log.w(TAG, "Switch to input $id failed", e)
        LaunchResult.Failed(e.message ?: e.javaClass.simpleName)
    }

    private companion object {
        const val TAG = "HearthInputs"
    }
}

internal class AndroidTvInputPlatform(private val context: Context) : TvInputPlatform {
    private val manager: TvInputManager? = try {
        context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
    } catch (_: Exception) {
        null
    }

    override fun inputs(): List<RawTvInput>? = manager?.tvInputList?.map { it.toRaw() }

    private fun TvInputInfo.toRaw() = RawTvInput(
        id = id,
        isHdmi = type == TvInputInfo.TYPE_HDMI,
        parentId = parentId,
        customLabel = if (Build.VERSION.SDK_INT >= 24) {
            runCatching { loadCustomLabel(context)?.toString() }.getOrNull()
        } else {
            null
        },
        label = runCatching { loadLabel(context)?.toString() }.getOrNull(),
    )

    override fun canView(id: String): Boolean {
        val intent = viewIntent(id)
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return info?.activityInfo != null
    }

    override fun view(id: String) = context.startActivity(viewIntent(id))

    override fun watch(onChange: () -> Unit) {
        val m = manager ?: return
        m.registerCallback(
            object : TvInputManager.TvInputCallback() {
                override fun onInputAdded(inputId: String) = onChange()
                override fun onInputRemoved(inputId: String) = onChange()
                override fun onInputUpdated(inputId: String) = onChange()
                override fun onTvInputInfoUpdated(inputInfo: TvInputInfo) = onChange()
            },
            Handler(Looper.getMainLooper()),
        )
    }

    override fun numbered(port: Int): String = context.getString(R.string.input_hdmi_numbered, port)

    private fun viewIntent(id: String) =
        Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
