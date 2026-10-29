package rs.novica.dobarboiler.scheduling

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Duration
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import rs.novica.dobarboiler.App
import rs.novica.dobarboiler.api.AristonException

/**
 * Alarm-backed boost backstop (SPEC §6.4/R4): completes an overrun boost even if
 * [BoostService] died. If an uncompleted session exists, mark it TIMEOUT, revert to the
 * planner state via one convergence pass, and notify. On retry runs the completion is
 * already recorded, so only the (idempotent) convergence repeats — the notification never
 * duplicates.
 */
class BoostTimeoutWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as App).container

        val boost = container.scheduleRepository.activeBoost().first()
        if (boost != null) {
            val now = container.clock.instant()
            if (now.isBefore(boost.timeoutAt.minus(EARLY_FIRE_TOLERANCE))) {
                // Stale alarm for a newer session — re-arm to the real timeout and leave.
                container.alarmScheduler.armBoostTimeout(boost.timeoutAt)
                return Result.success()
            }

            container.scheduleRepository.completeBoost(boost.id, "TIMEOUT")
            container.alarmScheduler.cancelBoostTimeout()

            val currentTemp = runCatching { container.deviceRepository.refresh(Duration.ZERO).temp }
                .getOrElse { container.deviceRepository.snapshot.value?.temp ?: 0.0 }
            container.actionLog.log(
                kind = LogKinds.BOOST_TIMEOUT, trig = Triggers.BOOST,
                detail = "Boost prekinut po isteku — voda na ${currentTemp.roundToInt()} °C " +
                    "(cilj ${boost.targetTemp} °C)",
                outcome = LogOutcomes.SUCCESS,
            )
            container.notifier.boostTimeout(currentTemp)
        }

        // Revert to whatever the planner wants right now (also runs on retry passes).
        return when (val outcome = container.converger.converge(Triggers.BOOST)) {
            is ConvergeOutcome.Converged, is ConvergeOutcome.Skipped -> Result.success()
            is ConvergeOutcome.Failed -> {
                if (runAttemptCount < MAX_ATTEMPTS) return Result.retry()
                val detail = outcome.error.message ?: outcome.error.javaClass.simpleName
                container.actionLog.log(
                    kind = LogKinds.COMMAND_FAILED, trig = Triggers.BOOST,
                    detail = "Povratak na plan posle boosta nije uspeo: $detail",
                    outcome = LogOutcomes.FAILED,
                    attempt = runAttemptCount + 1,
                )
                // A powerless boiler already has its own ongoing alert (§7.5).
                if (outcome.error !is AristonException.DeviceOffline) container.notifier.actionFailed(detail)
                Result.failure()
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 7
        val EARLY_FIRE_TOLERANCE: Duration = Duration.ofSeconds(30)
    }
}
