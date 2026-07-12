package rs.novica.dobarboiler.domain

import java.time.Duration
import java.time.Instant

/** One measured heating-rate observation (°C per hour) tagged with the eco flag it ran under. */
data class RateSample(val at: Instant, val ratePerHourC: Double, val eco: Boolean)

class RateEstimator {

    /** EWMA α=0.3 per eco flag; returns null until >= 5 samples for that flag. */
    fun learnedRate(samples: List<RateSample>, eco: Boolean): Double? {
        val relevant = samples.filter { it.eco == eco }.sortedBy { it.at }
        if (relevant.size < MIN_SAMPLES) return null
        var ewma = relevant.first().ratePerHourC
        for (i in 1 until relevant.size) {
            ewma = ALPHA * relevant[i].ratePerHourC + (1 - ALPHA) * ewma
        }
        return ewma
    }

    /**
     * Extract a sample from two snapshots if valid: both heatReq=true, gap in 20..90 min,
     * same eco flag, temp rose >= 1.0 °C, power on in both. Null otherwise.
     */
    fun sampleFrom(a: DeviceSnapshot, b: DeviceSnapshot): RateSample? {
        val (earlier, later) = if (a.at <= b.at) a to b else b to a
        if (!earlier.on || !later.on) return null
        if (!earlier.heatReq || !later.heatReq) return null
        if (earlier.eco != later.eco) return null
        val gap = Duration.between(earlier.at, later.at)
        if (gap < MIN_GAP || gap > MAX_GAP) return null
        val rise = later.temp - earlier.temp
        if (rise < MIN_RISE_C) return null
        val hours = gap.toMillis() / 3_600_000.0
        return RateSample(at = later.at, ratePerHourC = rise / hours, eco = later.eco)
    }

    private companion object {
        const val ALPHA = 0.3
        const val MIN_SAMPLES = 5
        const val MIN_RISE_C = 1.0
        val MIN_GAP: Duration = Duration.ofMinutes(20)
        val MAX_GAP: Duration = Duration.ofMinutes(90)
    }
}
