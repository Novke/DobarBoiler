package rs.novica.dobarboiler.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class RateEstimatorTest {

    private val estimator = RateEstimator()
    private val t0 = Instant.parse("2026-07-15T00:00:00Z")

    private fun sample(minutes: Long, rate: Double, eco: Boolean = false) =
        RateSample(at = t0.plus(Duration.ofMinutes(minutes)), ratePerHourC = rate, eco = eco)

    private fun snap(
        atMinutes: Long, temp: Double, on: Boolean = true, heatReq: Boolean = true, eco: Boolean = false,
    ) = DeviceSnapshot(
        at = t0.plus(Duration.ofMinutes(atMinutes)), on = on, mode = 1, temp = temp,
        reqTemp = 60.0, antiLeg = false, heatReq = heatReq, eco = eco,
        remainingHeatTime = null, availableShowers = null,
    )

    // ---------------------------------------------------------------- learnedRate

    @Test
    fun `returns null below five samples for the flag`() {
        val four = (0L..3L).map { sample(it * 60, 15.0) }
        assertNull(estimator.learnedRate(four, eco = false))
        assertNull(estimator.learnedRate(emptyList(), eco = false))
    }

    @Test
    fun `ewma with alpha 0_3 over chronological samples`() {
        val samples = listOf(
            sample(0, 10.0), sample(60, 12.0), sample(120, 14.0), sample(180, 16.0), sample(240, 18.0),
        )
        // 10 → 10.6 → 11.62 → 12.934 → 14.4538
        assertEquals(14.4538, estimator.learnedRate(samples, eco = false)!!, 1e-9)
    }

    @Test
    fun `samples are ordered by time before smoothing`() {
        val ordered = listOf(
            sample(0, 10.0), sample(60, 12.0), sample(120, 14.0), sample(180, 16.0), sample(240, 18.0),
        )
        val shuffled = listOf(ordered[3], ordered[0], ordered[4], ordered[2], ordered[1])
        assertEquals(
            estimator.learnedRate(ordered, eco = false)!!,
            estimator.learnedRate(shuffled, eco = false)!!,
            1e-12,
        )
    }

    @Test
    fun `eco flags are learned independently`() {
        val samples =
            (0L..4L).map { sample(it * 60, 16.0, eco = false) } +
            (0L..2L).map { sample(it * 60 + 30, 11.0, eco = true) }
        assertEquals(16.0, estimator.learnedRate(samples, eco = false)!!, 1e-9)
        assertNull(estimator.learnedRate(samples, eco = true))   // only 3 eco samples
    }

    // ---------------------------------------------------------------- sampleFrom

    @Test
    fun `valid pair yields rate in degrees per hour`() {
        val s = estimator.sampleFrom(snap(0, 40.0), snap(30, 45.5))
        assertNotNull(s)
        assertEquals(11.0, s!!.ratePerHourC, 1e-9)   // 5.5 °C over 0.5 h
        assertEquals(t0.plus(Duration.ofMinutes(30)), s.at)
        assertEquals(false, s.eco)
    }

    @Test
    fun `argument order does not matter`() {
        val a = snap(0, 40.0)
        val b = snap(45, 48.0)
        assertEquals(estimator.sampleFrom(a, b), estimator.sampleFrom(b, a))
    }

    @Test
    fun `gap bounds are inclusive 20 to 90 minutes`() {
        assertNotNull(estimator.sampleFrom(snap(0, 40.0), snap(20, 42.0)))
        assertNotNull(estimator.sampleFrom(snap(0, 40.0), snap(90, 55.0)))
        assertNull(estimator.sampleFrom(snap(0, 40.0), snap(19, 42.0)))
        assertNull(estimator.sampleFrom(snap(0, 40.0), snap(91, 55.0)))
    }

    @Test
    fun `rejects when not heating in either snapshot`() {
        assertNull(estimator.sampleFrom(snap(0, 40.0, heatReq = false), snap(30, 45.0)))
        assertNull(estimator.sampleFrom(snap(0, 40.0), snap(30, 45.0, heatReq = false)))
    }

    @Test
    fun `rejects when power off in either snapshot`() {
        assertNull(estimator.sampleFrom(snap(0, 40.0, on = false), snap(30, 45.0)))
        assertNull(estimator.sampleFrom(snap(0, 40.0), snap(30, 45.0, on = false)))
    }

    @Test
    fun `rejects eco flag mismatch`() {
        assertNull(estimator.sampleFrom(snap(0, 40.0, eco = true), snap(30, 45.0, eco = false)))
    }

    @Test
    fun `rejects rise below one degree, accepts exactly one degree`() {
        assertNull(estimator.sampleFrom(snap(0, 40.0), snap(30, 40.9)))
        assertNull(estimator.sampleFrom(snap(0, 45.0), snap(30, 44.0)))   // falling temp
        assertNotNull(estimator.sampleFrom(snap(0, 40.0), snap(30, 41.0)))
    }

    @Test
    fun `eco sample carries the eco flag`() {
        val s = estimator.sampleFrom(snap(0, 40.0, eco = true), snap(60, 48.0, eco = true))
        assertNotNull(s)
        assertEquals(true, s!!.eco)
        assertEquals(8.0, s.ratePerHourC, 1e-9)
    }
}
