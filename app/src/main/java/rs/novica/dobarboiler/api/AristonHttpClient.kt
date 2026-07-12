package rs.novica.dobarboiler.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp implementation of [AristonApiClient] per docs/protocol.md and SPEC §2.3.
 *
 * Request pipeline (every call except login itself):
 *  1. Attach headers; if no token is stored, log in first via [CredentialProvider].
 *  2. 405 → re-login once, replace token, retry the original request once. Second 405 → [AristonException.AuthFailed].
 *  3. 404 → [AristonException.NotFound].
 *  4. 429 → [AristonException.RateLimited] immediately, no retry.
 *  5. Any other non-2xx or IOException → sleep 5 s, retry once → [AristonException.Transient].
 *  6. 2xx with empty body → success; with body → decode with `Json { ignoreUnknownKeys = true }`.
 *  7. A [Mutex] serializes login so concurrent 405s don't stampede.
 *
 * Max ONE retry per request, of either kind (auth-refresh or 5 s sleep), matching the
 * reference implementation.
 *
 * [baseUrl], [client] and [retryDelayMillis] have production defaults and exist only so
 * tests can point at MockWebServer and skip the real 5 s sleep.
 */
class AristonHttpClient(
    private val credentials: CredentialProvider,
    private val json: Json,
    private val baseUrl: String = DEFAULT_BASE_URL,
    client: OkHttpClient? = null,
    private val retryDelayMillis: Long = 5_000L,
) : AristonApiClient {

    companion object {
        const val DEFAULT_BASE_URL = "https://www.ariston-net.remotethermo.com/api/v2/"
        private const val USER_AGENT = "RestSharp/106.11.7.0"
        private const val TOKEN_HEADER = "ar.authToken"
        private const val CONTENT_TYPE = "application/json; charset=utf-8"
        private val ALLOWED_MODES = setOf(1, 5, 9)
    }

    private val jsonMediaType = CONTENT_TYPE.toMediaType()

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Serializes all logins so concurrent 405s trigger exactly one re-login. */
    private val loginMutex = Mutex()

    // ------------------------------------------------------------------ API

    override suspend fun login(email: String, password: String): String =
        loginMutex.withLock { doLogin(email, password) }

    override suspend fun listVelisPlants(): List<VelisPlant> =
        decode(ListSerializer(VelisPlantDto.serializer()), authedRequest("velis/plants"))
            .map { it.toModel() }

    override suspend fun getState(gwId: String): MedPlantData =
        decode(MedPlantDataDto.serializer(), authedRequest("velis/medPlantData/$gwId")).toModel()

    override suspend fun getPlantSettings(gwId: String): MedPlantSettings =
        decode(MedPlantSettingsDto.serializer(), authedRequest("velis/medPlantData/$gwId/plantSettings"))
            .toModel()

    override suspend fun setPower(gwId: String, on: Boolean) {
        authedRequest("velis/medPlantData/$gwId/switch", bareBooleanBody(on))
    }

    override suspend fun setTemperature(gwId: String, tempC: Double) {
        authedRequest(
            "velis/medPlantData/$gwId/temperature",
            jsonBody(TempRequest.serializer(), TempRequest(tempC)),
        )
    }

    override suspend fun setMode(gwId: String, mode: Int) {
        require(mode in ALLOWED_MODES) { "mode must be one of $ALLOWED_MODES, got $mode" }
        authedRequest(
            "velis/medPlantData/$gwId/mode",
            jsonBody(ModeRequest.serializer(), ModeRequest(mode)),
        )
    }

    override suspend fun setEco(gwId: String, eco: Boolean) {
        authedRequest("velis/medPlantData/$gwId/switchEco", bareBooleanBody(eco))
    }

    override suspend fun setAntilegionella(gwId: String, enabled: Boolean, oldEnabled: Boolean) {
        postPlantSetting(
            gwId,
            key = "MedAntilegionellaOnOff",
            change = SettingChange(new = if (enabled) 1.0 else 0.0, old = if (oldEnabled) 1.0 else 0.0),
        )
    }

    override suspend fun setMaxSetpoint(gwId: String, newTemp: Double, oldTemp: Double) {
        postPlantSetting(
            gwId,
            key = "MedMaxSetpointTemperature",
            change = SettingChange(new = newTemp, old = oldTemp),
        )
    }

    override suspend fun getDhwConsumption(gwId: String): List<ConsSequence> =
        decode(
            ListSerializer(ConsSequenceDto.serializer()),
            authedRequest("remote/reports/$gwId/consSequencesApi8?usages=Dhw"),
        ).map { it.toModel() }

    // ------------------------------------------------------- request pipeline

    /**
     * Executes an authenticated request against [path] (relative to [baseUrl]).
     * GET when [body] is null, POST otherwise. Returns the raw response body string
     * (empty for the SET endpoints). Implements the full SPEC §2.3 pipeline.
     */
    private suspend fun authedRequest(path: String, body: RequestBody? = null): String {
        var token = ensureToken()
        var retried = false
        while (true) {
            val request = Request.Builder()
                .url(baseUrl + path)
                .header("User-Agent", USER_AGENT)
                .header(TOKEN_HEADER, token)
                .header("Content-Type", CONTENT_TYPE)
                .apply { if (body != null) post(body) else get() }
                .build()

            val response = try {
                call(request)
            } catch (e: IOException) {
                if (retried) throw AristonException.Transient("network error: ${e.message}", e)
                retried = true
                delay(retryDelayMillis)
                continue
            }

            val delayBeforeRetry: Boolean = response.use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> return text
                    resp.code == 405 -> {
                        if (retried) throw AristonException.AuthFailed("token rejected twice (405)")
                        retried = true
                        token = refreshToken(staleToken = token)
                        false // retry immediately with the fresh token
                    }
                    resp.code == 404 -> throw AristonException.NotFound()
                    resp.code == 429 ->
                        throw AristonException.RateLimited(text.ifBlank { "HTTP 429" })
                    else -> {
                        if (retried) {
                            throw AristonException.Transient("HTTP ${resp.code}: ${text.take(200)}")
                        }
                        retried = true
                        true // transient — sleep 5 s before the single retry
                    }
                }
            }
            if (delayBeforeRetry) delay(retryDelayMillis)
        }
    }

    /** Returns the stored token, logging in first (under the mutex) when there is none. */
    private suspend fun ensureToken(): String {
        credentials.token()?.takeIf { it.isNotEmpty() }?.let { return it }
        return loginMutex.withLock {
            // Another caller may have logged in while we waited for the lock.
            credentials.token()?.takeIf { it.isNotEmpty() } ?: run {
                val (email, password) = credentials.credentials()
                    ?: throw AristonException.AuthFailed("no credentials")
                doLogin(email, password)
            }
        }
    }

    /**
     * Re-login after a 405. Serialized by [loginMutex]; if another coroutine already
     * refreshed the token while we waited, reuses it instead of logging in again.
     */
    private suspend fun refreshToken(staleToken: String): String = loginMutex.withLock {
        credentials.token()?.takeIf { it.isNotEmpty() && it != staleToken }?.let { return it }
        val (email, password) = credentials.credentials()
            ?: throw AristonException.AuthFailed("no credentials")
        try {
            doLogin(email, password)
        } catch (e: AristonException.AuthFailed) {
            // Only genuine credential rejections become AuthFailed; RateLimited and
            // Transient (5xx/IOException) propagate as-is — a cloud outage during
            // re-login is a connection problem, not a changed password (protocol.md §8).
            throw AristonException.AuthFailed("login failed — password changed?")
        }
    }

    /**
     * Raw login POST. Callers hold [loginMutex]. On success stores the token via
     * [CredentialProvider] and returns it. 5xx/IOException get the single 5 s retry;
     * any other non-2xx means bad credentials → [AristonException.AuthFailed].
     */
    private suspend fun doLogin(email: String, password: String): String {
        val bodyText = json.encodeToString(LoginRequest.serializer(), LoginRequest(email, password))
        val request = Request.Builder()
            .url(baseUrl + "accounts/login")
            .header("User-Agent", USER_AGENT)
            .header(TOKEN_HEADER, "")
            .header("Content-Type", CONTENT_TYPE)
            .post(bodyText.toRequestBody(jsonMediaType))
            .build()

        var retried = false
        while (true) {
            val response = try {
                call(request)
            } catch (e: IOException) {
                if (retried) throw AristonException.Transient("login: network error: ${e.message}", e)
                retried = true
                delay(retryDelayMillis)
                continue
            }

            response.use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> {
                        val token = decode(LoginResponse.serializer(), text).token
                        credentials.storeToken(token)
                        return token
                    }
                    resp.code == 429 ->
                        throw AristonException.RateLimited(text.ifBlank { "HTTP 429" })
                    resp.code in 500..599 -> {
                        if (retried) throw AristonException.Transient("login: HTTP ${resp.code}")
                        retried = true
                    }
                    else -> throw AristonException.AuthFailed("login failed (HTTP ${resp.code})")
                }
            }
            delay(retryDelayMillis)
        }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun postPlantSetting(gwId: String, key: String, change: SettingChange) {
        val bodyText = json.encodeToString(
            MapSerializer(String.serializer(), SettingChange.serializer()),
            mapOf(key to change),
        )
        authedRequest("velis/medPlantData/$gwId/plantSettings", bodyText.toRequestBody(jsonMediaType))
    }

    /** Bare JSON literal `true`/`false` — NOT an object (protocol.md §0). */
    private fun bareBooleanBody(value: Boolean): RequestBody =
        (if (value) "true" else "false").toRequestBody(jsonMediaType)

    private fun <T> jsonBody(serializer: kotlinx.serialization.SerializationStrategy<T>, value: T): RequestBody =
        json.encodeToString(serializer, value).toRequestBody(jsonMediaType)

    private fun <T> decode(deserializer: DeserializationStrategy<T>, body: String): T =
        try {
            json.decodeFromString(deserializer, body)
        } catch (e: SerializationException) {
            throw AristonException.Transient("malformed response: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw AristonException.Transient("malformed response: ${e.message}", e)
        }

    private suspend fun call(request: Request): Response =
        withContext(Dispatchers.IO) { http.newCall(request).execute() }
}
