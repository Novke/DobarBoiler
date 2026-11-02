package rs.novica.dobarboiler.api

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** One line of a release changelog (written by tools/release_lib.py). */
@Serializable
data class ChangeDto(val pr: Int? = null, val text: String)

/** A published APK: `latest.json` and each row of `versions.json` at http://novica-dev/boiler-apk/. */
@Serializable
data class ReleaseDto(
    val version: String,
    val versionCode: Int,
    val file: String,
    val sha256: String? = null,
    val size: Long? = null,
    val date: String? = null,
    val test: Boolean = false,
    val changelog: List<ChangeDto> = emptyList(),
) {
    /** Android only installs a higher versionCode over the running app. */
    fun isNewerThan(installedCode: Int): Boolean = versionCode > installedCode
}

class ReleaseException(message: String) : IOException(message)

/**
 * Reads the release channel that tools/release-apk.sh publishes and downloads an APK, checking its sha256.
 * The channel is on the tailnet (cleartext allowed for `novica-dev` only, see network_security_config).
 */
class ReleaseClient(
    baseUrl: String = DEFAULT_URL,
    private val json: Json = Json { ignoreUnknownKeys = true },
    client: OkHttpClient? = null,
) {
    companion object {
        const val DEFAULT_URL = "http://novica-dev/boiler-apk/"
    }

    private val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** null = nothing published yet (404). */
    suspend fun latest(): ReleaseDto? = getText("latest.json")?.let { json.decodeFromString(ReleaseDto.serializer(), it) }

    /** Newest first; empty before the first versioned release. */
    suspend fun versions(): List<ReleaseDto> =
        getText("versions.json")?.let { json.decodeFromString(ListSerializer(ReleaseDto.serializer()), it) }.orEmpty()

    /**
     * Downloads [release] into [dest] (via a temp file) and verifies its sha256; [onProgress] gets 0..1
     * when the size is known. A mismatch deletes the file and throws — never install a half or foreign APK.
     */
    suspend fun download(release: ReleaseDto, dest: File, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(base + release.file).build()
        val tmp = File(dest.parentFile, dest.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw ReleaseException("HTTP ${resp.code} za ${release.file}")
            val body = resp.body ?: throw ReleaseException("prazan odgovor")
            val total = release.size ?: body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        if (total != null) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        val expected = release.sha256
        if (expected != null && !expected.equals(actual, ignoreCase = true)) {
            tmp.delete()
            throw ReleaseException("sha256 se ne slaže (preuzimanje oštećeno)")
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw ReleaseException("ne mogu da sačuvam ${dest.name}")
        }
    }

    private suspend fun getText(path: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(base + path).header("Cache-Control", "no-cache").build()
        http.newCall(request).execute().use { resp ->
            when {
                resp.code == 404 -> null
                !resp.isSuccessful -> throw ReleaseException("HTTP ${resp.code} za $path")
                else -> resp.body?.string() ?: throw ReleaseException("prazan odgovor za $path")
            }
        }
    }
}
