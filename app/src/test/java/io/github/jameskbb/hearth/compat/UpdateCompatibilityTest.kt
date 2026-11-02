package io.github.jameskbb.hearth.compat

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.jameskbb.hearth.updates.AndroidUpdateInstaller
import io.github.jameskbb.hearth.updates.ApkVerification
import io.github.jameskbb.hearth.updates.InstallResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Installer routes and URI permissions on every runtime, without installing packages in tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Config.ALL_SDKS], application = Application::class, qualifiers = "land-television-xhdpi")
class UpdateCompatibilityTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test fun installerNeverReceivesAFileUriOrAnUnscopedStorageGrant() {
        val uri = Uri.parse("content://${app.packageName}.updates/updates/update.apk")
        val intents = AndroidUpdateInstaller(app).installationIntents(uri)
        assertEquals(2, intents.size)
        for (intent in intents) {
            assertEquals(uri, intent.data)
            assertEquals("content", intent.data!!.scheme)
            assertEquals(AndroidUpdateInstaller.APK_MIME, intent.type)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    @Test fun fileProviderOnlySharesThePrivateUpdatesDirectory() {
        val update = File(app.cacheDir, "updates/update.apk").apply { parentFile!!.mkdirs(); writeText("sample") }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", update)
        assertEquals("content", uri.scheme)
        assertTrue(uri.path!!.startsWith("/updates/"))
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(app, "${app.packageName}.updates", File(app.cacheDir, "weather.json"))
        }
    }

    @Test fun verificationRejectsFilesOutsideUpdatesAndNeverStartsAnInstaller() = runBlocking {
        val file = File(app.cacheDir, "other.apk").apply { writeText("sample") }
        val installer = AndroidUpdateInstaller(app)
        assertEquals(ApkVerification.Unreadable, installer.verify(file, 9))
        assertEquals(InstallResult.VerificationFailed, installer.install(file, 9))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun missingPermissionSettingsIsReportedWithoutLaunchingAnything() {
        assertFalse(AndroidUpdateInstaller(app).openPermissionSettings())
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun installPermissionSettingsUsesAnAvailableSystemRoute() {
        val component = ComponentName("com.example.settings", "com.example.settings.Security")
        val application = ApplicationInfo().apply {
            packageName = component.packageName
            enabled = true
            flags = ApplicationInfo.FLAG_INSTALLED
        }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = component.packageName
            applicationInfo = application
        })
        shadowOf(app.packageManager).addActivityIfNotPresent(component).apply {
            applicationInfo = application
            exported = true
            enabled = true
        }
        shadowOf(app.packageManager).addIntentFilterForActivity(component, IntentFilter(Settings.ACTION_SECURITY_SETTINGS).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        })
        assertTrue(AndroidUpdateInstaller(app).openPermissionSettings())
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_SECURITY_SETTINGS, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Suppress("DEPRECATION")
    @Test fun legacyUnknownSourcesReadsTheDeviceSettingWithoutChangingIt() {
        if (Build.VERSION.SDK_INT < 26) {
            Settings.Secure.putInt(app.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0)
            assertFalse(AndroidUpdateInstaller(app).canRequestInstalls())
            Settings.Secure.putInt(app.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 1)
            assertTrue(AndroidUpdateInstaller(app).canRequestInstalls())
            assertEquals(1, Settings.Secure.getInt(app.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS))
        } else {
            // This runtime uses the per-source package-manager permission rather than the old flag.
            Settings.Secure.putInt(app.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 1)
            assertFalse(AndroidUpdateInstaller(app).canRequestInstalls())
        }
    }
}
