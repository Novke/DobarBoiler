package rs.novica.dobarboiler.scheduling

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager

/**
 * Re-converges the world after events that invalidate armed alarms (SPEC §6.3):
 * reboot, app update, wall-clock change, timezone change, and the exact-alarm
 * permission being (re)granted on API 31–32.
 *
 * All five paths funnel into [RescheduleWorker] → `converge(Triggers.BOOT)`, which
 * self-heals by construction: state is converged, never replayed. A pending
 * vacation-return survives reboot because it is a Room row plus a re-armed alarm.
 */
class BootstrapReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,          // TIME_SET
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> Unit
            else -> return
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_RESCHEDULE,
            ExistingWorkPolicy.REPLACE,
            AlarmReceiver.expeditedRequest(RescheduleWorker::class.java),
        )
    }

    companion object {
        const val UNIQUE_RESCHEDULE = "reschedule"
    }
}
