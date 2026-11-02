package io.github.jameskbb.hearth.updates

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/** Unauthenticated GitHub release reads. No cookies, credentials or installation identifiers. */
class GitHubUpdateApi(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Int = 15_000,
    private val connectionFactory: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : UpdateApi {
    override suspend fun latestRelease(): UpdateRelease? = withContext(io) {
        try {
            withTimeout(60_000) {
                val releaseBody = getText(UpdateManifest.RELEASE_URL, UpdateManifest.MAX_RELEASE_BYTES, noReleaseOn404 = true)
                    ?: return@withTimeout null
                val assets = UpdateManifest.parseAssets(releaseBody)
                val manifest = getText(assets.manifestUrl, UpdateManifest.MAX_MANIFEST_BYTES)
                    ?: throw UpdateException(UpdateFailure.InvalidRelease)
                UpdateManifest.parseRelease(manifest, assets)
            }
        } catch (e: TimeoutCancellationException) {
            throw UpdateException(UpdateFailure.Network, e)
        }
    }

    override suspend fun download(release: UpdateRelease, destination: File, progress: (Int?) -> Unit) = withContext(io) {
        require(release.sizeBytes in 1..UpdateManifest.MAX_APK_BYTES)
        val connection = connect(release.apkUrl, "application/octet-stream")
        try {
            requireSuccess(connection)
            val declared = connection.getHeaderField("Content-Length")?.toLongOrNull()
            if (declared != null && declared != release.sizeBytes) throw UpdateException(UpdateFailure.Verification)
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    var lastProgress = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > release.sizeBytes) throw UpdateException(UpdateFailure.Verification)
                        output.write(buffer, 0, count)
                        val percent = (total * 100 / release.sizeBytes).toInt()
                        if (percent != lastProgress) {
                            progress(percent)
                            lastProgress = percent
                        }
                    }
                    if (total != release.sizeBytes) throw UpdateException(UpdateFailure.Verification)
                }
            }
        } catch (e: IOException) {
            throw UpdateException(UpdateFailure.Download, e)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun getText(url: String, limit: Int, noReleaseOn404: Boolean = false): String? {
        val connection = connect(url, if (url == UpdateManifest.RELEASE_URL) "application/vnd.github+json" else "application/json")
        try {
            if (noReleaseOn404 && connection.responseCode == 404) return null
            requireSuccess(connection)
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (output.size() > limit) throw UpdateException(UpdateFailure.InvalidRelease)
                }
                return String(output.toByteArray(), Charsets.UTF_8)
            }
        } catch (e: IOException) {
            throw UpdateException(UpdateFailure.Network, e)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun connect(initial: String, accept: String): HttpsURLConnection {
        var url = initial
        repeat(5) { hop ->
            coroutineContext.ensureActive()
            if (!UpdateUrlPolicy.allowed(url)) throw UpdateException(UpdateFailure.InvalidRelease)
            val connection = try {
                connectionFactory(URL(url)).apply {
                    connectTimeout = timeoutMillis
                    readTimeout = timeoutMillis
                    useCaches = false
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", accept)
                    setRequestProperty("User-Agent", "Hearth-Updater")
                    setRequestProperty("Accept-Encoding", "identity")
                }
            } catch (e: IOException) {
                throw UpdateException(UpdateFailure.Network, e)
            }
            var returned = false
            try {
                if (connection.responseCode !in setOf(301, 302, 303, 307, 308)) {
                    returned = true
                    return connection
                }
                val location = connection.getHeaderField("Location")
                    ?: throw UpdateException(UpdateFailure.InvalidRelease)
                if (hop == 4) throw UpdateException(UpdateFailure.InvalidRelease)
                url = URL(URL(url), location).toExternalForm()
            } catch (e: IOException) {
                throw UpdateException(UpdateFailure.Network, e)
            } finally {
                // A returned connection belongs to the caller, including error responses.
                if (!returned) connection.disconnect()
            }
        }
        throw UpdateException(UpdateFailure.InvalidRelease)
    }

    private fun requireSuccess(connection: HttpsURLConnection) {
        when (connection.responseCode) {
            200 -> Unit
            403, 429 -> throw UpdateException(UpdateFailure.RateLimited)
            else -> throw UpdateException(UpdateFailure.Network)
        }
    }
}
