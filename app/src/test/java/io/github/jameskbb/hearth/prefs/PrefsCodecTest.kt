package io.github.jameskbb.hearth.prefs

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jameskbb.hearth.apps.AppKey
import io.github.jameskbb.hearth.theme.ThemeId
import io.github.jameskbb.hearth.theme.ThemeMode
import io.github.jameskbb.hearth.theme.ThemePeriod
import io.github.jameskbb.hearth.theme.ThemeRotationState
import io.github.jameskbb.hearth.theme.ThemeScheduleConfig
import io.github.jameskbb.hearth.weather.Place
import io.github.jameskbb.hearth.weather.TemperatureUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsCodecTest {
    private val a = AppKey("com.example.a/com.example.a.Main")
    private val b = AppKey("com.example.b/.TvActivity")
    private val c = AppKey("com.example.c/com.example.c.ui.Launch\$Inner")

    @Test fun emptyPreferencesGiveDefaults() {
        assertEquals(HearthPrefs(), PrefsCodec.read(emptyPreferences()))
    }

    @Test fun roundTripKeepsEverything() {
        val value = HearthPrefs(
            favorites = listOf(c, a, b),
            hidden = setOf(b, c),
            theme = ThemeId("aurora"),
            themeMode = ThemeMode.SurpriseMe,
            themeSchedule = ThemeScheduleConfig(
                listOf(
                    ThemePeriod("wake", "Wake up", 6 * 60 + 15, listOf(ThemeId("misty-forest")), false),
                    ThemePeriod(
                        "rest", "Rest", 19 * 60, listOf(ThemeId("dusk"), ThemeId("aurora")), true,
                    ),
                ),
            ),
            followDaySelections = mapOf(
                "rest" to ThemeRotationState("rest|America/Chicago|12345", ThemeId("aurora")),
            ),
            surpriseThemeIds = setOf(ThemeId("aurora"), ThemeId("dusk")),
            surpriseSelection = ThemeRotationState("surprise|America/Chicago|12345", ThemeId("dusk")),
            reducedMotion = true,
            seeded = true,
            homeLabelEnabled = true,
            homeLabel = "My Workshop",
            weatherEnabled = true,
            weatherPlace = Place("Reykjavík", "Capital Region", "Iceland", 64.13548, -21.89541),
            temperatureUnit = TemperatureUnit.Celsius,
        )
        val prefs = mutablePreferencesOf()
        PrefsCodec.write(prefs, value)
        assertEquals(value, PrefsCodec.read(prefs))
    }

    @Test fun favoritesKeepOrder() {
        val list = listOf(b, c, a)
        assertEquals(list, PrefsCodec.decodeFavorites(PrefsCodec.encodeFavorites(list)))
    }

    @Test fun emptyFavoritesRoundTrip() {
        assertEquals("", PrefsCodec.encodeFavorites(emptyList()))
        assertEquals(emptyList<AppKey>(), PrefsCodec.decodeFavorites(""))
        assertEquals(emptyList<AppKey>(), PrefsCodec.decodeFavorites(null))
    }

    @Test fun decodeDropsMalformedAndDuplicateKeys() {
        val raw = listOf(a.id, "", "noslash", "/.Main", "pkg/", "x/y/z", a.id, b.id, "  ").joinToString("\n")
        assertEquals(listOf(a, b), PrefsCodec.decodeFavorites(raw))
    }

    @Test fun decodeToleratesWindowsLineEndings() {
        assertEquals(listOf(a, b), PrefsCodec.decodeFavorites("${a.id}\r\n${b.id}\r\n"))
    }

    @Test fun hiddenDropsMalformed() {
        assertEquals(setOf(a), PrefsCodec.decodeHidden(setOf(a.id, "junk", "")))
        assertEquals(emptySet<AppKey>(), PrefsCodec.decodeHidden(null))
    }

    @Test fun blankThemeFallsBackToDefault() {
        val prefs = mutablePreferencesOf(PrefsCodec.THEME to "  ")
        assertEquals(ThemeId.Default, PrefsCodec.read(prefs).theme)
    }

    @Test fun manualThemeKeepsTheStoredKeyFromBeforeThemePacks() {
        // A device that chose Aurora as a wallpaper keeps it as a theme.
        val prefs = mutablePreferencesOf(stringPreferencesKey("wallpaper") to "aurora")
        assertEquals(ThemeId("aurora"), PrefsCodec.read(prefs).theme)
        assertEquals(ThemeMode.Single, PrefsCodec.read(prefs).themeMode)
        val written = mutablePreferencesOf()
        PrefsCodec.write(written, HearthPrefs(theme = ThemeId("aurora"), themeMode = ThemeMode.FollowTheDay))
        assertEquals("auto", written[stringPreferencesKey("wallpaper")])
        assertEquals("aurora", written[PrefsCodec.SINGLE_THEME])
    }

    @Test fun legacyAutoMigratesToFollowTheDayAndKeepsADefaultSingleTheme() {
        val migrated = PrefsCodec.read(mutablePreferencesOf(PrefsCodec.THEME to "auto"))
        assertEquals(ThemeMode.FollowTheDay, migrated.themeMode)
        assertEquals(ThemeId.Default, migrated.theme)
        assertEquals(ThemeScheduleConfig.Recommended, migrated.themeSchedule)
    }

    @Test fun uninstalledPackIdsAreKept() {
        // An id for a pack that is not installed is kept: the pack may come back.
        assertEquals(ThemeId("gone-now"), PrefsCodec.read(mutablePreferencesOf(PrefsCodec.THEME to "gone-now")).theme)
    }

    @Test fun malformedThemeIdFallsBackToDefault() {
        assertEquals(ThemeId.Default, PrefsCodec.read(mutablePreferencesOf(PrefsCodec.THEME to "Misty Forest")).theme)
    }

    @Test fun scheduleCodecKeepsPeriodOrderNamesPoolsAndShuffle() {
        val schedule = ThemeScheduleConfig(
            listOf(
                ThemePeriod("late", "Late hours", 22 * 60, listOf(ThemeId("aurora")), false),
                ThemePeriod(
                    "early", "Early hours", 5 * 60 + 45,
                    listOf(ThemeId("morning-glow"), ThemeId("misty-forest")), true,
                ),
            ),
        )
        assertEquals(schedule, PrefsCodec.decodeThemeSchedule(PrefsCodec.encodeThemeSchedule(schedule)))
    }

    @Test fun malformedScheduleFallsBackWithoutCrashing() {
        assertEquals(ThemeScheduleConfig.Recommended, PrefsCodec.decodeThemeSchedule(setOf("broken")))
        val duplicateStarts = setOf(
            "0\tone\t300\t0\tOne\tdusk",
            "1\ttwo\t300\t0\tTwo\taurora",
        )
        assertEquals(ThemeScheduleConfig.Recommended, PrefsCodec.decodeThemeSchedule(duplicateStarts))
    }

    @Test fun scheduleDecodeDropsBadThemeIdsButKeepsThePeriod() {
        val decoded = PrefsCodec.decodeThemeSchedule(setOf("0\tmorning\t300\t1\tMorning\taurora,Not Valid,auto"))
        assertEquals(listOf(ThemeId("aurora")), decoded.periods.single().themeIds)
    }

    @Test fun periodNamesAreCleanAndBounded() {
        assertEquals("Morning glow", ThemePeriod.cleanName("  Morning\nglow  "))
        assertEquals(ThemePeriod.MAX_NAME_LENGTH, ThemePeriod.cleanName("x".repeat(60)).length)
    }

    @Test fun rotationStateCodecsDropMalformedEntries() {
        val good = ThemeRotationState("evening|UTC|123", ThemeId("dusk"))
        assertEquals(good, PrefsCodec.decodeRotationState(PrefsCodec.encodeRotationState(good)))
        assertEquals(null, PrefsCodec.decodeRotationState("no-tab"))
        assertEquals(null, PrefsCodec.decodeRotationState("key\tauto"))
        assertEquals(null, PrefsCodec.decodeRotationState("bad\nkey\tdusk"))

        val map = mapOf("evening" to good)
        assertEquals(map, PrefsCodec.decodeFollowDaySelections(PrefsCodec.encodeFollowDaySelections(map)))
    }

    @Test fun anExplicitModeAndSingleThemeOverrideLegacyWallpaper() {
        val prefs = mutablePreferencesOf(
            PrefsCodec.THEME to "auto",
            PrefsCodec.THEME_MODE to ThemeMode.SurpriseMe.storedName,
            PrefsCodec.SINGLE_THEME to "aurora",
        )
        val read = PrefsCodec.read(prefs)
        assertEquals(ThemeMode.SurpriseMe, read.themeMode)
        assertEquals(ThemeId("aurora"), read.theme)
    }

    @Test fun valueOfWrongTypeFallsBackToDefault() {
        // Same key name as a boolean field, stored as a string: must not crash the read.
        val prefs = mutablePreferencesOf(stringPreferencesKey("seeded") to "yes")
        assertFalse(PrefsCodec.read(prefs).seeded)
    }

    @Test fun writeNeverStoresInvalidKeys() {
        val prefs = mutablePreferencesOf()
        PrefsCodec.write(prefs, HearthPrefs(favorites = listOf(a, AppKey("bad\nkey/x")), hidden = setOf(AppKey("nope"))))
        val read = PrefsCodec.read(prefs)
        assertEquals(listOf(a), read.favorites)
        assertTrue(read.hidden.isEmpty())
    }

    @Test fun keyValidation() {
        assertTrue(PrefsCodec.isValidKey(a))
        assertTrue(PrefsCodec.isValidKey(b))
        assertFalse(PrefsCodec.isValidKey(AppKey("")))
        assertFalse(PrefsCodec.isValidKey(AppKey("a b/c")))
    }

    @Test fun weatherIsOffWithNoCityAndTheLocaleUnitByDefault() {
        val p = PrefsCodec.read(emptyPreferences())
        assertFalse(p.weatherEnabled)
        assertEquals(null, p.weatherPlace)
        assertEquals(null, p.temperatureUnit)
    }

    @Test fun clearingTheCityAndUnitRemovesTheirKeys() {
        val prefs = mutablePreferencesOf()
        val place = Place("Springfield", null, "United States", 39.8, -89.6)
        PrefsCodec.write(prefs, HearthPrefs(weatherPlace = place, temperatureUnit = TemperatureUnit.Fahrenheit))
        assertEquals(place, PrefsCodec.read(prefs).weatherPlace)
        PrefsCodec.write(prefs, HearthPrefs())
        assertFalse(prefs.contains(PrefsCodec.PLACE_NAME))
        assertFalse(prefs.contains(PrefsCodec.PLACE_LAT))
        assertFalse(prefs.contains(PrefsCodec.TEMPERATURE_UNIT))
        assertEquals(HearthPrefs(), PrefsCodec.read(prefs))
    }

    @Test fun anIncompleteOrImpossibleCityReadsAsNone() {
        val noCoordinates = mutablePreferencesOf(PrefsCodec.PLACE_NAME to "Oslo")
        assertEquals(null, PrefsCodec.read(noCoordinates).weatherPlace)
        val impossible = mutablePreferencesOf(
            PrefsCodec.PLACE_NAME to "Oslo",
            PrefsCodec.PLACE_LAT to 95.0,
            PrefsCodec.PLACE_LON to 10.0,
        )
        assertEquals(null, PrefsCodec.read(impossible).weatherPlace)
        val unknownUnit = mutablePreferencesOf(PrefsCodec.TEMPERATURE_UNIT to "kelvin")
        assertEquals(null, PrefsCodec.read(unknownUnit).temperatureUnit)
    }

    private val input = AppKey.forInput("com.example.tv/.hdmi.HdmiInputService/HW2")

    @Test fun inputKeysAreValidDespiteTheirSlashes() {
        assertTrue(PrefsCodec.isValidKey(input))
        assertFalse(PrefsCodec.isValidKey(AppKey("input:")))
        assertFalse(PrefsCodec.isValidKey(AppKey("input:has space")))
        assertFalse(PrefsCodec.isValidKey(AppKey("input:a\nb")))
        assertEquals(listOf(a, input), PrefsCodec.decodeFavorites(PrefsCodec.encodeFavorites(listOf(a, input))))
        assertEquals(setOf(input), PrefsCodec.decodeHidden(setOf(input.id, "input:", "input: x")))
    }

    @Test fun inputLabelsRoundTrip() {
        val value = HearthPrefs(
            favorites = listOf(input),
            hidden = setOf(input),
            inputLabels = mapOf("tv/.Hdmi/HW1" to "Game console", "tv/.Hdmi/HW2" to "Blu-ray, living room"),
        )
        val prefs = mutablePreferencesOf()
        PrefsCodec.write(prefs, value)
        assertEquals(value, PrefsCodec.read(prefs))
    }

    @Test fun inputLabelsAreCleanedAndMalformedOnesDropped() {
        val raw = setOf(
            "tv/HW1\t  Console  ",
            "tv/HW2\t   ",
            "\tNo id",
            "no tab",
            "tv/HW3\tTwo\tTabs",
            "tv/HW4\t" + "x".repeat(60),
        )
        assertEquals(
            mapOf("tv/HW1" to "Console", "tv/HW3" to "Two Tabs", "tv/HW4" to "x".repeat(PrefsCodec.MAX_INPUT_LABEL)),
            PrefsCodec.decodeInputLabels(raw),
        )
        assertEquals(emptySet<String>(), PrefsCodec.encodeInputLabels(mapOf("bad id" to "x", "tv/HW1" to " ")))
    }
}
