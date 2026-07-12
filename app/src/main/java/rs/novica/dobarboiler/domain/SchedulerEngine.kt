package rs.novica.dobarboiler.domain

import java.time.Instant

interface SchedulerEngine {
    /** Pure. THE function. Deterministic for (now, inputs, zone). */
    fun resolve(now: Instant, inputs: ScheduleInputs): Resolution

    /** Pure. HBT lead math, exposed for UI preview ("grejanje kreće u ~19:40") and tests. */
    fun computedStart(override: OneTimeOverride.HeatByTime, inputs: ScheduleInputs, now: Instant): Instant

    /** Pure. Diff desired vs observed → minimal ordered command list. */
    fun plan(desired: DesiredState, observed: DeviceSnapshot?): List<BoilerCommand>
}
