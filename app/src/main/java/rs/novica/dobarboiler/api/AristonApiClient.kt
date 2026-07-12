package rs.novica.dobarboiler.api

/** One entry from `GET velis/plants`, reduced to what the app needs. */
data class VelisPlant(
    val gw: String,
    val name: String,
    val sn: String?,
    val sys: Int,
    val wheType: Int,
    val online: Boolean,
)

/** Live device state from `GET velis/medPlantData/{gw}`. `rmTm` is the raw "HH:MM:SS" string. */
data class MedPlantData(
    val on: Boolean,
    val mode: Int,
    val temp: Double,
    val reqTemp: Double,
    val procReqTemp: Double?,
    val eco: Boolean,
    val pwrOpt: Boolean?,
    val antiLeg: Boolean,
    val heatReq: Boolean,
    val avShw: Int?,
    val rmTm: String?,
)

/** Plant settings from `GET velis/medPlantData/{gw}/plantSettings`. */
data class MedPlantSettings(
    val antilegionellaOn: Boolean,
    val heatingRate: Int?,
    val maxSetpoint: Double,
    val maxSetpointMin: Double,
    val maxSetpointMax: Double,
)

/** One consumption sequence from `GET remote/reports/{gw}/consSequencesApi8?usages=Dhw`. */
data class ConsSequence(val kind: Int, val period: Int, val values: List<Double>)

/**
 * Ariston cloud client (docs/protocol.md). All calls are suspend; all throw
 * [AristonException] subtypes on failure.
 */
interface AristonApiClient {
    /** POST accounts/login. Stores token via [CredentialProvider] on success. */
    suspend fun login(email: String, password: String): String // returns token

    suspend fun listVelisPlants(): List<VelisPlant> // GET velis/plants

    suspend fun getState(gwId: String): MedPlantData // GET velis/medPlantData/{gw}

    suspend fun getPlantSettings(gwId: String): MedPlantSettings // GET .../plantSettings

    suspend fun setPower(gwId: String, on: Boolean) // POST .../switch, bare boolean body

    suspend fun setTemperature(gwId: String, tempC: Double) // POST .../temperature {"new":t}

    suspend fun setMode(gwId: String, mode: Int) // POST .../mode {"new":m}; m ∈ {1,5,9}

    suspend fun setEco(gwId: String, eco: Boolean) // POST .../switchEco, bare boolean

    suspend fun setAntilegionella(gwId: String, enabled: Boolean, oldEnabled: Boolean) // plantSettings old/new 1.0/0.0

    suspend fun setMaxSetpoint(gwId: String, newTemp: Double, oldTemp: Double) // plantSettings old/new

    suspend fun getDhwConsumption(gwId: String): List<ConsSequence> // GET remote/reports/{gw}/consSequencesApi8?usages=Dhw
}
