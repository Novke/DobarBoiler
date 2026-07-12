package rs.novica.dobarboiler.scheduling

import android.content.Context
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
 * Verify-poll 90 s after each command batch (SPEC §6.4): confirm the slow device bus
 * actually applied what we asserted.
 *
 * - Match → `VERIFY_POLL / SUCCESS` log.
 * - Mismatch, first pass → one re-`converge(Triggers.VERIFY)` (which reuses the snapshot
 *   just fetched and does NOT enqueue another verify itself), then a single second-pass
 *   check 90 s later.
 * - Still mismatched on the second pass → FAILED log + error notification.
 * - An active manual hold means the mismatch is the user's own doing — skip quietly.
 *
 * Non-expedited by design; a verify poll has no urgency.
 */
class VerifyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as App).container
        if (container.credentialStore.gwId == null) return Result.success()

        val snapshot = try {
            container.deviceRepository.refresh(Duration.ZERO)
        } catch (_: Exception) {
            return if (runAttemptCount < MAX_POLL_ATTEMPTS) Result.retry() else Result.success()
        }

        val now = container.clock.instant()
        val runtime = container.settings.runtimeSnapshot()

        val hold = runtime.manualHoldUntil
        if (hold != null && now.isBefore(hold)) {
            container.actionLog.log(
                kind = LogKinds.VERIFY, trig = Triggers.VERIFY,
                detail = "Ručna promena aktivna — provera preskočena",
                outcome = LogOutcomes.SKIPPED,
            )
            return Result.success()
        }

        val applied = AppliedStateCodec.decode(runtime.lastAppliedStateJson)
            ?: return Result.success() // nothing ever asserted — nothing to verify

        if (AppliedStateCodec.matches(applied, snapshot)) {
            container.actionLog.log(
                kind = LogKinds.VERIFY, trig = Triggers.VERIFY,
                detail = "Stanje potvrđeno", outcome = LogOutcomes.SUCCESS,
            )
            return Result.success()
        }

        val secondTry = inputData.getBoolean(KEY_SECOND_TRY, false)
        if (!secondTry) {
            container.actionLog.log(
                kind = LogKinds.VERIFY, trig = Triggers.VERIFY,
                detail = "Neslaganje sa zadatim stanjem — ponovna konvergencija",
                outcome = LogOutcomes.RETRYING,
            )
            // Reuses the snapshot we just fetched; may instead detect a manual change and
            // set a hold, which the second pass below then treats as SKIPPED.
            container.converger.converge(Triggers.VERIFY, pollFresh = false)

            val secondPass = OneTimeWorkRequestBuilder<VerifyWorker>()
                .setInitialDelay(Duration.ofSeconds(90))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setInputData(workDataOf(KEY_SECOND_TRY to true))
                .build()
            // APPEND_OR_REPLACE: never REPLACE-cancels this (still running) worker.
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                secondPass,
            )
            return Result.success()
        }

        val detail = "Bojler ne prati zadato stanje ni posle ponovnog pokušaja"
        container.actionLog.log(
            kind = LogKinds.VERIFY, trig = Triggers.VERIFY,
            detail = detail, outcome = LogOutcomes.FAILED,
        )
        container.notifier.actionFailed(detail)
        return Result.failure()
    }

    companion object {
        const val UNIQUE_NAME = "verify"
        const val KEY_SECOND_TRY = "secondTry"
        private const val MAX_POLL_ATTEMPTS = 3
    }
}
