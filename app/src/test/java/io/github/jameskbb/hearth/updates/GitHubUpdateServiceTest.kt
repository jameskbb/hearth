package io.github.jameskbb.hearth.updates

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubUpdateServiceTest {
    @get:Rule val temp = TemporaryFolder()
    private val payload = "test signed APK placeholder".toByteArray()
    private val release = UpdateRelease(
        versionCode = 4,
        versionName = "0.2.0",
        apkUrl = "https://github.com/jameskbb/hearth/releases/download/v0.2.0/app-release.apk",
        sha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) },
        sizeBytes = payload.size.toLong(),
        minSdk = 21,
    )

    private open inner class FakeApi(var result: UpdateRelease? = release) : UpdateApi {
        var checks = 0
        var downloads = 0
        var bytes = payload
        override suspend fun latestRelease(): UpdateRelease? {
            checks++
            return result
        }
        override suspend fun download(release: UpdateRelease, destination: File, progress: (Int?) -> Unit) {
            downloads++
            destination.writeBytes(bytes)
            progress(100)
        }
    }

    @Test fun constructingAndObservingNeverUsesNetwork() = runTest {
        val api = FakeApi()
        val service = GitHubUpdateService(temp.newFolder(), 3, 21, this, api)
        runCurrent()
        assertEquals(UpdateState.Idle, service.state.value)
        assertEquals(0, api.checks)
        assertEquals(0, api.downloads)
        service.download()
        runCurrent()
        assertEquals(0, api.downloads)
    }

    @Test fun checkOffersNewerReleaseButDoesNotDownloadIt() = runTest {
        val api = FakeApi()
        val service = GitHubUpdateService(temp.newFolder(), 3, 21, this, api)
        service.check()
        assertEquals(UpdateState.Checking, service.state.value)
        assertEquals(UpdateState.Available(release), service.state.first { it is UpdateState.Available })
        assertEquals(1, api.checks)
        assertEquals(0, api.downloads)
    }

    @Test fun noPublishedReleaseIsDifferentFromAlreadyCurrent() = runTest {
        val api = FakeApi(null)
        val service = GitHubUpdateService(temp.newFolder(), 3, 21, this, api)
        service.check()
        service.state.first { it == UpdateState.NoRelease }
        api.result = release.copy(versionCode = 3)
        service.check()
        service.state.first { it == UpdateState.UpToDate }
        api.result = release.copy(versionCode = 2)
        service.check()
        service.state.first { it == UpdateState.UpToDate }
        assertEquals(0, api.downloads)
    }

    @Test fun blocksReleaseThatNeedsNewerAndroid() = runTest {
        val api = FakeApi(release.copy(minSdk = 28))
        val service = GitHubUpdateService(temp.newFolder(), 3, 25, this, api)
        service.check()
        assertEquals(
            UpdateState.Failed(UpdateFailure.Incompatible),
            service.state.first { it is UpdateState.Failed },
        )
        service.download()
        assertEquals(0, api.downloads)
    }

    @Test fun successfulDownloadHasExactSizeAndChecksum() = runTest {
        val directory = temp.newFolder()
        val api = FakeApi()
        val service = GitHubUpdateService(directory, 3, 21, this, api)
        service.check()
        service.state.first { it is UpdateState.Available }
        service.download()
        val state = service.state.first { it is UpdateState.Ready } as UpdateState.Ready
        assertTrue(payload.contentEquals(state.file.readBytes()))
        assertEquals(directory.canonicalFile, state.file.canonicalFile.parentFile)
        assertTrue(state.file.name.endsWith(".apk"))
        assertEquals(release, state.release)
        assertEquals(1, api.downloads)
        service.check()
        service.state.first { it is UpdateState.Available }
        assertFalse(state.file.exists())
    }

    @Test fun corruptedOrTruncatedDownloadIsDeletedAndCannotBecomeReady() = runTest {
        for (bytes in listOf("wrong".toByteArray(), ByteArray(payload.size) { 1 })) {
            val directory = temp.newFolder()
            val service = GitHubUpdateService(directory, 3, 21, this, FakeApi().apply { this.bytes = bytes })
            service.check()
            service.state.first { it is UpdateState.Available }
            service.download()
            assertEquals(
                UpdateState.Failed(UpdateFailure.Verification),
                service.state.first { it is UpdateState.Failed },
            )
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun downloadFailureDeletesPartialBytes() = runTest {
        val directory = temp.newFolder()
        val api = object : FakeApi() {
            override suspend fun download(release: UpdateRelease, destination: File, progress: (Int?) -> Unit) {
                destination.writeText("partial")
                throw UpdateException(UpdateFailure.Download)
            }
        }
        val service = GitHubUpdateService(directory, 3, 21, this, api)
        service.check()
        service.state.first { it is UpdateState.Available }
        service.download()
        assertEquals(
            UpdateState.Failed(UpdateFailure.Download),
            service.state.first { it is UpdateState.Failed },
        )
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun canceledDownloadDeletesPartialBytesAfterItsWorkerStops() = runTest {
        val directory = temp.newFolder()
        val downloadStarted = CompletableDeferred<Unit>()
        val owner = Job(coroutineContext[Job])
        val api = object : FakeApi() {
            override suspend fun download(release: UpdateRelease, destination: File, progress: (Int?) -> Unit) {
                destination.writeText("partial")
                downloadStarted.complete(Unit)
                awaitCancellation()
            }
        }
        val service = GitHubUpdateService(directory, 3, 21, CoroutineScope(coroutineContext + owner), api)
        service.check()
        service.state.first { it is UpdateState.Available }
        service.download()
        downloadStarted.await()
        service.cancel()
        owner.complete()
        owner.join()
        assertEquals(UpdateState.Idle, service.state.value)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun newCheckRemovesDownloadAbandonedByPreviousProcess() = runTest {
        val directory = temp.newFolder()
        val abandoned = File(directory, "hearth-abandoned.apk").apply { writeText("partial") }
        val service = GitHubUpdateService(directory, 3, 21, this, FakeApi())
        assertTrue(abandoned.exists())
        service.check()
        service.state.first { it is UpdateState.Available }
        assertFalse(abandoned.exists())
    }

    @Test fun repeatedSelectDoesNotDuplicateCheckAndCanceledResultCannotOverwriteNewOne() = runTest {
        val started = CompletableDeferred<Unit>()
        val allowOldResult = CompletableDeferred<Unit>()
        val api = object : FakeApi() {
            override suspend fun latestRelease(): UpdateRelease? {
                checks++
                if (checks == 1) {
                    started.complete(Unit)
                    // Simulate an HTTP call that returns after cancellation was requested.
                    withContext(NonCancellable) { allowOldResult.await() }
                    return release
                }
                return null
            }
        }
        val service = GitHubUpdateService(temp.newFolder(), 3, 21, this, api)
        service.check()
        started.await()
        service.check()
        assertEquals(1, api.checks)
        service.cancel()
        assertEquals(UpdateState.Idle, service.state.value)
        service.check()
        service.state.first { it == UpdateState.NoRelease }
        allowOldResult.complete(Unit)
        runCurrent()
        assertEquals(UpdateState.NoRelease, service.state.value)
    }

    @Test fun rateLimitFailureCanBeRetriedManually() = runTest {
        val api = object : FakeApi() {
            override suspend fun latestRelease(): UpdateRelease? {
                checks++
                if (checks == 1) throw UpdateException(UpdateFailure.RateLimited)
                return release
            }
        }
        val service = GitHubUpdateService(temp.newFolder(), 3, 21, this, api)
        service.check()
        assertEquals(UpdateState.Failed(UpdateFailure.RateLimited), service.state.first { it is UpdateState.Failed })
        service.check()
        service.state.first { it is UpdateState.Available }
        assertEquals(2, api.checks)
    }
}
