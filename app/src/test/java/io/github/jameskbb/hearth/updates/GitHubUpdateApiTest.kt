package io.github.jameskbb.hearth.updates

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class GitHubUpdateApiTest {
    @get:Rule val temp = TemporaryFolder()
    private val apkUrl = "https://github.com/jameskbb/hearth/releases/download/v0.2.0/app-release.apk"
    private val manifestUrl = "https://github.com/jameskbb/hearth/releases/download/v0.2.0/hearth-update.json"
    private val release = UpdateRelease(4, "0.2.0", apkUrl, "a".repeat(64), 3, 21)

    private class Response(
        url: String,
        private val status: Int = 200,
        body: ByteArray = byteArrayOf(),
        private val headers: Map<String, String> = emptyMap(),
    ) : HttpsURLConnection(URL(url)) {
        val input = ByteArrayInputStream(body)
        var disconnected = false
        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String): String? = headers[name]
        override fun getInputStream() = input
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    @Test fun noStableReleaseDoesNotFetchAnyAssetsAndClosesConnection() = runTest {
        val response = Response(UpdateManifest.RELEASE_URL, 404)
        var requests = 0
        val api = GitHubUpdateApi(StandardTestDispatcher(testScheduler), connectionFactory = {
            requests++
            assertEquals(UpdateManifest.RELEASE_URL, it.toExternalForm())
            response
        })
        assertNull(api.latestRelease())
        assertEquals(1, requests)
        assertTrue(response.disconnected)
        assertFalse(response.instanceFollowRedirects)
        assertEquals("Hearth-Updater", response.getRequestProperty("User-Agent"))
        assertNull(response.getRequestProperty("Authorization"))
        assertNull(response.getRequestProperty("Cookie"))
    }

    @Test fun returnsRateLimitWithoutPretendingDeviceIsUpToDate() = runTest {
        for (status in listOf(403, 429)) {
            val response = Response(UpdateManifest.RELEASE_URL, status)
            val api = GitHubUpdateApi(StandardTestDispatcher(testScheduler), connectionFactory = { response })
            assertFailure(UpdateFailure.RateLimited) { api.latestRelease() }
            assertTrue(response.disconnected)
        }
    }

    @Test fun refusesRedirectToExternalHostBeforeOpeningIt() = runTest {
        val response = Response(UpdateManifest.RELEASE_URL, 302, headers = mapOf("Location" to "https://example.com/release"))
        var requests = 0
        val api = GitHubUpdateApi(StandardTestDispatcher(testScheduler), connectionFactory = {
            requests++
            response
        })
        assertFailure(UpdateFailure.InvalidRelease) { api.latestRelease() }
        assertEquals(1, requests)
        assertTrue(response.disconnected)
    }

    @Test fun rejectsCleartextRedirectAndStopsRedirectLoop() = runTest {
        for (location in listOf("http://github.com/jameskbb/hearth/releases/download/v0.2.0/app-release.apk", apkUrl)) {
            val responses = mutableListOf<Response>()
            val api = GitHubUpdateApi(StandardTestDispatcher(testScheduler), connectionFactory = {
                Response(it.toExternalForm(), 302, headers = mapOf("Location" to location)).also(responses::add)
            })
            assertFailure(UpdateFailure.InvalidRelease) { api.download(release, temp.newFile(), {}) }
            assertTrue(responses.size <= 5)
            assertTrue(responses.all { it.disconnected })
        }
    }

    @Test fun followsGitHubAssetRedirectWithoutSendingCredentials() = runTest {
        val deliveryUrl = "https://release-assets.githubusercontent.com/github-production-release-asset/file?token=example"
        val initial = Response(apkUrl, 302, headers = mapOf("Location" to deliveryUrl))
        val delivery = Response(deliveryUrl, body = byteArrayOf(1, 2, 3), headers = mapOf("Content-Length" to "3"))
        val responses = mapOf(apkUrl to initial, deliveryUrl to delivery)
        val api = GitHubUpdateApi(StandardTestDispatcher(testScheduler), connectionFactory = { responses.getValue(it.toExternalForm()) })
        val destination = temp.newFile()
        val progress = mutableListOf<Int?>()
        api.download(release, destination, progress::add)
        assertTrue(destination.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(listOf(100), progress)
        assertTrue(initial.disconnected && delivery.disconnected)
        assertNull(delivery.getRequestProperty("Authorization"))
        assertEquals("identity", delivery.getRequestProperty("Accept-Encoding"))
    }

    @Test fun rejectsOversizedMetadataBody() = runTest {
        val response = Response(UpdateManifest.RELEASE_URL, body = ByteArray(UpdateManifest.MAX_RELEASE_BYTES + 1) { ' '.code.toByte() })
        val api = GitHubUpdateApi(StandardTestDispatcher(testScheduler), connectionFactory = { response })
        assertFailure(UpdateFailure.InvalidRelease) { api.latestRelease() }
        assertTrue(response.disconnected)
    }

    @Test fun rejectsTruncatedOversizedAndConflictingLengthDownloads() = runTest {
        for ((body, headers) in listOf(
            byteArrayOf(1, 2) to emptyMap(),
            byteArrayOf(1, 2, 3, 4) to emptyMap(),
            byteArrayOf(1, 2, 3) to mapOf("Content-Length" to "4"),
        )) {
            val response = Response(apkUrl, body = body, headers = headers)
            val api = GitHubUpdateApi(StandardTestDispatcher(testScheduler), connectionFactory = { response })
            assertFailure(UpdateFailure.Verification) { api.download(release, temp.newFile(), {}) }
            assertTrue(response.disconnected)
        }
    }

    @Test fun networkOpenFailureIsReportedAsNetworkFailure() = runTest {
        val api = GitHubUpdateApi(StandardTestDispatcher(testScheduler), connectionFactory = { throw IOException("offline") })
        assertFailure(UpdateFailure.Network) { api.latestRelease() }
    }

    @Test fun resolvesManifestAndApkFromTheSamePublishedRelease() = runTest {
        val metadata = """{"draft":false,"prerelease":false,"tag_name":"v0.2.0","assets":[
            {"name":"hearth-update.json","state":"uploaded","size":300,"browser_download_url":"$manifestUrl"},
            {"name":"app-release.apk","state":"uploaded","size":3,"browser_download_url":"$apkUrl"}
        ]}"""
        val manifest = """{"versionCode":4,"versionName":"0.2.0","apkAsset":"app-release.apk",
            "sha256":"${"a".repeat(64)}","sizeBytes":3,"minSdk":21}"""
        val responses = mapOf(
            UpdateManifest.RELEASE_URL to Response(UpdateManifest.RELEASE_URL, body = metadata.toByteArray()),
            manifestUrl to Response(manifestUrl, body = manifest.toByteArray()),
        )
        val api = GitHubUpdateApi(StandardTestDispatcher(testScheduler), connectionFactory = { responses.getValue(it.toExternalForm()) })
        assertEquals(release, api.latestRelease())
        assertTrue(responses.values.all { it.disconnected })
    }

    private suspend fun assertFailure(expected: UpdateFailure, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected updater failure")
        } catch (e: UpdateException) {
            assertEquals(expected, e.reason)
        }
    }
}
