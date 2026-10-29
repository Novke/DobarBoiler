package rs.novica.dobarboiler.scheduling

import android.content.Context
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import rs.novica.dobarboiler.data.ActionLogRepository
import rs.novica.dobarboiler.data.SettingsStore
import rs.novica.dobarboiler.domain.DeviceLink
import rs.novica.dobarboiler.notifications.Notifier

/**
 * Turns "the cloud cannot reach the boiler" observations into the offline alert (SPEC §7.5).
 *
 * Fed by every poll that matters: [Converger] (refresh + command batch), [LinkCheckWorker]
 * (every 15 min) and the dashboard's manual refresh. The outage start lives in
 * `RuntimeState.deviceOfflineSince`, so it survives process death; [DeviceLink] decides
 * when an outage is confirmed. Never calls [Converger] itself — Converger calls in here
 * while holding its mutex.
 */
class DeviceLinkMonitor(
    private val context: Context,
    private val settings: SettingsStore,
    private val log: ActionLogRepository,
    private val notifier: Notifier,
    private val clock: Clock,
) {

    /** The cloud answered with the gateway-offline error ([rs.novica.dobarboiler.api.AristonException.DeviceOffline]). */
    suspend fun reportOffline(trigger: String) {
        val now = clock.instant()
        var decision: DeviceLink.Offline? = null
        settings.updateRuntime { r ->
            DeviceLink.offline(r.deviceOfflineSince, now)
                .also { decision = it }
                .let { r.copy(deviceOfflineSince = it.since) }
        }
        val d = decision ?: return
        if (d.firstSeen) {
            log.log(
                kind = LogKinds.DEVICE_OFFLINE, trig = trigger,
                detail = "Bojler se ne javlja (gateway van mreže) — proveravam ponovo",
                outcome = LogOutcomes.RETRYING,
            )
            LinkCheckWorker.enqueueConfirm(context)
        }
        if (d.alert) notifier.deviceOffline(timeLabel(d.since, now))
    }

    /**
     * A successful read; [observedAt] is the snapshot's timestamp (refresh may return a cached
     * one). Returns `true` when this ended an outage — the caller should then converge (unless
     * it is converging already) so the plan is re-asserted.
     */
    suspend fun reportOnline(trigger: String, observedAt: Instant): Boolean {
        var decision: DeviceLink.Online? = null
        settings.updateRuntime { r ->
            val d = DeviceLink.online(r.deviceOfflineSince, observedAt).also { decision = it }
            // Forget what we last asserted: whatever the boiler reports after regaining power is
            // drift to correct, not a manual change to respect (§7.4 detection compares to it).
            if (d.recovered) r.copy(deviceOfflineSince = null, lastAppliedStateJson = null, lastAppliedAt = null) else r
        }
        val d = decision?.takeIf { it.recovered } ?: return false
        val outage = durationLabel(d.outage ?: Duration.ZERO)
        log.log(
            kind = LogKinds.DEVICE_ONLINE, trig = trigger,
            detail = "Bojler ponovo na vezi posle $outage",
            outcome = LogOutcomes.SUCCESS,
        )
        notifier.deviceOnline(outage)
        return true
    }

    /** "HH:mm" for today, "dd.MM. HH:mm" otherwise. */
    private fun timeLabel(at: Instant, now: Instant): String {
        val zone = clock.zone
        val zdt = at.atZone(zone)
        val pattern = if (zdt.toLocalDate() == now.atZone(zone).toLocalDate()) "HH:mm" else "dd.MM. HH:mm"
        return zdt.format(DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag("sr-Latn-RS")))
    }

    /** "4 min" / "2 h" / "47 h 41 min". */
    private fun durationLabel(d: Duration): String {
        val minutes = d.toMinutes()
        return when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }
}
