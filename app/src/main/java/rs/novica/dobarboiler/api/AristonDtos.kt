package rs.novica.dobarboiler.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Wire DTOs for the Ariston cloud API (docs/protocol.md). All are decoded with a
 * `Json { ignoreUnknownKeys = true }` instance — the cloud sends many extra fields.
 */

@Serializable
data class LoginRequest(
    @SerialName("usr") val usr: String,
    @SerialName("pwd") val pwd: String,
)

@Serializable
data class LoginResponse(
    @SerialName("token") val token: String,
)

/** Decodes a JSON boolean or number (non-zero = true); anything else is false. Encodes a plain boolean. */
object FlexibleBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("rs.novica.dobarboiler.FlexibleBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeBoolean()
        val primitive = element as? JsonPrimitive ?: return false
        return primitive.booleanOrNull ?: primitive.doubleOrNull?.let { it != 0.0 } ?: false
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

@Serializable
data class VelisPlantDto(
    @SerialName("gw") val gw: String,
    @SerialName("name") val name: String = "",
    @SerialName("sn") val sn: String? = null,
    @SerialName("sys") val sys: Int = 0,
    @SerialName("wheType") val wheType: Int = 0,
    // docs/protocol.md had `true`/`false`; the live cloud sends `1`/`0` (2026-11-10). Accept both.
    @SerialName("lnk") @Serializable(with = FlexibleBooleanSerializer::class) val lnk: Boolean = false,
) {
    fun toModel(): VelisPlant = VelisPlant(gw = gw, name = name, sn = sn, sys = sys, wheType = wheType, online = lnk)
}

@Serializable
data class MedPlantDataDto(
    @SerialName("on") val on: Boolean,
    @SerialName("mode") val mode: Int,
    @SerialName("temp") val temp: Double,
    @SerialName("reqTemp") val reqTemp: Double,
    @SerialName("procReqTemp") val procReqTemp: Double? = null,
    @SerialName("eco") val eco: Boolean = false,
    @SerialName("pwrOpt") val pwrOpt: Boolean? = null,
    @SerialName("antiLeg") val antiLeg: Boolean = false,
    @SerialName("heatReq") val heatReq: Boolean = false,
    @SerialName("avShw") val avShw: Int? = null,
    @SerialName("rmTm") val rmTm: String? = null,
) {
    fun toModel(): MedPlantData = MedPlantData(
        on = on, mode = mode, temp = temp, reqTemp = reqTemp, procReqTemp = procReqTemp,
        eco = eco, pwrOpt = pwrOpt, antiLeg = antiLeg, heatReq = heatReq, avShw = avShw, rmTm = rmTm,
    )
}

@Serializable
data class MedPlantSettingsDto(
    @SerialName("MedAntilegionellaOnOff") val antilegionellaOn: Boolean = false,
    @SerialName("MedHeatingRate") val heatingRate: Int? = null,
    @SerialName("MedMaxSetpointTemperature") val maxSetpoint: Double,
    @SerialName("MedMaxSetpointTemperatureMin") val maxSetpointMin: Double = 40.0,
    @SerialName("MedMaxSetpointTemperatureMax") val maxSetpointMax: Double = 80.0,
) {
    fun toModel(): MedPlantSettings = MedPlantSettings(
        antilegionellaOn = antilegionellaOn, heatingRate = heatingRate,
        maxSetpoint = maxSetpoint, maxSetpointMin = maxSetpointMin, maxSetpointMax = maxSetpointMax,
    )
}

/** Body of `POST .../temperature`: `{"new": 60.0}`. */
@Serializable
data class TempRequest(
    @SerialName("new") val new: Double,
)

/** Body of `POST .../mode`: `{"new": 1}`. */
@Serializable
data class ModeRequest(
    @SerialName("new") val new: Int,
)

/** Old/new pair for `POST .../plantSettings`: `{"<key>": {"new": 1.0, "old": 0.0}}`. */
@Serializable
data class SettingChange(
    @SerialName("new") val new: Double,
    @SerialName("old") val old: Double,
)

@Serializable
data class ConsSequenceDto(
    @SerialName("k") val k: Int,
    @SerialName("p") val p: Int,
    @SerialName("v") val v: List<Double> = emptyList(),
) {
    fun toModel(): ConsSequence = ConsSequence(kind = k, period = p, values = v)
}
