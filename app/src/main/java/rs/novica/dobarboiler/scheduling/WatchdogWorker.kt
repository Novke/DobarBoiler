package rs.novica.dobarboiler.scheduling

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import rs.novica.dobarboiler.App
import rs.novica.dobarboiler.R

/**
 * Periodic 6 h safety net (SPEC §6.4, unique name "watchdog", KEEP — enqueued by
 * `AppContainer.enqueueWatchdog()` from `App.onCreate`).
 *
 * Checks:
 * - transition alarm armed and `nextTransitionAt` in the future — else (including the
 *   missed-alarm case `nextTransitionAt < now`) run a full convergence;
 * - `canScheduleExact()` — surfaces the reliability notification when exact alarms are off;
 * - notification permission — logged (notifying about blocked notifications is pointless;
 *   the in-app health card is the visible surface);
 * - prunes old logs; expired-override pruning happens inside `scheduleInputs` on every
 *   convergence pass.
 *
 * Nothing stays broken longer than 6 h (§7.2).
 */
class WatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as App).container
        val now = container.clock.instant()

        if (!container.alarmScheduler.canScheduleExact()) {
            container.notifier.permissionProblem(
                applicationContext.getString(R.string.notif_alarms_off),
            )
        }
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            container.actionLog.log(
                kind = LogKinds.CONVERGE, trig = Triggers.WATCHDOG,
                detail = "Notifikacije su blokirane — greške neće biti vidljive",
                outcome = LogOutcomes.SKIPPED,
            )
        }

        runCatching { container.actionLog.prune(olderThanDays = 90) }

        if (container.credentialStore.gwId == null) return Result.success()

        val runtime = container.settings.runtimeSnapshot()
        val nextAt = runtime.nextTransitionAt
        val scheduleBroken = nextAt == null ||            // nothing armed at all
            !nextAt.isAfter(now) ||                       // missed alarm
            !container.alarmScheduler.isTransitionArmed() // alarm silently dropped (force-stop etc.)

        if (scheduleBroken) {
            container.actionLog.log(
                kind = LogKinds.CONVERGE, trig = Triggers.WATCHDOG,
                detail = "Raspored nije naoružan ili je alarm propušten — konvergencija",
                outcome = LogOutcomes.RETRYING,
            )
            val outcome = container.converger.converge(Triggers.WATCHDOG)
            if (outcome is ConvergeOutcome.Failed && runAttemptCount < MAX_ATTEMPTS) {
                return Result.retry()
            }
        }
        return Result.success()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
