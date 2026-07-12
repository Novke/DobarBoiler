package rs.novica.dobarboiler.scheduling

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.time.Duration
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import rs.novica.dobarboiler.App
import rs.novica.dobarboiler.di.AppContainer
import rs.novica.dobarboiler.domain.BoostSession
import rs.novica.dobarboiler.domain.DeviceSnapshot
import rs.novica.dobarboiler.domain.EngineConfig
import rs.novica.dobarboiler.notifications.Notifier

/**
 * Foreground boost runner (SPEC §6.5, R2/R3/R4).
 *
 * App "Boost" never touches device mode 9: the boiler stays in mode 1 (MANUAL) and boost
 * is simply power on + user-picked temperature + eco off (asserted by `converge(BOOST)`),
 * monitored by an in-process 90 s poll loop under a `specialUse` foreground service.
 * An exact alarm at `timeoutAt` ([BoostTimeoutWorker]) is the backstop that completes the
 * boost even if this service dies.
 *
 * Completion (either): `temp >= target − 0.5`, or
 * `!heatReq && reqTemp >= target && temp >= target − 2` (firmware satisfied close to target).
 * Then: complete DONE → converge (reverts to planner state) → done notification → stop.
 *
 * Starting a boost while one is active = cancel-then-start (`ScheduleRepository.startBoost`
 * cancels the previous session); redelivered start intents adopt the live session instead
 * of restarting it, so a process death never resets the timeout.
 */
class BoostService : Service() {

