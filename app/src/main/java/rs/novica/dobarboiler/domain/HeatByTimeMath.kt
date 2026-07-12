package rs.novica.dobarboiler.domain

import java.time.Duration
import java.time.Instant

/**
 * When must heating start so the water is at [OneTimeOverride.HeatByTime.targetTemp]
 * at `eventTime`?
 *
 * `lead = deltaC / rate`, padded by `safetyMarginPercent`, clamped to `maxLead`.
 * `deltaC <= 0` yields a zero lead, i.e. start == eventTime.
 */
fun hbtStart(o: OneTimeOverride.HeatByTime, estimateTempC: Double, cfg: HeatingRateConfig): Instant {
    val deltaC = (o.targetTemp - estimateTempC).coerceAtLeast(0.0)
    val lead = Duration.ofSeconds((deltaC / cfg.effectiveRatePerHour(o.eco) * 3600.0).toLong())
    val padded = lead.multipliedBy((100L + cfg.safetyMarginPercent)).dividedBy(100L)
    return o.eventTime.minus(minOf(padded, cfg.maxLead))
}
