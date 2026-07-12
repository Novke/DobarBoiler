package rs.novica.dobarboiler.domain

import java.time.Instant
import java.time.LocalDate

/** One-shot schedule modifications. The data layer purges expired rows. */
sealed interface OneTimeOverride {
    val id: Long
    val createdAt: Instant

    /** Replace (or skip) the planner assignment for a single window-start date. */
    data class DayReplacement(
        override val id: Long, override val createdAt: Instant,
        val date: LocalDate,           // window-start date being replaced
        val presetId: Long?,           // null = skip heating that night
    ) : OneTimeOverride

    /** "Toplo u..." — hot water ready at an absolute instant, DST-immune. */
    data class HeatByTime(
        override val id: Long, override val createdAt: Instant,
        val eventTime: Instant,        // absolute instant — DST-immune
        val targetTemp: Int,           // 40..75
        val eco: Boolean = false,      // speed by default
        val holdMinutes: Int = 60,     // maintain past eventTime; 0..180
    ) : OneTimeOverride
}

/** Boiler-off hold; `resumeAt == null` means "until cancelled by the user". */
data class VacationHold(
    val id: Long, val startsAt: Instant, val resumeAt: Instant?,
    /** §6.6 — run the anti-legionella hygiene cycle on return from a >7-day hold. */
    val antiLegOnReturn: Boolean = true,
)

/** An app-level boost run (device stays in mode 1; boost = power + temp + eco off). */
data class BoostSession(
    val id: Long, val startedAt: Instant, val targetTemp: Int,
    val timeoutAt: Instant,            // startedAt + config.boostMaxDuration
    val completedAt: Instant? = null,
)
