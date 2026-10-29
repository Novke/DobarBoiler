package rs.novica.dobarboiler.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import rs.novica.dobarboiler.R
import kotlin.math.roundToInt

/**
 * Central place for every notification the app posts (SPEC §5).
 *
 * All user-visible text comes from `strings.xml` (Serbian latinica, keys per SPEC §9).
 * Tapping any notification opens `MainActivity`; error notifications additionally carry
 * [EXTRA_OPEN_HISTORY] so the UI can land on the history tab. The activity is targeted by
 * class name (not a class reference) because `notifications/` depends on no other module
 * (SPEC §1.2) — the component lives in the same APK, so an explicit intent still resolves.
 *
 * Posting is permission-safe: on API 33+ the user may deny POST_NOTIFICATIONS, in which case
 * posting is silently skipped (the watchdog/health card surfaces the problem elsewhere).
 * [boostOngoing] only *builds* the notification — `BoostService` passes it to
 * `startForeground(3001, ...)`, which is exempt from the runtime notification permission.
 */
class Notifier(private val context: Context) {

    companion object {
        // Frozen notification ids (SPEC §5).
        const val ID_ACTION_FAILED = 1001
        const val ID_PERMISSION_PROBLEM = 1002
        const val ID_AUTH_FAILED = 1003
        const val ID_HBT_LATE = 1004
        const val ID_DEVICE_OFFLINE = 1005
        const val ID_MANUAL_CHANGE = 2001
        const val ID_SCHEDULED_EVENT = 2002
        const val ID_DEVICE_ONLINE = 2003
        const val ID_BOOST_ONGOING = 3001
        const val ID_BOOST_DONE = 3002
        const val ID_BOOST_TIMEOUT = 3003

        /**
         * Boolean extra on the content intent. `true` on ERRORS-channel notifications —
         * MainActivity should open the history tab when present and true.
         */
        const val EXTRA_OPEN_HISTORY = "rs.novica.dobarboiler.extra.OPEN_HISTORY"

        private const val MAIN_ACTIVITY_CLASS = "rs.novica.dobarboiler.ui.MainActivity"

        /**
         * `notif_boost_timeout` formats as "Boost prekinut posle %1$s — …" but the frozen
         * signature `boostTimeout(currentTemp: Double)` carries no duration, so a fixed
         * "maksimalnog trajanja" ("the maximum duration") fills the slot — always true,
         * since the timeout fires exactly at `boostMaxDuration`.
         */
        private const val MAX_DURATION_LABEL = "maksimalnog trajanja"

        /** Accent for the offline alert (Material red 700). */
        private const val OFFLINE_RED = 0xFFD32F2F.toInt()
    }

    // ---------------------------------------------------------------------------------------
    // ERRORS channel ("greske", HIGH)
    // ---------------------------------------------------------------------------------------

