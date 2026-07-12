package rs.novica.dobarboiler.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PlanDiffTest {

    private val engine = DefaultSchedulerEngine(ZoneId.of("Europe/Belgrade"))

    private fun snap(
        on: Boolean = true, mode: Int = 1, reqTemp: Double = 55.0, eco: Boolean = true,
    ) = DeviceSnapshot(
        at = Instant.parse("2026-07-15T01:00:00Z"), on = on, mode = mode, temp = 45.0,
        reqTemp = reqTemp, antiLeg = false, heatReq = true, eco = eco,
        remainingHeatTime = null, availableShowers = 1,
    )

    private val onState = DesiredState(powerOn = true, targetTemp = 55, eco = true)
    private val offState = DesiredState(powerOn = false, targetTemp = null, eco = null)

    @Test
    fun `null snapshot - full assert when turning on`() {
        assertEquals(
            listOf(
                BoilerCommand.SetMode(1),
                BoilerCommand.SetPower(true),
                BoilerCommand.SetTemperature(55),
                BoilerCommand.SetEco(true),
            ),
            engine.plan(onState, null),
        )
    }

    @Test
    fun `null snapshot - full assert when turning off sends only mode and power`() {
        assertEquals(
            listOf(BoilerCommand.SetMode(1), BoilerCommand.SetPower(false)),
            engine.plan(offState, null),
        )
    }

    @Test
    fun `desired off - single power command, never touches temp eco or mode`() {
        assertEquals(
            listOf(BoilerCommand.SetPower(false)),
            engine.plan(offState, snap(on = true, mode = 9, reqTemp = 70.0, eco = false)),
        )
    }

    @Test
    fun `desired off and already off - no commands`() {
        assertTrue(engine.plan(offState, snap(on = false)).isEmpty())
    }

    @Test
    fun `desired on with everything wrong - full ordered batch`() {
        assertEquals(
            listOf(
                BoilerCommand.SetMode(1),
                BoilerCommand.SetPower(true),
                BoilerCommand.SetTemperature(55),
                BoilerCommand.SetEco(true),
            ),
            engine.plan(onState, snap(on = false, mode = 9, reqTemp = 40.0, eco = false)),
        )
    }

    @Test
    fun `converged state - no commands`() {
        assertTrue(engine.plan(onState, snap()).isEmpty())
    }

    @Test
    fun `mode is normalized to manual even when everything else matches`() {
        assertEquals(
            listOf(BoilerCommand.SetMode(1)),
            engine.plan(onState, snap(mode = 5)),
        )
    }

    @Test
    fun `reqTemp comparison rounds to nearest degree`() {
        // 54.6 rounds to 55 → no temp command
        assertTrue(engine.plan(onState, snap(reqTemp = 54.6)).isEmpty())
        // 54.4 rounds to 54 → temp command
        assertEquals(
            listOf(BoilerCommand.SetTemperature(55)),
            engine.plan(onState, snap(reqTemp = 54.4)),
        )
    }

    @Test
    fun `null eco in desired never touches eco`() {
        val noEco = DesiredState(powerOn = true, targetTemp = 55, eco = null)
        assertTrue(engine.plan(noEco, snap(eco = false)).isEmpty())
        assertTrue(engine.plan(noEco, snap(eco = true)).isEmpty())
    }

    @Test
    fun `null target temp in desired never touches temperature`() {
        val noTemp = DesiredState(powerOn = true, targetTemp = null, eco = true)
        assertTrue(engine.plan(noTemp, snap(reqTemp = 42.0)).isEmpty())
    }

    @Test
    fun `only eco differs - single eco command`() {
        assertEquals(
            listOf(BoilerCommand.SetEco(true)),
            engine.plan(onState, snap(eco = false)),
        )
    }

    @Test
    fun `power off but mode wrong still normalizes mode when desired on`() {
        assertEquals(
            listOf(BoilerCommand.SetMode(1), BoilerCommand.SetPower(true)),
            engine.plan(onState, snap(on = false, mode = 9)),
        )
    }
}
