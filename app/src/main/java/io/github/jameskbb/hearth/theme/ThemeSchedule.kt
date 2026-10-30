package io.github.jameskbb.hearth.theme

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/** The three ways Hearth can choose the theme shown on Home. */
enum class ThemeMode(val storedName: String) {
    Single("single"),
    FollowTheDay("follow_day"),
    SurpriseMe("surprise_me");

    companion object {
        fun fromStored(value: String?): ThemeMode? = entries.firstOrNull { it.storedName == value }
    }
}

/**
 * One named part of a day. Periods have starts, not independently editable ends: the next
 * period's start is this period's end. That representation covers every local minute exactly
 * once and cannot contain an overlap.
 */
data class ThemePeriod(
    val id: String,
    val name: String,
    val startMinute: Int,
    val themeIds: List<ThemeId>,
    val shuffle: Boolean,
) {
    init {
        require(isValidId(id)) { "invalid period id" }
        require(name.isNotEmpty() && cleanName(name) == name) { "invalid period name" }
        require(startMinute in 0 until MINUTES_PER_DAY) { "invalid period start" }
        require(themeIds.all { ThemeId.isValidPackId(it.name) }) { "invalid period theme" }
        require(themeIds.distinct().size == themeIds.size) { "duplicate period theme" }
    }

    companion object {
        const val MAX_NAME_LENGTH = 40
        const val MINUTES_PER_DAY = 24 * 60
        private val ID = Regex("[a-z0-9-]{1,40}")

        fun isValidId(value: String): Boolean = ID.matches(value)

        fun cleanName(value: String): String = value
            .map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .trim()
            .take(MAX_NAME_LENGTH)
            .trim()
    }
}

/** A complete day made from ordered, uniquely identified period boundaries. */
data class ThemeScheduleConfig(val periods: List<ThemePeriod>) {
    init {
        require(periods.isNotEmpty()) { "a schedule needs at least one period" }
        require(periods.map { it.id }.distinct().size == periods.size) { "duplicate period id" }
        require(periods.map { it.startMinute }.distinct().size == periods.size) { "duplicate period start" }
    }

    /** Chronological order, regardless of the order used by a caller or old stored data. */
    val orderedPeriods: List<ThemePeriod> = periods.sortedBy { it.startMinute }

    companion object {
        val Recommended = ThemeScheduleConfig(
            listOf(
                ThemePeriod(
                    id = "morning",
                    name = "Morning",
                    startMinute = 5 * 60,
                    themeIds = listOf(ThemeId("morning-glow"), ThemeId("misty-forest")),
                    shuffle = true,
                ),
                ThemePeriod(
                    id = "daytime",
                    name = "Daytime",
                    startMinute = 9 * 60,
                    themeIds = listOf(ThemeId("sunlit-valley"), ThemeId("golden-prairie")),
                    shuffle = true,
                ),
                ThemePeriod(
                    id = "evening",
                    name = "Evening",
                    startMinute = 17 * 60,
                    themeIds = listOf(ThemeId("dusk"), ThemeId("desert-twilight")),
                    shuffle = true,
                ),
                ThemePeriod(
                    id = "night",
                    name = "Night",
                    startMinute = 20 * 60 + 30,
                    themeIds = listOf(ThemeId("aurora")),
                    shuffle = false,
                ),
            ),
        )
    }
}

/** One concrete occurrence of a configured period on the device's local timeline. */
data class ScheduleOccurrence(
    val period: ThemePeriod,
    val startMillis: Long,
    val endMillis: Long,
    /** Includes the zone and resolved instant, so timezone and boundary edits cannot reuse a choice. */
    val key: String,
)

/** The persisted choice for one period, or for Surprise Me's local day. */
data class ThemeRotationState(val occurrenceKey: String, val themeId: ThemeId)

/** A resolved theme and any new rotation state the caller should persist. */
data class ThemeResolution(
    val themeId: ThemeId,
    val rotationState: ThemeRotationState?,
    val nextChangeMillis: Long,
    val period: ThemePeriod? = null,
)

/**
 * Pure local-clock scheduling and stable theme rotation. No network, location, or background
 * polling is involved. Callers re-run resolution on resume and once at [nextBoundaryMillis].
 */