    /** A scheduled action ultimately failed (worker retries exhausted). */
    fun actionFailed(detail: String) {
        val notification = builder(Channels.ERRORS, ID_ACTION_FAILED, openHistory = true)
            .setContentText(context.getString(R.string.notif_action_failed, detail))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notif_action_failed, detail)),
            )
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        post(ID_ACTION_FAILED, notification)
    }

    /** A reliability-critical permission regressed (exact alarms, notifications, battery). */
    fun permissionProblem(text: String) {
        val notification = builder(Channels.ERRORS, ID_PERMISSION_PROBLEM, openHistory = false)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        post(ID_PERMISSION_PROBLEM, notification)
    }

    /** Cloud re-login failed — probably a changed password. */
    fun authFailed() {
        val text = context.getString(R.string.login_error)
        val notification = builder(Channels.ERRORS, ID_AUTH_FAILED, openHistory = false)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        post(ID_AUTH_FAILED, notification)
    }

    /** Heat-by-time started too late; water is projected to miss the target by [shortfallC] °C. */
    fun hbtLate(shortfallC: Int) {
        val text = context.getString(R.string.notif_hbt_late, shortfallC)
        val notification = builder(Channels.ERRORS, ID_HBT_LATE, openHistory = true)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        post(ID_HBT_LATE, notification)
    }

    /**
     * The boiler's gateway is unreachable — almost always a tripped power switch (SPEC §7.5).
     * Ongoing and not auto-cancelled, so "Obriši sve" leaves it alone; re-posted on every
     * offline observation (≤ 15 min apart), so it comes back if swiped away (Android 14+
     * lets the user swipe ongoing notifications). Alerts only when it is not already shown.
     * Removed by [deviceOnline].
     */
    fun deviceOffline(sinceLabel: String) {
        val text = context.getString(R.string.notif_device_offline, sinceLabel)
        val notification = builder(Channels.ERRORS, ID_DEVICE_OFFLINE, openHistory = false)
            .setContentTitle(context.getString(R.string.notif_device_offline_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(OFFLINE_RED)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .build()
        post(ID_DEVICE_OFFLINE, notification)
    }

    // ---------------------------------------------------------------------------------------
    // EVENTS channel ("dogadjaji", LOW, silent)
    // ---------------------------------------------------------------------------------------

    /** An external (physical-button) change was detected; the app respects it until [untilLabel]. */
    fun manualChangeDetected(untilLabel: String) {
        val text = context.getString(R.string.notif_manual_change, untilLabel)
        val notification = builder(Channels.EVENTS, ID_MANUAL_CHANGE, openHistory = false)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        post(ID_MANUAL_CHANGE, notification)
    }

    /**
     * A routine scheduled event happened (window start/end, vacation resumed, override expired).
     * [text] is already localized by the caller (e.g. `notif_heat_started`, `notif_vacation_resumed`).
     */
    fun scheduledEvent(text: String) {
        val notification = builder(Channels.EVENTS, ID_SCHEDULED_EVENT, openHistory = false)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        post(ID_SCHEDULED_EVENT, notification)
    }

    // ---------------------------------------------------------------------------------------
    // BOOST channel ("boost")
    // ---------------------------------------------------------------------------------------

    /**
     * Builds (does NOT post) the ongoing foreground-service notification for an active boost.
     * `BoostService` uses it for `startForeground(3001, ...)` and re-posts it on every 90 s poll.
     * Silent + only-alert-once so the periodic updates never make a sound (R11 "LOW ongoing").
     */
    fun boostOngoing(currentTemp: Double, target: Int, etaMinutes: Long?): Notification {
        val text = context.getString(
            R.string.boost_notif,
            currentTemp.roundToInt(),
            target,
            formatMinutes(etaMinutes),
        )
        return builder(Channels.BOOST, ID_BOOST_ONGOING, openHistory = false)
            .setContentText(text)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Boost reached its target temperature. */
    fun boostDone(target: Int) {
        val text = context.getString(R.string.notif_boost_done, target)
        val notification = builder(Channels.BOOST, ID_BOOST_DONE, openHistory = false)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        post(ID_BOOST_DONE, notification)
    }

    /** Boost hit its maximum duration before reaching the target. */
    fun boostTimeout(currentTemp: Double) {
        val text = context.getString(
            R.string.notif_boost_timeout,
            MAX_DURATION_LABEL,
            currentTemp.roundToInt(),
        )
        val notification = builder(Channels.BOOST, ID_BOOST_TIMEOUT, openHistory = false)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        post(ID_BOOST_TIMEOUT, notification)
    }

    /** The boiler answers again: drop the offline alert and leave a silent note. */
    fun deviceOnline(outageLabel: String) {
        NotificationManagerCompat.from(context).cancel(ID_DEVICE_OFFLINE)
        val text = context.getString(R.string.notif_device_online, outageLabel)
        val notification = builder(Channels.EVENTS, ID_DEVICE_ONLINE, openHistory = false)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        post(ID_DEVICE_ONLINE, notification)
    }

    /** Removes the ongoing boost notification (service death / cancel paths). */
    fun cancelBoost() {
        NotificationManagerCompat.from(context).cancel(ID_BOOST_ONGOING)
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private fun builder(
        channelId: String,
        notificationId: Int,
        openHistory: Boolean,
    ): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentIntent(contentIntent(notificationId, openHistory))
            .setAutoCancel(true)

    private fun contentIntent(requestCode: Int, openHistory: Boolean): PendingIntent {
        val intent = Intent().apply {
            setClassName(context, MAIN_ACTIVITY_CLASS)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(EXTRA_OPEN_HISTORY, openHistory)
        }
        // Distinct requestCode per notification id keeps the PendingIntents (and their extras)
        // from colliding; FLAG_IMMUTABLE per SPEC §5.
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun post(id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call — nothing sensible to do.
        }
    }

    /** "52 min" / "1 h 20 min" / "2 h"; ellipsis when no estimate is available. */
    private fun formatMinutes(minutes: Long?): String = when {
        minutes == null || minutes < 0 -> "…"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}
