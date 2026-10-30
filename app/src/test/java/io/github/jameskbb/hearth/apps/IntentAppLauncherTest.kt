package io.github.jameskbb.hearth.apps

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentAppLauncherTest {
    private val key = AppKey("com.example.tv/.Main")

    private class FakePlatform(
        var enabled: Boolean? = true,
        var category: String? = LaunchCategories.TV,
        var exists: Boolean = true,
        var onStart: () -> Unit = {},
    ) : LaunchPlatform {
        val started = mutableListOf<Pair<AppKey, String>>()
        override fun isAppEnabled(packageName: String) = enabled
        override fun launchCategory(key: AppKey) = category
        override fun activityExists(key: AppKey) = exists
        override fun start(key: AppKey, category: String) {
            onStart()
            started += key to category
        }
    }

    @Test fun launchesTvAppWithLeanbackCategory() {
        val p = FakePlatform()
        assertEquals(LaunchResult.Launched, IntentAppLauncher(p).launch(key))
        assertEquals(listOf(key to LaunchCategories.TV), p.started)
    }

    @Test fun launchesOtherAppWithLauncherCategory() {
        val p = FakePlatform(category = LaunchCategories.OTHER)
        assertEquals(LaunchResult.Launched, IntentAppLauncher(p).launch(key))
        assertEquals(LaunchCategories.OTHER, p.started.single().second)
    }

    @Test fun missingPackageIsNotInstalled() {
        val p = FakePlatform(enabled = null)
        assertEquals(LaunchResult.NotInstalled, IntentAppLauncher(p).launch(key))
        assertEquals(emptyList<Any>(), p.started)
    }

    @Test fun disabledAppFails() {
        val p = FakePlatform(enabled = false)
        assertEquals(LaunchResult.Failed("disabled"), IntentAppLauncher(p).launch(key))
        assertEquals(emptyList<Any>(), p.started)
    }

    @Test fun activityGoneIsNotInstalled() {
        val p = FakePlatform(category = null, exists = false)
        assertEquals(LaunchResult.NotInstalled, IntentAppLauncher(p).launch(key))
    }

    @Test fun existingActivityWithoutLauncherCategoryIsStillTried() {
        val p = FakePlatform(category = null, exists = true)
        assertEquals(LaunchResult.Launched, IntentAppLauncher(p).launch(key))
        assertEquals(LaunchCategories.OTHER, p.started.single().second)
    }

    @Test fun activityNotFoundAfterUninstallIsNotInstalled() {
        val p = FakePlatform()
        p.onStart = { p.enabled = null; throw ActivityNotFoundException("gone") }
        assertEquals(LaunchResult.NotInstalled, IntentAppLauncher(p).launch(key))
    }

    @Test fun activityNotFoundWhilePresentFails() {
        // The android.jar stub drops the message, so only the result type is checked here.
        val p = FakePlatform(onStart = { throw ActivityNotFoundException("no match") })
        assertTrue(IntentAppLauncher(p).launch(key) is LaunchResult.Failed)
    }

    @Test fun securityExceptionFails() {
        val p = FakePlatform(onStart = { throw SecurityException("not exported") })
        assertEquals(LaunchResult.Failed("not exported"), IntentAppLauncher(p).launch(key))
    }

    @Test fun anyOtherExceptionFailsInsteadOfCrashing() {
        val p = FakePlatform(onStart = { throw IllegalStateException("boom") })
        assertEquals(LaunchResult.Failed("boom"), IntentAppLauncher(p).launch(key))
    }

    @Test fun exceptionWithoutMessageUsesTypeName() {
        val p = FakePlatform(onStart = { throw RuntimeException() })
        assertEquals(LaunchResult.Failed("RuntimeException"), IntentAppLauncher(p).launch(key))
    }
}
