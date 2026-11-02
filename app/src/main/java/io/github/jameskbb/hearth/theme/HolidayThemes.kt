package io.github.jameskbb.hearth.theme

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Offline holiday overrides, bounded by local calendar days rather than 24-hour durations. */
object HolidayThemes {
    fun themeOn(date: LocalDate): ThemeId? = dates(date.year)[date]

    fun resolve(nowMillis: Long, zone: ZoneId, available: List<ThemeId>): ThemeResolution? {
        val date = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val id = themeOn(date)?.takeIf { it in available } ?: return null
        return ThemeResolution(id, null, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
    }

    /** The next holiday start or end, including the next year's New Year's Day. */
    fun nextBoundaryMillis(nowMillis: Long, zone: ZoneId): Long {
        val date = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return (dates(date.year).keys + dates(date.year + 1).keys)
            .flatMap { listOf(it, it.plusDays(1)) }
            .map { it.atStartOfDay(zone).toInstant().toEpochMilli() }
            .filter { it > nowMillis }
            .min()
    }

    private fun dates(year: Int): Map<LocalDate, ThemeId> = mapOf(
        LocalDate.of(year, 1, 1) to ThemeId("new-year"),
        easter(year) to ThemeId("easter"),
        LocalDate.of(year, 7, 4) to ThemeId("july-fourth"),
        LocalDate.of(year, 10, 31) to ThemeId("halloween"),
        LocalDate.of(year, 12, 25) to ThemeId("christmas"),
    )

    // Gregorian (Western) Easter, using the US Naval Observatory's integer algorithm:
    // https://aa.usno.navy.mil/faq/easter
    private fun easter(year: Int): LocalDate {
        val century = year / 100
        val cycle = year % 19
        val correction = (century - 17) / 25
        var moon = (century - century / 4 - (century - correction) / 3 + 19 * cycle + 15) % 30
        moon -= (moon / 28) * (1 - (moon / 28) * (29 / (moon + 1)) * ((21 - cycle) / 11))
        val weekday = (year + year / 4 + moon + 2 - century + century / 4) % 7
        val offset = moon - weekday
        val month = 3 + (offset + 40) / 44
        return LocalDate.of(year, month, offset + 28 - 31 * (month / 4))
    }
}
