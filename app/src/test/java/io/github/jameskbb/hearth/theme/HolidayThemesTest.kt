package io.github.jameskbb.hearth.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class HolidayThemesTest {
    @Test fun holidaysApplyOnlyOnTheirCalendarDate() {
        val dates = mapOf(
            "2026-01-01" to "new-year", "2026-04-05" to "easter", "2026-07-04" to "july-fourth",
            "2026-10-31" to "halloween", "2026-12-25" to "christmas",
        )
        dates.forEach { (raw, id) ->
            val date = LocalDate.parse(raw)
            assertEquals(ThemeId(id), HolidayThemes.themeOn(date))
            assertNull(HolidayThemes.themeOn(date.minusDays(1)))
            assertNull(HolidayThemes.themeOn(date.plusDays(1)))
        }
    }

    @Test fun easterMovesWithTheGregorianCalendarIncludingCenturyCorrections() {
        // Published Western Easter dates, including both ends of its March/April range.
        listOf("2008-03-23", "2024-03-31", "2025-04-20", "2027-03-28", "2038-04-25", "2100-03-28")
            .forEach { assertEquals(ThemeId("easter"), HolidayThemes.themeOn(LocalDate.parse(it))) }
    }

    @Test fun missingHolidayPackLeavesTheChosenModeInCharge() {
        val now = LocalDate.of(2026, 12, 25).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertNull(HolidayThemes.resolve(now, ZoneOffset.UTC, listOf(ThemeId.Default)))
        val result = HolidayThemes.resolve(now, ZoneOffset.UTC, listOf(ThemeId("christmas")))!!
        assertEquals(ThemeId("christmas"), result.themeId)
        assertNull(result.rotationState)
    }

    @Test fun boundariesUseLocalDatesAcrossZonesAndTheYearRollover() {
        val utc = ZoneOffset.UTC
        val christmas = LocalDate.of(2026, 12, 25).atStartOfDay(utc).toInstant().toEpochMilli()
        val west = ZoneId.of("America/Los_Angeles")
        assertNull(HolidayThemes.resolve(christmas, west, listOf(ThemeId("christmas"))))
        val localChristmas = LocalDate.of(2026, 12, 25).atStartOfDay(west).toInstant().toEpochMilli()
        assertEquals(localChristmas, HolidayThemes.nextBoundaryMillis(christmas, west))
        assertEquals(
            LocalDate.of(2026, 12, 26).atStartOfDay(west).toInstant().toEpochMilli(),
            HolidayThemes.nextBoundaryMillis(localChristmas, west),
        )
        val december31 = LocalDate.of(2026, 12, 31).atStartOfDay(utc).toInstant().toEpochMilli()
        assertEquals(
            LocalDate.of(2027, 1, 1).atStartOfDay(utc).toInstant().toEpochMilli(),
            HolidayThemes.nextBoundaryMillis(december31, utc),
        )
    }
}
