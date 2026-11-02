package rs.novica.dobarboiler.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rs.novica.dobarboiler.api.ReleaseClient
import rs.novica.dobarboiler.api.ReleaseDto

sealed interface UpdateState {
    data object Unknown : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: ReleaseDto?) : UpdateState
    data class Available(val release: ReleaseDto) : UpdateState
    data class Downloading(val release: ReleaseDto, val progress: Float) : UpdateState
    /** Android must first allow DobarBoiler to install apps (one-time, in system settings). */
    data class NeedsPermission(val release: ReleaseDto) : UpdateState
    /** The system installer is open; it replaces the app and restarts nothing by itself. */
    data class Installing(val release: ReleaseDto) : UpdateState
    data class Failed(val message: String, val release: ReleaseDto?) : UpdateState
}

/**
 * In-app updates from the release channel that tools/release-apk.sh publishes (docs/release.md).
 * Checks `latest.json`, downloads the APK into the cache, verifies its sha256 and hands it to the
 * system installer, which always asks for one confirmation tap (Android allows no silent update for
 * sideloaded apps). Same key, higher versionCode → installs over the app and keeps its data.
 */
class AppUpdater(
    private val context: Context,
    private val client: ReleaseClient,
    private val installedCode: Int,
    private val clock: Clock,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _history = MutableStateFlow<List<ReleaseDto>>(emptyList())
    val history: StateFlow<List<ReleaseDto>> = _history.asStateFlow()

    private val mutex = Mutex()
    private var lastCheckAt: Instant? = null

    private val updatesDir: File get() = File(context.cacheDir, "updates").apply { mkdirs() }

    /** Asks the channel for the newest release; without [force] at most every [MIN_CHECK_GAP]. */
    suspend fun check(force: Boolean = false): ReleaseDto? = mutex.withLock {
        val now = clock.instant()
        val current = _state.value
        val recent = lastCheckAt?.let { Duration.between(it, now) < MIN_CHECK_GAP } == true
        if (!force && recent && current !is UpdateState.Failed) return@withLock (current as? UpdateState.Available)?.release
        if (current is UpdateState.Downloading || current is UpdateState.Installing) return@withLock current.releaseOrNull()
        _state.value = UpdateState.Checking
        try {
            val latest = client.latest()
            lastCheckAt = now
            _state.value = if (latest != null && latest.isNewerThan(installedCode)) {
                UpdateState.Available(latest)
            } else {
                UpdateState.UpToDate(latest)
            }
            latest?.takeIf { it.isNewerThan(installedCode) }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName, null)
            null
        }
    }

    /** Version history for Podešavanja → Verzije (newest first). */
    suspend fun refreshHistory() {
        runCatching { client.versions() }.onSuccess { _history.value = it }
    }

    fun isInstalled(release: ReleaseDto): Boolean = release.versionCode == installedCode

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** System screen "Instaliraj nepoznate aplikacije" for this app. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Download + verify + open the system installer. Without the install permission it opens the
     * permission screen instead and leaves [UpdateState.NeedsPermission]; tapping again continues.
     */
    suspend fun downloadAndInstall(release: ReleaseDto) {
        if (!canInstall()) {
            _state.value = UpdateState.NeedsPermission(release)
            context.startActivity(permissionIntent())
            return
        }
        val apk = File(updatesDir, "dobarboiler-${release.versionCode}.apk")
        try {
            updatesDir.listFiles()?.filter { it != apk }?.forEach { it.delete() }
            if (!apk.exists()) {
                _state.value = UpdateState.Downloading(release, 0f)
                client.download(release, apk) { p -> _state.value = UpdateState.Downloading(release, p) }
            }
            _state.value = UpdateState.Installing(release)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            apk.delete()
            _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName, release)
        }
    }

    private fun UpdateState.releaseOrNull(): ReleaseDto? = when (this) {
        is UpdateState.Available -> release
        is UpdateState.Downloading -> release
        is UpdateState.Installing -> release
        is UpdateState.NeedsPermission -> release
        else -> null
    }

    private companion object {
        val MIN_CHECK_GAP: Duration = Duration.ofMinutes(15)
    }
}
