package rs.novica.dobarboiler.scheduling

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import rs.novica.dobarboiler.App

/**
 * Full re-convergence after boot, app update, wall-clock/timezone change or exact-alarm
 * permission regrant (SPEC §6.4). `converge(Triggers.BOOT, pollFresh = true)` recomputes
 * the desired state from Room/DataStore and re-arms the single transition alarm, so a
 * pending vacation return (a Room row) survives any of these events.
 */
class RescheduleWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as App).container
        return when (val outcome = container.converger.converge(Triggers.BOOT, pollFresh = true)) {
            is ConvergeOutcome.Converged, is ConvergeOutcome.Skipped -> Result.success()
            is ConvergeOutcome.Failed -> {
                if (runAttemptCount < MAX_ATTEMPTS) return Result.retry()
                val detail = outcome.error.message ?: outcome.error.javaClass.simpleName
                container.actionLog.log(
                    kind = LogKinds.COMMAND_FAILED,
                    trig = Triggers.BOOT,
                    detail = "Ponovno zakazivanje nije uspelo: $detail",
                    outcome = LogOutcomes.FAILED,
                    attempt = runAttemptCount + 1,
                )
                container.notifier.actionFailed(detail)
                Result.failure()
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 7
    }
}
