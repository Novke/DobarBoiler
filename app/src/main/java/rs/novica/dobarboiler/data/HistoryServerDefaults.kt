package rs.novica.dobarboiler.data

/**
 * History server defaults. Release APKs carry the server token (`BuildConfig.HISTORY_TOKEN`, injected
 * by `tools/release-apk.sh` from SSM, never committed), so a fresh install uploads its log without any
 * setup; a token or address entered in Settings → Server istorije still wins.
 */
object HistoryServerDefaults {

    const val URL = "http://novica-dev/boiler/"

    fun url(stored: String?, builtInToken: String): String? =
        stored ?: URL.takeIf { builtInToken.isNotBlank() }

    fun token(stored: String?, builtInToken: String): String? =
        stored ?: builtInToken.trim().takeIf { it.isNotEmpty() }
}
