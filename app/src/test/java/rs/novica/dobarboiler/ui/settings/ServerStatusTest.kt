package rs.novica.dobarboiler.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import rs.novica.dobarboiler.data.SyncState
import java.time.Duration
import java.time.Instant

class ServerStatusTest {
    private val now = Instant.parse("2026-10-20T10:00:00Z")

    @Test fun notConfiguredWins() {
        assertEquals(ServerStatus.NotConfigured, serverStatus(false, SyncState(lastSyncError = "x"), now))
    }

    @Test fun errorBeforeLastOk() {
        val s = SyncState(lastSyncOkAt = now.minusSeconds(60), lastSyncError = "HTTP 401")
        assertEquals(ServerStatus.Error("HTTP 401"), serverStatus(true, s, now))
    }

    @Test fun neverSentThenOk() {
        assertEquals(ServerStatus.NeverSent, serverStatus(true, SyncState(), now))
        assertEquals(ServerStatus.Ok(Duration.ofMinutes(3)),
            serverStatus(true, SyncState(lastSyncOkAt = now.minusSeconds(180)), now))
    }
}
