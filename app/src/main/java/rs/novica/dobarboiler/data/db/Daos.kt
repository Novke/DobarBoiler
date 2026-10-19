// Located in data/db/ per SPEC §4.1; declared in the parent package because every
// cross-module reference (AppContainer, repositories, SPEC snippets) uses
// `rs.novica.dobarboiler.data.*` for the Room types.
package rs.novica.dobarboiler.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDao {

    @Query("SELECT * FROM preset ORDER BY name COLLATE NOCASE")
    fun presetsFlow(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM preset")
    suspend fun all(): List<PresetEntity>

    @Query("SELECT * FROM preset WHERE id = :id")
    suspend fun byId(id: Long): PresetEntity?

    /**
     * Deliberately plain INSERT (no REPLACE): with `weekly_plan.presetId ON DELETE SET NULL`,
     * an `OnConflictStrategy.REPLACE` update would delete-and-reinsert the row and silently
     * null out plan assignments. Updates go through [update] instead.
     */
    @Insert
    suspend fun insert(preset: PresetEntity): Long

    @Update
    suspend fun update(preset: PresetEntity)

    @Query("DELETE FROM preset WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface WeeklyPlanDao {

    @Query("SELECT * FROM weekly_plan")
    fun planFlow(): Flow<List<WeeklyPlanEntity>>

    @Query("SELECT * FROM weekly_plan")
    suspend fun plan(): List<WeeklyPlanEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: WeeklyPlanEntity)

    @Query("SELECT COUNT(*) FROM weekly_plan WHERE presetId = :presetId")
    suspend fun countUsing(presetId: Long): Int
}

@Dao
interface DayOverrideDao {

    @Query("SELECT * FROM day_override ORDER BY date")
    fun allFlow(): Flow<List<DayOverrideEntity>>

    @Query("SELECT * FROM day_override ORDER BY date")
    suspend fun all(): List<DayOverrideEntity>

    @Query("SELECT * FROM day_override WHERE date = :date")
    suspend fun byDate(date: LocalDate): DayOverrideEntity?

    /** REPLACE is safe here: nothing references day_override rows. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DayOverrideEntity): Long

    @Query("DELETE FROM day_override WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("SELECT COUNT(*) FROM day_override WHERE presetId = :presetId")
    suspend fun countUsing(presetId: Long): Int

    @Query("DELETE FROM day_override WHERE date < :cutoff")
    suspend fun deleteBefore(cutoff: LocalDate): Int
}

@Dao
interface HeatByTimeDao {

    @Query("SELECT * FROM heat_by_time WHERE status = 'ACTIVE' ORDER BY eventAt")
    fun activeFlow(): Flow<List<HeatByTimeEntity>>

    @Query("SELECT * FROM heat_by_time WHERE status = 'ACTIVE' ORDER BY eventAt")
    suspend fun active(): List<HeatByTimeEntity>

    @Insert
    suspend fun insert(row: HeatByTimeEntity): Long

    @Query("UPDATE heat_by_time SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String): Int

    /** Marks every ACTIVE row whose event + hold has passed as EXPIRED. */
    @Query(
        "UPDATE heat_by_time SET status = 'EXPIRED' " +
            "WHERE status = 'ACTIVE' AND (eventAt + holdMinutes * 60000) <= :nowEpochMs",
    )
    suspend fun expire(nowEpochMs: Long): Int

    @Query("DELETE FROM heat_by_time WHERE status != 'ACTIVE' AND eventAt < :cutoffEpochMs")
    suspend fun deleteOld(cutoffEpochMs: Long): Int
}

@Dao
interface VacationDao {

    @Query("SELECT * FROM vacation WHERE active = 1 ORDER BY id DESC LIMIT 1")
    fun activeFlow(): Flow<VacationEntity?>

    @Query("SELECT * FROM vacation WHERE active = 1 ORDER BY id DESC LIMIT 1")
    suspend fun active(): VacationEntity?

    /** Latest hold regardless of the active flag — the just-ended hold at VACATION_RESUME. */
    @Query("SELECT * FROM vacation ORDER BY startsAt DESC, id DESC LIMIT 1")
    suspend fun latest(): VacationEntity?

    @Insert
    suspend fun insert(row: VacationEntity): Long

    @Query("UPDATE vacation SET active = 0 WHERE active = 1")
    suspend fun deactivateAll(): Int

    /** Deactivates holds whose scheduled resume instant has passed. */
    @Query(
        "UPDATE vacation SET active = 0 " +
            "WHERE active = 1 AND resumeAt IS NOT NULL AND resumeAt <= :nowEpochMs",
    )
    suspend fun deactivateFinished(nowEpochMs: Long): Int
}

@Dao
interface BoostSessionDao {

    @Query("SELECT * FROM boost_session WHERE completedAt IS NULL ORDER BY id DESC LIMIT 1")
    fun activeFlow(): Flow<BoostSessionEntity?>

    @Query("SELECT * FROM boost_session WHERE completedAt IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun active(): BoostSessionEntity?

    @Insert
    suspend fun insert(row: BoostSessionEntity): Long

    @Query(
        "UPDATE boost_session SET completedAt = :at, outcome = :outcome " +
            "WHERE id = :id AND completedAt IS NULL",
    )
    suspend fun complete(id: Long, at: Instant, outcome: String): Int

    @Query(
        "UPDATE boost_session SET completedAt = :at, outcome = 'CANCELLED' " +
            "WHERE completedAt IS NULL",
    )
    suspend fun cancelAllActive(at: Instant): Int
}

@Dao
interface ActionLogDao {

    @Insert
    suspend fun insert(entry: ActionLogEntity): Long

    @Query("SELECT * FROM action_log ORDER BY at DESC, id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<ActionLogEntity>>

    /** Rows above the upload high-water mark, oldest first (history-server sync). */
    @Query("SELECT * FROM action_log WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun after(afterId: Long, limit: Int): List<ActionLogEntity>

    @Query("DELETE FROM action_log WHERE at < :cutoff")
    suspend fun pruneBefore(cutoff: Instant): Int
}

@Dao
interface RateSampleDao {

    @Insert
    suspend fun insert(sample: RateSampleEntity): Long

    @Query("SELECT * FROM rate_sample ORDER BY at")
    suspend fun all(): List<RateSampleEntity>

    /** Keeps only the newest [keep] samples for the given eco flag. */
    @Query(
        "DELETE FROM rate_sample WHERE eco = :eco AND id NOT IN " +
            "(SELECT id FROM rate_sample WHERE eco = :eco ORDER BY at DESC, id DESC LIMIT :keep)",
    )
    suspend fun prune(eco: Boolean, keep: Int): Int
}

@Dao
interface SnapshotDao {

    @Query("SELECT * FROM snapshot WHERE id = ${SnapshotEntity.SINGLETON_ID}")
    suspend fun get(): SnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(snapshot: SnapshotEntity)
}
