package rs.novica.dobarboiler.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import rs.novica.dobarboiler.api.CredentialProvider

/**
 * SPEC §4.3 — credentials + token + selected device, in EncryptedSharedPreferences
 * (file "creds", AES256-GCM master key in Android Keystore). Everything else lives
 * in the Preferences DataStore ([SettingsStore]) per conflict resolution R15.
 *
 * All accessors are synchronous SharedPreferences reads — cheap after first load and
 * callable from OkHttp interceptor threads ([CredentialProvider] SPI is non-suspend).
 */
class CredentialStore(context: Context) : CredentialProvider {

    private val prefs: SharedPreferences = run {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun credentials(): Pair<String, String>? {
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        return email to password
    }

    override fun token(): String? = prefs.getString(KEY_TOKEN, null)

    override fun storeToken(token: String?) = prefs.edit {
        if (token == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, token)
    }

    fun storeCredentials(email: String, password: String) = prefs.edit {
        putString(KEY_EMAIL, email)
        putString(KEY_PASSWORD, password)
    }

    /** Logout: wipes credentials, token AND the device selection. */
    fun clear() = prefs.edit { clear() }

    fun isLoggedIn(): Boolean = credentials() != null

    /** Selected device gateway id (MAC), chosen during onboarding. */
    var gwId: String?
        get() = prefs.getString(KEY_GW_ID, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_GW_ID) else putString(KEY_GW_ID, value)
        }

    /** Cloud display name of the selected device (e.g. "Kupatilo"). */
    var deviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_DEVICE_NAME) else putString(KEY_DEVICE_NAME, value)
        }

    private companion object {
        const val FILE_NAME = "creds"
        const val KEY_EMAIL = "email"
        const val KEY_PASSWORD = "password"
        const val KEY_TOKEN = "token"
        const val KEY_GW_ID = "gwId"
        const val KEY_DEVICE_NAME = "deviceName"
    }
}
