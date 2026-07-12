package rs.novica.dobarboiler.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class HeatByTimeMathTest {

    private val event = Instant.parse("2026-07-15T19:00:00Z")

    private fun hbt(targetTemp: Int = 60, eco: Boolean = false) = OneTimeOverride.HeatByTime(
        id = 1, createdAt = event.minus(Duration.ofDays(1)),
        eventTime = event, targetTemp = targetTemp, eco = eco,
    )

    // ---------------------------------------------------------------- hbtStart

    @Test
    fun `lead is delta over rate padded by safety margin`() {
        // delta 20 °C at 15 °C/h = 4800 s; +15 % = 5520 s
        val start = hbtStart(hbt(targetTemp = 60), estimateTempC = 40.0, cfg = HeatingRateConfig())
        assertEquals(event.minusSeconds(5520), start)
    }

    @Test
    fun `non-positive delta means start equals eventTime`() {
        val cfg = HeatingRateConfig()
        assertEquals(event, hbtStart(hbt(targetTemp = 60), estimateTempC = 60.0, cfg))
        assertEquals(event, hbtStart(hbt(targetTemp = 60), estimateTempC = 71.5, cfg))
    }

    @Test
    fun `lead is clamped to maxLead`() {
        // delta 60 °C at 10 °C/h = 6 h; +15 % = 6.9 h → clamped to 6 h
        val cfg = HeatingRateConfig(ratePerHourC = 10.0)
        val start = hbtStart(hbt(targetTemp = 70), estimateTempC = 10.0, cfg)
        assertEquals(event.minus(Duration.ofHours(6)), start)
    }

    @Test
    fun `eco penalty factor slows the default rate`() {
        // eco rate = 15 * 0.75 = 11.25 °C/h; delta 22.5 °C = 7200 s; +15 % = 8280 s
        val start = hbtStart(hbt(targetTemp = 60, eco = true), estimateTempC = 37.5, cfg = HeatingRateConfig())
        assertEquals(event.minusSeconds(8280), start)
    }

    @Test
    fun `learned rates take priority over defaults`() {
        // full: learned 20 °C/h → delta 20 = 3600 s; +15 % = 4140 s
        val cfg = HeatingRateConfig(learnedRatePerHourC = 20.0, learnedEcoRatePerHourC = 10.0)
        assertEquals(event.minusSeconds(4140),
            hbtStart(hbt(targetTemp = 60, eco = false), estimateTempC = 40.0, cfg))
        // eco: learned 10 °C/h → delta 20 = 7200 s; +15 % = 8280 s (no penalty factor applied)
        assertEquals(event.minusSeconds(8280),
            hbtStart(hbt(targetTemp = 60, eco = true), estimateTempC = 40.0, cfg))
    }

    @Test
    fun `eco falls back to penalized learned full rate when no learned eco rate`() {
        // eco rate = 20 * 0.75 = 15 °C/h; delta 30 = 7200 s; +15 % = 8280 s
        val cfg = HeatingRateConfig(learnedRatePerHourC = 20.0)
        assertEquals(event.minusSeconds(8280),
            hbtStart(hbt(targetTemp = 60, eco = true), estimateTempC = 30.0, cfg))
    }

    // ---------------------------------------------------------------- computedStart estimate policy

    private val engine = DefaultSchedulerEngine(ZoneId.of("Europe/Belgrade"))

    private fun inputsWith(snapshot: DeviceSnapshot?) = ScheduleInputs(
        weeklyPlan = WeeklyPlan(DayOfWeek.entries.associateWith { null }),
        presets = emptyMap(), overrides = emptyList(), vacation = null, boost = null,
        lastSnapshot = snapshot, config = EngineConfig(),
    )

    private fun snapshot(at: Instant, temp: Double) = DeviceSnapshot(
        at = at, on = true, mode = 1, temp = temp, reqTemp = 55.0,
        antiLeg = false, heatReq = false, eco = false, remainingHeatTime = null, availableShowers = null,
    )

    @Test
    fun `fresh snapshot temp is used as the estimate`() {
        val now = event.minus(Duration.ofHours(8))
        // temp 50 → delta 10 at 15 °C/h = 2400 s; +15 % = 2760 s
        val start = engine.computedStart(hbt(targetTemp = 60),
            inputsWith(snapshot(now.minus(Duration.ofMinutes(10)), 50.0)), now)
        assertEquals(event.minusSeconds(2760), start)
    }

    @Test
    fun `stale snapshot falls back to pessimistic 30 degrees`() {
        val now = event.minus(Duration.ofHours(8))
        // estimate 30 → delta 30 at 15 °C/h = 7200 s; +15 % = 8280 s
        val start = engine.computedStart(hbt(targetTemp = 60),
            inputsWith(snapshot(now.minus(Duration.ofHours(3)), 50.0)), now)
        assertEquals(event.minusSeconds(8280), start)
    }

    @Test
    fun `absent snapshot falls back to pessimistic 30 degrees`() {
        val now = event.minus(Duration.ofHours(8))
        val start = engine.computedStart(hbt(targetTemp = 60), inputsWith(null), now)
        assertEquals(event.minusSeconds(8280), start)
    }
}
