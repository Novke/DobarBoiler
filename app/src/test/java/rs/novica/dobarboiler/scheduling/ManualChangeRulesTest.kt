package rs.novica.dobarboiler.scheduling

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import rs.novica.dobarboiler.domain.DeviceSnapshot

class ManualChangeRulesTest {

    private val appliedAt = Instant.parse("2026-10-29T16:15:00Z")

    /** What the app asserted for the boost: on, 42 °C, eco off. */
    private val boostApplied = AppliedStateCodec.Applied(powerOn = true, targetTemp = 42, eco = false)

    private fun snap(on: Boolean, at: Instant, reqTemp: Double = 42.0, eco: Boolean = false) = DeviceSnapshot(
        at = at, on = on, mode = 1, temp = 33.0, reqTemp = reqTemp, antiLeg = false,
        heatReq = false, eco = eco, remainingHeatTime = null, availableShowers = 0,
    )

    private val switchedOffByHand = snap(on = false, at = appliedAt.plus(Duration.ofMinutes(26)))

    @Test
    fun `boiler switched off by hand after an assert is an external change`() {
        assertTrue(ManualChangeRules.isExternal(boostApplied, appliedAt, switchedOffByHand))
    }

    @Test
    fun `difference inside the slow-bus grace is not an external change`() {
        val early = snap(on = false, at = appliedAt.plus(Duration.ofMinutes(2)))
        assertFalse(ManualChangeRules.isExternal(boostApplied, appliedAt, early))
    }

    @Test
    fun `matching snapshot is not an external change`() {
        val same = snap(on = true, at = appliedAt.plus(Duration.ofHours(1)))
        assertFalse(ManualChangeRules.isExternal(boostApplied, appliedAt, same))
    }

    @Test
    fun `no baseline or no snapshot is never an external change`() {
        assertFalse(ManualChangeRules.isExternal(null, appliedAt, switchedOffByHand))
        assertFalse(ManualChangeRules.isExternal(boostApplied, null, switchedOffByHand))
        assertFalse(ManualChangeRules.isExternal(boostApplied, appliedAt, null))
    }

    /**
     * Regression 2026-10-29..31: boost switched off by hand → hold until 00:00 → the 00:00
     * window-start alarm cleared the hold but compared against the stale boost baseline,
     * re-detected the same "manual change" and held again — every night, forever.
     * Scheduled transitions and boost start/end must assert, never start a hold.
     */
    @Test
    fun `scheduled alarm and boost triggers assert and skip detection`() {
        assertTrue(ManualChangeRules.isAssertive(Triggers.ALARM))
        assertTrue(ManualChangeRules.isAssertive(Triggers.BOOST))
    }

    @Test
    fun `opportunistic polls still detect manual changes`() {
        for (t in listOf(Triggers.WATCHDOG, Triggers.VERIFY, Triggers.BAND, Triggers.BOOT, Triggers.USER)) {
            assertFalse(t, ManualChangeRules.isAssertive(t))
        }
    }

    @Test
    fun `boost switched off by hand is recognised only after the grace`() {
        assertTrue(ManualChangeRules.boostSwitchedOffByHand(switchedOffByHand, appliedAt))
        val early = snap(on = false, at = appliedAt.plus(Duration.ofMinutes(1)))
        assertFalse(ManualChangeRules.boostSwitchedOffByHand(early, appliedAt))
        val stillOn = snap(on = true, at = appliedAt.plus(Duration.ofMinutes(30)))
        assertFalse(ManualChangeRules.boostSwitchedOffByHand(stillOn, appliedAt))
    }
}
