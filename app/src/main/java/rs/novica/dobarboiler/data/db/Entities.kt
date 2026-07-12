// Located in data/db/ per SPEC §4.1; declared in the parent package because every
// cross-module reference (AppContainer, repositories, SPEC snippets) uses
// `rs.novica.dobarboiler.data.*` for the Room types.
package rs.novica.dobarboiler.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** SPEC §4.2 — `preset`. Window columns are "HH:mm" TEXT (null = default window). */
@Entity(
    tableName = "preset",
    indices = [Index(value = ["name"], unique = true)],
)
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetTemp: Int,
    val eco: Boolean,
    val windowStart: LocalTime? = null,
    val windowEnd: LocalTime? = null,
)

/** SPEC §4.2 — `weekly_plan`. PK = java.time DayOfWeek.value (1=MONDAY..7=SUNDAY). */
@Entity(
    tableName = "weekly_plan",
    foreignKeys = [
        ForeignKey(
            entity = PresetEntity::class,
            parentColumns = ["id"],
            childColumns = ["presetId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("presetId")],
)
data class WeeklyPlanEntity(
    @PrimaryKey val dayOfWeek: Int,
    val presetId: Long?,
)

/** SPEC §4.2 — `day_override`. Upsert keyed on the unique window-start date. */
@Entity(
    tableName = "day_override",
    indices = [Index(value = ["date"], unique = true)],
)
data class DayOverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val presetId: Long?,               // null = skip heating that night
    val createdAt: Instant,
)

/** SPEC §4.2 — `heat_by_time`. */
@Entity(tableName = "heat_by_time")
data class HeatByTimeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventAt: Instant,
    val targetTemp: Int,
    val eco: Boolean,
    val holdMinutes: Int,
    val createdAt: Instant,
    @ColumnInfo(defaultValue = "ACTIVE") val status: String = STATUS_ACTIVE,
) {
    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_EXPIRED = "EXPIRED"
        const val STATUS_SKIPPED = "SKIPPED"
    }
}

/** SPEC §4.2 — `vacation`. At most one row has `active = 1`. */
@Entity(tableName = "vacation")
data class VacationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startsAt: Instant,
    val resumeAt: Instant?,            // null = until cancelled
    val active: Boolean,
    /** §6.6 — user's choice: hygiene cycle on return from a >7-day hold (default ON). */
    @ColumnInfo(defaultValue = "1") val antiLegOnReturn: Boolean = true,
)

/** SPEC §4.2 — `boost_session`. `completedAt == null` means still running. */
@Entity(tableName = "boost_session")
data class BoostSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Instant,
    val targetTemp: Int,
    val timeoutAt: Instant,
    val completedAt: Instant? = null,
    val outcome: String? = null,       // DONE | TIMEOUT | CANCELLED
)

/** SPEC §4.2 — `action_log`. Kind/trigger constants live in scheduling/Converger.kt. */
@Entity(tableName = "action_log")
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Instant,
    val kind: String,
    val trig: String,
    val detail: String,
    val outcome: String,               // SUCCESS | RETRYING | FAILED | SKIPPED
    @ColumnInfo(defaultValue = "1") val attempt: Int = 1,
    val stateJson: String? = null,
)

/** SPEC §4.2 — `rate_sample`. Raw learned-rate observations (last 50 per eco flag kept). */
@Entity(tableName = "rate_sample")
data class RateSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Instant,
    val ratePerHourC: Double,
    val eco: Boolean,
)

/**
 * SPEC §4.2 — `snapshot`: the single last DeviceSnapshot, serialized as JSON.
 * The spec's `CHECK(id=1)` is not expressible via Room annotations; the invariant is
 * enforced by always writing [SINGLETON_ID].
 */
@Entity(tableName = "snapshot")
data class SnapshotEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val at: Instant,
    val json: String,
) {
    companion object { const val SINGLETON_ID = 1 }
}
