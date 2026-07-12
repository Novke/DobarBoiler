package rs.novica.dobarboiler.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import rs.novica.dobarboiler.R

/**
 * Notification channels per SPEC §5 / R11.
 *
 * - [ERRORS]  ("greske")   — HIGH: failed scheduled actions, auth failures, permission problems, late HBT.
 * - [BOOST]   ("boost")    — DEFAULT channel importance so boost completion/timeout alert audibly;
 *                            the *ongoing* boost (FGS) notification is posted silent + only-alert-once
 *                            on this same channel, which realizes the "LOW ongoing / DEFAULT done"
 *                            split from R11 (an Android channel has a single fixed importance,
 *                            so the low-key ongoing behavior is achieved per-notification).
 * - [EVENTS]  ("dogadjaji") — LOW and fully silent: window transitions, vacation resume,
 *                            override expiry, manual-change detection.
 *
 * [ensure] is idempotent — `createNotificationChannel` is a no-op for an existing id — and is
 * called from `App.onCreate()`. minSdk is 26, so no API-level guard is needed.
 */
object Channels {

    const val ERRORS = "greske"
    const val BOOST = "boost"
    const val EVENTS = "dogadjaji"

    fun ensure(context: Context) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val errors = NotificationChannel(
            ERRORS,
            context.getString(R.string.notif_ch_errors),
            NotificationManager.IMPORTANCE_HIGH,
        )

        val boost = NotificationChannel(
            BOOST,
            context.getString(R.string.notif_ch_boost),
            NotificationManager.IMPORTANCE_DEFAULT,
        )

        val events = NotificationChannel(
            EVENTS,
            context.getString(R.string.notif_ch_events),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(errors, boost, events))
    }
}
