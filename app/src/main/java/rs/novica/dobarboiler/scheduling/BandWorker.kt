package rs.novica.dobarboiler.scheduling

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Duration
import kotlin.math.roundToInt
import rs.novica.dobarboiler.App
import rs.novica.dobarboiler.domain.BoilerCommand
import rs.novica.dobarboiler.domain.DesiredState
import rs.novica.dobarboiler.domain.MaintainMode
import rs.novica.dobarboiler.domain.StateSource

/**
 * Band-tick worker (SPEC §6.4), fired by the `ACTION_BAND_TICK` alarm in two roles:
 *
 * - **Band mode ("Napredno održavanje")**: app-side hysteresis inside the heating window —
 *   `temp >= stopAt` → power off, `temp <= restartAt` → power on — then re-arms the next
 *   tick while still inside the window. The next tick is re-armed BEFORE sending commands
 *   so a command failure can never break the chain.
 * - **Simple mode**: the single mid-window sanity check — one poll plus a convergence pass
 *   over the fresh snapshot (`pollFresh = false` avoids a double network hit), which gives
 *   drift correction and manual-change detection for free.
 */
class BandWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as App).container
        val gwId = container.credentialStore.gwId ?: return Result.success()

        val snapshot = try {
            container.deviceRepository.refresh(Duration.ZERO)
        } catch (_: Exception) {
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }

        val now = container.clock.instant()
        val inputs = container.scheduleRepository.scheduleInputs(now)
        val resolution = container.engine.resolve(now, inputs)
        val band = (inputs.config.maintainMode as? MaintainMode.Band)?.band
        val inWindowOn = resolution.desired.powerOn &&
            (resolution.source == StateSource.WEEKLY_PLAN || resolution.source == StateSource.DAY_OVERRIDE)

        if (band == null || !inWindowOn) {
            // Simple-mode mid-window check, or the window ended while this tick was pending:
            // one convergence pass over the snapshot we just fetched.
            container.converger.converge(Triggers.BAND, pollFresh = false)
            return Result.success()
        }

        // Re-arm first — the chain must survive a command failure.
        val windowEnd = resolution.nextTransition?.at
        val nextTick = now.plus(band.pollEvery)
        if (windowEnd == null || nextTick.isBefore(windowEnd)) {
            container.alarmScheduler.armBandTick(nextTick)
        }

        val runtime = container.settings.runtimeSnapshot()
        if (runtime.manualHoldUntil?.let { now.isBefore(it) } == true) {
            return Result.success() // user's manual change owns the boiler until the next transition
        }

        try {
            when {
                snapshot.on && snapshot.temp >= band.stopAt -> {
                    container.deviceRepository.execute(gwId, listOf(BoilerCommand.SetPower(false)))
                    container.settings.updateRuntime {
                        it.copy(
                            lastAppliedStateJson = AppliedStateCodec.encode(
                                DesiredState(powerOn = false, targetTemp = null, eco = null),
                            ),
                            lastAppliedAt = now,
                        )
                    }
                    container.actionLog.log(
                        kind = LogKinds.CONVERGE, trig = Triggers.BAND,
                        detail = "Histerezis: pauza na ${snapshot.temp.roundToInt()} °C (≥ ${band.stopAt})",
                        outcome = LogOutcomes.SUCCESS,
                    )
                }
                !snapshot.on && snapshot.temp <= band.restartAt -> {
                    container.deviceRepository.execute(gwId, listOf(BoilerCommand.SetPower(true)))
                    container.settings.updateRuntime {
                        it.copy(
                            lastAppliedStateJson = AppliedStateCodec.encode(resolution.desired),
                            lastAppliedAt = now,
                        )
                    }
                    container.actionLog.log(
                        kind = LogKinds.CONVERGE, trig = Triggers.BAND,
                        detail = "Histerezis: ponovo grejem od ${snapshot.temp.roundToInt()} °C (≤ ${band.restartAt})",
                        outcome = LogOutcomes.SUCCESS,
                    )
                }
                // Inside the band — nothing to do; the firmware keeps heating/idling.
            }
        } catch (e: Exception) {
            container.actionLog.log(
                kind = LogKinds.COMMAND_FAILED, trig = Triggers.BAND,
                detail = "Histerezis komanda nije uspela: ${e.message ?: e.javaClass.simpleName}",
                outcome = LogOutcomes.RETRYING,
                attempt = runAttemptCount + 1,
            )
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
        return Result.success()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
