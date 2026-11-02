package io.github.jameskbb.hearth.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** One user-requested operation at a time; late completion can never replace a newer state. */
class GitHubUpdateService(
    private val directory: File,
    private val currentVersionCode: Long,
    private val sdkInt: Int,
    private val scope: CoroutineScope,
    private val api: UpdateApi = GitHubUpdateApi(),
) : UpdateService {
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private val lock = Any()
    private var generation = 0L
    private var job: Job? = null

    override fun check() = synchronized(lock) {
        if (mutableState.value is UpdateState.Checking || mutableState.value is UpdateState.Downloading) return@synchronized
        val token = ++generation
        mutableState.value = UpdateState.Checking
        start(token) {
            withContext(Dispatchers.IO) {
                // Also removes abandoned files from a process killed during a prior download.
                synchronized(lock) {
                    if (token == generation) {
                        directory.listFiles()?.filter { it.name.startsWith("hearth-") && it.name.endsWith(".apk") }
                            ?.forEach { it.delete() }
                    }
                }
            }
            val release = api.latestRelease()
            publish(token, when {
                release == null -> UpdateState.NoRelease
                release.versionCode <= currentVersionCode -> UpdateState.UpToDate
                release.minSdk > sdkInt -> UpdateState.Failed(UpdateFailure.Incompatible)
                else -> UpdateState.Available(release)
            })
        }
    }

    override fun download() = synchronized(lock) {
        val release = (mutableState.value as? UpdateState.Available)?.release ?: return@synchronized
        val token = ++generation
        mutableState.value = UpdateState.Downloading(release, null)
        start(token) {
            withContext(Dispatchers.IO) {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update cache")
                // Each operation owns its file. A canceled HTTP read can finish after a new check.
                val file = File.createTempFile("hearth-", ".apk", directory)
                var retained = false
                try {
                    withTimeout(5 * 60_000L) {
                        api.download(release, file) { progress ->
                            publish(token, UpdateState.Downloading(release, progress?.coerceIn(0, 100)))
                        }
                        verify(file, release)
                    }
                    coroutineContext.ensureActive()
                    retained = publish(token, UpdateState.Ready(release, file))
                } finally {
                    if (!retained) file.delete()
                }
            }
        }
    }

    override fun cancel() = synchronized(lock) {
        ++generation
        job?.cancel()
        job = null
        val previous = mutableState.value as? UpdateState.Ready
        mutableState.value = UpdateState.Idle
        if (previous != null) scope.launch(Dispatchers.IO) { previous.file.delete() }
    }

    private fun start(token: Long, action: suspend () -> Unit) {
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                action()
            } catch (_: TimeoutCancellationException) {
                publish(token, UpdateState.Failed(UpdateFailure.Download))
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                publish(token, UpdateState.Failed(e.reason))
            } catch (_: IOException) {
                publish(token, UpdateState.Failed(UpdateFailure.Download))
            } catch (_: Exception) {
                publish(token, UpdateState.Failed(UpdateFailure.InvalidRelease))
            }
        }.also { it.start() }
    }

    private fun publish(token: Long, state: UpdateState): Boolean = synchronized(lock) {
        if (token != generation) false else {
            mutableState.value = state
            true
        }
    }

    private suspend fun verify(file: File, release: UpdateRelease) {
        if (file.length() != release.sizeBytes) throw UpdateException(UpdateFailure.Verification)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (!hex.equals(release.sha256, ignoreCase = true)) throw UpdateException(UpdateFailure.Verification)
    }
}
