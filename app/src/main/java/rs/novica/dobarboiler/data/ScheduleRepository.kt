package rs.novica.dobarboiler.data

import androidx.room.InvalidationTracker
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import rs.novica.dobarboiler.domain.BoostSession
import rs.novica.dobarboiler.domain.OneTimeOverride
import rs.novica.dobarboiler.domain.Preset
import rs.novica.dobarboiler.domain.RateSample
import rs.novica.dobarboiler.domain.ScheduleInputs
import rs.novica.dobarboiler.domain.VacationHold
import rs.novica.dobarboiler.domain.WeeklyPlan

/**
 * SPEC §4.5 — single source of the resolver's [ScheduleInputs], plus all CRUD the UI
 * needs for presets, weekly plan, one-time overrides, vacation, boost and rate samples.
 */
class ScheduleRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val zone: () -> ZoneId,
) {
    private val presetDao = db.presetDao()
    private val planDao = db.weeklyPlanDao()
    private val dayOverrideDao = db.dayOverrideDao()
    private val hbtDao = db.heatByTimeDao()
    private val vacationDao = db.vacationDao()
    private val boostDao = db.boostSessionDao()
    private val rateSampleDao = db.rateSampleDao()
    private val snapshotDao = db.snapshotDao()

    /**
     * Assembles everything the resolver needs. Also purges (marks) expired inputs in
     * place: HBT rows past `eventTime + hold` become EXPIRED, vacation holds past
     * `resumeAt` become inactive. Hard deletion of old rows happens in [pruneExpired]
     * (watchdog). Day overrides for past window-dates are simply filtered out —
     * yesterday's date is retained because a midnight-crossing window that started
     * yesterday may still be running.
     */
    suspend fun scheduleInputs(now: Instant): ScheduleInputs {
        val nowMs = now.toEpochMilli()
        hbtDao.expire(nowMs)
        vacationDao.deactivateFinished(nowMs)

        val presets = presetDao.all().associate { it.id to it.toDomain() }
        val plan = planRowsToWeeklyPlan(planDao.plan())

        val today = now.atZone(zone()).toLocalDate()
        val overrides: List<OneTimeOverride> =
            dayOverrideDao.all()
                .filter { it.date >= today.minusDays(1) }
                .map { it.toDomain() } +
                hbtDao.active().map { it.toDomain() }

        val vacation = vacationDao.active()?.toDomain()
            ?.takeIf { it.resumeAt == null || it.resumeAt!!.isAfter(now) }
        val boost = boostDao.active()?.toDomain()
        val lastSnapshot = snapshotDao.get()?.toDomainSnapshot()
        val config = settings.settingsSnapshot().engineConfig

        return ScheduleInputs(
            weeklyPlan = plan,
            presets = presets,
            overrides = overrides,
            vacation = vacation,
            boost = boost,
            lastSnapshot = lastSnapshot,
            config = config,
        )
    }

    /**
     * Emits whenever any schedule-input table or any setting changes — UI refresh and
     * alarm re-arm trigger. The snapshot table is deliberately excluded (it changes on
     * every poll and never alters the resolution inputs' intent).
     */
    fun inputsChanged(): Flow<Unit> {
        val tableChanges: Flow<Unit> = callbackFlow {
            val observer = object : InvalidationTracker.Observer(
                "preset", "weekly_plan", "day_override", "heat_by_time", "vacation", "boost_session",
            ) {
                override fun onInvalidated(tables: Set<String>) {
                    trySend(Unit)
                }
            }
            db.invalidationTracker.addObserver(observer)
            awaitClose { db.invalidationTracker.removeObserver(observer) }
        }.flowOn(Dispatchers.IO)
        // AppSettings shares the DataStore file with RuntimeState: dedupe, or every
        // runtime write (lastCloudContactAt on each poll, applied-state after each
        // batch) would emit a spurious inputs-changed event.
        val settingChanges: Flow<Unit> = settings.settings.distinctUntilChanged().drop(1).map { }
        return merge(tableChanges, settingChanges).conflate()
    }

    // -- Presets -----------------------------------------------------------------

    fun presets(): Flow<List<Preset>> =
        presetDao.presetsFlow().map { rows -> rows.map { it.toDomain() } }

    suspend fun upsertPreset(p: Preset): Long =
        if (p.id <= 0) {
            presetDao.insert(p.toEntity())
        } else {
            presetDao.update(p.toEntity())
            p.id
        }

    /** False (and no deletion) while the preset is referenced by the weekly plan or a day override. */
    suspend fun deletePreset(id: Long): Boolean {
        if (presetInUse(id)) return false
        presetDao.delete(id)
        return true
    }

    suspend fun presetInUse(id: Long): Boolean =
        planDao.countUsing(id) > 0 || dayOverrideDao.countUsing(id) > 0

    // -- Weekly plan --------------------------------------------------------------

    fun weeklyPlan(): Flow<WeeklyPlan> = planDao.planFlow().map(::planRowsToWeeklyPlan)

    suspend fun assign(day: DayOfWeek, presetId: Long?) {
        planDao.upsert(WeeklyPlanEntity(dayOfWeek = day.value, presetId = presetId))
    }

    // -- One-time overrides ---------------------------------------------------------

    fun activeOverrides(now: Instant): Flow<List<OneTimeOverride>> {
        val today = now.atZone(zone()).toLocalDate()
        return combine(dayOverrideDao.allFlow(), hbtDao.activeFlow()) { days, hbts ->
            days.filter { it.date >= today.minusDays(1) }.map { it.toDomain() } +
                hbts.map { it.toDomain() }
                    .filter { it.eventTime.plusSeconds(it.holdMinutes * 60L).isAfter(now) }
        }
    }

    /** Upsert keyed on the window-start date (unique). */
    suspend fun upsertDayOverride(date: LocalDate, presetId: Long?) {
        val existing = dayOverrideDao.byDate(date)
        dayOverrideDao.upsert(
            DayOverrideEntity(
                id = existing?.id ?: 0,
                date = date,
                presetId = presetId,
                createdAt = existing?.createdAt ?: Instant.now(),
            ),
        )
    }

    suspend fun addHeatByTime(
        eventTime: Instant,
        targetTemp: Int,
        eco: Boolean,
        holdMinutes: Int,
    ): Long = hbtDao.insert(
        HeatByTimeEntity(
            eventAt = eventTime,
            targetTemp = targetTemp.coerceIn(40, 75),
            eco = eco,
            holdMinutes = holdMinutes.coerceIn(0, 180),
            createdAt = Instant.now(),
            status = HeatByTimeEntity.STATUS_ACTIVE,
        ),
    )

    /** `day = true` targets the day_override table; otherwise the HBT row is marked SKIPPED. */
    suspend fun cancelOverride(id: Long, day: Boolean) {
        if (day) {
            dayOverrideDao.delete(id)
        } else {
            hbtDao.setStatus(id, HeatByTimeEntity.STATUS_SKIPPED)
        }
    }

    // -- Vacation ------------------------------------------------------------------

    fun vacation(): Flow<VacationHold?> = vacationDao.activeFlow().map { it?.toDomain() }

    /**
     * Latest hold regardless of the active flag — the Converger's anti-legionella check
     * runs after `scheduleInputs` has already deactivated the finished hold.
     */
    suspend fun latestVacation(): VacationHold? = vacationDao.latest()?.toDomain()

    /** Max one active hold: any previous hold is deactivated first. */
    suspend fun startVacation(resumeAt: Instant?, antiLegOnReturn: Boolean = true): Long {
        vacationDao.deactivateAll()
        return vacationDao.insert(
            VacationEntity(
                startsAt = Instant.now(),
                resumeAt = resumeAt,
                active = true,
                antiLegOnReturn = antiLegOnReturn,
            ),
        )
    }

    suspend fun cancelVacation() {
        vacationDao.deactivateAll()
    }

    // -- Boost ---------------------------------------------------------------------

    fun activeBoost(): Flow<BoostSession?> = boostDao.activeFlow().map { it?.toDomain() }

    /** Completes any previous session first (outcome CANCELLED), then starts a new one. */
    suspend fun startBoost(targetTemp: Int, timeoutAt: Instant): Long {
        val now = Instant.now()
        boostDao.cancelAllActive(now)
        return boostDao.insert(
            BoostSessionEntity(
                startedAt = now,
                targetTemp = targetTemp.coerceIn(40, 75),
                timeoutAt = timeoutAt,
            ),
        )
    }

    suspend fun completeBoost(id: Long, outcome: String) {
        boostDao.complete(id, Instant.now(), outcome)
    }

    // -- Rate samples ----------------------------------------------------------------

    suspend fun addRateSample(s: RateSample) {
        rateSampleDao.insert(s.toEntity())
        rateSampleDao.prune(s.eco, KEEP_SAMPLES_PER_FLAG)
    }

    suspend fun rateSamples(): List<RateSample> = rateSampleDao.all().map { it.toDomain() }

    // -- Maintenance (SPEC §4.2 prune policy; called by WatchdogWorker) ---------------

    /** Deletes expired day_override / non-ACTIVE heat_by_time rows older than 7 days
     *  and trims rate samples to the last 50 per eco flag. */
    suspend fun pruneExpired(now: Instant) {
        val cutoffDate = now.atZone(zone()).toLocalDate().minusDays(PRUNE_AFTER_DAYS)
        dayOverrideDao.deleteBefore(cutoffDate)
        hbtDao.deleteOld(now.minus(Duration.ofDays(PRUNE_AFTER_DAYS)).toEpochMilli())
        rateSampleDao.prune(eco = false, keep = KEEP_SAMPLES_PER_FLAG)
        rateSampleDao.prune(eco = true, keep = KEEP_SAMPLES_PER_FLAG)
    }

    private fun planRowsToWeeklyPlan(rows: List<WeeklyPlanEntity>): WeeklyPlan {
        val byDay = rows.associate { it.dayOfWeek to it.presetId }
        return WeeklyPlan(DayOfWeek.entries.associateWith { byDay[it.value] })
    }

    private companion object {
        const val KEEP_SAMPLES_PER_FLAG = 50
        const val PRUNE_AFTER_DAYS = 7L
    }
}
