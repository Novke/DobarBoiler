package rs.novica.dobarboiler.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * The pure scheduling brain. Deterministic for (now, inputs, zone); no I/O, no clock reads.
 *
 * Precedence (first active rule wins):
 * 1. Boost  2. Heat-by-time  3. Vacation  4. Day override  5. Weekly plan  6. Default (OFF / KEEP_WARM)
 */
class DefaultSchedulerEngine(private val zone: ZoneId) : SchedulerEngine {

    // ------------------------------------------------------------------ resolve

    override fun resolve(now: Instant, inputs: ScheduleInputs): Resolution {
        val cfg = inputs.config
        val next = nextTransition(now, inputs)

        // 1. Boost
        val boost = inputs.boost
        if (boost != null && boost.completedAt == null && now < boost.timeoutAt) {
            return Resolution(
                desired = DesiredState(powerOn = true, targetTemp = clamp(boost.targetTemp, cfg), eco = false),
                source = StateSource.BOOST,
                nextTransition = next,
            )
        }

        // 2. Heat-by-time (multiple active: highest targetTemp wins; tie → later eventTime)
        val activeHbt = inputs.overrides
            .filterIsInstance<OneTimeOverride.HeatByTime>()
            .filter { o ->
                val start = computedStart(o, inputs, now)
                start <= now && now < holdEnd(o)
            }
            .maxWithOrNull(compareBy({ it.targetTemp }, { it.eventTime }))
        if (activeHbt != null) {
            return Resolution(
                desired = DesiredState(powerOn = true, targetTemp = clamp(activeHbt.targetTemp, cfg), eco = activeHbt.eco),
                source = StateSource.HEAT_BY_TIME,
                nextTransition = next,
            )
        }

        // 3. Vacation
        val vacation = inputs.vacation
        if (vacation != null && vacation.startsAt <= now &&
            (vacation.resumeAt == null || now < vacation.resumeAt)
        ) {
            return Resolution(
                desired = DesiredState(powerOn = false, targetTemp = null, eco = null),
                source = StateSource.VACATION,
                nextTransition = next,
            )
        }

        // 4 + 5. Day override / weekly plan — find the window containing `now`.
        val active = activeWindowDay(now, inputs)
        if (active != null) {
            val (day, _) = active
            return when {
                day.override != null && day.override.presetId == null ->
                    // Skip-night override: explicitly off for the whole window.
                    Resolution(
                        desired = DesiredState(powerOn = false, targetTemp = null, eco = null),
                        source = StateSource.DAY_OVERRIDE,
                        nextTransition = next,
                    )
                day.preset != null -> Resolution(
                    desired = DesiredState(
                        powerOn = true,
                        targetTemp = clamp(day.preset.targetTemp, cfg),
                        eco = day.preset.eco,
                    ),
                    source = if (day.override != null) StateSource.DAY_OVERRIDE else StateSource.WEEKLY_PLAN,
                    nextTransition = next,
                )
                else -> defaultResolution(cfg, next)
            }
        }

        // 6. Default
        return defaultResolution(cfg, next)
    }

    private fun defaultResolution(cfg: EngineConfig, next: Transition?): Resolution =
        when (cfg.outsideWindow) {
            OutsideWindowBehavior.OFF -> Resolution(
                desired = DesiredState(powerOn = false, targetTemp = null, eco = null),
                source = StateSource.DEFAULT_OFF,
                nextTransition = next,
            )
            OutsideWindowBehavior.KEEP_WARM -> Resolution(
                desired = DesiredState(powerOn = true, targetTemp = clamp(cfg.keepWarmTemp, cfg), eco = true),
                source = StateSource.KEEP_WARM,
                nextTransition = next,
            )
        }

    // ------------------------------------------------------------------ heat-by-time

    override fun computedStart(
        override: OneTimeOverride.HeatByTime,
        inputs: ScheduleInputs,
        now: Instant,
    ): Instant {
        val snapshot = inputs.lastSnapshot
        val estimate =
            if (snapshot != null && Duration.between(snapshot.at, now).abs() < SNAPSHOT_FRESH) snapshot.temp
            else STALE_TEMP_ASSUMPTION_C   // pessimistic → starts early, never late
        return hbtStart(override, estimate, inputs.config.heatingRate)
    }

    private fun holdEnd(o: OneTimeOverride.HeatByTime): Instant =
        o.eventTime.plus(Duration.ofMinutes(o.holdMinutes.toLong()))

    // ------------------------------------------------------------------ plan (diff)

    override fun plan(desired: DesiredState, observed: DeviceSnapshot?): List<BoilerCommand> {
        if (observed == null) {
            // Full assert — we know nothing about the device.
            return buildList {
                add(BoilerCommand.SetMode(MODE_MANUAL))
                add(BoilerCommand.SetPower(desired.powerOn))
                if (desired.powerOn && desired.targetTemp != null) add(BoilerCommand.SetTemperature(desired.targetTemp))
                if (desired.powerOn && desired.eco != null) add(BoilerCommand.SetEco(desired.eco))
            }
        }
        if (!desired.powerOn) {
            // Never touch temp/eco/mode when turning off.
            return if (observed.on) listOf(BoilerCommand.SetPower(false)) else emptyList()
        }
        return buildList {
            if (observed.mode != MODE_MANUAL) add(BoilerCommand.SetMode(MODE_MANUAL))
            if (!observed.on) add(BoilerCommand.SetPower(true))
            val t = desired.targetTemp
            if (t != null && observed.reqTemp.roundToInt() != t) add(BoilerCommand.SetTemperature(t))
            val e = desired.eco
            if (e != null && observed.eco != e) add(BoilerCommand.SetEco(e))
        }
    }

