package rs.novica.dobarboiler.di

import android.app.Application
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Clock
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import rs.novica.dobarboiler.api.AristonApiClient
import rs.novica.dobarboiler.api.AristonHttpClient
import rs.novica.dobarboiler.data.ActionLogRepository
import rs.novica.dobarboiler.data.ServerSync
import rs.novica.dobarboiler.api.HistoryApiClient
import rs.novica.dobarboiler.scheduling.ServerSyncWorker
import rs.novica.dobarboiler.data.AppDatabase
import rs.novica.dobarboiler.data.CredentialStore
import rs.novica.dobarboiler.data.DeviceRepository
import rs.novica.dobarboiler.data.ScheduleRepository
import rs.novica.dobarboiler.data.SettingsStore
import rs.novica.dobarboiler.domain.DefaultSchedulerEngine
import rs.novica.dobarboiler.domain.SchedulerEngine
import rs.novica.dobarboiler.notifications.Notifier
import rs.novica.dobarboiler.scheduling.AlarmScheduler
import rs.novica.dobarboiler.scheduling.Converger
import rs.novica.dobarboiler.scheduling.WatchdogWorker

/**
 * Manual dependency-injection graph (SPEC §10.2 — frozen wiring surface, NO Hilt).
 *
 * Everything is lazily initialized so process start stays cheap; the first background
 * worker or the first Compose screen materializes only the slice of the graph it needs.
 */
class AppContainer(private val app: Application) {

    /**
     * Process-lifetime scope for work that must outlive any single ViewModel —
     * notably the USER convergence launched from sheets/dialogs that dismiss
     * (and clear their ViewModelStore) immediately after saving.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Configured time zone. Read once from [SettingsStore] on first access
     * (default "Europe/Belgrade"); a zone change in Settings triggers a full
     * re-convergence anyway (TIMEZONE_CHANGED / user path), so a per-process
     * snapshot is sufficient and keeps this a plain `val` per the frozen contract.
     */
    val zone: ZoneId by lazy {
        runCatching { ZoneId.of(runBlocking { settings.settingsSnapshot().zoneId }) }
            .getOrDefault(ZoneId.of("Europe/Belgrade"))
    }

    val clock: Clock by lazy { Clock.system(zone) }

    val json: Json by lazy { Json { ignoreUnknownKeys = true } }

    val credentialStore: CredentialStore by lazy { CredentialStore(app) }

    val apiClient: AristonApiClient by lazy { AristonHttpClient(credentialStore, json) }

    val db: AppDatabase by lazy {
        Room.databaseBuilder(app, AppDatabase::class.java, "dobarboiler.db").build()
    }

    val settings: SettingsStore by lazy { SettingsStore(app) }

    val scheduleRepository: ScheduleRepository by lazy {
        ScheduleRepository(db, settings) { zone }
    }

    val deviceRepository: DeviceRepository by lazy {
        DeviceRepository(apiClient, credentialStore, db, settings, clock)
    }

    val actionLog: ActionLogRepository by lazy { ActionLogRepository(db) }

    /** History-server upload (design §5.1); null client = server not configured → no-op. */
    val serverSync: ServerSync by lazy {
        ServerSync(
            fetchAfter = { after, limit -> actionLog.after(after, limit) },
            store = settings,
            clientFactory = {
                val url = credentialStore.historyServerUrl
                val token = credentialStore.historyServerToken
                if (url == null || token == null) null else HistoryApiClient(url, token, json)
            },
            clock = clock,
        )
    }

    val engine: SchedulerEngine by lazy { DefaultSchedulerEngine(zone) }

    val alarmScheduler: AlarmScheduler by lazy { AlarmScheduler(app) }

    val notifier: Notifier by lazy { Notifier(app) }

    val converger: Converger by lazy {
        Converger(
            engine = engine,
            schedule = scheduleRepository,
            device = deviceRepository,
            settings = settings,
            creds = credentialStore,
            log = actionLog,
            alarms = alarmScheduler,
            notifier = notifier,
            clock = clock,
        )
    }

    /** Hourly history-server upload, unique "server_sync_periodic", KEEP. */
    fun enqueueServerSync() = ServerSyncWorker.enqueuePeriodic(app)

    /** Periodic 6 h watchdog (SPEC §6.4): unique name "watchdog", KEEP, requires network. */
    fun enqueueWatchdog() {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            "watchdog",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
