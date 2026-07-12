// Located in data/db/ per SPEC §4.1; declared in the parent package because every
// cross-module reference (AppContainer, repositories, SPEC snippets) uses
// `rs.novica.dobarboiler.data.*` for the Room types.
package rs.novica.dobarboiler.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * SPEC §4.2 — database `dobarboiler.db`, version 1, no exported schema.
 * Built by `di/AppContainer` via `Room.databaseBuilder(app, AppDatabase::class.java, "dobarboiler.db")`.
 */
@Database(
    entities = [
        PresetEntity::class,
        WeeklyPlanEntity::class,
        DayOverrideEntity::class,
        HeatByTimeEntity::class,
        VacationEntity::class,
        BoostSessionEntity::class,
        ActionLogEntity::class,
        RateSampleEntity::class,
        SnapshotEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun presetDao(): PresetDao
    abstract fun weeklyPlanDao(): WeeklyPlanDao
    abstract fun dayOverrideDao(): DayOverrideDao
    abstract fun heatByTimeDao(): HeatByTimeDao
    abstract fun vacationDao(): VacationDao
    abstract fun boostSessionDao(): BoostSessionDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun rateSampleDao(): RateSampleDao
    abstract fun snapshotDao(): SnapshotDao
}
