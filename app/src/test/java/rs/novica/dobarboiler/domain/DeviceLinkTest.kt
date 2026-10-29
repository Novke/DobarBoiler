package rs.novica.dobarboiler.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class DeviceLinkTest {

    private val t0 = Instant.parse("2026-10-27T14:56:00Z")

    @Test
    fun `first offline observation starts the clock but does not alert`() {
        val d = DeviceLink.offline(previousSince = null, now = t0)
        assertEquals(t0, d.since)
        assertTrue(d.firstSeen)
        assertFalse(d.alert)
    }

    @Test
    fun `repeat observation inside the confirm window does not alert`() {
        val d = DeviceLink.offline(previousSince = t0, now = t0.plus(Duration.ofMinutes(2)))
        assertEquals(t0, d.since)
        assertFalse(d.firstSeen)
        assertFalse(d.alert)
    }

    @Test
    fun `observation after the confirm window alerts and keeps the original start`() {
        val d = DeviceLink.offline(previousSince = t0, now = t0.plus(DeviceLink.CONFIRM_AFTER))
        assertEquals(t0, d.since)
        assertTrue(d.alert)
    }

    @Test
    fun `start stamped in the future (clock moved back) restarts the clock`() {
        val now = t0.minus(Duration.ofHours(1))
        val d = DeviceLink.offline(previousSince = t0, now = now)
        assertEquals(now, d.since)
        assertTrue(d.firstSeen)
        assertFalse(d.alert)
    }

    @Test
    fun `online after an outage reports recovery and its duration`() {
        val now = t0.plus(Duration.ofHours(48))
        val d = DeviceLink.online(previousSince = t0, observedAt = now)
        assertTrue(d.recovered)
        assertEquals(Duration.ofHours(48), d.outage)
    }

    @Test
    fun `online while already online is a no-op`() {
        val d = DeviceLink.online(previousSince = null, observedAt = t0)
        assertFalse(d.recovered)
        assertNull(d.outage)
    }

    @Test
    fun `cached snapshot from before the outage does not end it`() {
        val d = DeviceLink.online(previousSince = t0, observedAt = t0.minus(Duration.ofMinutes(1)))
        assertFalse(d.recovered)
    }
}
