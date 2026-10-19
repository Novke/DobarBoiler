package rs.novica.dobarboiler.scheduling

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rs.novica.dobarboiler.R
import rs.novica.dobarboiler.api.AristonException
import rs.novica.dobarboiler.data.ActionLogRepository
import rs.novica.dobarboiler.data.IntentCodec
import rs.novica.dobarboiler.data.CredentialStore
import rs.novica.dobarboiler.data.DeviceRepository
import rs.novica.dobarboiler.data.ScheduleRepository
import rs.novica.dobarboiler.data.SettingsStore
import rs.novica.dobarboiler.domain.DesiredState
import rs.novica.dobarboiler.domain.DeviceSnapshot
import rs.novica.dobarboiler.domain.MaintainMode
import rs.novica.dobarboiler.domain.ManualChangePolicy
import rs.novica.dobarboiler.domain.OneTimeOverride
import rs.novica.dobarboiler.domain.Resolution
import rs.novica.dobarboiler.domain.ScheduleInputs
import rs.novica.dobarboiler.domain.SchedulerEngine
import rs.novica.dobarboiler.domain.StateSource
import rs.novica.dobarboiler.domain.TransitionReason
import rs.novica.dobarboiler.notifications.Notifier

/** `action_log.kind` values (SPEC §6.2 — frozen). */
object LogKinds {
    const val WINDOW_START = "WINDOW_START"; const val WINDOW_END = "WINDOW_END"
    const val HBT_START = "HBT_START"; const val HBT_END = "HBT_END"
    const val BOOST_START = "BOOST_START"; const val BOOST_COMPLETE = "BOOST_COMPLETE"; const val BOOST_TIMEOUT = "BOOST_TIMEOUT"
    const val VACATION_START = "VACATION_START"; const val VACATION_RESUME = "VACATION_RESUME"
    const val OVERRIDE_EXPIRED = "OVERRIDE_EXPIRED"; const val MANUAL_CHANGE = "MANUAL_CHANGE_DETECTED"
    const val REASSERT = "REASSERT"; const val VERIFY = "VERIFY_POLL"; const val COMMAND_FAILED = "COMMAND_FAILED"
    const val DRIFT = "DRIFT_CORRECTED"; const val CONVERGE = "CONVERGE"
}

/** `action_log.trig` values (SPEC §6.2 — frozen). */
object Triggers {
    const val ALARM = "ALARM"; const val BOOT = "BOOT"; const val WATCHDOG = "WATCHDOG"
    const val USER = "USER"; const val VERIFY = "VERIFY"; const val BAND = "BAND"; const val BOOST = "BOOST"
}

/** `action_log.outcome` values (SPEC §4.2 schema). */
object LogOutcomes {
    const val SUCCESS = "SUCCESS"; const val RETRYING = "RETRYING"
    const val FAILED = "FAILED"; const val SKIPPED = "SKIPPED"
}

sealed interface ConvergeOutcome {
    data class Converged(val commandsSent: Int) : ConvergeOutcome
    data class Skipped(val why: String) : ConvergeOutcome        // e.g. manual hold, no gwId
    data class Failed(val error: Throwable) : ConvergeOutcome    // caller (worker) maps to Result.retry()
}

/**
 * Serialization of the `DesiredState` the app last asserted, persisted in
 * `RuntimeState.lastAppliedStateJson`. Shared by [Converger], [VerifyWorker] and [BandWorker].
 */
internal object AppliedStateCodec {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Applied(val powerOn: Boolean, val targetTemp: Int? = null, val eco: Boolean? = null)

    fun encode(desired: DesiredState): String =
        json.encodeToString(
            Applied(
                powerOn = desired.powerOn,
                targetTemp = if (desired.powerOn) desired.targetTemp else null,
                eco = if (desired.powerOn) desired.eco else null,
            ),
        )

    fun decode(raw: String?): Applied? =
        raw?.let { runCatching { json.decodeFromString<Applied>(it) }.getOrNull() }

    /** Does the observed snapshot agree with what the app last asserted (±0.5 °C on reqTemp)? */
    fun matches(applied: Applied, snapshot: DeviceSnapshot): Boolean {
        if (snapshot.on != applied.powerOn) return false
        if (applied.powerOn) {
            val t = applied.targetTemp
            if (t != null && abs(snapshot.reqTemp - t) > 0.5) return false
            val e = applied.eco
            if (e != null && snapshot.eco != e) return false
        }
        return true
    }
}