object ThemeSchedule {
    private const val MINUTES_PER_DAY = ThemePeriod.MINUTES_PER_DAY

    /** The occurrence containing [nowMillis], including periods that cross midnight. */
    fun currentOccurrence(
        schedule: ThemeScheduleConfig,
        nowMillis: Long,
        zone: ZoneId,
    ): ScheduleOccurrence {
        val now = Instant.ofEpochMilli(nowMillis)
        val localDate = now.atZone(zone).toLocalDate()
        val starts = boundaryInstants(schedule, localDate.minusDays(1), localDate.plusDays(1), zone)
        val current = starts.lastOrNull { !it.at.toInstant().isAfter(now) }
            ?: error("schedule has no boundary before now")
        val end = starts.firstOrNull { it.at.toInstant().isAfter(current.at.toInstant()) }
            ?: boundaryInstants(schedule, localDate.plusDays(2), localDate.plusDays(2), zone).first()
        val startMillis = current.at.toInstant().toEpochMilli()
        val endMillis = end.at.toInstant().toEpochMilli()
        return ScheduleOccurrence(
            period = current.period,
            startMillis = startMillis,
            endMillis = endMillis,
            key = occurrenceKey(current.period.id, zone, startMillis),
        )
    }

    /** The first actual boundary instant strictly after [nowMillis]. */
    fun nextBoundaryMillis(schedule: ThemeScheduleConfig, nowMillis: Long, zone: ZoneId): Long {
        val now = Instant.ofEpochMilli(nowMillis)
        val date = now.atZone(zone).toLocalDate()
        return boundaryInstants(schedule, date, date.plusDays(2), zone)
            .first { it.at.toInstant().isAfter(now) }
            .at.toInstant().toEpochMilli()
    }

    /** A fixed pack, with a graceful fallback if that pack is currently unavailable. */
    fun resolveSingle(choice: ThemeId, available: Collection<ThemeId>): ThemeResolution {
        val ids = usable(available)
        return ThemeResolution(
            themeId = choice.takeIf { it in ids } ?: fallback(ids),
            rotationState = null,
            nextChangeMillis = Long.MAX_VALUE,
        )
    }

    /**
     * Resolve Follow the Day. Pass the saved state for the current period id. A valid saved
     * choice stays in place for the whole occurrence, across launches and restarts. On a new
     * occurrence, shuffle avoids that period's previous choice when another option is usable.
     */
    fun resolveFollowDay(
        schedule: ThemeScheduleConfig,
        saved: ThemeRotationState?,
        available: Collection<ThemeId>,
        nowMillis: Long,
        zone: ZoneId,
        autoEligible: Collection<ThemeId> = available,
        chooseIndex: (Int) -> Int = { Random.nextInt(it) },
    ): ThemeResolution {
        val occurrence = currentOccurrence(schedule, nowMillis, zone)
        val usable = usable(available)
        val eligible = occurrence.period.themeIds.filter { it in usable }.distinct()
        val current = saved?.takeIf { it.occurrenceKey == occurrence.key && it.themeId in eligible }
        val selected = current?.themeId ?: when {
            eligible.isEmpty() -> fallback(usable.filter { it in autoEligible })
            !occurrence.period.shuffle || eligible.size == 1 -> eligible.first()
            else -> chooseAvoiding(eligible, saved?.themeId, chooseIndex)
        }
        val state = ThemeRotationState(occurrence.key, selected)
        return ThemeResolution(selected, state, occurrence.endMillis, occurrence.period)
    }

