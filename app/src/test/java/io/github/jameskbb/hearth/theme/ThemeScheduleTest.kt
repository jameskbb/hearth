package io.github.jameskbb.hearth.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ThemeScheduleTest {
    private fun at(h: Int, m: Int) = ThemeSchedule.daypartAt(h * 60 + m)

    @Test fun boundariesStartTheirDaypart() {
        assertEquals(Daypart.Morning, at(5, 0))
        assertEquals(Daypart.Day, at(9, 0))
        assertEquals(Daypart.Evening, at(17, 0))
        assertEquals(Daypart.Night, at(20, 30))
    }

    @Test fun theMinuteBeforeABoundaryIsStillThePreviousDaypart() {
        assertEquals(Daypart.Night, at(4, 59))
        assertEquals(Daypart.Morning, at(8, 59))
        assertEquals(Daypart.Day, at(16, 59))
        assertEquals(Daypart.Evening, at(20, 29))
    }

    @Test fun midnightWrapsToNight() {
        assertEquals(Daypart.Night, at(0, 0))
        assertEquals(Daypart.Night, at(23, 59))
        assertEquals(Daypart.Night, ThemeSchedule.daypartAt(24 * 60))
    }

    private val utc: ZoneId = ZoneOffset.UTC
    private fun millis(h: Int, m: Int, day: Int = 22) =
        LocalDateTime.of(2026, 10, day, h, m).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun daypartFromMillisUsesTheZone() {
        assertEquals(Daypart.Evening, ThemeSchedule.daypartAt(millis(18, 0), utc))
        assertEquals(Daypart.Day, ThemeSchedule.daypartAt(millis(18, 0), ZoneOffset.ofHours(-3)))
    }

    @Test fun nextBoundaryIsTheNextOneToday() {
        assertEquals(millis(9, 0), ThemeSchedule.nextBoundaryMillis(millis(5, 0), utc))
        assertEquals(millis(20, 30), ThemeSchedule.nextBoundaryMillis(millis(17, 1), utc))
    }

    @Test fun nextBoundaryAfterNightIsTomorrowMorning() {
        assertEquals(millis(5, 0, day = 23), ThemeSchedule.nextBoundaryMillis(millis(20, 30), utc))
        assertEquals(millis(5, 0), ThemeSchedule.nextBoundaryMillis(millis(0, 0), utc))
    }

    private fun info(id: String, vararg parts: Daypart) = ThemeInfo(
        ThemeId(id), id, null, null, parts.toSet(), PaletteSpec(0, 0, 0, 0, 0, 0, 0), bundled = true,
    )

    private val bundled = listOf(
        info("misty-forest", Daypart.Morning),
        info("desert-twilight", Daypart.Day),
        info("dusk", Daypart.Evening),
        info("aurora", Daypart.Night),
    )

    @Test fun anOptOutWithDaypartsStillCannotEnterLegacyAutomaticDiscovery() {
        val manual = info("manual-pack", Daypart.Morning).copy(autoRotationEligible = false)
        assertEquals(ThemeId("misty-forest"), ThemeSchedule.resolveAuto(listOf(manual) + bundled, Daypart.Morning))
    }

    @Test fun implicitRotationPreservesExistingChoicesAndNeverFallsBackToAManualPack() {
        val original = bundled.map { it.id }
        val manual = ThemeId("manual-pack")
        for (index in original.indices) {
            val before = ThemeSchedule.resolveSurprise(emptySet(), null, original, 0L, utc) { index }
            val after = ThemeSchedule.resolveSurprise(emptySet(), null, original + manual, 0L, utc,
                autoEligible = original) { index }
            assertEquals(before, after)
        }
        val noEligible = ThemeSchedule.resolveSurprise(emptySet(), null, listOf(manual), 0L, utc,
            autoEligible = emptyList())
        assertEquals(ThemeId.Default, noEligible.themeId)
        val missingExplicit = ThemeSchedule.resolveSurprise(setOf(ThemeId("missing")), null, listOf(manual), 0L, utc,
            autoEligible = emptyList())
        assertEquals(ThemeId.Default, missingExplicit.themeId)
    }

    @Test fun autoPicksTheFirstPackListingTheDaypart() {
        assertEquals(ThemeId("misty-forest"), ThemeSchedule.resolveAuto(bundled, Daypart.Morning))
        assertEquals(ThemeId("aurora"), ThemeSchedule.resolveAuto(bundled, Daypart.Night))
        val withInstalled = bundled + info("alpine", Daypart.Night)
        assertEquals(ThemeId("aurora"), ThemeSchedule.resolveAuto(withInstalled, Daypart.Night))
    }

    @Test fun autoWithNoMatchKeepsDusk() {
        assertEquals(ThemeId.Default, ThemeSchedule.resolveAuto(listOf(info("aurora", Daypart.Night)), Daypart.Day))
        assertEquals(ThemeId.Default, ThemeSchedule.resolveAuto(emptyList(), Daypart.Day))
    }

    @Test fun aChoiceThatIsNoLongerListedFallsBackToDusk() {
        assertEquals(ThemeId.Default, ThemeSchedule.resolve(ThemeId("uninstalled"), bundled, Daypart.Day))
        assertEquals(ThemeId("aurora"), ThemeSchedule.resolve(ThemeId("aurora"), bundled, Daypart.Day))
        assertEquals(ThemeId("desert-twilight"), ThemeSchedule.resolve(ThemeId.Auto, bundled, Daypart.Day))
    }

    private val allRecommended = listOf(
        ThemeId("morning-glow"), ThemeId("misty-forest"),
        ThemeId("sunlit-valley"), ThemeId("golden-prairie"),
        ThemeId("dusk"), ThemeId("desert-twilight"), ThemeId("aurora"),
    )

    @Test fun recommendedScheduleHasTheRequestedBoundariesAndPools() {
        val periods = ThemeScheduleConfig.Recommended.orderedPeriods
        assertEquals(listOf(300, 540, 1020, 1230), periods.map { it.startMinute })
        assertEquals(
            listOf(ThemeId("morning-glow"), ThemeId("misty-forest")),
            periods[0].themeIds,
        )
        assertEquals(
            listOf(ThemeId("sunlit-valley"), ThemeId("golden-prairie")),
            periods[1].themeIds,
        )
        assertEquals(listOf(ThemeId("dusk"), ThemeId("desert-twilight")), periods[2].themeIds)
        assertEquals(listOf(ThemeId("aurora")), periods[3].themeIds)
    }

    @Test fun everyRecommendedBoundaryStartsItsPeriodExactly() {
        val schedule = ThemeScheduleConfig.Recommended
        val cases = listOf(
            Triple(4 * 60 + 59, "night", "morning"),
            Triple(8 * 60 + 59, "morning", "daytime"),
            Triple(16 * 60 + 59, "daytime", "evening"),
            Triple(20 * 60 + 29, "evening", "night"),
        )
        cases.forEach { (beforeMinute, beforeId, afterId) ->
            val before = ThemeSchedule.currentOccurrence(
                schedule, millis(beforeMinute / 60, beforeMinute % 60), utc,
            )
            val boundary = before.endMillis
            assertEquals(beforeId, before.period.id)
            assertEquals(afterId, ThemeSchedule.currentOccurrence(schedule, boundary, utc).period.id)
        }
    }

    @Test fun customBoundariesCoverTheWholeDayIncludingOvernight() {
        val schedule = ThemeScheduleConfig(
            listOf(
                ThemePeriod("late", "Late", 22 * 60, listOf(ThemeId("aurora")), false),
                ThemePeriod("early", "Early", 7 * 60 + 15, listOf(ThemeId("dusk")), false),
                ThemePeriod("middle", "Middle", 12 * 60, listOf(ThemeId("misty-forest")), false),
            ),
        )
        val beforeFirst = ThemeSchedule.currentOccurrence(schedule, millis(6, 0), utc)
        assertEquals("late", beforeFirst.period.id)
        assertEquals(millis(22, 0, day = 21), beforeFirst.startMillis)
        assertEquals(millis(7, 15), beforeFirst.endMillis)
        assertEquals("early", ThemeSchedule.currentOccurrence(schedule, millis(7, 15), utc).period.id)
        assertEquals("middle", ThemeSchedule.currentOccurrence(schedule, millis(21, 59), utc).period.id)
        assertEquals("late", ThemeSchedule.currentOccurrence(schedule, millis(22, 0), utc).period.id)
    }

    @Test fun onePeriodScheduleSpansSuccessiveLocalDays() {
        val schedule = ThemeScheduleConfig(
            listOf(ThemePeriod("all-day", "All day", 4 * 60, listOf(ThemeId("dusk")), false)),
        )
        val occurrence = ThemeSchedule.currentOccurrence(schedule, millis(12, 0), utc)
        assertEquals(millis(4, 0), occurrence.startMillis)
        assertEquals(millis(4, 0, day = 23), occurrence.endMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateStartsCannotCreateAnAmbiguousSchedule() {
        ThemeScheduleConfig(
            listOf(
                ThemePeriod("one", "One", 300, emptyList(), false),
                ThemePeriod("two", "Two", 300, emptyList(), false),
            ),
        )
    }

    @Test fun followDaySelectionIsStableForTheOccurrence() {
        val first = ThemeSchedule.resolveFollowDay(
            ThemeScheduleConfig.Recommended, null, allRecommended, millis(18, 0), utc,
        ) { 1 }
        val restored = ThemeSchedule.resolveFollowDay(
            ThemeScheduleConfig.Recommended, first.rotationState, allRecommended, millis(19, 45), utc,
        ) { 0 }
        assertEquals(ThemeId("desert-twilight"), first.themeId)
        assertEquals(first.themeId, restored.themeId)
        assertEquals(first.rotationState, restored.rotationState)
        assertEquals(millis(20, 30), restored.nextChangeMillis)
    }

    @Test fun followDayAvoidsThePeriodsPreviousSelection() {
        val friday = ThemeSchedule.resolveFollowDay(
            ThemeScheduleConfig.Recommended, null, allRecommended, millis(18, 0), utc,
        ) { 0 }
        val saturday = ThemeSchedule.resolveFollowDay(
            ThemeScheduleConfig.Recommended, friday.rotationState, allRecommended, millis(18, 0, day = 23), utc,
        ) { 0 }
        assertEquals(ThemeId("dusk"), friday.themeId)
        assertEquals(ThemeId("desert-twilight"), saturday.themeId)
    }

    @Test fun eachPeriodKeepsItsOwnRotationHistory() {
        val priorEvening = ThemeRotationState("old-evening", ThemeId("dusk"))
        val selected = ThemeSchedule.resolveFollowDay(
            ThemeScheduleConfig.Recommended, priorEvening, allRecommended, millis(18, 0), utc,
        ) { 0 }
        assertEquals(ThemeId("desert-twilight"), selected.themeId)
        assertEquals("evening", selected.period?.id)
    }

    @Test fun fixedPeriodUsesItsFirstUsableAssignedTheme() {
        val night = ThemeSchedule.resolveFollowDay(
            ThemeScheduleConfig.Recommended, null, allRecommended, millis(23, 0), utc,
        ) { error("fixed selection must not ask for randomness") }
        assertEquals(ThemeId("aurora"), night.themeId)
    }

    @Test fun removedAndInvalidAssignedThemesFallBackGracefully() {
        val withoutEvening = allRecommended - setOf(ThemeId("dusk"), ThemeId("desert-twilight"))
        val result = ThemeSchedule.resolveFollowDay(
            ThemeScheduleConfig.Recommended,
            ThemeRotationState("old", ThemeId("desert-twilight")),
            withoutEvening,
            millis(18, 0),
            utc,
        ) { 0 }
        assertEquals(ThemeId("morning-glow"), result.themeId)

        val withDusk = ThemeSchedule.resolveFollowDay(
            ThemeScheduleConfig.Recommended, null, listOf(ThemeId.Default), millis(18, 0), utc,
        ) { 0 }
        assertEquals(ThemeId.Default, withDusk.themeId)
    }

    @Test fun singleThemeKeepsTheChoiceOrFallsBack() {
        assertEquals(
            ThemeId("aurora"),
            ThemeSchedule.resolveSingle(ThemeId("aurora"), allRecommended).themeId,
        )
        assertEquals(
            ThemeId.Default,
            ThemeSchedule.resolveSingle(ThemeId("removed"), allRecommended).themeId,
        )
    }

    @Test fun surpriseMeIsStableForOneLocalDayAndAvoidsYesterday() {
        val first = ThemeSchedule.resolveSurprise(
            emptySet(), null, listOf(ThemeId("dusk"), ThemeId("aurora")), millis(10, 0), utc,
        ) { 0 }
        val sameDay = ThemeSchedule.resolveSurprise(
            emptySet(), first.rotationState, listOf(ThemeId("dusk"), ThemeId("aurora")), millis(23, 59), utc,
        ) { 1 }
        val tomorrow = ThemeSchedule.resolveSurprise(
            emptySet(), first.rotationState, listOf(ThemeId("dusk"), ThemeId("aurora")), millis(0, 1, day = 23), utc,
        ) { 0 }
        assertEquals(ThemeId("dusk"), first.themeId)
        assertEquals(first.themeId, sameDay.themeId)
        assertEquals(ThemeId("aurora"), tomorrow.themeId)
        assertEquals(millis(0, 0, day = 23), first.nextChangeMillis)
    }

    @Test fun surpriseMeRespectsANonEmptyEnabledPool() {
        val result = ThemeSchedule.resolveSurprise(
            setOf(ThemeId("aurora"), ThemeId("not-installed")),
            null,
            allRecommended,
            millis(10, 0),
            utc,
        ) { 0 }
        assertEquals(ThemeId("aurora"), result.themeId)
    }

    @Test fun timezoneChangeProducesANewOccurrenceIdentity() {
        val instant = millis(18, 0)
        val utcOccurrence = ThemeSchedule.currentOccurrence(ThemeScheduleConfig.Recommended, instant, utc)
        val chicago = ZoneId.of("America/Chicago")
        val localOccurrence = ThemeSchedule.currentOccurrence(ThemeScheduleConfig.Recommended, instant, chicago)
        assertNotEquals(utcOccurrence.key, localOccurrence.key)
        assertTrue(utcOccurrence.key.contains("|Z|"))
        assertTrue(localOccurrence.key.contains("America/Chicago"))
    }

    @Test fun springGapBoundaryUsesTheZonesResolvedInstant() {
        val newYork = ZoneId.of("America/New_York")
        val schedule = ThemeScheduleConfig(
            listOf(
                ThemePeriod("before", "Before", 0, listOf(ThemeId("dusk")), false),
                ThemePeriod("after", "After", 2 * 60 + 30, listOf(ThemeId("aurora")), false),
            ),
        )
        val before = ZonedDateTime.of(2026, 3, 8, 1, 59, 0, 0, newYork).toInstant().toEpochMilli()
        val resolved = LocalDateTime.of(2026, 3, 8, 2, 30).atZone(newYork).toInstant().toEpochMilli()
        assertEquals(resolved, ThemeSchedule.nextBoundaryMillis(schedule, before, newYork))
        assertEquals("after", ThemeSchedule.currentOccurrence(schedule, resolved, newYork).period.id)
    }

    @Test fun fallOverlapDoesNotMoveBackToTheEarlierPeriod() {
        val newYork = ZoneId.of("America/New_York")
        val schedule = ThemeScheduleConfig(
            listOf(
                ThemePeriod("before", "Before", 0, listOf(ThemeId("dusk")), false),
                ThemePeriod("after", "After", 90, listOf(ThemeId("aurora")), false),
            ),
        )
        val secondOneFifteen = ZonedDateTime.ofLocal(
            LocalDateTime.of(2026, 11, 1, 1, 15),
            newYork,
            ZoneOffset.ofHours(-5),
        ).toInstant().toEpochMilli()
        assertEquals("after", ThemeSchedule.currentOccurrence(schedule, secondOneFifteen, newYork).period.id)
    }

    @Test fun surpriseDayLengthFollowsDaylightSavingTime() {
        val newYork = ZoneId.of("America/New_York")
        val noon = ZonedDateTime.of(2026, 3, 8, 12, 0, 0, 0, newYork).toInstant().toEpochMilli()
        val result = ThemeSchedule.resolveSurprise(emptySet(), null, allRecommended, noon, newYork) { 0 }
        val start = LocalDate.of(2026, 3, 8).atStartOfDay(newYork).toInstant().toEpochMilli()
        assertEquals(23L * 60 * 60 * 1000, result.nextChangeMillis - start)
    }
}
