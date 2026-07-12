package rs.novica.dobarboiler.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * A daily heating window expressed as wall-clock times in the configured zone.
 * The window belongs to the calendar date on which it STARTS (day identity rule):
 * 00:30–07:30 on Saturday = Saturday's assignment.
 */
data class HeatingWindow(val start: LocalTime, val end: LocalTime) {
    val crossesMidnight: Boolean get() = end <= start

    companion object {
        val DEFAULT = HeatingWindow(LocalTime.of(0, 30), LocalTime.of(7, 30))
    }
}

/**
 * A named heating profile. [targetTemp] is expected in 40..75; the resolver additionally
 * clamps every temperature it emits to `40..min(75, maxSetpointGuard ?: 75)`, so an
 * out-of-range value can never reach the device.
 */
data class Preset(
    val id: Long,
    val name: String,                  // unique, user-visible ("Sam", "Gosti")
    val targetTemp: Int,               // clamped 40..75 at construction
    val eco: Boolean = true,
    val windowOverride: HeatingWindow? = null,   // null = use EngineConfig.defaultWindow
)

/**
 * Preset assignment per day of week; a `null` value (or absent key) means no heating
 * that night. Day identity rule: the window belongs to the calendar date on which it
 * STARTS.
 */
data class WeeklyPlan(val assignments: Map<DayOfWeek, Long?>)

/** Last observed device state, parsed from the cloud `medPlantData` payload. */
data class DeviceSnapshot(
    val at: Instant,
    val on: Boolean,
    val mode: Int,                     // raw 1|5|9
    val temp: Double,
    val reqTemp: Double,
    val antiLeg: Boolean,
    val heatReq: Boolean,
    val eco: Boolean,
    val remainingHeatTime: Duration?,  // parsed rmTm
    val availableShowers: Int?,
)
