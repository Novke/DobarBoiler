package rs.novica.dobarboiler.data

import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rs.novica.dobarboiler.api.MedPlantData
import rs.novica.dobarboiler.domain.BoostSession
import rs.novica.dobarboiler.domain.DeviceSnapshot
import rs.novica.dobarboiler.domain.HeatingWindow
import rs.novica.dobarboiler.domain.OneTimeOverride
import rs.novica.dobarboiler.domain.Preset
import rs.novica.dobarboiler.domain.RateSample
import rs.novica.dobarboiler.domain.VacationHold

/** Module-private Json for persistence payloads (snapshot row, applied-state JSON). */
internal val DataJson: Json = Json { ignoreUnknownKeys = true }

// ---------------------------------------------------------------------------
// Cloud DTO → domain
// ---------------------------------------------------------------------------

/** Maps the raw cloud payload to a domain snapshot, parsing `rmTm` "HH:MM:SS". */
fun MedPlantData.toSnapshot(at: Instant): DeviceSnapshot = DeviceSnapshot(
    at = at,
    on = on,
    mode = mode,
    temp = temp,
    reqTemp = reqTemp,
    antiLeg = antiLeg,
    heatReq = heatReq,
    eco = eco,
    remainingHeatTime = parseRmTm(rmTm),
    availableShowers = avShw,
)

/**
 * Parses the device's remaining-heat-time string ("HH:MM:SS", occasionally "MM:SS").
 * Lenient: any malformed value yields null rather than a crash — the field is cosmetic.
 */
internal fun parseRmTm(raw: String?): Duration? {
    val parts = raw?.trim()?.takeIf { it.isNotEmpty() }?.split(':') ?: return null
    if (parts.size !in 2..3) return null
    val numbers = parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
    val seconds = numbers.fold(0L) { acc, n -> acc * 60 + n }
    return Duration.ofSeconds(seconds).takeIf { !it.isNegative }
}

// ---------------------------------------------------------------------------
// DeviceSnapshot ↔ JSON (snapshot table row; survives restarts)
// ---------------------------------------------------------------------------

@Serializable
internal data class SnapshotJson(
    val at: Long,
    val on: Boolean,
    val mode: Int,
    val temp: Double,
    val reqTemp: Double,
    val antiLeg: Boolean,
    val heatReq: Boolean,
    val eco: Boolean,
    val remainingHeatSeconds: Long? = null,
    val availableShowers: Int? = null,
) {
    fun toDomain(): DeviceSnapshot = DeviceSnapshot(
        at = Instant.ofEpochMilli(at),
        on = on,
        mode = mode,
        temp = temp,
        reqTemp = reqTemp,
        antiLeg = antiLeg,
        heatReq = heatReq,
        eco = eco,
        remainingHeatTime = remainingHeatSeconds?.let(Duration::ofSeconds),
        availableShowers = availableShowers,
    )

    companion object {
        fun from(s: DeviceSnapshot): SnapshotJson = SnapshotJson(
            at = s.at.toEpochMilli(),
            on = s.on,
            mode = s.mode,
            temp = s.temp,
            reqTemp = s.reqTemp,
            antiLeg = s.antiLeg,
            heatReq = s.heatReq,
            eco = s.eco,
            remainingHeatSeconds = s.remainingHeatTime?.seconds,
            availableShowers = s.availableShowers,
        )
    }
}

internal fun DeviceSnapshot.toJsonString(): String =
    DataJson.encodeToString(SnapshotJson.serializer(), SnapshotJson.from(this))

/** Null on corrupt/legacy payloads — callers treat that as "no snapshot yet". */
internal fun SnapshotEntity.toDomainSnapshot(): DeviceSnapshot? = runCatching {
    DataJson.decodeFromString(SnapshotJson.serializer(), json).toDomain()
}.getOrNull()

// ---------------------------------------------------------------------------
// Room entities ↔ domain
// ---------------------------------------------------------------------------

fun PresetEntity.toDomain(): Preset = Preset(
    id = id,
    name = name,
    targetTemp = targetTemp.coerceIn(40, 75),
    eco = eco,
    windowOverride = if (windowStart != null && windowEnd != null) {
        HeatingWindow(windowStart, windowEnd)
    } else {
        null
    },
)

fun Preset.toEntity(): PresetEntity = PresetEntity(
    id = if (id > 0) id else 0,
    name = name,
    targetTemp = targetTemp.coerceIn(40, 75),
    eco = eco,
    windowStart = windowOverride?.start,
    windowEnd = windowOverride?.end,
)

fun DayOverrideEntity.toDomain(): OneTimeOverride.DayReplacement =
    OneTimeOverride.DayReplacement(
        id = id,
        createdAt = createdAt,
        date = date,
        presetId = presetId,
    )

fun HeatByTimeEntity.toDomain(): OneTimeOverride.HeatByTime =
    OneTimeOverride.HeatByTime(
        id = id,
        createdAt = createdAt,
        eventTime = eventAt,
        targetTemp = targetTemp,
        eco = eco,
        holdMinutes = holdMinutes,
    )

fun VacationEntity.toDomain(): VacationHold =
    VacationHold(id = id, startsAt = startsAt, resumeAt = resumeAt)

fun BoostSessionEntity.toDomain(): BoostSession = BoostSession(
    id = id,
    startedAt = startedAt,
    targetTemp = targetTemp,
    timeoutAt = timeoutAt,
    completedAt = completedAt,
)

fun RateSampleEntity.toDomain(): RateSample =
    RateSample(at = at, ratePerHourC = ratePerHourC, eco = eco)

fun RateSample.toEntity(): RateSampleEntity =
    RateSampleEntity(at = at, ratePerHourC = ratePerHourC, eco = eco)
