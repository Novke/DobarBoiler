package rs.novica.dobarboiler.domain

import java.time.Duration
import java.time.Instant

/**
 * Pure bookkeeping for the "boiler offline" alert (SPEC §7.5). The only state is
 * `offlineSince` — the first time the cloud reported the gateway unreachable — persisted
 * in `RuntimeState.deviceOfflineSince`.
 *
 * A single M2M 404 is not enough to alert: the cloud produced an isolated one on
 * 2026-10-25 while the boiler was fine. The alert fires once the gateway has stayed
 * unreachable for [CONFIRM_AFTER].
 */
object DeviceLink {

    val CONFIRM_AFTER: Duration = Duration.ofMinutes(3)

    /** [since] to persist; [firstSeen] = outage just started; [alert] = show the offline notification. */
    data class Offline(val since: Instant, val firstSeen: Boolean, val alert: Boolean)

    /** [recovered] = this reading ended an outage; [outage] = how long it lasted. */
    data class Online(val recovered: Boolean, val outage: Duration?)

    fun offline(previousSince: Instant?, now: Instant): Offline {
        // A start in the future means the wall clock moved backwards — restart the clock.
        val since = previousSince?.takeIf { !it.isAfter(now) }
        return if (since == null) {
            Offline(since = now, firstSeen = true, alert = false)
        } else {
            Offline(since = since, firstSeen = false, alert = Duration.between(since, now) >= CONFIRM_AFTER)
        }
    }

    /**
     * A snapshot taken at [observedAt] was read successfully. Only a reading taken after the
     * outage started ends it — a cached snapshot from before the first failure proves nothing.
     */
    fun online(previousSince: Instant?, observedAt: Instant): Online =
        if (previousSince == null || !observedAt.isAfter(previousSince)) Online(recovered = false, outage = null)
        else Online(recovered = true, outage = Duration.between(previousSince, observedAt))
}
