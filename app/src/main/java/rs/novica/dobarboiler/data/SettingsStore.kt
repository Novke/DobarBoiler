package rs.novica.dobarboiler.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import rs.novica.dobarboiler.domain.BandConfig
import rs.novica.dobarboiler.domain.EngineConfig
import rs.novica.dobarboiler.domain.HeatingRateConfig
import rs.novica.dobarboiler.domain.HeatingWindow
import rs.novica.dobarboiler.domain.MaintainMode
import rs.novica.dobarboiler.domain.ManualChangePolicy
import rs.novica.dobarboiler.domain.OutsideWindowBehavior

/** SPEC §4.4 — assembled app settings (engine config + zone + reassert delay). */
data class AppSettings(
    val engineConfig: EngineConfig,          // assembled from individual keys + learned rates
    val zoneId: String,                      // default "Europe/Belgrade"
    val reassertAfterMinutes: Int,           // used when policy == REASSERT (default 15)
)

/** Runtime state persisted outside Room (single-consumer, small, hot). */
data class RuntimeState(
    val nextTransitionAt: Instant?,
    val nextTransitionReason: String?,
    val lastAppliedStateJson: String?,       // DesiredState the app last asserted
    val lastAppliedAt: Instant?,
    val manualHoldUntil: Instant?,           // SPEC §7.4
    val lastCloudContactAt: Instant?,
)

/** Process-wide singleton DataStore for the "settings" file. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * SPEC §4.4 — Preferences DataStore over file "settings". Individual keys are
 * decomposed/assembled here so [EngineConfig] stays a pure domain type.
 *
 * Note: `EngineConfig.fallbackPreset` is a code-level constant and is deliberately
 * NOT persisted — [update] transforms that change it have no lasting effect.
 */
class SettingsStore(context: Context) : SyncStateStore {

    private val ds: DataStore<Preferences> = context.applicationContext.settingsDataStore

    // -- Settings ------------------------------------------------------------

    val settings: Flow<AppSettings> = ds.data.map { it.toAppSettings() }

