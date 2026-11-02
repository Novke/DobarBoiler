package rs.novica.dobarboiler.scheduling

import java.time.Duration
import java.time.Instant
import rs.novica.dobarboiler.domain.DeviceSnapshot

/**
 * When does a snapshot count as someone pressing the boiler's buttons (SPEC §6.2 step 4, §7.4)?
 * Pure, so the rules stay unit-testable apart from the Android-bound [Converger].
 */
internal object ManualChangeRules {

    /** Grace for the slow device bus before a differing snapshot counts as external. */
    val GRACE: Duration = Duration.ofMinutes(3)

    /**
     * Scheduled transitions and boost start/end/cancel assert the plan: they clear any hold and
     * never start a new one. Detecting there would compare against the baseline the manual change
     * already contradicted, re-arm the hold until the next transition, and repeat at every
     * transition — the boiler would never come back on its own.
     */
    fun isAssertive(trigger: String): Boolean =
        trigger == Triggers.ALARM || trigger == Triggers.BOOST

    /** Does [snapshot] contradict what the app last asserted, past the slow-bus grace? */
    fun isExternal(
        applied: AppliedStateCodec.Applied?,
        appliedAt: Instant?,
        snapshot: DeviceSnapshot?,
    ): Boolean =
        applied != null && appliedAt != null && snapshot != null &&
            snapshot.at.isAfter(appliedAt.plus(GRACE)) &&
            !AppliedStateCodec.matches(applied, snapshot)

    /** During a boost: is the boiler off although the app switched it on more than [GRACE] ago? */
    fun boostSwitchedOffByHand(snapshot: DeviceSnapshot, appliedAt: Instant): Boolean =
        !snapshot.on && snapshot.at.isAfter(appliedAt.plus(GRACE))
}
