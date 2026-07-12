package rs.novica.dobarboiler.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.time.Duration

/**
 * Receives the exact alarms armed by [AlarmScheduler] and immediately hands off to
 * WorkManager (SPEC §6.3). Zero I/O here: the ~10 s receiver budget cannot cover a
 * login-refresh plus slow-bus round trips, while WorkManager gives persistence, the
 * network constraint and exponential backoff for free.
 *
 * Not exported, no intent filter — only our own explicit PendingIntents reach it.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val (workerClass, uniqueName) = when (intent.action) {
            AlarmScheduler.ACTION_TRANSITION -> TransitionWorker::class.java to UNIQUE_TRANSITION
            AlarmScheduler.ACTION_BAND_TICK -> BandWorker::class.java to UNIQUE_BAND
            AlarmScheduler.ACTION_BOOST_TIMEOUT -> BoostTimeoutWorker::class.java to UNIQUE_BOOST_TIMEOUT
            else -> return
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.REPLACE,
            expeditedRequest(workerClass),
        )
    }

    companion object {
        const val UNIQUE_TRANSITION = "transition"
        const val UNIQUE_BAND = "band"
        const val UNIQUE_BOOST_TIMEOUT = "boostTimeout"

        /**
         * Shared request shape for all alarm-driven work (SPEC §6.3): expedited with
         * non-expedited fallback, CONNECTED constraint, exponential backoff from 30 s.
         */
        internal fun expeditedRequest(workerClass: Class<out ListenableWorker>): OneTimeWorkRequest =
            OneTimeWorkRequest.Builder(workerClass)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .build()
    }
}