    suspend fun settingsSnapshot(): AppSettings = ds.data.first().toAppSettings()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        ds.edit { prefs -> prefs.write(transform(prefs.toAppSettings())) }
    }

    // -- Runtime state ---------------------------------------------------------

    val runtime: Flow<RuntimeState> = ds.data.map { it.toRuntimeState() }

    suspend fun runtimeSnapshot(): RuntimeState = ds.data.first().toRuntimeState()

    suspend fun updateRuntime(transform: (RuntimeState) -> RuntimeState) {
        ds.edit { prefs -> prefs.write(transform(prefs.toRuntimeState())) }
    }

    // -- History-server sync (design §5.1) ----------------------------------------

    val sync: Flow<SyncState> = ds.data.map { it.toSyncState() }

    override suspend fun syncState(): SyncState = ds.data.first().toSyncState()

    override suspend fun updateSync(transform: (SyncState) -> SyncState) {
        ds.edit { prefs -> prefs.writeSync(transform(prefs.toSyncState())) }
    }

    /** Stores the Converger's current intent, but only when it differs from the stored one apart from its time. */
    suspend fun recordIntent(intentJson: String) {
        ds.edit { prefs ->
            val current = prefs.toSyncState()
            if (!IntentCodec.sameIntent(current.lastIntentJson, intentJson)) {
                prefs.writeSync(current.copy(lastIntentJson = intentJson))
            }
        }
    }

    // -- Focused writers (hot paths) -------------------------------------------

    /** Recomputed EWMAs from DeviceRepository; null resets to the configured default rate. */
    suspend fun setLearnedRates(full: Double?, eco: Double?) {
        ds.edit { prefs ->
            prefs.setOrRemove(K.LEARNED_RATE, full)
            prefs.setOrRemove(K.LEARNED_ECO_RATE, eco)
        }
    }

    /** Live `MedMaxSetpointTemperature` from plantSettings; extra temp clamp when set. */
    suspend fun setMaxSetpointGuard(value: Int?) {
        ds.edit { prefs -> prefs.setOrRemove(K.MAX_SETPOINT_GUARD, value) }
    }

    // -- Keys and (de)composition ----------------------------------------------

    private object K {
        val WINDOW_START = stringPreferencesKey("window_start")
        val WINDOW_END = stringPreferencesKey("window_end")
        val OUTSIDE_WINDOW = stringPreferencesKey("outside_window")
        val KEEP_WARM_TEMP = intPreferencesKey("keep_warm_temp")
        val BAND_ENABLED = booleanPreferencesKey("band_enabled")
        val BAND_STOP_AT = intPreferencesKey("band_stop_at")
        val BAND_RESTART_AT = intPreferencesKey("band_restart_at")
        val BAND_POLL_MINUTES = intPreferencesKey("band_poll_minutes")
        val RATE_PER_HOUR = doublePreferencesKey("rate_per_hour_c")
        val ECO_PENALTY_FACTOR = doublePreferencesKey("eco_penalty_factor")
        val SAFETY_MARGIN_PERCENT = intPreferencesKey("safety_margin_percent")
        val MAX_LEAD_MINUTES = intPreferencesKey("max_lead_minutes")
        val LEARNED_RATE = doublePreferencesKey("learned_rate")
        val LEARNED_ECO_RATE = doublePreferencesKey("learned_eco_rate")
        val BOOST_MAX_MINUTES = intPreferencesKey("boost_max_minutes")
        val MANUAL_POLICY = stringPreferencesKey("manual_policy")
        val MID_WINDOW_CHECK = booleanPreferencesKey("mid_window_check")
        val MAX_SETPOINT_GUARD = intPreferencesKey("max_setpoint_guard")
        val ZONE_ID = stringPreferencesKey("zone_id")
        val REASSERT_AFTER_MINUTES = intPreferencesKey("reassert_after_minutes")

        val RT_NEXT_TRANSITION_AT = longPreferencesKey("rt_next_transition_at")
        val RT_NEXT_TRANSITION_REASON = stringPreferencesKey("rt_next_transition_reason")
        val RT_LAST_APPLIED_STATE_JSON = stringPreferencesKey("rt_last_applied_state_json")
        val RT_LAST_APPLIED_AT = longPreferencesKey("rt_last_applied_at")
        val RT_MANUAL_HOLD_UNTIL = longPreferencesKey("rt_manual_hold_until")
        val RT_LAST_CLOUD_CONTACT_AT = longPreferencesKey("rt_last_cloud_contact_at")
        val SYNC_INSTALL_ID = stringPreferencesKey("rt_install_id")
        val SYNC_LAST_ACTION_ID = longPreferencesKey("rt_last_uploaded_action_id")
        val SYNC_INTENT_JSON = stringPreferencesKey("rt_last_intent_json")
        val SYNC_INTENT_UPLOADED_JSON = stringPreferencesKey("rt_last_intent_uploaded_json")
        val SYNC_OK_AT = longPreferencesKey("rt_last_sync_ok_at")
        val SYNC_ERROR = stringPreferencesKey("rt_last_sync_error")
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val defaults = EngineConfig()

        val window = HeatingWindow(
            start = parseTime(this[K.WINDOW_START]) ?: defaults.defaultWindow.start,
            end = parseTime(this[K.WINDOW_END]) ?: defaults.defaultWindow.end,
        )

        val maintainMode = if (this[K.BAND_ENABLED] == true) {
            runCatching {
                MaintainMode.Band(
                    BandConfig(
                        stopAt = this[K.BAND_STOP_AT] ?: 56,
                        restartAt = this[K.BAND_RESTART_AT] ?: 52,
                        pollEvery = Duration.ofMinutes(
                            (this[K.BAND_POLL_MINUTES] ?: 15).coerceIn(10, 30).toLong(),
                        ),
                    ),
                )
            }.getOrDefault(MaintainMode.Simple) // invalid band (< 2 °C) degrades to Simple
        } else {
            MaintainMode.Simple
        }

        val rateDefaults = defaults.heatingRate
        val heatingRate = HeatingRateConfig(
            ratePerHourC = this[K.RATE_PER_HOUR] ?: rateDefaults.ratePerHourC,
            ecoPenaltyFactor = this[K.ECO_PENALTY_FACTOR] ?: rateDefaults.ecoPenaltyFactor,
            safetyMarginPercent = this[K.SAFETY_MARGIN_PERCENT] ?: rateDefaults.safetyMarginPercent,
            maxLead = this[K.MAX_LEAD_MINUTES]?.toLong()?.let(Duration::ofMinutes)
                ?: rateDefaults.maxLead,
            learnedRatePerHourC = this[K.LEARNED_RATE],
            learnedEcoRatePerHourC = this[K.LEARNED_ECO_RATE],
        )

        val engineConfig = EngineConfig(
            defaultWindow = window,
            outsideWindow = parseEnum(this[K.OUTSIDE_WINDOW], defaults.outsideWindow),
            keepWarmTemp = this[K.KEEP_WARM_TEMP] ?: defaults.keepWarmTemp,
            maintainMode = maintainMode,
            heatingRate = heatingRate,
            boostMaxDuration = this[K.BOOST_MAX_MINUTES]?.toLong()?.let(Duration::ofMinutes)
                ?: defaults.boostMaxDuration,
            manualChangePolicy = parseEnum(this[K.MANUAL_POLICY], defaults.manualChangePolicy),
            midWindowCheck = this[K.MID_WINDOW_CHECK] ?: defaults.midWindowCheck,
            maxSetpointGuard = this[K.MAX_SETPOINT_GUARD],
            fallbackPreset = defaults.fallbackPreset,
        )

        return AppSettings(
            engineConfig = engineConfig,
            zoneId = this[K.ZONE_ID] ?: DEFAULT_ZONE_ID,
            reassertAfterMinutes = this[K.REASSERT_AFTER_MINUTES] ?: DEFAULT_REASSERT_MINUTES,
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        val cfg = s.engineConfig
        this[K.WINDOW_START] = cfg.defaultWindow.start.format(HH_MM)
        this[K.WINDOW_END] = cfg.defaultWindow.end.format(HH_MM)
        this[K.OUTSIDE_WINDOW] = cfg.outsideWindow.name
        this[K.KEEP_WARM_TEMP] = cfg.keepWarmTemp

        when (val mode = cfg.maintainMode) {
            is MaintainMode.Simple -> this[K.BAND_ENABLED] = false // band numbers retained
            is MaintainMode.Band -> {
                this[K.BAND_ENABLED] = true
                this[K.BAND_STOP_AT] = mode.band.stopAt
                this[K.BAND_RESTART_AT] = mode.band.restartAt
                this[K.BAND_POLL_MINUTES] = mode.band.pollEvery.toMinutes().toInt()
            }
        }

        val rate = cfg.heatingRate
        this[K.RATE_PER_HOUR] = rate.ratePerHourC
        this[K.ECO_PENALTY_FACTOR] = rate.ecoPenaltyFactor
        this[K.SAFETY_MARGIN_PERCENT] = rate.safetyMarginPercent
        this[K.MAX_LEAD_MINUTES] = rate.maxLead.toMinutes().toInt()
        setOrRemove(K.LEARNED_RATE, rate.learnedRatePerHourC)
        setOrRemove(K.LEARNED_ECO_RATE, rate.learnedEcoRatePerHourC)

        this[K.BOOST_MAX_MINUTES] = cfg.boostMaxDuration.toMinutes().toInt()
        this[K.MANUAL_POLICY] = cfg.manualChangePolicy.name
        this[K.MID_WINDOW_CHECK] = cfg.midWindowCheck
        setOrRemove(K.MAX_SETPOINT_GUARD, cfg.maxSetpointGuard)

        this[K.ZONE_ID] = s.zoneId
        this[K.REASSERT_AFTER_MINUTES] = s.reassertAfterMinutes
    }

    private fun Preferences.toRuntimeState(): RuntimeState = RuntimeState(
        nextTransitionAt = this[K.RT_NEXT_TRANSITION_AT]?.let(Instant::ofEpochMilli),
        nextTransitionReason = this[K.RT_NEXT_TRANSITION_REASON],
        lastAppliedStateJson = this[K.RT_LAST_APPLIED_STATE_JSON],
        lastAppliedAt = this[K.RT_LAST_APPLIED_AT]?.let(Instant::ofEpochMilli),
        manualHoldUntil = this[K.RT_MANUAL_HOLD_UNTIL]?.let(Instant::ofEpochMilli),
        lastCloudContactAt = this[K.RT_LAST_CLOUD_CONTACT_AT]?.let(Instant::ofEpochMilli),
    )

    private fun MutablePreferences.write(r: RuntimeState) {
        setOrRemove(K.RT_NEXT_TRANSITION_AT, r.nextTransitionAt?.toEpochMilli())
        setOrRemove(K.RT_NEXT_TRANSITION_REASON, r.nextTransitionReason)
        setOrRemove(K.RT_LAST_APPLIED_STATE_JSON, r.lastAppliedStateJson)
        setOrRemove(K.RT_LAST_APPLIED_AT, r.lastAppliedAt?.toEpochMilli())
        setOrRemove(K.RT_MANUAL_HOLD_UNTIL, r.manualHoldUntil?.toEpochMilli())
        setOrRemove(K.RT_LAST_CLOUD_CONTACT_AT, r.lastCloudContactAt?.toEpochMilli())
    }

    private fun Preferences.toSyncState(): SyncState = SyncState(
        installId = this[K.SYNC_INSTALL_ID],
        lastUploadedActionId = this[K.SYNC_LAST_ACTION_ID] ?: 0L,
        lastIntentJson = this[K.SYNC_INTENT_JSON],
        lastIntentUploadedJson = this[K.SYNC_INTENT_UPLOADED_JSON],
        lastSyncOkAt = this[K.SYNC_OK_AT]?.let(Instant::ofEpochMilli),
        lastSyncError = this[K.SYNC_ERROR],
    )

    private fun MutablePreferences.writeSync(s: SyncState) {
        setOrRemove(K.SYNC_INSTALL_ID, s.installId)
        this[K.SYNC_LAST_ACTION_ID] = s.lastUploadedActionId
        setOrRemove(K.SYNC_INTENT_JSON, s.lastIntentJson)
        setOrRemove(K.SYNC_INTENT_UPLOADED_JSON, s.lastIntentUploadedJson)
        setOrRemove(K.SYNC_OK_AT, s.lastSyncOkAt?.toEpochMilli())
        setOrRemove(K.SYNC_ERROR, s.lastSyncError)
    }

    private fun <T> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    private fun parseTime(raw: String?): LocalTime? =
        raw?.let { runCatching { LocalTime.parse(it, HH_MM) }.getOrNull() }

    private inline fun <reified E : Enum<E>> parseEnum(raw: String?, default: E): E =
        raw?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private companion object {
        const val DEFAULT_ZONE_ID = "Europe/Belgrade"
        const val DEFAULT_REASSERT_MINUTES = 15
        val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
