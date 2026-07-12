package rs.novica.dobarboiler.domain

import java.time.Duration

enum class OutsideWindowBehavior { OFF, KEEP_WARM }
enum class ManualChangePolicy { RESPECT_UNTIL_NEXT_TRANSITION, REASSERT }

data class BandConfig(
    val stopAt: Int,                   // e.g. 56
    val restartAt: Int,                // e.g. 52
    val pollEvery: Duration = Duration.ofMinutes(15),   // 10..30 min
) { init { require(stopAt >= restartAt + 2) { "band must be >= 2°C" } } }

sealed interface MaintainMode {
    data object Simple : MaintainMode
    data class Band(val band: BandConfig) : MaintainMode
}

data class HeatingRateConfig(
    val ratePerHourC: Double = 15.0,        // 80 L @ ~1.5 kW
    val ecoPenaltyFactor: Double = 0.75,
    val safetyMarginPercent: Int = 15,
    val maxLead: Duration = Duration.ofHours(6),
    val learnedRatePerHourC: Double? = null,      // eco=false EWMA, used when non-null
    val learnedEcoRatePerHourC: Double? = null,   // eco=true EWMA
) {
    fun effectiveRatePerHour(eco: Boolean): Double =
        if (eco) learnedEcoRatePerHourC ?: ((learnedRatePerHourC ?: ratePerHourC) * ecoPenaltyFactor)
        else learnedRatePerHourC ?: ratePerHourC
}

data class EngineConfig(
    val defaultWindow: HeatingWindow = HeatingWindow.DEFAULT,
    val outsideWindow: OutsideWindowBehavior = OutsideWindowBehavior.OFF,
    val keepWarmTemp: Int = 40,
    val maintainMode: MaintainMode = MaintainMode.Simple,
    val heatingRate: HeatingRateConfig = HeatingRateConfig(),
    val boostMaxDuration: Duration = Duration.ofHours(4),      // 1..6 h
    val manualChangePolicy: ManualChangePolicy = ManualChangePolicy.RESPECT_UNTIL_NEXT_TRANSITION,
    val midWindowCheck: Boolean = true,
    val maxSetpointGuard: Int? = null,   // live MedMaxSetpointTemperature; extra clamp when set
    val fallbackPreset: Preset = Preset(id = -1, name = "Podrazumevano", targetTemp = 55, eco = true),
)
