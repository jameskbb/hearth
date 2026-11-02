package io.github.jameskbb.hearth.updates

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Manual updates only. Constructing or observing the service never starts a request. */
interface UpdateService {
    val state: StateFlow<UpdateState>
    fun check()
    fun download()
    fun cancel()
}

data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val minSdk: Int,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object NoRelease : UpdateState
    data class Available(val release: UpdateRelease) : UpdateState
    data class Downloading(val release: UpdateRelease, val percent: Int?) : UpdateState
    /** Bytes have the release's exact size and digest. The installer also checks APK identity. */
    data class Ready(val release: UpdateRelease, val file: File) : UpdateState
    data class Failed(val reason: UpdateFailure) : UpdateState
}

enum class UpdateFailure { Network, RateLimited, InvalidRelease, Incompatible, Download, Verification }

class UpdateException(val reason: UpdateFailure, cause: Throwable? = null) : Exception(cause)

interface UpdateApi {
    /** Null means GitHub has no published stable release. */
    suspend fun latestRelease(): UpdateRelease?
    suspend fun download(release: UpdateRelease, destination: File, progress: (Int?) -> Unit)
}
