package io.github.jameskbb.hearth.updates

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Validates our private download, then delegates installation to the device's package installer. */
class AndroidUpdateInstaller(context: Context) : UpdateInstaller {
    private val app = context.applicationContext
    private val pm = app.packageManager
    private val directory = File(app.cacheDir, "updates")

    override suspend fun verify(file: File, expectedVersionCode: Long): ApkVerification =
        withContext(Dispatchers.IO) { verifyArchive(file, expectedVersionCode) }

    override suspend fun install(file: File, expectedVersionCode: Long): InstallResult {
        if (verify(file, expectedVersionCode) != ApkVerification.Valid) return InstallResult.VerificationFailed
        return withContext(Dispatchers.Main) {
            if (!canRequestInstalls()) return@withContext InstallResult.PermissionRequired
            try {
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
                val intent = installationIntents(uri).firstOrNull(::resolves)
                    ?: return@withContext InstallResult.Unavailable
                app.startActivity(intent)
                InstallResult.Started
            } catch (_: SecurityException) {
                if (!canRequestInstalls()) InstallResult.PermissionRequired else InstallResult.Unavailable
            } catch (_: Exception) {
                InstallResult.Unavailable
            }
        }
    }

    override fun canRequestInstalls(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 26) {
            pm.canRequestPackageInstalls()
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(app.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
        }
    } catch (_: Exception) {
        false
    }

    override fun openPermissionSettings(): Boolean {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= 26) {
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")))
            }
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        // Vendor settings may expose a route but refuse to launch it. Try the next verified route.
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!resolves(intent)) continue
            try {
                app.startActivity(intent)
                return true
            } catch (_: Exception) {
                // Opening a settings page never grants installation permission itself.
            }
        }
        return false
    }

    private fun verifyArchive(file: File, expectedVersionCode: Long): ApkVerification = try {
        if (!file.isFile || file.length() == 0L || file.extension != "apk" ||
            file.canonicalFile.parentFile != directory.canonicalFile
        ) {
            ApkVerification.Unreadable
        } else {
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val candidate = archive(file, flags)
            if (candidate == null) {
                ApkVerification.Unreadable
            } else {
                verifyApkIdentity(installed(flags).identity(), candidate.identity(), expectedVersionCode, Build.VERSION.SDK_INT)
            }
        }
    } catch (_: Exception) {
        ApkVerification.Unreadable
    }

    private fun installed(flags: Int): PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
        pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(app.packageName, flags)
    }

    private fun archive(file: File, flags: Int): PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
        pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageArchiveInfo(file.absolutePath, flags)
    }

    private fun PackageInfo.identity(): ApkIdentity {
        // Compare current signers, never merely any certificate from the rotation history.
        val certificates = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else {
            @Suppress("DEPRECATION")
            signatures
        }
        val signers = certificates.orEmpty().map { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }.toSet()
        val version = if (Build.VERSION.SDK_INT >= 28) longVersionCode else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }
        // Earlier archive parsers reject unsupported uses-sdk declarations while parsing.
        val minimum = if (Build.VERSION.SDK_INT >= 24) applicationInfo?.minSdkVersion ?: Int.MAX_VALUE else 1
        return ApkIdentity(packageName, version, minimum, signers)
    }

    @Suppress("DEPRECATION")
    internal fun installationIntents(uri: Uri): List<Intent> =
        listOf(Intent.ACTION_INSTALL_PACKAGE, Intent.ACTION_VIEW).map { action ->
            Intent(action).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("Hearth update", uri)
            }
        }

    private fun resolves(intent: Intent): Boolean = try {
        val result = if (Build.VERSION.SDK_INT >= 33) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        result?.activityInfo?.let { it.enabled && it.exported } == true
    } catch (_: Exception) {
        false
    }

    internal companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}

/** A concrete subclass avoids provider initialization issues on vendor firmware. */
class UpdateFileProvider : FileProvider()
