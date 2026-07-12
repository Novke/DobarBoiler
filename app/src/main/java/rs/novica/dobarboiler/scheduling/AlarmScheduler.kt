package rs.novica.dobarboiler.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant

/**
 * Thin wrapper around [AlarmManager] (SPEC §6.1).
 *
 * - `setExactAndAllowWhileIdle` for everything (`setAlarmClock` rejected: status-bar icon,
 *   full wake, no benefit).
 * - Fixed request code per action, `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE` — re-arming
 *   atomically replaces the previous alarm, so there is never more than one transition
 *   alarm pending.
 * - If exact alarms are not permitted (API 31–32 with the permission revoked), falls back
 *   to inexact `setAndAllowWhileIdle`; the 6 h [WatchdogWorker] bounds the error and
 *   surfaces the health issue to the user.
 *
 * The context is `internal` (not `private`) so the rest of the scheduling module can reach
 * `WorkManager.getInstance(...)` without widening the frozen [Converger] constructor.
 */
class AlarmScheduler(internal val context: Context) {

    companion object {
        const val ACTION_TRANSITION = "rs.novica.dobarboiler.ACTION_TRANSITION"
        const val ACTION_BAND_TICK = "rs.novica.dobarboiler.ACTION_BAND_TICK"
        const val ACTION_BOOST_TIMEOUT = "rs.novica.dobarboiler.ACTION_BOOST_TIMEOUT"
        const val RC_TRANSITION = 1
        const val RC_BAND = 2
        const val RC_BOOST = 3
    }

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** True when exact alarms may be scheduled (always true below API 31 and with USE_EXACT_ALARM). */
    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /** setExactAndAllowWhileIdle; fixed requestCode per action => re-arming atomically replaces. */
    fun armTransition(at: Instant) = arm(at, ACTION_TRANSITION, RC_TRANSITION)

    fun armBandTick(at: Instant) = arm(at, ACTION_BAND_TICK, RC_BAND)

    fun armBoostTimeout(at: Instant) = arm(at, ACTION_BOOST_TIMEOUT, RC_BOOST)

    fun cancelBandTick() = cancel(ACTION_BAND_TICK, RC_BAND)

    fun cancelBoostTimeout() = cancel(ACTION_BOOST_TIMEOUT, RC_BOOST)

    fun cancelTransition() = cancel(ACTION_TRANSITION, RC_TRANSITION)

    /** Watchdog check: is a transition alarm PendingIntent currently registered? */
    internal fun isTransitionArmed(): Boolean =
        pendingIntent(ACTION_TRANSITION, RC_TRANSITION, PendingIntent.FLAG_NO_CREATE) != null

    // -------------------------------------------------------------------- internals

    private fun arm(at: Instant, action: String, requestCode: Int) {
        val pi = requireNotNull(pendingIntent(action, requestCode, PendingIntent.FLAG_UPDATE_CURRENT))
        val triggerAtMillis = at.toEpochMilli()
        try {
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                // Inexact fallback — WatchdogWorker surfaces the degraded-reliability health issue.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call (API 31–32 race).
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun cancel(action: String, requestCode: Int) {
        val pi = pendingIntent(action, requestCode, PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private fun pendingIntent(action: String, requestCode: Int, baseFlags: Int): PendingIntent? {
        // Explicit intent — AlarmReceiver is not exported and has no intent filter (SPEC §7.1).
        val intent = Intent(action).setClass(context, AlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            baseFlags or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