    // ------------------------------------------------------------------ next transition

    private fun nextTransition(now: Instant, inputs: ScheduleInputs): Transition? {
        val candidates = mutableListOf<Transition>()

        fun add(at: Instant?, reason: TransitionReason) {
            if (at != null && at > now) candidates += Transition(at, reason)
        }

        // Boost timeout
        inputs.boost?.takeIf { it.completedAt == null }?.let { add(it.timeoutAt, TransitionReason.BOOST_TIMEOUT) }

        // Heat-by-time boundaries
        for (o in inputs.overrides.filterIsInstance<OneTimeOverride.HeatByTime>()) {
            val start = computedStart(o, inputs, now)
            add(start, TransitionReason.HBT_START)
            add(start.minus(REASSESS_LEAD), TransitionReason.HBT_REASSESS)
            add(holdEnd(o), TransitionReason.HBT_END)
        }

        // Vacation boundaries
        inputs.vacation?.let {
            add(it.startsAt, TransitionReason.VACATION_START)
            add(it.resumeAt, TransitionReason.VACATION_RESUME)
        }

        // Window boundaries for yesterday (midnight-spanning) and the next 8 days,
        // after applying day overrides and per-preset windows.
        val today = now.atZone(zone).toLocalDate()
        for (offset in -1L..8L) {
            val date = today.plusDays(offset)
            val day = effectiveDay(date, inputs) ?: continue
            val span = windowSpan(date, day.window)
            if (day.override != null && day.override.presetId == null) {
                // Skip-night: the state still changes at the boundaries (relevant for
                // KEEP_WARM), and the override expires with its window.
                add(span.first, TransitionReason.WINDOW_START)
                add(span.second, TransitionReason.OVERRIDE_EXPIRY)
            } else {
                add(span.first, TransitionReason.WINDOW_START)
                add(span.second, TransitionReason.WINDOW_END)
                if (day.override != null) add(span.second, TransitionReason.OVERRIDE_EXPIRY)
            }
        }

        return candidates.minByOrNull { it.at }
    }

    // ------------------------------------------------------------------ window machinery

    /**
     * Effective schedule for the window that STARTS on [date]:
     * preset = dayOverride(date) ?: plan[date.dayOfWeek]; window = preset.windowOverride ?: defaultWindow.
     * Returns null when the date has neither an override nor a plan assignment (no window at all).
     */
    private fun effectiveDay(date: LocalDate, inputs: ScheduleInputs): EffectiveDay? {
        val cfg = inputs.config
        val override = inputs.overrides
            .filterIsInstance<OneTimeOverride.DayReplacement>()
            .filter { it.date == date }
            .maxByOrNull { it.createdAt }
        val planPresetId = inputs.weeklyPlan.assignments[date.dayOfWeek]
        val planPreset = planPresetId?.let { inputs.presets[it] ?: cfg.fallbackPreset }

        val preset: Preset? = when {
            override != null && override.presetId != null ->
                inputs.presets[override.presetId] ?: cfg.fallbackPreset
            override != null -> null                       // skip night
            else -> planPreset
        }
        if (override == null && planPreset == null) return null

        // Skip-night keeps the window shape of what it replaced (or the default).
        val window = preset?.windowOverride ?: planPreset?.windowOverride ?: cfg.defaultWindow
        return EffectiveDay(date, override, preset, window)
    }

    /** Concrete [start, end) instants of [window] starting on [date], DST-aware. */
    private fun windowSpan(date: LocalDate, window: HeatingWindow): Pair<Instant, Instant> {
        val start = toInstant(date, window.start)
        val endDate = if (window.crossesMidnight) date.plusDays(1) else date
        val end = toInstant(endDate, window.end)
        return start to end
    }

    /**
     * LocalTime → Instant in [zone]. DST gap → first valid instant after the gap;
     * overlap → earlier offset (heating starts sooner, never later).
     */
    private fun toInstant(date: LocalDate, time: LocalTime): Instant {
        val ldt = LocalDateTime.of(date, time)
        val transition = zone.rules.getTransition(ldt)
        return if (transition != null && transition.isGap) transition.instant
        else ldt.atZone(zone).withEarlierOffsetAtOverlap().toInstant()
    }

    /**
     * Which day's window contains [now]? Checks today first, then yesterday (whose window
     * can only reach into today when it crosses midnight).
     */
    private fun activeWindowDay(now: Instant, inputs: ScheduleInputs): Pair<EffectiveDay, Pair<Instant, Instant>>? {
        val today = now.atZone(zone).toLocalDate()
        for (date in listOf(today, today.minusDays(1))) {
            val day = effectiveDay(date, inputs) ?: continue
            if (date != today && !day.window.crossesMidnight) continue
            val span = windowSpan(date, day.window)
            if (now >= span.first && now < span.second) return day to span
        }
        return null
    }

    private fun clamp(temp: Int, cfg: EngineConfig): Int {
        val max = minOf(TEMP_MAX, cfg.maxSetpointGuard ?: TEMP_MAX)
        return temp.coerceIn(TEMP_MIN, max)
    }

    private data class EffectiveDay(
        val date: LocalDate,
        val override: OneTimeOverride.DayReplacement?,
        val preset: Preset?,          // null = skip heating (override with presetId == null)
        val window: HeatingWindow,
    )

    private companion object {
        const val MODE_MANUAL = 1
        const val TEMP_MIN = 40
        const val TEMP_MAX = 75
        const val STALE_TEMP_ASSUMPTION_C = 30.0
        val SNAPSHOT_FRESH: Duration = Duration.ofHours(2)
        val REASSESS_LEAD: Duration = Duration.ofMinutes(30)
    }
}
