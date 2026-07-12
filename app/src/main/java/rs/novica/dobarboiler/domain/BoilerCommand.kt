package rs.novica.dobarboiler.domain

sealed interface BoilerCommand {
    data class SetMode(val mode: Int) : BoilerCommand          // engine only ever emits SetMode(1)
    data class SetPower(val on: Boolean) : BoilerCommand
    data class SetTemperature(val temp: Int) : BoilerCommand   // pre-clamped
    data class SetEco(val eco: Boolean) : BoilerCommand
    data class SetAntilegionella(val on: Boolean) : BoilerCommand  // used only by vacation-return logic
}