    companion object {
        private const val ACTION_START = "rs.novica.dobarboiler.boost.START"
        private const val ACTION_CANCEL = "rs.novica.dobarboiler.boost.CANCEL"
        private const val EXTRA_TARGET_TEMP = "targetTemp"
        private val POLL_INTERVAL: Duration = Duration.ofSeconds(90)

        /** Called from foreground UI only (SPEC §6.5). */
        fun start(context: Context, targetTemp: Int) {
            val intent = Intent(context, BoostService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TARGET_TEMP, targetTemp)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, BoostService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val container: AppContainer
        get() = (applicationContext as App).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_CANCEL -> {
                job?.cancel()
                job = scope.launch { cancelActiveBoost() }
                START_NOT_STICKY
            }
            else -> {
                // ACTION_START, a redelivered start intent, or a null restart intent.
                val requestedTarget = intent?.getIntExtra(EXTRA_TARGET_TEMP, -1)?.takeIf { it > 0 }
                goForeground(requestedTarget)
                job?.cancel()
                job = scope.launch { runBoost(requestedTarget) }
                START_REDELIVER_INTENT
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        container.notifier.cancelBoost()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ boost loop

    private suspend fun runBoost(requestedTarget: Int?) {
        val c = container
        val existing = c.scheduleRepository.activeBoost().first()

        val session: BoostSession = when {
            requestedTarget == null && existing != null -> existing // service restart — adopt
            requestedTarget == null -> { stopSelf(); return }       // nothing to resume
            existing != null && existing.targetTemp == requestedTarget -> existing // redelivery
            else -> {
                // Fresh boost; the repository cancels any previous session first.
                val cfg = c.settings.settingsSnapshot().engineConfig
                val now = c.clock.instant()
                val timeoutAt = now.plus(cfg.boostMaxDuration)
                val id = c.scheduleRepository.startBoost(requestedTarget, timeoutAt)
                c.actionLog.log(
                    kind = LogKinds.BOOST_START, trig = Triggers.USER,
                    detail = "Boost do $requestedTarget °C (najduže ${cfg.boostMaxDuration.toHours()} h)",
                    outcome = LogOutcomes.SUCCESS,
                )
                BoostSession(id = id, startedAt = now, targetTemp = requestedTarget, timeoutAt = timeoutAt)
            }
        }

        // Alarm backstop first — from this point the boost completes even if we die.
        c.alarmScheduler.armBoostTimeout(session.timeoutAt)
        // Assert power on + target temp + eco off (precedence rule 1 in the resolver).
        c.converger.converge(Triggers.BOOST)

        while (true) {
            val active = c.scheduleRepository.activeBoost().first()
            if (active == null || active.id != session.id) break // completed/cancelled elsewhere

            val now = c.clock.instant()
            if (!now.isBefore(session.timeoutAt)) {
                completeTimeout(session)
                break
            }

            val snapshot = runCatching { c.deviceRepository.refresh(Duration.ZERO) }.getOrNull()
            if (snapshot != null) {
                if (isComplete(snapshot, session.targetTemp)) {
                    completeDone(session, snapshot)
                    break
                }
                updateNotification(snapshot, session)
            }
            delay(POLL_INTERVAL.toMillis())
        }
        stopSelf()
    }

    private fun isComplete(snapshot: DeviceSnapshot, target: Int): Boolean =
        snapshot.temp >= target - 0.5 ||
            (!snapshot.heatReq && snapshot.reqTemp >= target && snapshot.temp >= target - 2)

    private suspend fun completeDone(session: BoostSession, snapshot: DeviceSnapshot) {
        val c = container
        c.scheduleRepository.completeBoost(session.id, "DONE")
        c.alarmScheduler.cancelBoostTimeout()
        c.converger.converge(Triggers.BOOST) // revert to planner state
        c.notifier.boostDone(session.targetTemp)
        c.actionLog.log(
            kind = LogKinds.BOOST_COMPLETE, trig = Triggers.BOOST,
            detail = "Boost završen — voda na ${snapshot.temp.roundToInt()} °C",
            outcome = LogOutcomes.SUCCESS,
        )
    }

    private suspend fun completeTimeout(session: BoostSession) {
        val c = container
        c.scheduleRepository.completeBoost(session.id, "TIMEOUT")
        c.alarmScheduler.cancelBoostTimeout()
        val currentTemp = c.deviceRepository.snapshot.value?.temp ?: 0.0
        c.converger.converge(Triggers.BOOST)
        c.notifier.boostTimeout(currentTemp)
        c.actionLog.log(
            kind = LogKinds.BOOST_TIMEOUT, trig = Triggers.BOOST,
            detail = "Boost prekinut po isteku — voda na ${currentTemp.roundToInt()} °C " +
                "(cilj ${session.targetTemp} °C)",
            outcome = LogOutcomes.SUCCESS,
        )
    }

    private suspend fun cancelActiveBoost() {
        val c = container
        val active = c.scheduleRepository.activeBoost().first()
        if (active != null) {
            c.scheduleRepository.completeBoost(active.id, "CANCELLED")
            c.actionLog.log(
                kind = LogKinds.BOOST_COMPLETE, trig = Triggers.USER,
                detail = "Boost prekinut na zahtev korisnika",
                outcome = LogOutcomes.SUCCESS,
            )
            c.converger.converge(Triggers.BOOST) // revert to planner state
        }
        c.alarmScheduler.cancelBoostTimeout()
        stopSelf()
    }

    // ------------------------------------------------------------------ notification

    private fun goForeground(target: Int?) {
        val c = container
        val temp = c.deviceRepository.snapshot.value?.temp ?: 0.0
        val notification = c.notifier.boostOngoing(temp, target ?: temp.roundToInt(), null)
        startForegroundCompat(notification)
    }

    private suspend fun updateNotification(snapshot: DeviceSnapshot, session: BoostSession) {
        val cfg = container.settings.settingsSnapshot().engineConfig
        val eta = etaMinutes(snapshot, session.targetTemp, cfg)
        startForegroundCompat(container.notifier.boostOngoing(snapshot.temp, session.targetTemp, eta))
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifier.ID_BOOST_ONGOING, notification, type)
    }

    /**
     * ETA for the notification: the device's own `rmTm` when plausible (actively heating,
     * 1 min..6 h), otherwise remaining delta over the learned/configured full-power rate.
     */
    private fun etaMinutes(snapshot: DeviceSnapshot, target: Int, cfg: EngineConfig): Long? {
        val remainingC = target - snapshot.temp
        if (remainingC <= 0.5) return 0
        val rmTm = snapshot.remainingHeatTime
        if (rmTm != null && snapshot.heatReq &&
            rmTm >= Duration.ofMinutes(1) && rmTm <= Duration.ofHours(6)
        ) {
            return rmTm.toMinutes()
        }
        val rate = cfg.heatingRate.effectiveRatePerHour(eco = false)
        if (rate <= 0.0) return null
        return ceil(remainingC / rate * 60.0).toLong()
    }
}
