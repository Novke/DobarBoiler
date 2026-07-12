package rs.novica.dobarboiler.api

/**
 * SPI for credential/token storage, implemented in `data/` by `CredentialStore`
 * (EncryptedSharedPreferences). The api module itself stays Android-free.
 */
interface CredentialProvider {
    /** Email to password, or null if the user is not logged in. */
    fun credentials(): Pair<String, String>?

    /** The last stored Ariston cloud token, or null if none. */
    fun token(): String?

    /** Persist (or clear, when null) the Ariston cloud token. */
    fun storeToken(token: String?)
}
