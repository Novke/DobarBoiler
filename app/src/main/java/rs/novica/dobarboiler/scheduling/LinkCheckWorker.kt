package rs.novica.dobarboiler.scheduling

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.util.concurrent.TimeUnit
import rs.novica.dobarboiler.App
import rs.novica.dobarboiler.api.AristonException

/**
 * Is the boiler reachable? (SPEC §7.5) One state read every 15 min, so a tripped power
 * switch is noticed even when no transition is due, and the offline alert clears soon
 * after power returns. While online the read reuses a snapshot younger than 5 min (another
 * poll already proved the link), so it adds at most ~96 cloud calls a day.
 *
 * Offline → [DeviceLinkMonitor.reportOffline] (alerts once confirmed). Back online →
 * converge with WATCHDOG, so the plan is re-asserted on a boiler that just got power back.
 * Other errors are ignored — the next period tries again; this check never notifies about
 * cloud hiccups.
 */
class LinkCheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as App).container
        if (c.credentialStore.gwId == null) return Result.success()
        // During an outage always ask the cloud — a cached snapshot would prove nothing.
        val offline = c.settings.runtimeSnapshot().deviceOfflineSince != null
        try {
            val snapshot = c.deviceRepository.refresh(if (offline) Duration.ZERO else REUSE_SNAPSHOT_YOUNGER_THAN)
            if (c.linkMonitor.reportOnline(Triggers.WATCHDOG, snapshot.at)) c.converger.converge(Triggers.WATCHDOG)
        } catch (_: AristonException.DeviceOffline) {
            c.linkMonitor.reportOffline(Triggers.WATCHDOG)
        } catch (_: Exception) {
            // Cloud/network trouble that says nothing about the boiler's power — next period.
        }
        return Result.success()
    }

    companion object {
        const val PERIODIC_NAME = "link_check"
        const val CONFIRM_NAME = "link_confirm"

        private val REUSE_SNAPSHOT_YOUNGER_THAN: Duration = Duration.ofMinutes(5)

        /** Confirmation read after the first offline observation (> [rs.novica.dobarboiler.domain.DeviceLink.CONFIRM_AFTER]). */
        private val CONFIRM_DELAY: Duration = Duration.ofMinutes(5)

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** From `App.onCreate`: unique "link_check", 15 min (WorkManager's minimum), KEEP. */
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<LinkCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** One extra read [CONFIRM_DELAY] after an outage starts, so the alert does not wait for the next period. */
        fun enqueueConfirm(context: Context) {
            val request = OneTimeWorkRequestBuilder<LinkCheckWorker>()
                .setInitialDelay(CONFIRM_DELAY)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(CONFIRM_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
