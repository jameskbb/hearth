package io.github.jameskbb.hearth.updates

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateManifestTest {
    private val prefix = "https://github.com/jameskbb/hearth/releases/download/v0.2.0/"
    private fun assets(): JSONObject = JSONObject("""{
        "draft":false,"prerelease":false,"tag_name":"v0.2.0",
        "assets":[
            {"name":"hearth-update.json","state":"uploaded","size":300,"browser_download_url":"${prefix}hearth-update.json"},
            {"name":"app-release.apk","state":"uploaded","size":4000,"browser_download_url":"${prefix}app-release.apk"}
        ]
    }""")
    private fun manifest(): JSONObject = JSONObject("""{
        "versionCode":4,"versionName":"0.2.0","apkAsset":"app-release.apk",
        "sha256":"${"A".repeat(64)}","sizeBytes":4000,"minSdk":21
    }""")
    private fun parse(value: JSONObject) = UpdateManifest.parseRelease(value.toString(), UpdateManifest.parseAssets(assets().toString()))

    @Test fun parsesStableReleaseAndNormalizesChecksum() {
        val release = parse(manifest())
        assertEquals(4L, release.versionCode)
        assertEquals("0.2.0", release.versionName)
        assertEquals("a".repeat(64), release.sha256)
        assertEquals(4000L, release.sizeBytes)
        assertEquals(21, release.minSdk)
        assertEquals("${prefix}app-release.apk", release.apkUrl)
    }

    @Test fun rejectsDraftPrereleaseAndBooleanStrings() {
        listOf("draft", "prerelease").forEach { key ->
            assertInvalid { UpdateManifest.parseAssets(assets().put(key, true).toString()) }
            assertInvalid { UpdateManifest.parseAssets(assets().put(key, "false").toString()) }
        }
    }

    @Test fun rejectsUnrelatedExternalOrTraversingAssetUrls() {
        listOf(
            "https://example.com/app-release.apk",
            "https://github.com/other/hearth/releases/download/v0.2.0/app-release.apk",
            "https://github.com/jameskbb/hearth/releases/download/v0.2.0/../app-release.apk",
            "${prefix}app-release.apk?redirect=elsewhere",
            "http://github.com/jameskbb/hearth/releases/download/v0.2.0/app-release.apk",
        ).forEach { url ->
            val root = assets()
            root.getJSONArray("assets").getJSONObject(1).put("browser_download_url", url)
            assertInvalid { UpdateManifest.parseAssets(root.toString()) }
        }
    }

    @Test fun rejectsMissingDuplicateAndUnfinishedAssets() {
        val missing = assets().apply { getJSONArray("assets").remove(0) }
        assertInvalid { UpdateManifest.parseAssets(missing.toString()) }
        val duplicate = assets().apply { getJSONArray("assets").put(getJSONArray("assets").getJSONObject(1)) }
        assertInvalid { UpdateManifest.parseAssets(duplicate.toString()) }
        val unfinished = assets().apply { getJSONArray("assets").getJSONObject(1).put("state", "new") }
        assertInvalid { UpdateManifest.parseAssets(unfinished.toString()) }
    }

    @Test fun rejectsNonIntegralAndOutOfRangeManifestNumbers() {
        listOf("versionCode", "sizeBytes", "minSdk").forEach { key ->
            listOf("4", 4.5, true, -1, 0, Long.MAX_VALUE, JSONObject.NULL).forEach { value ->
                assertInvalid { parse(manifest().put(key, value)) }
            }
        }
        assertInvalid { parse(manifest().put("versionCode", Int.MAX_VALUE.toLong() + 1)) }
        assertInvalid { parse(manifest().put("sizeBytes", 4001)) }
    }

    @Test fun rejectsMalformedChecksumsNamesAndApkSelection() {
        listOf("", "a".repeat(63), "g".repeat(64), "a".repeat(65)).forEach { sha ->
            assertInvalid { parse(manifest().put("sha256", sha)) }
        }
        listOf("", "current\nInstall", "a".repeat(41), "v1\u202e0").forEach { name ->
            assertInvalid { parse(manifest().put("versionName", name)) }
        }
        assertInvalid { parse(manifest().put("apkAsset", "another.apk")) }
        assertInvalid { parse(manifest().apply { remove("sha256") }) }
        assertInvalid { UpdateManifest.parseAssets("<html>error</html>") }
        assertInvalid { UpdateManifest.parseRelease("{}", UpdateManifest.parseAssets(assets().toString())) }
    }

    @Test fun boundsAdvertisedAssetSizes() {
        listOf(0L, -1L, UpdateManifest.MAX_APK_BYTES + 1).forEach { size ->
            val root = assets().apply { getJSONArray("assets").getJSONObject(1).put("size", size) }
            assertInvalid { UpdateManifest.parseAssets(root.toString()) }
        }
        val root = assets().apply {
            getJSONArray("assets").getJSONObject(0).put("size", UpdateManifest.MAX_MANIFEST_BYTES + 1)
        }
        assertInvalid { UpdateManifest.parseAssets(root.toString()) }
    }

    @Test fun acceptsOnlyExactHttpsReleaseHostsAndPaths() {
        listOf(
            UpdateManifest.RELEASE_URL,
            "${prefix}app-release.apk",
            "${prefix}hearth-update.json",
            "https://release-assets.githubusercontent.com/github-production-release-asset/file?token=example",
            "https://objects.githubusercontent.com/github-production-release-asset/file?token=example",
        ).forEach { assertTrue(it, UpdateUrlPolicy.allowed(it)) }
        listOf(
            "http://github.com/jameskbb/hearth/releases/download/v0.2.0/app-release.apk",
            "https://github.com.evil.example/jameskbb/hearth/releases/download/v0.2.0/app-release.apk",
            "https://evil.example/file.apk",
            "https://user@github.com/jameskbb/hearth/releases/download/v0.2.0/app-release.apk",
            "${prefix}app-release.apk#other",
            "${prefix}app-release.apk?other",
            "https://github.com:443/jameskbb/hearth/releases/download/v0.2.0/app-release.apk",
            "https://github.com/jameskbb/hearth/releases/download/v0.2.0/%2e%2e/app-release.apk",
            "https://api.github.com/repos/other/hearth/releases/latest",
            "https://api.github.com/repos/jameskbb/hearth/releases/latest?token=example",
            "https://raw.githubusercontent.com/jameskbb/hearth/main/app-release.apk",
        ).forEach { assertFalse(it, UpdateUrlPolicy.allowed(it)) }
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected rejected release")
        } catch (e: UpdateException) {
            assertEquals(UpdateFailure.InvalidRelease, e.reason)
        }
    }
}
