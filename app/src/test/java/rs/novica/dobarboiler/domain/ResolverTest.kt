package rs.novica.dobarboiler.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ResolverTest {

    private val zone = ZoneId.of("Europe/Belgrade")
    private val engine = DefaultSchedulerEngine(zone)

    private val presetSam = Preset(id = 1, name = "Sam", targetTemp = 55, eco = true)
    private val presetGosti = Preset(id = 2, name = "Gosti", targetTemp = 65, eco = false)

    /** 2026-07-15 is a Wednesday. */
    private fun local(date: String, time: String): Instant =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(zone).toInstant()

    private fun inputs(
        plan: Map<DayOfWeek, Long?> = DayOfWeek.entries.associateWith { 1L },
        presets: Map<Long, Preset> = mapOf(1L to presetSam, 2L to presetGosti),
        overrides: List<OneTimeOverride> = emptyList(),
        vacation: VacationHold? = null,
        boost: BoostSession? = null,
        snapshot: DeviceSnapshot? = null,
        config: EngineConfig = EngineConfig(),
    ) = ScheduleInputs(WeeklyPlan(plan), presets, overrides, vacation, boost, snapshot, config)

    private fun hbt(
        eventTime: Instant, targetTemp: Int = 60, eco: Boolean = false,
        holdMinutes: Int = 60, id: Long = 10,
    ) = OneTimeOverride.HeatByTime(
        id = id, createdAt = eventTime.minus(Duration.ofDays(1)),
        eventTime = eventTime, targetTemp = targetTemp, eco = eco, holdMinutes = holdMinutes,
    )

    private fun dayOverride(date: String, presetId: Long?, id: Long = 20) =
        OneTimeOverride.DayReplacement(
            id = id, createdAt = Instant.parse("2026-07-01T00:00:00Z"),
            date = LocalDate.parse(date), presetId = presetId,
        )

    // ---------------------------------------------------------------- precedence

    @Test
    fun `boost beats hbt vacation override and plan`() {
        val now = local("2026-07-15", "03:00")   // inside default window
        val res = engine.resolve(
            now,
            inputs(
                boost = BoostSession(1, now.minus(Duration.ofHours(1)), 68, now.plus(Duration.ofHours(1))),
                overrides = listOf(hbt(now.plus(Duration.ofMinutes(30))), dayOverride("2026-07-15", 2)),
                vacation = VacationHold(1, now.minus(Duration.ofDays(1)), null),
            ),
        )
        assertEquals(StateSource.BOOST, res.source)
        assertEquals(DesiredState(powerOn = true, targetTemp = 68, eco = false), res.desired)
    }

    @Test
    fun `hbt beats vacation`() {
        val now = local("2026-07-15", "20:00")
        val res = engine.resolve(
            now,
            inputs(
                overrides = listOf(hbt(now.plus(Duration.ofMinutes(30)), targetTemp = 60, eco = false)),
                vacation = VacationHold(1, now.minus(Duration.ofDays(1)), null),
            ),
        )
        assertEquals(StateSource.HEAT_BY_TIME, res.source)
        assertEquals(DesiredState(powerOn = true, targetTemp = 60, eco = false), res.desired)
    }

    @Test
    fun `vacation beats day override and weekly plan`() {
        val now = local("2026-07-15", "03:00")
        val res = engine.resolve(
            now,
            inputs(
                overrides = listOf(dayOverride("2026-07-15", 2)),
                vacation = VacationHold(1, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(2))),
            ),
        )
        assertEquals(StateSource.VACATION, res.source)
        assertEquals(DesiredState(powerOn = false, targetTemp = null, eco = null), res.desired)
    }

    @Test
    fun `day override beats weekly plan`() {
        val now = local("2026-07-15", "03:00")
        val res = engine.resolve(now, inputs(overrides = listOf(dayOverride("2026-07-15", 2))))
        assertEquals(StateSource.DAY_OVERRIDE, res.source)
        assertEquals(DesiredState(powerOn = true, targetTemp = 65, eco = false), res.desired)
    }

    @Test
    fun `skip-night override turns heating off inside window`() {
        val now = local("2026-07-15", "03:00")
        val res = engine.resolve(now, inputs(overrides = listOf(dayOverride("2026-07-15", null))))
        assertEquals(StateSource.DAY_OVERRIDE, res.source)
        assertFalse(res.desired.powerOn)
        assertNull(res.desired.targetTemp)
    }

    @Test
    fun `weekly plan applies inside window`() {
        val now = local("2026-07-15", "03:00")
        val res = engine.resolve(now, inputs())
        assertEquals(StateSource.WEEKLY_PLAN, res.source)
        assertEquals(DesiredState(powerOn = true, targetTemp = 55, eco = true), res.desired)
    }

    @Test
    fun `outside window default is off`() {
        val now = local("2026-07-15", "12:00")
        val res = engine.resolve(now, inputs())
        assertEquals(StateSource.DEFAULT_OFF, res.source)
        assertEquals(DesiredState(powerOn = false, targetTemp = null, eco = null), res.desired)
    }

    @Test
    fun `outside window keep warm`() {
        val now = local("2026-07-15", "12:00")
        val res = engine.resolve(now, inputs(config = EngineConfig(
            outsideWindow = OutsideWindowBehavior.KEEP_WARM, keepWarmTemp = 45)))
        assertEquals(StateSource.KEEP_WARM, res.source)
        assertEquals(DesiredState(powerOn = true, targetTemp = 45, eco = true), res.desired)
    }

    @Test
    fun `expired or completed boost is ignored`() {
        val now = local("2026-07-15", "03:00")
        val timedOut = BoostSession(1, now.minus(Duration.ofHours(5)), 68, now.minus(Duration.ofMinutes(1)))
        assertEquals(StateSource.WEEKLY_PLAN, engine.resolve(now, inputs(boost = timedOut)).source)

        val completed = BoostSession(2, now.minus(Duration.ofHours(1)), 68,
            now.plus(Duration.ofHours(1)), completedAt = now.minus(Duration.ofMinutes(5)))
        assertEquals(StateSource.WEEKLY_PLAN, engine.resolve(now, inputs(boost = completed)).source)
    }

    @Test
    fun `future vacation is not active yet`() {
        val now = local("2026-07-15", "03:00")
        val res = engine.resolve(now, inputs(
            vacation = VacationHold(1, now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(8)))))
        assertEquals(StateSource.WEEKLY_PLAN, res.source)
    }

    // ---------------------------------------------------------------- HBT selection

    @Test
    fun `overlapping hbts - highest target wins, tie broken by later eventTime`() {
        val now = local("2026-07-15", "20:00")
        val a = hbt(now.plus(Duration.ofMinutes(20)), targetTemp = 55, id = 11)
        val b = hbt(now.plus(Duration.ofMinutes(40)), targetTemp = 62, id = 12)
        val winner = engine.resolve(now, inputs(overrides = listOf(a, b)))
        assertEquals(62, winner.desired.targetTemp)

        val c = hbt(now.plus(Duration.ofMinutes(20)), targetTemp = 62, eco = true, id = 13)
        val d = hbt(now.plus(Duration.ofMinutes(40)), targetTemp = 62, eco = false, id = 14)
        val tie = engine.resolve(now, inputs(overrides = listOf(c, d)))
        assertEquals(false, tie.desired.eco)   // d has the later eventTime
    }

    @Test
    fun `hbt with zero hold ends exactly at eventTime`() {
        val event = local("2026-07-15", "21:00")
        val o = hbt(event, holdMinutes = 0)
        assertEquals(StateSource.HEAT_BY_TIME,
            engine.resolve(event.minus(Duration.ofMinutes(1)), inputs(overrides = listOf(o))).source)
        assertEquals(StateSource.DEFAULT_OFF,
            engine.resolve(event, inputs(overrides = listOf(o))).source)
    }

    // ---------------------------------------------------------------- windows

    @Test
    fun `midnight-spanning window belongs to its start date`() {
        val nightOwl = Preset(1, "Noć", 55, windowOverride = HeatingWindow(LocalTime.of(22, 0), LocalTime.of(6, 0)))
        val fridayOnly = inputs(
            plan = DayOfWeek.entries.associateWith { if (it == DayOfWeek.FRIDAY) 1L else null },
            presets = mapOf(1L to nightOwl),
        )
        // Friday 2026-07-17 23:00 — inside Friday's window
        assertEquals(StateSource.WEEKLY_PLAN,
            engine.resolve(local("2026-07-17", "23:00"), fridayOnly).source)
        // Saturday 03:00 — still Friday's window, even though Saturday has no assignment
        assertEquals(StateSource.WEEKLY_PLAN,
            engine.resolve(local("2026-07-18", "03:00"), fridayOnly).source)
        // Saturday 07:00 — Friday's window ended at 06:00
        assertEquals(StateSource.DEFAULT_OFF,
            engine.resolve(local("2026-07-18", "07:00"), fridayOnly).source)
    }

    @Test
    fun `window boundaries are start-inclusive end-exclusive`() {
        assertEquals(StateSource.WEEKLY_PLAN, engine.resolve(local("2026-07-15", "00:30"), inputs()).source)
        assertEquals(StateSource.DEFAULT_OFF, engine.resolve(local("2026-07-15", "07:30"), inputs()).source)
    }

    @Test
    fun `override preset window replaces plan window for that date`() {
        val morning = Preset(2, "Jutro", 60, eco = false,
            windowOverride = HeatingWindow(LocalTime.of(10, 0), LocalTime.of(12, 0)))
        val i = inputs(presets = mapOf(1L to presetSam, 2L to morning),
            overrides = listOf(dayOverride("2026-07-15", 2)))
        // Inside the override preset's window
        assertEquals(StateSource.DAY_OVERRIDE, engine.resolve(local("2026-07-15", "11:00"), i).source)
        // Inside the *default* window — but the override replaced the whole day
        assertEquals(StateSource.DEFAULT_OFF, engine.resolve(local("2026-07-15", "03:00"), i).source)
    }

    @Test
    fun `missing preset id falls back to fallbackPreset`() {
        val cfg = EngineConfig(fallbackPreset = Preset(-1, "Podrazumevano", 50, eco = true))
        val res = engine.resolve(local("2026-07-15", "03:00"),
            inputs(presets = emptyMap(), config = cfg))
        assertEquals(StateSource.WEEKLY_PLAN, res.source)
        assertEquals(50, res.desired.targetTemp)
    }

    @Test
    fun `temps clamped to 40-75 and maxSetpointGuard`() {
        val hot = Preset(1, "Vrelo", 90)
        assertEquals(75, engine.resolve(local("2026-07-15", "03:00"),
            inputs(presets = mapOf(1L to hot))).desired.targetTemp)
        assertEquals(65, engine.resolve(local("2026-07-15", "03:00"),
            inputs(presets = mapOf(1L to hot), config = EngineConfig(maxSetpointGuard = 65))).desired.targetTemp)
        val cold = Preset(1, "Hladno", 20)
        assertEquals(40, engine.resolve(local("2026-07-15", "03:00"),
            inputs(presets = mapOf(1L to cold))).desired.targetTemp)
    }

    // ---------------------------------------------------------------- DST

    @Test
    fun `dst spring-forward gap - window start resolves to first valid instant after gap`() {
        // Europe/Belgrade 2026-03-29: 02:00 CET jumps to 03:00 CEST; 02:30 does not exist.
        val gapPreset = Preset(1, "Gap", 55,
            windowOverride = HeatingWindow(LocalTime.of(2, 30), LocalTime.of(8, 0)))
        val i = inputs(
            plan = DayOfWeek.entries.associateWith { if (it == DayOfWeek.SUNDAY) 1L else null },
            presets = mapOf(1L to gapPreset),
        )
        val beforeGap = Instant.parse("2026-03-29T00:30:00Z")   // 01:30 CET
        val next = engine.resolve(beforeGap, i).nextTransition
        assertNotNull(next)
        assertEquals(TransitionReason.WINDOW_START, next!!.reason)
        assertEquals(Instant.parse("2026-03-29T01:00:00Z"), next.at)  // gap start = 03:00 CEST
        // And the window is active right at that instant.
        assertEquals(StateSource.WEEKLY_PLAN,
            engine.resolve(Instant.parse("2026-03-29T01:00:00Z"), i).source)
    }

    @Test
    fun `dst fall-back overlap - earlier offset wins so heating starts sooner`() {
        // Europe/Belgrade 2026-10-25: 03:00 CEST falls back to 02:00 CET; 02:30 occurs twice.
        val overlapPreset = Preset(1, "Overlap", 55,
            windowOverride = HeatingWindow(LocalTime.of(2, 30), LocalTime.of(8, 0)))
        val i = inputs(
            plan = DayOfWeek.entries.associateWith { if (it == DayOfWeek.SUNDAY) 1L else null },
            presets = mapOf(1L to overlapPreset),
        )
        val next = engine.resolve(Instant.parse("2026-10-25T00:00:00Z"), i).nextTransition
        assertNotNull(next)
        assertEquals(TransitionReason.WINDOW_START, next!!.reason)
        // 02:30 at the earlier offset (+02:00 CEST) = 00:30Z, not 01:30Z.
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), next.at)
    }

    // ---------------------------------------------------------------- nextTransition

    @Test
    fun `next transition is window start when outside window`() {
        val res = engine.resolve(local("2026-07-15", "12:00"), inputs())
        assertEquals(Transition(local("2026-07-16", "00:30"), TransitionReason.WINDOW_START), res.nextTransition)
    }

    @Test
    fun `next transition is window end when inside window`() {
        val res = engine.resolve(local("2026-07-15", "03:00"), inputs())
        assertEquals(Transition(local("2026-07-15", "07:30"), TransitionReason.WINDOW_END), res.nextTransition)
    }

    @Test
    fun `boost timeout appears as next transition`() {
        val now = local("2026-07-15", "12:00")
        val boost = BoostSession(1, now, 60, now.plus(Duration.ofHours(2)))
        val res = engine.resolve(now, inputs(
            plan = DayOfWeek.entries.associateWith { null }, boost = boost))
        assertEquals(Transition(boost.timeoutAt, TransitionReason.BOOST_TIMEOUT), res.nextTransition)
    }

    @Test
    fun `hbt reassess fires 30 min before computed start`() {
        val now = local("2026-07-15", "10:00")
        val event = now.plus(Duration.ofHours(10))
        val o = hbt(event, targetTemp = 60)   // no snapshot → estimate 30 °C → lead 2.3 h
        val res = engine.resolve(now, inputs(
            plan = DayOfWeek.entries.associateWith { null }, overrides = listOf(o)))
        val start = engine.computedStart(o, inputs(plan = DayOfWeek.entries.associateWith { null }), now)
        assertEquals(Transition(start.minus(Duration.ofMinutes(30)), TransitionReason.HBT_REASSESS), res.nextTransition)
    }

    @Test
    fun `vacation boundaries appear as transitions`() {
        val now = local("2026-07-15", "10:00")
        val emptyPlan = DayOfWeek.entries.associateWith { null as Long? }
        val future = VacationHold(1, now.plus(Duration.ofHours(3)), now.plus(Duration.ofDays(5)))
        assertEquals(Transition(future.startsAt, TransitionReason.VACATION_START),
            engine.resolve(now, inputs(plan = emptyPlan, vacation = future)).nextTransition)

        val active = VacationHold(2, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(2)))
        val res = engine.resolve(now, inputs(plan = emptyPlan, vacation = active))
        assertEquals(StateSource.VACATION, res.source)
        assertEquals(Transition(active.resumeAt!!, TransitionReason.VACATION_RESUME), res.nextTransition)
    }

    @Test
    fun `skip-night override expires at its window end`() {
        val now = local("2026-07-15", "03:00")
        val res = engine.resolve(now, inputs(overrides = listOf(dayOverride("2026-07-15", null))))
        assertEquals(Transition(local("2026-07-15", "07:30"), TransitionReason.OVERRIDE_EXPIRY), res.nextTransition)
    }

    @Test
    fun `empty world resolves to off with no transition`() {
        val res = engine.resolve(local("2026-07-15", "12:00"),
            inputs(plan = DayOfWeek.entries.associateWith { null }))
        assertEquals(StateSource.DEFAULT_OFF, res.source)
        assertFalse(res.desired.powerOn)
        assertNull(res.nextTransition)
    }

    @Test
    fun `redundant boundaries are still reported - keep warm changes at skip window start`() {
        // With KEEP_WARM, a skip-night's window start is a real state change (on → off).
        val now = local("2026-07-14", "23:00")
        val res = engine.resolve(now, inputs(
            overrides = listOf(dayOverride("2026-07-15", null)),
            config = EngineConfig(outsideWindow = OutsideWindowBehavior.KEEP_WARM),
        ))
        assertEquals(StateSource.KEEP_WARM, res.source)
        assertTrue(res.desired.powerOn)
        assertEquals(Transition(local("2026-07-15", "00:30"), TransitionReason.WINDOW_START), res.nextTransition)
    }
}
