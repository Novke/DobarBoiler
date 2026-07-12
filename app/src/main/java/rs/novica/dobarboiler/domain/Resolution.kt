package rs.novica.dobarboiler.domain

import java.time.Instant

data class ScheduleInputs(
    val weeklyPlan: WeeklyPlan,
    val presets: Map<Long, Preset>,
    val overrides: List<OneTimeOverride>,   // non-expired only (data layer purges)
    val vacation: VacationHold?,            // active/future only
    val boost: BoostSession?,               // uncompleted only
    val lastSnapshot: DeviceSnapshot?,      // for HBT lead math
    val config: EngineConfig,
)

data class DesiredState(
    val powerOn: Boolean,
    val targetTemp: Int?,   // null when powerOn=false (never send temp commands then)
    val eco: Boolean?,      // null = don't touch eco
)

enum class StateSource { BOOST, HEAT_BY_TIME, VACATION, DAY_OVERRIDE, WEEKLY_PLAN, KEEP_WARM, DEFAULT_OFF }

enum class TransitionReason {
    WINDOW_START, WINDOW_END, HBT_START, HBT_END, HBT_REASSESS,
    VACATION_START, VACATION_RESUME, BOOST_TIMEOUT, OVERRIDE_EXPIRY, NONE
}

data class Transition(val at: Instant, val reason: TransitionReason)

data class Resolution(
    val desired: DesiredState,
    val source: StateSource,
    val nextTransition: Transition?,   // null = nothing ever scheduled; re-arm on any table change
)
