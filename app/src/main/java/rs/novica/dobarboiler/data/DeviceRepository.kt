package rs.novica.dobarboiler.data

import java.time.Clock
import java.time.Duration
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rs.novica.dobarboiler.api.AristonApiClient
import rs.novica.dobarboiler.api.MedPlantSettings
import rs.novica.dobarboiler.domain.BoilerCommand
import rs.novica.dobarboiler.domain.DeviceSnapshot
import rs.novica.dobarboiler.domain.RateEstimator

/**
 * SPEC §4.6 — the only gateway between the app and the physical boiler.
 *
 * Polling is rate-limited (default 30 s min interval per api-findings etiquette), the
 * last snapshot is persisted in Room (survives restarts), and every successful poll
 * feeds [RateEstimator] with the previous/current snapshot pair to learn the real
 * heating rate per eco flag.
 */
class DeviceRepository(
    private val api: AristonApiClient,
    private val creds: CredentialStore,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val clock: Clock,
) {
    private val snapshotDao = db.snapshotDao()
    private val rateSampleDao = db.rateSampleDao()
    private val rateEstimator = RateEstimator()
    private val refreshMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _snapshot = MutableStateFlow<DeviceSnapshot?>(null)

    /** Last known snapshot (persisted; survives restart). */
    val snapshot: StateFlow<DeviceSnapshot?> = _snapshot.asStateFlow()

    init {
        // Warm the StateFlow from the persisted row; never clobber a fresher live poll.
        scope.launch {
            snapshotDao.get()?.toDomainSnapshot()?.let { _snapshot.compareAndSet(null, it) }
        }
    }

    /**
     * Poll cloud state. Rate-limited: if the last successful poll is younger than
     * [minInterval], returns the cached snapshot without a network call.
     * Updates `RuntimeState.lastCloudContactAt` on every real poll.
     */
    suspend fun refresh(minInterval: Duration = Duration.ofSeconds(30)): DeviceSnapshot =
        refreshMutex.withLock {
            val cached = _snapshot.value ?: snapshotDao.get()?.toDomainSnapshot()
                ?.also { _snapshot.compareAndSet(null, it) }
            val now = clock.instant()
            if (cached != null && Duration.between(cached.at, now) < minInterval) {
                return cached
            }

            val gwId = requireGwId()
            val fresh = api.getState(gwId).toSnapshot(clock.instant())

            snapshotDao.put(
                SnapshotEntity(
                    id = SnapshotEntity.SINGLETON_ID,
                    at = fresh.at,
                    json = fresh.toJsonString(),
                ),
            )
            settings.updateRuntime { it.copy(lastCloudContactAt = fresh.at) }
            if (cached != null) learnFrom(cached, fresh)
            _snapshot.value = fresh
            fresh
        }

    /**
     * Executes commands in order (they arrive pre-ordered from `SchedulerEngine.plan`).
     * Throws [rs.novica.dobarboiler.api.AristonException] on the first failure — the
     * caller retries the whole batch; commands are idempotent.
     */
    suspend fun execute(gwId: String, commands: List<BoilerCommand>) {
        for (command in commands) {
            when (command) {
                is BoilerCommand.SetMode -> api.setMode(gwId, command.mode)
                is BoilerCommand.SetPower -> api.setPower(gwId, command.on)
                is BoilerCommand.SetTemperature ->
                    api.setTemperature(gwId, command.temp.toDouble())
                is BoilerCommand.SetEco -> api.setEco(gwId, command.eco)
                is BoilerCommand.SetAntilegionella -> {
                    // plantSettings uses an old/new pattern → read-modify-write.
                    val current = api.getPlantSettings(gwId)
                    if (current.antilegionellaOn != command.on) {
                        api.setAntilegionella(gwId, command.on, current.antilegionellaOn)
                    }
                }
            }
        }
    }

    /** Reads plant settings and refreshes `maxSetpointGuard` in [SettingsStore]. */
    suspend fun plantSettings(): MedPlantSettings {
        val plantSettings = api.getPlantSettings(requireGwId())
        settings.setMaxSetpointGuard(plantSettings.maxSetpoint.roundToInt())
        return plantSettings
    }

    /** Writes the device's max-setpoint plant setting (old/new pattern) and the guard. */
    suspend fun applyMaxSetpoint(newTemp: Int) {
        val gwId = requireGwId()
        val current = api.getPlantSettings(gwId)
        if (current.maxSetpoint.roundToInt() != newTemp) {
            api.setMaxSetpoint(gwId, newTemp.toDouble(), current.maxSetpoint)
        }
        settings.setMaxSetpointGuard(newTemp)
    }

    /** Toggles the antilegionella plant setting (old/new pattern); no-op when already set. */
    suspend fun triggerAntilegionella(enabled: Boolean) {
        val gwId = requireGwId()
        val current = api.getPlantSettings(gwId)
        if (current.antilegionellaOn != enabled) {
            api.setAntilegionella(gwId, enabled, current.antilegionellaOn)
        }
    }

    /** Extracts a rate sample from consecutive snapshots and recomputes the EWMAs. */
    private suspend fun learnFrom(previous: DeviceSnapshot, current: DeviceSnapshot) {
        val sample = rateEstimator.sampleFrom(previous, current) ?: return
        rateSampleDao.insert(sample.toEntity())
        rateSampleDao.prune(sample.eco, KEEP_SAMPLES_PER_FLAG)
        val all = rateSampleDao.all().map { it.toDomain() }
        settings.setLearnedRates(
            full = rateEstimator.learnedRate(all, eco = false),
            eco = rateEstimator.learnedRate(all, eco = true),
        )
    }

    private fun requireGwId(): String =
        checkNotNull(creds.gwId) { "Nije izabran uređaj (gwId je prazan)" }

    private companion object {
        const val KEEP_SAMPLES_PER_FLAG = 50
    }
}
