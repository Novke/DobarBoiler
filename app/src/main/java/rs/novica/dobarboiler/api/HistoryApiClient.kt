package rs.novica.dobarboiler.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class HistoryApiException(val status: Int?, message: String) : Exception(message)

/**
 * Client for the read-only history server on novica-dev (design §5.1–5.2), reached over the tailnet.
 * Every call carries `Authorization: Bearer <token>`. Non-2xx and I/O errors become [HistoryApiException];
 * callers (ServerSync) decide whether to retry. Paths are relative, so a base URL with a prefix such as
 * `http://novica-dev/boiler/` keeps it.
 */
class HistoryApiClient(
    baseUrl: String,
    private val token: String,
    private val json: Json,
    client: OkHttpClient? = null,
) {
    private val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val http = client ?: OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    suspend fun postActions(batch: ActionsBatch): ActionsResult =
        decode(ActionsResult.serializer(), send("api/v1/app/actions", body(ActionsBatch.serializer(), batch)))

    suspend fun postIntent(intent: IntentDto) {
        send("api/v1/app/intent", body(IntentDto.serializer(), intent))
    }

    suspend fun health(): HealthDto = decode(HealthDto.serializer(), send("api/v1/health", null))

    /** Proves the token: an empty, authenticated upload that the server accepts without writing anything. */
    suspend fun checkToken(installId: String) {
        postActions(ActionsBatch(installId, emptyList()))
    }

    private fun <T> body(serializer: SerializationStrategy<T>, value: T): RequestBody =
        json.encodeToString(serializer, value).toRequestBody(jsonType)

    private fun <T> decode(deserializer: DeserializationStrategy<T>, text: String): T =
        try {
            json.decodeFromString(deserializer, text)
        } catch (e: Exception) {
            throw HistoryApiException(null, "unexpected response: ${e.message}")
        }

    private suspend fun send(path: String, body: RequestBody?): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(base + path).header("Authorization", "Bearer $token")
        val request = if (body == null) builder.get().build() else builder.post(body).build()
        try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw HistoryApiException(resp.code, "HTTP ${resp.code}: ${text.take(200)}")
                text
            }
        } catch (e: IOException) {
            throw HistoryApiException(null, e.message ?: e.javaClass.simpleName)
        }
    }
}
