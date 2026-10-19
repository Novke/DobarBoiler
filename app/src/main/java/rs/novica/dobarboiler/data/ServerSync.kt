package rs.novica.dobarboiler.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rs.novica.dobarboiler.api.ActionDto
import rs.novica.dobarboiler.api.ActionsBatch
import rs.novica.dobarboiler.api.DesiredDto
import rs.novica.dobarboiler.api.HistoryApiClient
import rs.novica.dobarboiler.api.IntentDto
import rs.novica.dobarboiler.domain.Resolution
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Upload bookkeeping for the history server, kept in DataStore (no Room schema change).
 * [lastUploadedActionId] is the high-water mark over `action_log.id`; [lastIntentJson] is the latest
 * intent written by the Converger and [lastIntentUploadedJson] the one the server already has.
 */
data class SyncState(
    val installId: String? = null,
    val lastUploadedActionId: Long = 0,
    val lastIntentJson: String? = null,
    val lastIntentUploadedJson: String? = null,
    val lastSyncOkAt: Instant? = null,
    val lastSyncError: String? = null,
)

interface SyncStateStore {
    suspend fun syncState(): SyncState
    suspend fun updateSync(transform: (SyncState) -> SyncState)
}

sealed interface SyncResult {
    data object NotConfigured : SyncResult
    data class Ok(val actions: Int, val intent: Boolean) : SyncResult
    data class Failed(val error: Throwable) : SyncResult
}

/** What the app wants right now, as stored between convergence and upload (installId added at upload). */
object IntentCodec {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Stored(val at: String, val source: String, val desired: DesiredDto, val next: String?)

    fun encode(res: Resolution, at: Instant): String = json.encodeToString(
        Stored.serializer(),
        Stored(
            at = at.toString(),
            source = res.source.name,
            desired = DesiredDto(
                powerOn = res.desired.powerOn,
                targetTemp = if (res.desired.powerOn) res.desired.targetTemp else null,
                eco = if (res.desired.powerOn) res.desired.eco else null,
            ),
            next = res.nextTransition?.at?.toString(),
        ),
    )

    private fun stored(raw: String): Stored? = runCatching { json.decodeFromString(Stored.serializer(), raw) }.getOrNull()

    fun decode(raw: String): IntentDto? = stored(raw)?.let {
        IntentDto(installId = "", at = it.at, source = it.source, desired = it.desired, nextTransitionAt = it.next)
    }

    /** Same intent apart from the time it was computed (a converge every few minutes is not a new intent). */
    fun sameIntent(a: String?, b: String?): Boolean {
        val x = a?.let(::stored) ?: return false
        val y = b?.let(::stored) ?: return false
        return x.copy(at = "") == y.copy(at = "")
    }
}

/**
 * Sends `action_log` rows above the high-water mark (batches of [BATCH]) and the current intent to the
 * history server. The mark only moves after a 2xx, and the server ignores duplicates, so a lost response
 * just means the batch is sent again. Without a configured server this is a no-op.
 */
class ServerSync(
    private val fetchAfter: suspend (Long, Int) -> List<ActionLogEntity>,
    private val store: SyncStateStore,
    private val clientFactory: () -> HistoryApiClient?,
    private val clock: Clock,
) {
    companion object {
        const val BATCH = 200
    }

    /** The one-time and the periodic worker share this singleton; one upload at a time. */
    private val mutex = Mutex()

    suspend fun run(): SyncResult = mutex.withLock { runLocked() }

    private suspend fun runLocked(): SyncResult {
        val client = clientFactory() ?: return SyncResult.NotConfigured
        val fresh = UUID.randomUUID().toString()
        store.updateSync { if (it.installId == null) it.copy(installId = fresh) else it }
        var state = store.syncState()
        val installId = state.installId ?: fresh
        var sent = 0
        var intentSent = false
        return try {
            while (true) {
                val rows = fetchAfter(state.lastUploadedActionId, BATCH)
                if (rows.isEmpty()) break
                client.postActions(ActionsBatch(installId, rows.map { it.toDto() }))
                val mark = rows.maxOf { it.id }
                store.updateSync { it.copy(lastUploadedActionId = maxOf(it.lastUploadedActionId, mark)) }
                state = store.syncState()
                sent += rows.size
                if (rows.size < BATCH) break
            }
            val intentJson = state.lastIntentJson
            if (intentJson != null && intentJson != state.lastIntentUploadedJson) {
                IntentCodec.decode(intentJson)?.let { client.postIntent(it.copy(installId = installId)) }
                store.updateSync { it.copy(lastIntentUploadedJson = intentJson) }
                intentSent = true
            }
            store.updateSync { it.copy(lastSyncOkAt = clock.instant(), lastSyncError = null) }
            SyncResult.Ok(sent, intentSent)
        } catch (e: Exception) {
            store.updateSync { it.copy(lastSyncError = e.message ?: e.javaClass.simpleName) }
            SyncResult.Failed(e)
        }
    }

    private fun ActionLogEntity.toDto() = ActionDto(
        localId = id, at = at.toString(), kind = kind, trig = trig, detail = detail, outcome = outcome,
        attempt = attempt, stateJson = stateJson,
    )
}
