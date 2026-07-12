package rs.novica.dobarboiler.data

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * SPEC §4.7 — append-only audit trail of everything the automation does.
 * Kind/trigger string constants are defined in `scheduling/Converger.kt` and passed
 * as plain strings so this layer stays enum-agnostic.
 */
class ActionLogRepository(db: AppDatabase) {

    private val dao = db.actionLogDao()

    suspend fun log(
        kind: String,
        trig: String,
        detail: String,
        outcome: String,
        attempt: Int = 1,
        stateJson: String? = null,
    ) {
        dao.insert(
            ActionLogEntity(
                at = Instant.now(),
                kind = kind,
                trig = trig,
                detail = detail,
                outcome = outcome,
                attempt = attempt,
                stateJson = stateJson,
            ),
        )
    }

    fun recent(limit: Int = 300): Flow<List<ActionLogEntity>> = dao.recent(limit)

    /** SPEC §4.2 prune policy: drop entries older than 90 days (WatchdogWorker). */
    suspend fun prune(olderThanDays: Int = 90) {
        dao.pruneBefore(Instant.now().minus(Duration.ofDays(olderThanDays.toLong())))
    }
}