/**
 * The single convergence routine — the whole app's heartbeat (SPEC §6.2).
 *
 * Every background entry point (alarm, boot, watchdog, verify, band tick, boost, UI edit)
 * funnels through [converge]: resolve desired state, poll once (rate-limited), detect
 * manual changes, diff-and-execute only the delta, log, and re-arm exactly one exact alarm
 * at the next transition. Idempotent: state is converged, never replayed.
 */
class Converger(
    private val engine: SchedulerEngine,
    private val schedule: ScheduleRepository,
    private val device: DeviceRepository,
    private val settings: SettingsStore,
    private val creds: CredentialStore,
    private val log: ActionLogRepository,
    private val alarms: AlarmScheduler,
    private val notifier: Notifier,
    private val clock: Clock,
) {

    /**
     * Serializes concurrent converge() calls (UI, workers, BoostService): the last caller
     * to enter resolves against the freshest plan and arms last, and two command batches
     * can never interleave.
     */
    private val convergeMutex = Mutex()

    /** The whole app's heartbeat. Safe to call from anywhere, any time. */
    suspend fun converge(trigger: String, pollFresh: Boolean = true): ConvergeOutcome =
        convergeMutex.withLock { convergeLocked(trigger, pollFresh) }
            .also { ServerSyncWorker.enqueueSoon(alarms.context) } // outside the lock; no-op without a server

    private suspend fun convergeLocked(trigger: String, pollFresh: Boolean): ConvergeOutcome {
        // 1. Not onboarded → nothing to do.
        val gwId = creds.gwId ?: return ConvergeOutcome.Skipped("not onboarded")
        val now = clock.instant()

        // Capture pre-run runtime state: the persisted reason tells us WHY the alarm that
        // triggered us was armed (needed for logging and the vacation-return special case).
        val preRuntime = settings.runtimeSnapshot()
        val firedReason = preRuntime.nextTransitionReason

        // Scheduled transitions outrank manual holds by definition (§7.4). Boost start/end
        // are explicit app-driven transitions and must assert the same way — otherwise a
        // mid-boost hold would silently block the boost revert (boiler left ON for hours).
        if ((trigger == Triggers.ALARM || trigger == Triggers.BOOST) && preRuntime.manualHoldUntil != null) {
            settings.updateRuntime { it.copy(manualHoldUntil = null) }
        }

        // 2. Resolve.
        val inputs: ScheduleInputs = try {
            schedule.scheduleInputs(now)
        } catch (e: Exception) {
            return ConvergeOutcome.Failed(e)
        }
        val res = engine.resolve(now, inputs)
        settings.recordIntent(IntentCodec.encode(res, now))

        // 3. Snapshot (rate-limited poll).
        val snapshot: DeviceSnapshot? = if (pollFresh) {
            try {
                device.refresh(Duration.ofSeconds(30))
            } catch (e: Exception) {
                if (e is AristonException.AuthFailed) notifier.authFailed()
                log.log(
                    kind = LogKinds.COMMAND_FAILED, trig = trigger,
                    detail = "Upit stanja nije uspeo: ${e.message ?: e.javaClass.simpleName}",
                    outcome = LogOutcomes.RETRYING,
                )
                // Arm the transition alarm anyway (mirrors the execute-failure path) —
                // the retry chain re-converges, and a wrong-but-armed schedule beats a
                // silent one after the retries are exhausted.
                armAlarms(now, res, inputs, trigger)
                return ConvergeOutcome.Failed(e)
            }
        } else {
            device.snapshot.value ?: inputs.lastSnapshot
        }

        val runtime = settings.runtimeSnapshot()
        var skipCommands = runtime.manualHoldUntil?.let { now.isBefore(it) } == true
        var manualDetectedNow = false

        // 4. Manual-change detection (§7.4).
        if (!skipCommands) {
            val applied = AppliedStateCodec.decode(runtime.lastAppliedStateJson)
            val appliedAt = runtime.lastAppliedAt
            val isExternal = applied != null && appliedAt != null && snapshot != null &&
                snapshot.at.isAfter(appliedAt.plus(MANUAL_GRACE)) &&
                !AppliedStateCodec.matches(applied, snapshot)
            if (isExternal) {
                when (inputs.config.manualChangePolicy) {
                    ManualChangePolicy.RESPECT_UNTIL_NEXT_TRANSITION -> {
                        val until = res.nextTransition?.at
                        settings.updateRuntime { it.copy(manualHoldUntil = until) }
                        log.log(
                            kind = LogKinds.MANUAL_CHANGE, trig = trigger,
                            detail = "Ručna promena: ${describeSnapshot(snapshot!!)} — poštujem do ${timeLabel(until, now)}",
                            outcome = LogOutcomes.SUCCESS,
                        )
                        notifier.manualChangeDetected(timeLabel(until, now))
                        skipCommands = true
                        manualDetectedNow = true
                    }
                    ManualChangePolicy.REASSERT -> {
                        log.log(
                            kind = LogKinds.REASSERT, trig = trigger,
                            detail = "Ručna promena: ${describeSnapshot(snapshot!!)} — vraćam na plan",
                            outcome = LogOutcomes.SUCCESS,
                        )
                    }
                }
            }
        }

        // 5. Diff and execute the delta.
        var commandsSent = 0
        if (!skipCommands) {
            val cmds = engine.plan(res.desired, snapshot)
            if (cmds.isNotEmpty()) {
                try {
                    device.execute(gwId, cmds)
                } catch (e: Exception) {
                    // The batch may be half-applied — device state is indeterminate. Drop the
                    // applied record so the retry treats the mismatch as ordinary drift instead
                    // of misclassifying our own half-applied state as an external manual change.
                    settings.updateRuntime { it.copy(lastAppliedStateJson = null, lastAppliedAt = null) }
                    if (e is AristonException.AuthFailed) notifier.authFailed()
                    log.log(
                        kind = LogKinds.COMMAND_FAILED, trig = trigger,
                        detail = "Slanje ${cmds.size} komandi nije uspelo: ${e.message ?: e.javaClass.simpleName}",
                        outcome = LogOutcomes.RETRYING,
                        stateJson = AppliedStateCodec.encode(res.desired),
                    )
                    // Arm the transition alarm anyway — the retry chain re-converges,
                    // and a wrong-but-armed schedule beats a silent one.
                    armAlarms(now, res, inputs, trigger)
                    return ConvergeOutcome.Failed(e)
                }
                commandsSent = cmds.size
                val appliedJson = AppliedStateCodec.encode(res.desired)
                settings.updateRuntime { it.copy(lastAppliedStateJson = appliedJson, lastAppliedAt = now) }
                log.log(
                    kind = kindFor(trigger, res, firedReason), trig = trigger,
                    detail = describeDesired(res), outcome = LogOutcomes.SUCCESS,
                    stateJson = appliedJson,
                )
                notifyScheduledEvent(trigger, res, firedReason, now)
                checkHbtLateStart(now, inputs, res, snapshot, trigger)
                if (trigger != Triggers.VERIFY) enqueueVerify()
            }

            // §6.6 — anti-legionella hygiene cycle on return from a >7-day vacation.
            if (trigger == Triggers.ALARM && firedReason == TransitionReason.VACATION_RESUME.name) {
                maybeTriggerAntilegionella(trigger)
            }
        }

        // 6 + 7. Persist next transition, arm exactly one transition alarm, band/mid-window tick.
        armAlarms(now, res, inputs, trigger)

        return if (skipCommands) {
            ConvergeOutcome.Skipped(if (manualDetectedNow) "manual change detected" else "manual hold active")
        } else {
            ConvergeOutcome.Converged(commandsSent)
        }
    }

    // ------------------------------------------------------------------ alarm arming (steps 6–7)

    private suspend fun armAlarms(now: Instant, res: Resolution, inputs: ScheduleInputs, trigger: String) {
        val nt = res.nextTransition
        settings.updateRuntime {
            it.copy(nextTransitionAt = nt?.at, nextTransitionReason = nt?.reason?.name)
        }
        if (nt != null) alarms.armTransition(nt.at) else alarms.cancelTransition()

        val cfg = inputs.config
        val inWindowOn = res.desired.powerOn &&
            (res.source == StateSource.WEEKLY_PLAN || res.source == StateSource.DAY_OVERRIDE)
        val band = (cfg.maintainMode as? MaintainMode.Band)?.band

        when {
            // Advanced hysteresis: 15-min (configurable) exact-alarm chain while inside the window.
            band != null && inWindowOn -> {
                val nextTick = now.plus(band.pollEvery)
                if (nt == null || nextTick.isBefore(nt.at)) alarms.armBandTick(nextTick)
                else alarms.cancelBandTick()
            }
            // Simple mode: single mid-window sanity poll (default ON). The BAND-triggered
            // converge itself must NOT re-arm, or each check would arm another at half the
            // remaining span (unbounded polls).
            cfg.maintainMode is MaintainMode.Simple && cfg.midWindowCheck && inWindowOn &&
                nt != null && nt.reason == TransitionReason.WINDOW_END && trigger != Triggers.BAND -> {
                val midpoint = now.plus(Duration.between(now, nt.at).dividedBy(2))
                if (Duration.between(now, midpoint) >= MIN_MID_WINDOW_LEAD) alarms.armBandTick(midpoint)
                else alarms.cancelBandTick()
            }
            else -> alarms.cancelBandTick()
        }
    }

    // ------------------------------------------------------------------ special cases

    /**
     * §6.6: at the VACATION_RESUME transition, after asserting planner state, run one
     * hygiene cycle when the just-ended vacation spanned more than 7 days. Best-effort:
     * if the data layer no longer exposes the ended vacation row, nothing happens
     * (the firmware's own anti-legionella remains the backstop). Failures are logged,
     * never fatal — the heating plan must not retry because of a hygiene extra.
     */
    private suspend fun maybeTriggerAntilegionella(trigger: String) {
        // The active-only flow is already empty here: scheduleInputs (step 2) deactivated
        // the finished hold. Read the latest row regardless of the active flag instead.
        val vacation = runCatching { schedule.latestVacation() }.getOrNull() ?: return
        if (!vacation.antiLegOnReturn) return
        val resumeAt = vacation.resumeAt ?: return
        if (Duration.between(vacation.startsAt, resumeAt) <= Duration.ofDays(7)) return
        try {
            device.triggerAntilegionella(true)
            log.log(
                kind = LogKinds.VACATION_RESUME, trig = trigger,
                detail = "Antibakterijski ciklus pokrenut posle odmora dužeg od 7 dana",
                outcome = LogOutcomes.SUCCESS,
            )
        } catch (e: Exception) {
            log.log(
                kind = LogKinds.COMMAND_FAILED, trig = trigger,
                detail = "Antibakterijski ciklus nije uspeo: ${e.message ?: e.javaClass.simpleName}",
                outcome = LogOutcomes.FAILED,
            )
        }
    }

    /**
     * §3.6: an HBT that is already active at first assertion started late; notify only when
     * the projected shortfall exceeds 5 °C:
     * `shortfall = target − (estimate + rate × hoursUntilEvent)`.
     */
    private suspend fun checkHbtLateStart(
        now: Instant,
        inputs: ScheduleInputs,
        res: Resolution,
        snapshot: DeviceSnapshot?,
        trigger: String,
    ) {
        if (res.source != StateSource.HEAT_BY_TIME) return
        if (trigger == Triggers.VERIFY || trigger == Triggers.BAND) return
        val hbt = inputs.overrides
            .filterIsInstance<OneTimeOverride.HeatByTime>()
            .filter { o ->
                val start = engine.computedStart(o, inputs, now)
                start <= now && now < o.eventTime.plus(Duration.ofMinutes(o.holdMinutes.toLong()))
            }
            .maxWithOrNull(compareBy({ it.targetTemp }, { it.eventTime }))
            ?: return
        if (!now.isBefore(hbt.eventTime)) return // already in the hold phase — nothing to project

        val estimate = snapshot
            ?.takeIf { Duration.between(it.at, now).abs() < Duration.ofHours(2) }
            ?.temp ?: 30.0
        val rate = inputs.config.heatingRate.effectiveRatePerHour(hbt.eco)
        val hoursUntilEvent = Duration.between(now, hbt.eventTime).toMillis() / 3_600_000.0
        val shortfall = hbt.targetTemp - (estimate + rate * hoursUntilEvent)
        if (shortfall > 5.0) {
            notifier.hbtLate(shortfall.roundToInt())
            log.log(
                kind = LogKinds.HBT_START, trig = trigger,
                detail = "Kasni start grejanja — projektovani manjak ~${shortfall.roundToInt()} °C",
                outcome = LogOutcomes.SUCCESS,
            )
        }
    }

    // ------------------------------------------------------------------ verify enqueue

    /** Verify-poll 90 s after each command batch (SPEC R5, §6.4). */
    private fun enqueueVerify() {
        val request = OneTimeWorkRequestBuilder<VerifyWorker>()
            .setInitialDelay(Duration.ofSeconds(90))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .setInputData(workDataOf(VerifyWorker.KEY_SECOND_TRY to false))
            .build()
        WorkManager.getInstance(alarms.context)
            .enqueueUniqueWork(VerifyWorker.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    // ------------------------------------------------------------------ logging / labels

    private fun kindFor(trigger: String, res: Resolution, firedReason: String?): String {
        val semanticTrigger = trigger == Triggers.ALARM || trigger == Triggers.USER ||
            trigger == Triggers.BOOT || trigger == Triggers.BOOST
        if (!semanticTrigger) return LogKinds.DRIFT
        return when (res.source) {
            StateSource.BOOST -> LogKinds.BOOST_START
            StateSource.HEAT_BY_TIME -> LogKinds.HBT_START
            StateSource.VACATION -> LogKinds.VACATION_START
            StateSource.KEEP_WARM -> LogKinds.CONVERGE
            StateSource.WEEKLY_PLAN, StateSource.DAY_OVERRIDE ->
                if (res.desired.powerOn) LogKinds.WINDOW_START else LogKinds.WINDOW_END
            StateSource.DEFAULT_OFF -> when (firedReason) {
                TransitionReason.HBT_END.name -> LogKinds.HBT_END
                TransitionReason.VACATION_RESUME.name -> LogKinds.VACATION_RESUME
                TransitionReason.OVERRIDE_EXPIRY.name -> LogKinds.OVERRIDE_EXPIRED
                else -> if (trigger == Triggers.ALARM) LogKinds.WINDOW_END else LogKinds.CONVERGE
            }
        }
    }

    /** Silent EVENTS-channel notifications for routine transitions (ALARM trigger only). */
    private fun notifyScheduledEvent(trigger: String, res: Resolution, firedReason: String?, now: Instant) {
        if (trigger != Triggers.ALARM) return
        val ctx = alarms.context
        when {
            firedReason == TransitionReason.VACATION_RESUME.name ->
                notifier.scheduledEvent(ctx.getString(R.string.notif_vacation_resumed))
            res.desired.powerOn &&
                (res.source == StateSource.WEEKLY_PLAN || res.source == StateSource.DAY_OVERRIDE) ->
                notifier.scheduledEvent(
                    ctx.getString(
                        R.string.notif_heat_started,
                        timeLabel(now, now),
                        res.desired.targetTemp ?: 0,
                    ),
                )
            !res.desired.powerOn && firedReason == TransitionReason.WINDOW_END.name ->
                notifier.scheduledEvent(ctx.getString(R.string.notif_heat_stopped))
        }
    }

    private fun describeDesired(res: Resolution): String {
        val d = res.desired
        return if (d.powerOn) {
            buildString {
                append("Uključeno")
                d.targetTemp?.let { append(", cilj $it °C") }
                d.eco?.let { append(if (it) ", eco" else ", puna snaga") }
                append(" [${res.source}]")
            }
        } else {
            "Isključeno [${res.source}]"
        }
    }

    private fun describeSnapshot(s: DeviceSnapshot): String =
        (if (s.on) "uključen, zadato ${s.reqTemp.roundToInt()} °C" else "isključen") +
            if (s.eco) ", eco" else ""

    /** "HH:mm" for today, "dd.MM. HH:mm" otherwise; "—" when there is no next transition. */
    private fun timeLabel(at: Instant?, now: Instant): String {
        if (at == null) return "—"
        val zone = clock.zone
        val zdt = at.atZone(zone)
        val pattern = if (zdt.toLocalDate() == now.atZone(zone).toLocalDate()) "HH:mm" else "dd.MM. HH:mm"
        return zdt.format(DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag("sr-Latn-RS")))
    }

    private companion object {
        /** Grace for the slow device bus before a differing snapshot counts as external (§6.2 step 4). */
        val MANUAL_GRACE: Duration = Duration.ofMinutes(3)

        /** Don't bother arming a mid-window check closer than this to `now`. */
        val MIN_MID_WINDOW_LEAD: Duration = Duration.ofMinutes(15)
    }
}
