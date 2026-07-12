package rs.novica.dobarboiler.scheduling

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Duration
import rs.novica.dobarboiler.App

/**
 * Runs the convergence routine when a transition alarm fires (SPEC §6.4).
 *
 * Retry table (§7.2): exponential backoff from 30 s, up to 7 attempts (~1 h span), then
 * FAILED log + error notification. Special case: if the desired state we failed to reach
 * is OFF (window end — every extra hour leaks money), a fresh 30-min-delayed retry chain
 * is enqueued under a separate unique name; each successful convergence from any trigger
 * supersedes it because convergence is idempotent.
 *
 * `RateLimited` errors go through the same `Result.retry()` path — the backoff IS the
 * 429 etiquette.
 */
class TransitionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as App).container
        // The 30-min OFF-retry chain is a rescue, not a scheduled transition: it converges
        // with WATCHDOG so a stale chain run can never wipe a legitimate manual hold the
        // user established after the window ended (§7.4).
        val trigger = if (inputData.getBoolean(KEY_OFF_RETRY, false)) Triggers.WATCHDOG else Triggers.ALARM
        return when (val outcome = container.converger.converge(trigger)) {
            is ConvergeOutcome.Converged, is ConvergeOutcome.Skipped -> Result.success()
            is ConvergeOutcome.Failed -> handleFailure(outcome.error)
        }
    }

    private suspend fun handleFailure(error: Throwable): Result {
        if (runAttemptCount < MAX_ATTEMPTS) return Result.retry()

        val container = (applicationContext as App).container
        val detail = error.message ?: error.javaClass.simpleName
        container.actionLog.log(
            kind = LogKinds.COMMAND_FAILED,
            trig = Triggers.ALARM,
            detail = "Odustajem posle ${runAttemptCount + 1} pokušaja: $detail",
            outcome = LogOutcomes.FAILED,
            attempt = runAttemptCount + 1,
        )
        container.notifier.actionFailed(detail)

        // Window-end OFF failure → keep trying every 30 min until success or supersession.
        val desiredOff = runCatching {
            val now = container.clock.instant()
            !container.engine.resolve(now, container.scheduleRepository.scheduleInputs(now)).desired.powerOn
        }.getOrDefault(false)
        if (desiredOff) {
            val retry = OneTimeWorkRequestBuilder<TransitionWorker>()
                .setInitialDelay(Duration.ofMinutes(30))
                .setInputData(workDataOf(KEY_OFF_RETRY to true))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                UNIQUE_OFF_RETRY,
                ExistingWorkPolicy.REPLACE,
                retry,
            )
        }
        return Result.failure()
    }

    companion object {
        /** Attempts 0..6 retry; attempt 7 gives up — backoff 30s→1m→2m→4m→8m→16m→32m ≈ 1 h span. */
        private const val MAX_ATTEMPTS = 7

        /**
         * Separate unique name so the 30-min OFF-retry chain never REPLACE-cancels itself
         * from inside its own `doWork`. A stale chain run after recovery is a free no-op
         * (idempotent convergence).
         */
        const val UNIQUE_OFF_RETRY = "transitionOffRetry"

        /** Marks a run of the 30-min OFF-retry chain (converges as WATCHDOG, not ALARM). */
        private const val KEY_OFF_RETRY = "offRetry"
    }
}
