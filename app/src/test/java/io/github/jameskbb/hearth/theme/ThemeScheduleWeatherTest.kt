package io.github.jameskbb.hearth.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Decision 0013, section 3: a new shuffled pick prefers packs with an exact weather match. */
class ThemeScheduleWeatherTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private fun millis(h: Int, m: Int, day: Int = 22) =
        LocalDateTime.of(2026, 10, day, h, m).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val snowy = SceneMood(SceneSky.Snow, SceneTemp.Cold)
    private val snowRule = VariantRule("snow", MoodRule(sky = setOf(SceneSky.Snow)))
    private val cloudyRule = VariantRule("overcast", MoodRule(sky = setOf(SceneSky.Cloudy)))

    private fun info(id: String, vararg rules: VariantRule) = ThemeInfo(
        ThemeId(id), id, null, null, emptySet(), PaletteSpec(0, 0, 0, 0, 0, 0, 0), bundled = true,
        weather = rules.toList(),
    )

    private val dusk = ThemeId("dusk")
    private val desert = ThemeId("desert-twilight")
    private val aurora = ThemeId("aurora")
    private val packs = listOf(info("dusk"), info("desert-twilight", snowRule), info("aurora", snowRule), info("morning-glow", cloudyRule))
    private val all = packs.map { it.id }

    @Test fun matchesAreExactWithoutSkyWidening() {
        assertEquals(setOf(desert, aurora), ThemeSchedule.weatherMatches(packs, snowy))
        // A storm would widen to cloudy for showing, but selection only prefers exact matches.
        assertEquals(emptySet<ThemeId>(), ThemeSchedule.weatherMatches(packs, SceneMood(SceneSky.Storm, SceneTemp.Mild)))
        assertEquals(emptySet<ThemeId>(), ThemeSchedule.weatherMatches(packs, null))
    }

    @Test fun aShuffledPeriodPrefersAMatchingPackInItsPool() {
        // The evening pool is Dusk and Desert Twilight; only Desert Twilight has snow.
        for (index in 0..5) {
            val r = ThemeSchedule.resolveFollowDay(
                ThemeScheduleConfig.Recommended, null, all, millis(18, 0), utc,
                weatherMatches = ThemeSchedule.weatherMatches(packs, snowy),
            ) { index }
            assertEquals(desert, r.themeId)
        }
    }

    @Test fun aMatchOutsideThePoolIsNeverPicked() {
        // Aurora matches but is not in the evening pool.
        val matches = setOf(aurora)
        for (index in 0..5) {
            val r = ThemeSchedule.resolveFollowDay(
                ThemeScheduleConfig.Recommended, null, all, millis(18, 0), utc, weatherMatches = matches,
            ) { index }
            assertTrue(r.themeId in setOf(dusk, desert))
            assertEquals(
                ThemeSchedule.resolveFollowDay(ThemeScheduleConfig.Recommended, null, all, millis(18, 0), utc) { index }.themeId,
                r.themeId,
            )
        }
    }

    @Test fun thePreviousPickIsStillAvoided() {
        // Yesterday evening was Desert Twilight: today the only match is the previous pick, so the
        // pick is made as without weather, which avoids it.
        val yesterday = ThemeRotationState("evening|UTC|0", desert)
        val r = ThemeSchedule.resolveFollowDay(
            ThemeScheduleConfig.Recommended, yesterday, all, millis(18, 0), utc, weatherMatches = setOf(desert),
        ) { 0 }
        assertEquals(dusk, r.themeId)
    }

    @Test fun aPickAlreadyMadeIsNeverRerolledForWeather() {
        val first = ThemeSchedule.resolveFollowDay(ThemeScheduleConfig.Recommended, null, all, millis(18, 0), utc) { 0 }
        assertEquals(dusk, first.themeId)
        val later = ThemeSchedule.resolveFollowDay(
            ThemeScheduleConfig.Recommended, first.rotationState, all, millis(19, 0), utc, weatherMatches = setOf(desert),
        ) { 0 }
        assertEquals(dusk, later.themeId)
        assertEquals(first.rotationState, later.rotationState)
    }

    @Test fun fixedPeriodsKeepTheirFirstUsablePack() {
        val fixed = ThemeScheduleConfig(listOf(ThemePeriod("all-day", "All day", 0, listOf(dusk, desert), shuffle = false)))
        val r = ThemeSchedule.resolveFollowDay(fixed, null, all, millis(18, 0), utc, weatherMatches = setOf(desert)) { 1 }
        assertEquals(dusk, r.themeId)
    }

    @Test fun surpriseMePrefersMatchesInItsPoolOnly() {
        val pool = setOf(dusk, desert, ThemeId("morning-glow"))
        for (index in 0..5) {
            val r = ThemeSchedule.resolveSurprise(pool, null, all, millis(12, 0), utc, weatherMatches = setOf(desert, aurora)) { index }
            assertEquals(desert, r.themeId)
        }
        // Nothing in the pool matches: as without weather.
        for (index in 0..5) {
            val r = ThemeSchedule.resolveSurprise(pool, null, all, millis(12, 0), utc, weatherMatches = setOf(aurora)) { index }
            assertEquals(ThemeSchedule.resolveSurprise(pool, null, all, millis(12, 0), utc) { index }.themeId, r.themeId)
        }
        // Today's pick stays for the day whatever the weather.
        val today = ThemeSchedule.resolveSurprise(pool, null, all, millis(8, 0), utc) { 0 }
        val again = ThemeSchedule.resolveSurprise(pool, today.rotationState, all, millis(20, 0), utc, weatherMatches = setOf(desert)) { 2 }
        assertEquals(today.themeId, again.themeId)
        assertEquals(ThemeSchedule.surpriseKey(millis(8, 0), utc), today.rotationState!!.occurrenceKey)
    }
}
