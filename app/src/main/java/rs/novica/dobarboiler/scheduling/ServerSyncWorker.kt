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
import rs.novica.dobarboiler.App
import rs.novica.dobarboiler.data.SyncResult
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Uploads `action_log` and the current intent to the history server (design §5.1).
 * Failures never notify and never write to `action_log` (that would feed the log back into itself);
 * they only show up in Settings → Server istorije.
 */
class ServerSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        when ((applicationContext as App).container.serverSync.run()) {
            is SyncResult.Failed -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            else -> Result.success()
        }

    companion object {
        const val UNIQUE_NAME = "server_sync"
        const val PERIODIC_NAME = "server_sync_periodic"
        private const val MAX_RETRIES = 5

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** After every converge: one upload ~15 s later (REPLACE batches bursts of converges). */
        fun enqueueSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<ServerSyncWorker>()
                .setInitialDelay(Duration.ofSeconds(15))
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Safety net: catches up whatever the one-time jobs missed (tailnet down, phone offline). */
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<ServerSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
