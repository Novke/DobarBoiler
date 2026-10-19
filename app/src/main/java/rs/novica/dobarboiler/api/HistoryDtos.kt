package rs.novica.dobarboiler.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire format of the history server (server/boilerd/api.py). Field names are the server's, snake_case. */
@Serializable
data class ActionDto(
    @SerialName("local_id") val localId: Long,
    val at: String,
    val kind: String,
    val trig: String,
    val detail: String,
    val outcome: String,
    val attempt: Int,
    @SerialName("state_json") val stateJson: String?,
)

@Serializable
data class ActionsBatch(
    @SerialName("install_id") val installId: String,
    val actions: List<ActionDto>,
)

@Serializable
data class ActionsResult(val accepted: Int, val duplicates: Int)

@Serializable
data class DesiredDto(
    @SerialName("power_on") val powerOn: Boolean,
    @SerialName("target_temp") val targetTemp: Int?,
    val eco: Boolean?,
)

@Serializable
data class IntentDto(
    @SerialName("install_id") val installId: String,
    val at: String,
    val source: String,
    val desired: DesiredDto,
    @SerialName("next_transition_at") val nextTransitionAt: String?,
)

@Serializable
data class HealthDto(
    val ok: Boolean,
    @SerialName("last_state_at") val lastStateAt: String?,
)