    /**
     * Resolve Surprise Me once per local day. An empty enabled pool means usable automatically eligible themes.
     * A non-empty pool respects the user's choices; if all of them disappear, Hearth falls back.
     */
    fun resolveSurprise(
        enabled: Collection<ThemeId>,
        saved: ThemeRotationState?,
        available: Collection<ThemeId>,
        nowMillis: Long,
        zone: ZoneId,
        autoEligible: Collection<ThemeId> = available,
        chooseIndex: (Int) -> Int = { Random.nextInt(it) },
    ): ThemeResolution {
        val usable = usable(available)
        val requested = enabled.filter { ThemeId.isValidPackId(it.name) }.distinct()
        val eligible = if (requested.isEmpty()) usable.filter { it in autoEligible } else requested.filter { it in usable }
        val start = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().atStartOfDay(zone)
        val next = start.toLocalDate().plusDays(1).atStartOfDay(zone)
        val key = "surprise|${zone.id}|${start.toInstant().toEpochMilli()}"
        val current = saved?.takeIf { it.occurrenceKey == key && it.themeId in eligible }
        val selected = current?.themeId ?: when {
            eligible.isEmpty() -> fallback(usable.filter { it in autoEligible })
            eligible.size == 1 -> eligible.first()
            else -> chooseAvoiding(eligible, saved?.themeId, chooseIndex)
        }
        return ThemeResolution(
            themeId = selected,
            rotationState = ThemeRotationState(key, selected),
            nextChangeMillis = next.toInstant().toEpochMilli(),
        )
    }

    /** The gallery's included set, including explicit ids that are temporarily unavailable. */
    fun surprisePool(enabled: Set<ThemeId>, themes: List<ThemeInfo>): Set<ThemeId> =
        enabled.ifEmpty { themes.filter { it.autoRotationEligible }.mapTo(linkedSetOf()) { it.id } }

    private data class Boundary(val period: ThemePeriod, val at: ZonedDateTime)

    private fun boundaryInstants(
        schedule: ThemeScheduleConfig,
        firstDate: LocalDate,
        lastDate: LocalDate,
        zone: ZoneId,
    ): List<Boundary> {
        val out = ArrayList<Boundary>()
        var date = firstDate
        while (!date.isAfter(lastDate)) {
            schedule.orderedPeriods.forEach { period ->
                val time = LocalTime.of(period.startMinute / 60, period.startMinute % 60)
                // atZone resolves a daylight-saving gap forward and an overlap to its earlier offset.
                out += Boundary(period, date.atTime(time).atZone(zone))
            }
            date = date.plusDays(1)
        }
        return out.sortedWith(compareBy({ it.at.toInstant() }, { it.period.startMinute }))
    }

    private fun occurrenceKey(periodId: String, zone: ZoneId, startMillis: Long): String =
        "$periodId|${zone.id}|$startMillis"

    private fun usable(available: Collection<ThemeId>): List<ThemeId> =
        available.filter { ThemeId.isValidPackId(it.name) }.distinct()

    private fun fallback(available: List<ThemeId>): ThemeId = when {
        ThemeId.Default in available -> ThemeId.Default
        available.isNotEmpty() -> available.first()
        else -> ThemeId.Default
    }

    private fun chooseAvoiding(
        eligible: List<ThemeId>,
        previous: ThemeId?,
        chooseIndex: (Int) -> Int,
    ): ThemeId {
        val choices = eligible.filterNot { it == previous }.ifEmpty { eligible }
        return choices[Math.floorMod(chooseIndex(choices.size), choices.size)]
    }

    // Compatibility helpers for callers migrating from fixed Auto dayparts.

    fun daypartAt(minuteOfDay: Int): Daypart {
        val m = Math.floorMod(minuteOfDay, MINUTES_PER_DAY)
        return when {
            m < 5 * 60 -> Daypart.Night
            m < 9 * 60 -> Daypart.Morning
            m < 17 * 60 -> Daypart.Day
            m < 20 * 60 + 30 -> Daypart.Evening
            else -> Daypart.Night
        }
    }

    fun daypartAt(millis: Long, zone: ZoneId): Daypart {
        val time = Instant.ofEpochMilli(millis).atZone(zone)
        return daypartAt(time.hour * 60 + time.minute)
    }

    fun nextBoundaryMillis(nowMillis: Long, zone: ZoneId): Long =
        nextBoundaryMillis(ThemeScheduleConfig.Recommended, nowMillis, zone)

    fun resolveAuto(themes: List<ThemeInfo>, daypart: Daypart): ThemeId =
        themes.firstOrNull { it.autoRotationEligible && daypart in it.dayparts }?.id ?: ThemeId.Default

    fun resolve(choice: ThemeId, themes: List<ThemeInfo>, daypart: Daypart): ThemeId = when {
        choice.isAuto -> resolveAuto(themes, daypart)
        themes.any { it.id == choice } -> choice
        else -> ThemeId.Default
    }
}
