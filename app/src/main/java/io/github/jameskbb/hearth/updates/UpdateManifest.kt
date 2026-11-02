package io.github.jameskbb.hearth.updates

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** A deliberately small release format. All fields are untrusted until validated. */
object UpdateManifest {
    const val RELEASE_URL = "https://api.github.com/repos/jameskbb/hearth/releases/latest"
    const val MANIFEST_ASSET = "hearth-update.json"
    const val APK_ASSET = "app-release.apk"
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 16 * 1024
    const val MAX_RELEASE_BYTES = 256 * 1024

    data class Assets(val manifestUrl: String, val apkUrl: String, val apkSize: Long)

    fun parseAssets(body: String): Assets = parse {
        val root = JSONObject(body)
        require(root.get("draft") == false && root.get("prerelease") == false)
        val tag = root.get("tag_name") as? String ?: error("tag")
        require(tag.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}")))
        val assets = root.getJSONArray("assets")
        require(assets.length() in 2..100)
        val byName = (0 until assets.length()).map { assets.getJSONObject(it) }
        fun asset(name: String): JSONObject = byName.single { it.opt("name") == name }
        val manifest = asset(MANIFEST_ASSET)
        val apk = asset(APK_ASSET)
        require(manifest.get("state") == "uploaded" && apk.get("state") == "uploaded")
        require(integer(manifest, "size") in 1..MAX_MANIFEST_BYTES.toLong())
        val apkSize = integer(apk, "size")
        require(apkSize in 1..MAX_APK_BYTES)
        fun url(asset: JSONObject, name: String): String {
            val value = asset.get("browser_download_url") as? String ?: error("url")
            require(value == "https://github.com/jameskbb/hearth/releases/download/$tag/$name")
            return value
        }
        Assets(url(manifest, MANIFEST_ASSET), url(apk, APK_ASSET), apkSize)
    }

    fun parseRelease(body: String, assets: Assets): UpdateRelease = parse {
        val root = JSONObject(body)
        val code = integer(root, "versionCode")
        require(code in 1..Int.MAX_VALUE.toLong())
        val name = root.get("versionName") as? String ?: error("versionName")
        require(name.length in 1..40 && name.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_-]*")))
        require(root.get("apkAsset") == APK_ASSET)
        val sha = root.get("sha256") as? String ?: error("sha256")
        require(sha.matches(Regex("[a-fA-F0-9]{64}")))
        val size = integer(root, "sizeBytes")
        require(size == assets.apkSize && size in 1..MAX_APK_BYTES)
        val sdk = integer(root, "minSdk")
        require(sdk in 21..10_000)
        UpdateRelease(code, name, assets.apkUrl, sha.lowercase(Locale.ROOT), size, sdk.toInt())
    }

    /** Reject JSON strings, booleans, fractional numbers and silently truncated values. */
    private fun integer(root: JSONObject, key: String): Long = when (val value = root.get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> error("integer")
    }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw UpdateException(UpdateFailure.InvalidRelease, e)
    }
}

/** Redirects stay on HTTPS and on GitHub's specific release delivery hosts. */
object UpdateUrlPolicy {
    private val assetHosts = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")

    fun allowed(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null &&
            when (uri.host) {
                "api.github.com" -> uri.rawPath == "/repos/jameskbb/hearth/releases/latest" && uri.rawQuery == null
                "github.com" -> uri.rawQuery == null && uri.rawPath.matches(
                    Regex("/jameskbb/hearth/releases/download/[A-Za-z0-9][A-Za-z0-9._+-]{0,79}/(?:hearth-update\\.json|app-release\\.apk)"),
                )
                in assetHosts -> uri.rawPath.startsWith("/") && !uri.rawPath.contains("%")
                else -> false
            }
    } catch (_: Exception) {
        false
    }
}
