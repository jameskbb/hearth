package io.github.jameskbb.hearth.compat

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.tv.TvContract
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.ResourceFont
import io.github.jameskbb.hearth.R
import io.github.jameskbb.hearth.apps.LaunchCategories
import io.github.jameskbb.hearth.apps.activityInfo
import io.github.jameskbb.hearth.apps.applicationInfo
import io.github.jameskbb.hearth.apps.packageInfo
import io.github.jameskbb.hearth.apps.queryActivities
import io.github.jameskbb.hearth.prefs.DataStorePrefsRepository
import io.github.jameskbb.hearth.system.AndroidDisplayFactsReader
import io.github.jameskbb.hearth.system.AndroidSystemRoutes
import io.github.jameskbb.hearth.system.AndroidTvInputPlatform
import io.github.jameskbb.hearth.system.SystemRoute
import io.github.jameskbb.hearth.system.registerClockChanges
import io.github.jameskbb.hearth.system.primaryLocale
import io.github.jameskbb.hearth.theme.ThemeManifestCodec
import io.github.jameskbb.hearth.theme.ThemeOrder
import io.github.jameskbb.hearth.ui.theme.HearthFonts
import io.github.jameskbb.hearth.weather.OpenMeteo
import io.github.jameskbb.hearth.weather.Place
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real Android framework implementations in SDK sandboxes, without a TV image or host emulator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Config.ALL_SDKS], application = Application::class, qualifiers = "land-television-xhdpi")
class AndroidCompatibilityTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val pm: PackageManager get() = context.packageManager
    private val sample = ComponentName("com.example.tv", "com.example.tv.MainActivity")

    @Test fun selectedRuntimeActuallyExecutes() {
        val selected = requireNotNull(System.getProperty("hearth.compatSdks")).split(",").map(String::toInt)
        assertTrue("Unexpected Android runtime ${Build.VERSION.SDK_INT}", Build.VERSION.SDK_INT in selected)
        // CI checks every requested API has evidence, preventing a filtered or skipped SDK from passing.
        val directory = File(requireNotNull(System.getProperty("hearth.compatEvidence")))
        directory.mkdirs()
        File(directory, "api-${Build.VERSION.SDK_INT}.txt").writeText(Build.VERSION.SDK_INT.toString())
    }

    @Test fun displayFactsUseOnlyAvailablePlatformCapabilities() {
        val facts = AndroidDisplayFactsReader(context).read()
        assertTrue(facts.widthPx > 0)
        assertTrue(facts.heightPx > 0)
        assertTrue(facts.densityDpi > 0)
        assertTrue(facts.refreshRateHz > 0f)
        if (Build.VERSION.SDK_INT < 23) assertTrue(facts.supportedModes.isEmpty())
        if (Build.VERSION.SDK_INT < 24) assertTrue(facts.hdrTypes.isEmpty())
        if (Build.VERSION.SDK_INT < 26) assertFalse(facts.isWideColorGamut)
    }

    @Test fun missingDisplayServiceGivesHonestEmptyFacts() {
        val absent = withoutService(Context.DISPLAY_SERVICE)
        val facts = AndroidDisplayFactsReader(absent).read()
        assertEquals(0, facts.widthPx)
        assertEquals(0, facts.heightPx)
        assertEquals(0f, facts.refreshRateHz, 0f)
        assertTrue(facts.supportedModes.isEmpty())
        assertTrue(facts.hdrTypes.isEmpty())
    }

    @Test fun packageInfoAndLabelsSurviveLegacyAndTypedFlagOverloads() {
        installSample()
        assertEquals(sample.packageName, pm.packageInfo(sample.packageName).packageName)
        assertEquals(1234L, pm.packageInfo(sample.packageName).lastUpdateTime)
        assertEquals(sample.packageName, pm.applicationInfo(sample.packageName).packageName)
        assertEquals(sample.className, pm.activityInfo(sample).name)
    }

    @Test fun absentPackagesStillThrowRatherThanInventingAnEntry() {
        assertThrows(PackageManager.NameNotFoundException::class.java) { pm.packageInfo(sample.packageName) }
        assertThrows(PackageManager.NameNotFoundException::class.java) { pm.applicationInfo(sample.packageName) }
        assertThrows(PackageManager.NameNotFoundException::class.java) { pm.activityInfo(sample) }
    }

    @Test fun leanbackAndPhoneDiscoveryStaySeparate() {
        installSample()
        shadowOf(pm).addIntentFilterForActivity(sample, IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        })
        assertTrue(pm.queryActivities(LaunchCategories.mainIntent(LaunchCategories.TV))
            .any { it.activityInfo.packageName == sample.packageName })
        assertTrue(pm.queryActivities(LaunchCategories.mainIntent(LaunchCategories.OTHER))
            .none { it.activityInfo.packageName == sample.packageName })
    }

    @Test fun settingsRouteIsHiddenWhenNothingResolves() {
        val routes = AndroidSystemRoutes(context)
        assertFalse(routes.isAvailable(SystemRoute.Settings))
        assertFalse(routes.open(SystemRoute.Settings))
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun settingsRouteOpensOnlyItsVerifiedIntent() {
        installSample()
        shadowOf(pm).addIntentFilterForActivity(sample, IntentFilter(Settings.ACTION_SETTINGS).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        })
        val routes = AndroidSystemRoutes(context)
        assertTrue(routes.isAvailable(SystemRoute.Settings))
        assertTrue(routes.open(SystemRoute.Settings))
        val started = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_SETTINGS, started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun missingTvInputServiceIsSafeOnEveryRuntime() {
        val platform = AndroidTvInputPlatform(withoutService(Context.TV_INPUT_SERVICE))
        assertNull(platform.inputs())
        platform.watch { fail("A missing service cannot produce an input callback") }
    }

    @Test fun hdmiSwitchUsesTheDocumentedPassthroughUri() {
        val platform = AndroidTvInputPlatform(context)
        val id = "com.example.tv/.Inputs/HW1"
        assertFalse(platform.canView(id))
        platform.view(id)
        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(TvContract.buildChannelUriForPassthroughInput(id), started.data)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun clockReceiverDeliversChangesAndCanBeUnregistered() {
        val received = mutableListOf<String?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { received += intent?.action }
        }
        context.registerClockChanges(receiver)
        for (action in listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED)) {
            context.sendBroadcast(Intent(action))
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED), received)
        context.unregisterReceiver(receiver)
        context.sendBroadcast(Intent(Intent.ACTION_DATE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, received.size)
    }

    @Test fun clockAndTitleFontsKeepTheirStaticFallbackBeforeOreo() {
        val old = Build.VERSION.SDK_INT < 26
        assertEquals(listOf(if (old) R.font.literata_clock_light else R.font.literata), resources(HearthFonts.LiterataClock))
        assertEquals(
            if (old) listOf(R.font.literata_title_regular, R.font.literata_title_light)
            else listOf(R.font.literata, R.font.literata),
            resources(HearthFonts.LiterataTitle),
        )
    }

    @Test fun bodyFontsKeepEveryBundledWeightBeforeOreo() {
        val expected = if (Build.VERSION.SDK_INT < 26) {
            listOf(R.font.instrument_sans_regular, R.font.instrument_sans_medium, R.font.instrument_sans_semibold)
        } else {
            listOf(R.font.instrument_sans, R.font.instrument_sans, R.font.instrument_sans)
        }
        assertEquals(expected, resources(HearthFonts.InstrumentSans))
        for (id in expected) context.resources.openRawResource(id).use { assertTrue(it.read() >= 0) }
    }

    @Test fun androidPreferenceFilesSurviveRepositoryRestart() = runBlocking {
        val firstJob = SupervisorJob()
        try {
            val first = DataStorePrefsRepository.create(context, CoroutineScope(firstJob + Dispatchers.IO))
            assertFalse(first.prefs.first().weatherEnabled)
            first.update { it.copy(homeLabel = "Welcome Home", homeLabelEnabled = true, reducedMotion = true) }
            assertEquals("Welcome Home", first.prefs.first().homeLabel)
        } finally {
            firstJob.cancelAndJoin()
        }
        val nextJob = SupervisorJob()
        try {
            val next = DataStorePrefsRepository.create(context, CoroutineScope(nextJob + Dispatchers.IO)).prefs.first()
            assertEquals("Welcome Home", next.homeLabel)
            assertTrue(next.homeLabelEnabled)
            assertTrue(next.reducedMotion)
            assertFalse(next.weatherEnabled)
        } finally {
            nextJob.cancelAndJoin()
        }
    }

    @Test fun bundledThemesParseWithThePlatformsJsonImplementation() {
        val ids = context.assets.open("themes/order.txt").bufferedReader().use { ThemeOrder.parseOrder(it.readText()) }
        val manifests = ids.map { id ->
            context.assets.open("themes/$id/theme.json").bufferedReader().use { ThemeManifestCodec.parse(it.readText()) }
        }
        assertTrue(manifests.any { it.id.name == "dusk" })
        assertTrue(manifests.any { it.id.name == "easter" && !it.autoRotationEligible })
        assertTrue(manifests.all { it.layers.isNotEmpty() })
    }

    @Test fun weatherFixtureParsesWithThePlatformsJsonImplementation() {
        // No network, no location lookup. Unlike the ordinary suite, this uses Android's org.json.
        val fixture = requireNotNull(javaClass.getResource("/openmeteo/forecast.json")).readText()
        val report = OpenMeteo.parseForecast(fixture, Place("Example", null, "Example", 0.0, 0.0), 1234L)
        assertEquals(17.8, report.temperatureC, 0.0)
        assertEquals(21.1, report.highC!!, 0.0)
        assertEquals(11.2, report.lowC!!, 0.0)
        assertEquals(2, report.weatherCode)
        assertEquals(1234L, report.fetchedAt)
    }

    @Test fun clockAndWeatherUseTheConfiguredLocaleOnOldAndNewAndroid() {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.FRANCE)
        assertEquals(Locale.FRANCE, configuration.primaryLocale())
    }

    @Suppress("DEPRECATION")
    @Test fun emptyLocaleConfigurationUsesTheSystemDefault() {
        val configuration = Configuration()
        if (Build.VERSION.SDK_INT >= 24) {
            configuration.setLocales(android.os.LocaleList.getEmptyLocaleList())
        } else {
            configuration.locale = null
        }
        assertEquals(Locale.getDefault(), configuration.primaryLocale())
    }

    private fun resources(family: FontFamily): List<Int> =
        (family as FontListFontFamily).fonts.map { (it as ResourceFont).resId }

    private fun withoutService(name: String): Context = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(service: String): Any? = if (service == name) null else super.getSystemService(service)
    }

    private fun installSample() {
        val app = ApplicationInfo().apply {
            packageName = sample.packageName
            enabled = true
            flags = ApplicationInfo.FLAG_INSTALLED
        }
        shadowOf(pm).installPackage(PackageInfo().apply {
            packageName = sample.packageName
            applicationInfo = app
            lastUpdateTime = 1234L
        })
        shadowOf(pm).addActivityIfNotPresent(sample).apply {
            applicationInfo = app
            enabled = true
            exported = true
        }
    }
}
