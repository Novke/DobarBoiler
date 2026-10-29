# DobarBoiler — Authoritative Implementation Spec (v1.0, 2026-07-12)

Single source of truth for implementation. Supersedes the four specialist design documents where they disagree.
Ground truth for the device/protocol: `docs/api-findings.md` and `docs/protocol.md`.

Package root: `rs.novica.dobarboiler`. Single Gradle module `:app`. Kotlin 2.x, Jetpack Compose + Material3,
minSdk 26, targetSdk 35, **plain OkHttp + kotlinx.serialization (no Retrofit)**, Room via KSP, DataStore
(settings) + EncryptedSharedPreferences (credentials), AlarmManager exact alarms + BroadcastReceiver +
WorkManager fallback, **NO Hilt** — manual DI via `AppContainer`. UI language: Serbian latinica.

Implementation agents: implement ONLY the files of your module, but treat every Kotlin signature in this
document as a frozen contract — other modules compile against it verbatim.

---

## 0. Conflict resolutions (binding)

| # | Topic | Conflict | Resolution |
|---|-------|----------|------------|
| R1 | HTTP stack | "Retrofit or plain OkHttp" | **Plain OkHttp + kotlinx.serialization.** The `/switch`, `/switchEco` endpoints take bare JSON literals `true`/`false`; a hand-rolled client is simpler and avoids the Retrofit converter gotcha. |
| R2 | Boost mechanism | Protocol exposes mode 9 = BOOST (target forced to `MedMaxSetpointTemperatureMax`) vs requirement "boost to user-picked temp" | **Never use mode 9 (or 5).** The app keeps the device in mode **1 (MANUAL)** at all times and normalizes to it (`plan()` emits `SetMode(1)` when snapshot mode ≠ 1). App "Boost" = power on + `temperature=target` + eco off + polling. Mode 9's target is not user-selectable, so it cannot implement the requirement. |
| R3 | Boost polling cadence | 60 s (background doc) vs 90 s (scheduler doc) | **90 s.** Safely above the 30–60 s etiquette floor; at ~15 °C/h water moves only ~0.4 °C per poll, so nothing is lost. |
| R4 | Boost execution vehicle | WorkManager periodic vs foreground service | **Foreground service** (`specialUse` FGS, started from foreground UI) doing an in-process 90 s poll loop, plus an **exact alarm at `timeoutAt`** as a backstop that completes the boost even if the service dies. |
| R5 | Verify-poll delay | 90 s vs "2 min" (UX doc) | **90 s** after each command batch. |
| R6 | Mid-window sanity poll | 03:30 vs 04:00 | **Window midpoint** (e.g. 04:00 for 00:30–07:30), configurable on/off, default ON. |
| R7 | Band-mode (advanced hysteresis) cadence | 5–10 min WorkManager vs 15 min alarm chain | **15-min `setExactAndAllowWhileIdle` chain** (safely above the ~9-min Doze while-idle throttle), configurable 10–30 min. |
| R8 | Rate learning | EWMA α=0.3 per eco flag vs median of last 10 | **EWMA α=0.3, kept separately per eco flag; active after ≥5 samples.** Raw samples also stored in Room for the UI's "Nauči iz merenja". |
| R9 | HBT safety margin | 15 % (scheduler) vs fixed 15–20 min (background/UX) | **Percent-based: `safetyMarginPercent = 15`** (config), plus `maxLead = 6 h` clamp. UI shows the resulting minutes. |
| R10 | Outside-window behavior | `OFF \| KEEP_WARM` vs UX "Ne diraj" | **`OFF` (default) \| `KEEP_WARM`** per the domain design. UX string for KEEP_WARM: "Održavaj %d °C i preko dana (skuplja struja!)". "Ne diraj" is dropped — it conflicts with idempotent convergence. |
| R11 | Notification channels | 3 channels (background) vs 2 (UX) | **3 channels: `greske` (HIGH), `boost` (LOW ongoing + DEFAULT done), `dogadjaji` (LOW, silent).** |
| R12 | HBT hold | 60 min default vs "22:00 grace" | **`holdMinutes = 60` default, configurable 0–180 per override.** |
| R13 | Component packaging | `sched/` + `work/` + `boost/` (background doc) | Required layout wins: everything scheduling-related (receivers, workers, BoostService, AlarmScheduler, Converger) lives in **`scheduling/`**. |
| R14 | Max temp bound | Device range "40–75" vs plantSettings `MedMaxSetpointTemperature` (often 65) | Domain clamps to `40..75` **and additionally** to the live `MedMaxSetpointTemperature` read at login (stored in Settings). Settings screen exposes a "Maksimalna temperatura" control that writes the plant setting (old/new pattern) if the user wants >65. |
| R15 | Credentials store | "EncryptedSharedPreferences or DataStore" | **EncryptedSharedPreferences for email/password/token; Preferences DataStore for everything else** (settings + runtime state). |
| R16 | Maintain phase | app-side hysteresis vs firmware thermostat | **Simple mode is default** (window start: on+temp+eco; window end: off; firmware maintains the setpoint — `heatReq` proves the firmware thermostat is autonomous). App-side band control ships as opt-in "Napredno održavanje". All three designs agreed; this is confirmed as final. |
| R17 | Manual-button policy | — (all agreed) | **RESPECT_UNTIL_NEXT_TRANSITION default**, `REASSERT` opt-in. Implemented in `Converger` via a `manualHoldUntil` runtime flag (§7.4), NOT in the pure resolver. |
| R18 | Heat-by-time vs vacation precedence | — | HBT (and Boost) beat Vacation, per the scheduler design precedence table (§4.4). |

---

## 1. Architecture overview

**Core principle: idempotent state convergence.** One pure function (`SchedulerEngine.resolve`) computes what
the boiler should be doing *right now* plus the instant of the next change. Every background entry point
(alarm, boot, watchdog, verify, UI edit) runs the same routine — **Converger** — which:

1. loads `ScheduleInputs` from Room/DataStore,
2. calls `resolve(now, inputs)`,
3. polls the device once (rate-limited),
4. detects manual changes (§7.4),
5. diffs desired vs observed via `plan()` and executes only the delta commands,
6. logs to `action_log`, notifies on failure,
7. arms exactly ONE exact alarm at `resolution.nextTransition`.

Missed alarms, reboots, clock changes and process death all self-heal because state is converged, never
replayed. No in-memory state is load-bearing; everything is in Room/DataStore.

**Nightly budget (default simple mode):** 00:30 alarm → ≤4 commands (mode-normalize, power, temp, eco) +
1 verify poll (+90 s); ~04:00 sanity poll; 07:30 alarm → 1 command + 1 verify poll. ≤5 commands, ≤3 polls.

### 1.1 Package structure

```
rs.novica.dobarboiler
├── App.kt                       Application, WorkManager Configuration.Provider   [scaffold]
├── di/AppContainer.kt           manual DI graph                                   [scaffold]
├── api/                         Ariston cloud HTTP client (OkHttp)                [api]
├── domain/                      pure Kotlin engine — NO android.* imports         [domain]
├── data/                        Room, DataStore, EncryptedSharedPreferences, repos[data]
├── scheduling/                  alarms, receivers, workers, BoostService, Converger [scheduling]
├── notifications/               channels + Notifier                               [notifications]
└── ui/                          Compose screens, ViewModels, navigation, theme    [ui + scaffold(theme)]
```

### 1.2 Dependency directions (compile-time)

`domain` depends on nothing. `api` depends on nothing (defines its own DTOs + `CredentialProvider`).
`data` depends on `domain` + `api`. `notifications` depends on nothing (Android only).
`scheduling` depends on `domain`, `data`, `notifications`. `ui` depends on all. `di/AppContainer` wires all.

---

## 2. `api/` — Ariston cloud client

Implements `docs/protocol.md` exactly. Base URL `https://www.ariston-net.remotethermo.com/api/v2/`.
Headers on every request: `User-Agent: RestSharp/106.11.7.0`, `ar.authToken: <token or "">`,
`Content-Type: application/json; charset=utf-8`. OkHttp timeouts: connect 30 s, read 60 s.

### 2.1 Files

| File | Contents |
|---|---|
| `api/AristonApiClient.kt` | interface (below) |
| `api/AristonDtos.kt` | `@Serializable` DTOs: `LoginRequest(usr,pwd)`, `LoginResponse(token)`, `VelisPlantDto`, `MedPlantDataDto`, `MedPlantSettingsDto`, `TempRequest(new: Double)`, `ModeRequest(new: Int)`, `SettingChange(new: Double, old: Double)`, `ConsSequenceDto(k,p,v)` — all with `@SerialName` matching protocol.md and `ignoreUnknownKeys=true` Json |
| `api/AristonHttpClient.kt` | OkHttp implementation of the interface incl. 405 re-login logic |
| `api/AristonException.kt` | error hierarchy (below) |
| `api/CredentialProvider.kt` | SPI implemented by `data.CredentialStore` |

### 2.2 Frozen contracts

```kotlin
package rs.novica.dobarboiler.api

/** Implemented in data/ by CredentialStore. */
interface CredentialProvider {
    fun credentials(): Pair<String, String>?   // email to password, null if not logged in
    fun token(): String?
    fun storeToken(token: String?)
}

sealed class AristonException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthFailed(message: String) : AristonException(message)          // re-login failed or double 405
    class RateLimited(message: String) : AristonException(message)         // 429 — DO NOT auto-retry
    class Transient(message: String, cause: Throwable? = null) : AristonException(message, cause) // 5xx/timeout after 1 retry
    class DeviceOffline(message: String) : AristonException(message)       // 5xx "M2M platform error … 404": gateway unreachable (§7.5)
    class NotFound : AristonException("404")                               // callers usually map to null
}

data class VelisPlant(val gw: String, val name: String, val sn: String?, val sys: Int,
                      val wheType: Int, val online: Boolean)

data class MedPlantData(
    val on: Boolean, val mode: Int, val temp: Double, val reqTemp: Double,
    val procReqTemp: Double?, val eco: Boolean, val pwrOpt: Boolean?, val antiLeg: Boolean,
    val heatReq: Boolean, val avShw: Int?, val rmTm: String?)   // rmTm raw "HH:MM:SS"

data class MedPlantSettings(
    val antilegionellaOn: Boolean, val heatingRate: Int?,
    val maxSetpoint: Double, val maxSetpointMin: Double, val maxSetpointMax: Double)

/** All calls are suspend; all throw AristonException subtypes on failure. */
interface AristonApiClient {
    /** POST accounts/login. Stores token via CredentialProvider on success. */
    suspend fun login(email: String, password: String): String              // returns token
    suspend fun listVelisPlants(): List<VelisPlant>                         // GET velis/plants
    suspend fun getState(gwId: String): MedPlantData                        // GET velis/medPlantData/{gw}
    suspend fun getPlantSettings(gwId: String): MedPlantSettings            // GET .../plantSettings
    suspend fun setPower(gwId: String, on: Boolean)                         // POST .../switch, bare boolean body
    suspend fun setTemperature(gwId: String, tempC: Double)                 // POST .../temperature {"new":t}
    suspend fun setMode(gwId: String, mode: Int)                            // POST .../mode {"new":m}; m ∈ {1,5,9}
    suspend fun setEco(gwId: String, eco: Boolean)                          // POST .../switchEco, bare boolean
    suspend fun setAntilegionella(gwId: String, enabled: Boolean, oldEnabled: Boolean) // plantSettings old/new 1.0/0.0
    suspend fun setMaxSetpoint(gwId: String, newTemp: Double, oldTemp: Double)         // plantSettings old/new
    suspend fun getDhwConsumption(gwId: String): List<ConsSequence>         // GET remote/reports/{gw}/consSequencesApi8?usages=Dhw
}

data class ConsSequence(val kind: Int, val period: Int, val values: List<Double>)
```

### 2.3 Request pipeline (inside `AristonHttpClient`)

Per request (except login itself):
1. Attach headers. If no token stored, perform login first (using `CredentialProvider.credentials()`;
   throw `AuthFailed("no credentials")` if none).
2. On **405**: re-login once, replace token, retry the original request **once**. Second 405 → `AuthFailed`.
   Re-login failure → `AuthFailed("login failed — password changed?")`.
3. On **404**: throw `NotFound` (GET state callers map it; SET callers treat as failure).
4. On **429**: throw `RateLimited` immediately — no retry (higher layers back off via WorkManager).
5. On **5xx whose body contains `M2M platform error` and `Http status: 404`**: throw `DeviceOffline`
   immediately — the cloud cannot reach the boiler's gateway (§7.5); a 5 s retry cannot fix that.
6. On any other non-2xx or IOException: sleep 5 s, retry **once**; then throw `Transient`.
7. 2xx with empty body → success (`Unit`); with body → decode with `Json { ignoreUnknownKeys = true }`.
8. A `Mutex` serializes login so concurrent 405s don't stampede.

Bare-boolean bodies: `"true"`/`"false"`.toRequestBody(`"application/json; charset=utf-8"`.toMediaType()).

---

## 3. `domain/` — pure Kotlin scheduling engine

**Zero Android imports. `java.time` only. No I/O, no clock reads — `now` is always a parameter,
`ZoneId` is constructor-injected (prod: `Europe/Belgrade` default, overridable from settings).**
This package is reusable by a future web client backend.

### 3.1 Files

`domain/Model.kt`, `domain/Overrides.kt`, `domain/Config.kt`, `domain/Resolution.kt`,
`domain/BoilerCommand.kt`, `domain/SchedulerEngine.kt`, `domain/DefaultSchedulerEngine.kt`,
`domain/HeatByTimeMath.kt`, `domain/RateEstimator.kt`
Tests: `test/.../domain/ResolverTest.kt`, `HeatByTimeMathTest.kt`, `PlanDiffTest.kt`, `RateEstimatorTest.kt`.

### 3.2 Frozen model types

```kotlin
package rs.novica.dobarboiler.domain

import java.time.*

data class HeatingWindow(val start: LocalTime, val end: LocalTime) {
    val crossesMidnight: Boolean get() = end <= start
    companion object { val DEFAULT = HeatingWindow(LocalTime.of(0, 30), LocalTime.of(7, 30)) }
}

data class Preset(
    val id: Long,
    val name: String,                  // unique, user-visible ("Sam", "Gosti")
    val targetTemp: Int,               // clamped 40..75 at construction
    val eco: Boolean = true,
    val windowOverride: HeatingWindow? = null,   // null = use EngineConfig.defaultWindow
)

data class WeeklyPlan(val assignments: Map<DayOfWeek, Long?>)  // presetId per day; null = no heating that night
// Day identity rule: the window belongs to the calendar date on which it STARTS.
// 00:30–07:30 on Saturday = Saturday's assignment (UI labels it "noć uoči subote" where helpful).

sealed interface OneTimeOverride {
    val id: Long
    val createdAt: Instant

    data class DayReplacement(
        override val id: Long, override val createdAt: Instant,
        val date: LocalDate,           // window-start date being replaced
        val presetId: Long?,           // null = skip heating that night
    ) : OneTimeOverride

    data class HeatByTime(
        override val id: Long, override val createdAt: Instant,
        val eventTime: Instant,        // absolute instant — DST-immune
        val targetTemp: Int,           // 40..75
        val eco: Boolean = false,      // speed by default
        val holdMinutes: Int = 60,     // maintain past eventTime; 0..180
    ) : OneTimeOverride
}

data class VacationHold(val id: Long, val startsAt: Instant, val resumeAt: Instant?) // null = until cancelled

data class BoostSession(
    val id: Long, val startedAt: Instant, val targetTemp: Int,
    val timeoutAt: Instant,            // startedAt + config.boostMaxDuration
    val completedAt: Instant? = null,
)

data class DeviceSnapshot(
    val at: Instant,
    val on: Boolean,
    val mode: Int,                     // raw 1|5|9
    val temp: Double,
    val reqTemp: Double,
    val antiLeg: Boolean,
    val heatReq: Boolean,
    val eco: Boolean,
    val remainingHeatTime: Duration?,  // parsed rmTm
    val availableShowers: Int?,
)
```

### 3.3 Frozen config types

```kotlin
package rs.novica.dobarboiler.domain

enum class OutsideWindowBehavior { OFF, KEEP_WARM }
enum class ManualChangePolicy { RESPECT_UNTIL_NEXT_TRANSITION, REASSERT }

data class BandConfig(
    val stopAt: Int,                   // e.g. 56
    val restartAt: Int,                // e.g. 52
    val pollEvery: Duration = Duration.ofMinutes(15),   // 10..30 min
) { init { require(stopAt >= restartAt + 2) { "band must be >= 2°C" } } }

sealed interface MaintainMode {
    data object Simple : MaintainMode
    data class Band(val band: BandConfig) : MaintainMode
}

data class HeatingRateConfig(
    val ratePerHourC: Double = 15.0,        // 80 L @ ~1.5 kW
    val ecoPenaltyFactor: Double = 0.75,
    val safetyMarginPercent: Int = 15,
    val maxLead: Duration = Duration.ofHours(6),
    val learnedRatePerHourC: Double? = null,      // eco=false EWMA, used when non-null
    val learnedEcoRatePerHourC: Double? = null,   // eco=true EWMA
) {
    fun effectiveRatePerHour(eco: Boolean): Double =
        if (eco) learnedEcoRatePerHourC ?: ((learnedRatePerHourC ?: ratePerHourC) * ecoPenaltyFactor)
        else learnedRatePerHourC ?: ratePerHourC
}

data class EngineConfig(
    val defaultWindow: HeatingWindow = HeatingWindow.DEFAULT,
    val outsideWindow: OutsideWindowBehavior = OutsideWindowBehavior.OFF,
    val keepWarmTemp: Int = 40,
    val maintainMode: MaintainMode = MaintainMode.Simple,
    val heatingRate: HeatingRateConfig = HeatingRateConfig(),
    val boostMaxDuration: Duration = Duration.ofHours(4),      // 1..6 h
    val manualChangePolicy: ManualChangePolicy = ManualChangePolicy.RESPECT_UNTIL_NEXT_TRANSITION,
    val midWindowCheck: Boolean = true,
    val maxSetpointGuard: Int? = null,   // live MedMaxSetpointTemperature; extra clamp when set
    val fallbackPreset: Preset = Preset(id = -1, name = "Podrazumevano", targetTemp = 55, eco = true),
)
```

### 3.4 Resolver — frozen contract and precedence

```kotlin
package rs.novica.dobarboiler.domain

import java.time.Instant

data class ScheduleInputs(
    val weeklyPlan: WeeklyPlan,
    val presets: Map<Long, Preset>,
    val overrides: List<OneTimeOverride>,   // non-expired only (data layer purges)
    val vacation: VacationHold?,            // active/future only
    val boost: BoostSession?,               // uncompleted only
    val lastSnapshot: DeviceSnapshot?,      // for HBT lead math
    val config: EngineConfig,
)

data class DesiredState(
    val powerOn: Boolean,
    val targetTemp: Int?,   // null when powerOn=false (never send temp commands then)
    val eco: Boolean?,      // null = don't touch eco
)

enum class StateSource { BOOST, HEAT_BY_TIME, VACATION, DAY_OVERRIDE, WEEKLY_PLAN, KEEP_WARM, DEFAULT_OFF }

enum class TransitionReason {
    WINDOW_START, WINDOW_END, HBT_START, HBT_END, HBT_REASSESS,
    VACATION_START, VACATION_RESUME, BOOST_TIMEOUT, OVERRIDE_EXPIRY, NONE
}

data class Transition(val at: Instant, val reason: TransitionReason)

data class Resolution(
    val desired: DesiredState,
    val source: StateSource,
    val nextTransition: Transition?,   // null = nothing ever scheduled; re-arm on any table change
)

interface SchedulerEngine {
    /** Pure. THE function. Deterministic for (now, inputs, zone). */
    fun resolve(now: Instant, inputs: ScheduleInputs): Resolution

    /** Pure. HBT lead math, exposed for UI preview ("grejanje kreće u ~19:40") and tests. */
    fun computedStart(override: OneTimeOverride.HeatByTime, inputs: ScheduleInputs, now: Instant): Instant

    /** Pure. Diff desired vs observed → minimal ordered command list. */
    fun plan(desired: DesiredState, observed: DeviceSnapshot?): List<BoilerCommand>
}

class DefaultSchedulerEngine(private val zone: java.time.ZoneId) : SchedulerEngine { /* impl */ }
```

**Precedence (evaluated top-down; first active rule wins):**

| # | Rule | Active when | DesiredState |
|---|------|-------------|--------------|
| 1 | Boost | uncompleted session, `now < timeoutAt` | on, boost.targetTemp, eco=false |
| 2 | Heat-by-time | any HBT with `computedStart <= now < eventTime + hold` (multiple: highest targetTemp wins; tie → later eventTime) | on, o.targetTemp, o.eco |
| 3 | Vacation | `startsAt <= now && (resumeAt == null \|\| now < resumeAt)` | off, null, null |
| 4 | Day override | DayReplacement for today's window-date AND now inside that window | presetId==null → off; else on, preset temp/eco |
| 5 | Weekly plan | now inside today's window AND `plan[dow] != null` | on, preset temp/eco |
| 6 | Default | otherwise | `outsideWindow==OFF` → off; `KEEP_WARM` → on, keepWarmTemp, eco=true |

Deliberate consequences: **HBT and Boost beat Vacation** (explicit present-tense human intent punches a hole
in the hold; UI surfaces "Zagrejaće se uprkos odmoru"). Missing preset id → `config.fallbackPreset` + the
caller logs a FAILED-grade entry (resolver itself never crashes). All temps clamped `40..min(75, maxSetpointGuard ?: 75)`.

**Window resolution for date D:** preset = dayOverride(D) ?: plan[D.dayOfWeek]; window = preset.windowOverride
?: config.defaultWindow; convert `LocalTime` → `Instant` via `ZonedDateTime` in `zone`
(DST gap → first valid instant after gap; overlap → earlier offset — heating starts sooner, never later).

**`nextTransition`:** min over all candidate boundary instants strictly after `now` that are relevant:
boost `timeoutAt`; each HBT's `computedStart`, `computedStart − 30 min` (as `HBT_REASSESS`, only if > now)
and `eventTime + hold`; vacation `startsAt`/`resumeAt`; window start/end for today and the next 8 days
(after applying overrides and per-preset windows); day-override expiry (== its window end). It is CORRECT
to return a boundary that produces zero commands — the Converger diff makes redundant wake-ups free.
Empty world → `null`.

### 3.5 `plan()` — frozen diff semantics

```kotlin
package rs.novica.dobarboiler.domain

sealed interface BoilerCommand {
    data class SetMode(val mode: Int) : BoilerCommand          // engine only ever emits SetMode(1)
    data class SetPower(val on: Boolean) : BoilerCommand
    data class SetTemperature(val temp: Int) : BoilerCommand   // pre-clamped
    data class SetEco(val eco: Boolean) : BoilerCommand
    data class SetAntilegionella(val on: Boolean) : BoilerCommand  // used only by vacation-return logic
}
```

Rules (ordered output):
- `observed == null` → full assert: `[SetMode(1) , SetPower , SetTemperature? , SetEco?]` (temp/eco only if non-null in desired and powerOn).
- desired off: `[SetPower(false)]` if `observed.on`, else `[]`. Never touch temp/eco/mode when turning off.
- desired on: emit in order — `SetMode(1)` if `observed.mode != 1`; `SetPower(true)` if `!observed.on`;
  `SetTemperature(t)` if `desired.targetTemp != null && observed.reqTemp.roundToInt() != t`;
  `SetEco(e)` if `desired.eco != null && observed.eco != e`.

### 3.6 Heat-by-time math (`HeatByTimeMath.kt`)

```kotlin
fun hbtStart(o: OneTimeOverride.HeatByTime, estimateTempC: Double, cfg: HeatingRateConfig): Instant {
    val deltaC = (o.targetTemp - estimateTempC).coerceAtLeast(0.0)
    val lead = Duration.ofSeconds((deltaC / cfg.effectiveRatePerHour(o.eco) * 3600.0).toLong())
    val padded = lead.multipliedBy((100L + cfg.safetyMarginPercent)).dividedBy(100L)
    return o.eventTime.minus(minOf(padded, cfg.maxLead))
}
```

`SchedulerEngine.computedStart` wraps `hbtStart` with the temp estimate policy:
- snapshot fresher than 2 h → use `snapshot.temp`;
- stale/absent → assume **30.0 °C** (pessimistic → starts early, never late).
- `deltaC <= 0` → start = `eventTime` (turn on only to hold; hold=0 ⇒ effectively no-op → log SKIPPED).
- If `computedStart <= now < eventTime+hold` → active NOW (late start; Converger logs and notifies only when
  projected shortfall > 5 °C: `shortfall = target − (estimate + rate × hoursUntilEvent)`).
- The `HBT_REASSESS` transition (start − 30 min) makes the Converger take one fresh poll and re-arm with the
  recomputed start.

### 3.7 Rate learning (`RateEstimator.kt`)

```kotlin
data class RateSample(val at: Instant, val ratePerHourC: Double, val eco: Boolean)

class RateEstimator {
    /** EWMA α=0.3 per eco flag; returns null until >= 5 samples for that flag. */
    fun learnedRate(samples: List<RateSample>, eco: Boolean): Double?

    /** Extract a sample from two snapshots if valid: both heatReq=true, gap in 20..90 min,
     *  same eco flag, temp rose >= 1.0 °C, power on in both. Null otherwise. */
    fun sampleFrom(a: DeviceSnapshot, b: DeviceSnapshot): RateSample?
}
```

The Converger/BoostService feed consecutive snapshots into `sampleFrom` and persist accepted samples;
`SettingsStore` exposes the resulting learned rates into `HeatingRateConfig`.

---

## 4. `data/` — persistence and repositories

### 4.1 Files

`data/db/AppDatabase.kt`, `data/db/Entities.kt`, `data/db/Daos.kt`, `data/db/Converters.kt`,
`data/CredentialStore.kt`, `data/SettingsStore.kt`, `data/ScheduleRepository.kt`,
`data/DeviceRepository.kt`, `data/ActionLogRepository.kt`, `data/Mappers.kt`

### 4.2 Room schema (database `dobarboiler.db`, version 1, `exportSchema = false`)

```
preset            (id INTEGER PK AUTOINCREMENT, name TEXT NOT NULL UNIQUE,
                   targetTemp INTEGER NOT NULL, eco INTEGER NOT NULL,
                   windowStart TEXT NULL, windowEnd TEXT NULL)          -- "HH:mm" or null
weekly_plan       (dayOfWeek INTEGER PK,        -- 1=MONDAY..7=SUNDAY (java.time DayOfWeek.value)
                   presetId INTEGER NULL REFERENCES preset(id) ON DELETE SET NULL)
day_override      (id INTEGER PK AUTOINCREMENT, date TEXT NOT NULL UNIQUE,  -- ISO yyyy-MM-dd; upsert on date
                   presetId INTEGER NULL, createdAt INTEGER NOT NULL)        -- epoch ms
heat_by_time      (id INTEGER PK AUTOINCREMENT, eventAt INTEGER NOT NULL,   -- epoch ms
                   targetTemp INTEGER NOT NULL, eco INTEGER NOT NULL,
                   holdMinutes INTEGER NOT NULL, createdAt INTEGER NOT NULL,
                   status TEXT NOT NULL DEFAULT 'ACTIVE')                    -- ACTIVE|EXPIRED|SKIPPED
vacation          (id INTEGER PK AUTOINCREMENT, startsAt INTEGER NOT NULL,
                   resumeAt INTEGER NULL, active INTEGER NOT NULL)           -- max 1 active row
boost_session     (id INTEGER PK AUTOINCREMENT, startedAt INTEGER NOT NULL,
                   targetTemp INTEGER NOT NULL, timeoutAt INTEGER NOT NULL,
                   completedAt INTEGER NULL, outcome TEXT NULL)              -- DONE|TIMEOUT|CANCELLED
action_log        (id INTEGER PK AUTOINCREMENT, at INTEGER NOT NULL,
                   kind TEXT NOT NULL, trig TEXT NOT NULL,                  -- see enums §7.2
                   detail TEXT NOT NULL, outcome TEXT NOT NULL,             -- SUCCESS|RETRYING|FAILED|SKIPPED
                   attempt INTEGER NOT NULL DEFAULT 1, stateJson TEXT NULL)
rate_sample       (id INTEGER PK AUTOINCREMENT, at INTEGER NOT NULL,
                   ratePerHourC REAL NOT NULL, eco INTEGER NOT NULL)
snapshot          (id INTEGER PK CHECK(id=1), at INTEGER NOT NULL, json TEXT NOT NULL) -- last DeviceSnapshot
```

Prune policy (WatchdogWorker calls repository methods): action_log > 90 days, rate_sample keep last 50 per
eco flag, expired day_override/heat_by_time rows > 7 days old.

### 4.3 CredentialStore (EncryptedSharedPreferences, file `creds`)

```kotlin
package rs.novica.dobarboiler.data

class CredentialStore(context: android.content.Context) : rs.novica.dobarboiler.api.CredentialProvider {
    override fun credentials(): Pair<String, String>?
    override fun token(): String?
    override fun storeToken(token: String?)
    fun storeCredentials(email: String, password: String)
    fun clear()                                  // logout (keeps the history-server URL/token)
    fun isLoggedIn(): Boolean
    var gwId: String?                            // selected device gateway id
    var deviceName: String?
    var historyServerUrl: String?                // history server (design 2026-10-18 §5.2), e.g. http://novica-dev/boiler/
    var historyServerToken: String?              // BOILER_API_TOKEN
}
```

### 4.4 SettingsStore (Preferences DataStore, file `settings`)

Exposes both a `Flow<AppSettings>` and a suspend snapshot read. Frozen surface:

```kotlin
package rs.novica.dobarboiler.data

import rs.novica.dobarboiler.domain.*
import java.time.Instant
import kotlinx.coroutines.flow.Flow

data class AppSettings(
    val engineConfig: EngineConfig,          // assembled from individual keys + learned rates
    val zoneId: String,                      // default "Europe/Belgrade"
    val reassertAfterMinutes: Int,           // used when policy == REASSERT (default 15)
)

/** Runtime state persisted outside Room (single-consumer, small, hot). */
data class RuntimeState(
    val nextTransitionAt: Instant?, val nextTransitionReason: String?,
    val lastAppliedStateJson: String?,       // DesiredState the app last asserted
    val lastAppliedAt: Instant?,
    val manualHoldUntil: Instant?,           // §7.4
    val lastCloudContactAt: Instant?,
    val deviceOfflineSince: Instant? = null, // §7.5 — first M2M 404 of the current outage (key rt_device_offline_since)
)

class SettingsStore(context: android.content.Context) {
    val settings: Flow<AppSettings>
    suspend fun settingsSnapshot(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)

    val runtime: Flow<RuntimeState>
    suspend fun runtimeSnapshot(): RuntimeState
    suspend fun updateRuntime(transform: (RuntimeState) -> RuntimeState)

    suspend fun setLearnedRates(full: Double?, eco: Double?)
    suspend fun setMaxSetpointGuard(value: Int?)     // from live plantSettings
}
```

### 4.5 ScheduleRepository

```kotlin
package rs.novica.dobarboiler.data

import rs.novica.dobarboiler.domain.*
import kotlinx.coroutines.flow.Flow
import java.time.*

class ScheduleRepository(db: AppDatabase, settings: SettingsStore, zone: () -> ZoneId) {
    /** Assemble everything the resolver needs; also purges expired overrides
     *  (logging OVERRIDE_EXPIRED via returned list). */
    suspend fun scheduleInputs(now: Instant): ScheduleInputs
    /** Emits whenever any input table or setting changes — UI + re-arm trigger. */
    fun inputsChanged(): Flow<Unit>

    // Presets
    fun presets(): Flow<List<Preset>>
    suspend fun upsertPreset(p: Preset): Long
    suspend fun deletePreset(id: Long): Boolean      // false if referenced by plan (UI must reassign first)
    suspend fun presetInUse(id: Long): Boolean

    // Weekly plan
    fun weeklyPlan(): Flow<WeeklyPlan>
    suspend fun assign(day: DayOfWeek, presetId: Long?)

    // Overrides
    fun activeOverrides(now: Instant): Flow<List<OneTimeOverride>>
    suspend fun upsertDayOverride(date: LocalDate, presetId: Long?)
    suspend fun addHeatByTime(eventTime: Instant, targetTemp: Int, eco: Boolean, holdMinutes: Int): Long
    suspend fun cancelOverride(id: Long, day: Boolean)   // day=true → day_override table

    // Vacation
    fun vacation(): Flow<VacationHold?>
    suspend fun startVacation(resumeAt: Instant?): Long
    suspend fun cancelVacation()

    // Boost
    fun activeBoost(): Flow<BoostSession?>
    suspend fun startBoost(targetTemp: Int, timeoutAt: Instant): Long   // completes any previous first (CANCELLED)
    suspend fun completeBoost(id: Long, outcome: String)                // DONE|TIMEOUT|CANCELLED

    // Rate samples
    suspend fun addRateSample(s: RateSample)
    suspend fun rateSamples(): List<RateSample>
}
```

### 4.6 DeviceRepository

```kotlin
package rs.novica.dobarboiler.data

import rs.novica.dobarboiler.api.*
import rs.novica.dobarboiler.domain.*
import kotlinx.coroutines.flow.StateFlow
import java.time.*

class DeviceRepository(
    private val api: AristonApiClient,
    private val creds: CredentialStore,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val clock: Clock,
) {
    /** Last known snapshot (persisted; survives restart). */
    val snapshot: StateFlow<DeviceSnapshot?>

    /** Poll cloud state. Rate-limited: if last successful poll < minInterval ago,
     *  returns the cached snapshot without a network call. Updates lastCloudContactAt. */
    suspend fun refresh(minInterval: Duration = Duration.ofSeconds(30)): DeviceSnapshot

    /** Execute commands in order; maps BoilerCommand → api calls; throws AristonException
     *  on first failure (caller retries whole batch — commands are idempotent). */
    suspend fun execute(gwId: String, commands: List<BoilerCommand>)

    suspend fun plantSettings(): MedPlantSettings           // also refreshes maxSetpointGuard in SettingsStore
    suspend fun applyMaxSetpoint(newTemp: Int)              // read-modify-write old/new
    suspend fun triggerAntilegionella(enabled: Boolean)     // read-modify-write old/new
}
```

`refresh` parses `rmTm` ("HH:MM:SS") → `Duration`; persists snapshot row; feeds `RateEstimator.sampleFrom`
with the previous snapshot and stores accepted samples + recomputed EWMAs.

### 4.7 ActionLogRepository

```kotlin
package rs.novica.dobarboiler.data

class ActionLogRepository(db: AppDatabase) {
    suspend fun log(kind: String, trig: String, detail: String, outcome: String,
                    attempt: Int = 1, stateJson: String? = null)
    fun recent(limit: Int = 300): kotlinx.coroutines.flow.Flow<List<ActionLogEntity>>
    suspend fun prune(olderThanDays: Int = 90)
}
```

(Kind/trigger string constants are defined in `scheduling/Converger.kt` — see §7.2 — and passed as strings
so `data/` stays enum-agnostic.)

---

## 5. `notifications/` — channels and Notifier

Files: `notifications/Channels.kt`, `notifications/Notifier.kt`.

Channels (created idempotently in `App.onCreate` via `Channels.ensure(context)`):

| id | name | importance |
|---|---|---|
| `greske` | "Greške" | HIGH |
| `boost` | "Boost" | LOW (ongoing) — completion posted on `dogadjaji`-style DEFAULT importance within same channel |
| `dogadjaji` | "Događaji" | LOW, silent |

```kotlin
package rs.novica.dobarboiler.notifications

object Channels { const val ERRORS = "greske"; const val BOOST = "boost"; const val EVENTS = "dogadjaji"
                  fun ensure(context: android.content.Context) }

class Notifier(private val context: android.content.Context) {
    fun actionFailed(detail: String)                                  // ERRORS, id 1001
    fun permissionProblem(text: String)                               // ERRORS, id 1002
    fun authFailed()                                                  // ERRORS, id 1003 "Prijava nije uspela…"
    fun manualChangeDetected(untilLabel: String)                      // EVENTS, id 2001
    fun scheduledEvent(text: String)                                  // EVENTS, id 2002 (window ok, vacation resumed, override expired)
    fun hbtLate(shortfallC: Int)                                      // ERRORS, id 1004
    fun deviceOffline(sinceLabel: String)                             // ERRORS, id 1005, ongoing, only-alert-once (§7.5)
    fun deviceOnline(outageLabel: String)                             // cancels 1005; EVENTS, id 2003
    fun boostOngoing(currentTemp: Double, target: Int, etaMinutes: Long?): android.app.Notification // FGS notif, id 3001
    fun boostDone(target: Int)                                        // BOOST, id 3002
    fun boostTimeout(currentTemp: Double)                             // BOOST, id 3003
    fun cancelBoost()
}
```

All notification texts come from `strings.xml` (§10). Tapping any notification opens `MainActivity`
(history tab for failures).

---

## 6. `scheduling/` — alarms, workers, boost service

### 6.1 AlarmScheduler

```kotlin
package rs.novica.dobarboiler.scheduling

import java.time.Instant

class AlarmScheduler(private val context: android.content.Context) {
    companion object {
        const val ACTION_TRANSITION = "rs.novica.dobarboiler.ACTION_TRANSITION"
        const val ACTION_BAND_TICK = "rs.novica.dobarboiler.ACTION_BAND_TICK"
        const val ACTION_BOOST_TIMEOUT = "rs.novica.dobarboiler.ACTION_BOOST_TIMEOUT"
        const val RC_TRANSITION = 1; const val RC_BAND = 2; const val RC_BOOST = 3
    }
    fun canScheduleExact(): Boolean
    /** setExactAndAllowWhileIdle; fixed requestCode per action => re-arming atomically replaces. */
    fun armTransition(at: Instant)
    fun armBandTick(at: Instant)
    fun armBoostTimeout(at: Instant)
    fun cancelBandTick(); fun cancelBoostTimeout(); fun cancelTransition()
}
```

- **`setExactAndAllowWhileIdle` for everything.** `setAlarmClock` rejected (status-bar icon, full wake, no benefit).
- Exactly ONE transition alarm at any time (`RC_TRANSITION`, `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`).
- If `canScheduleExact()` is false, fall back to `setAndAllowWhileIdle` (inexact) AND surface a health issue;
  the 6 h watchdog bounds the error.

### 6.2 Converger — the single convergence routine

```kotlin
package rs.novica.dobarboiler.scheduling

import rs.novica.dobarboiler.data.*
import rs.novica.dobarboiler.domain.*
import rs.novica.dobarboiler.notifications.Notifier
import java.time.Clock

object LogKinds {   // action_log.kind values
    const val WINDOW_START="WINDOW_START"; const val WINDOW_END="WINDOW_END"
    const val HBT_START="HBT_START"; const val HBT_END="HBT_END"
    const val BOOST_START="BOOST_START"; const val BOOST_COMPLETE="BOOST_COMPLETE"; const val BOOST_TIMEOUT="BOOST_TIMEOUT"
    const val VACATION_START="VACATION_START"; const val VACATION_RESUME="VACATION_RESUME"
    const val OVERRIDE_EXPIRED="OVERRIDE_EXPIRED"; const val MANUAL_CHANGE="MANUAL_CHANGE_DETECTED"
    const val REASSERT="REASSERT"; const val VERIFY="VERIFY_POLL"; const val COMMAND_FAILED="COMMAND_FAILED"
    const val DRIFT="DRIFT_CORRECTED"; const val CONVERGE="CONVERGE"
    const val DEVICE_OFFLINE="DEVICE_OFFLINE"; const val DEVICE_ONLINE="DEVICE_ONLINE"   // §7.5, no state_json
}
object Triggers { const val ALARM="ALARM"; const val BOOT="BOOT"; const val WATCHDOG="WATCHDOG"
                  const val USER="USER"; const val VERIFY="VERIFY"; const val BAND="BAND"; const val BOOST="BOOST" }

sealed interface ConvergeOutcome {
    data class Converged(val commandsSent: Int) : ConvergeOutcome
    data class Skipped(val why: String) : ConvergeOutcome        // e.g. manual hold, no gwId
    data class Failed(val error: Throwable) : ConvergeOutcome    // caller (worker) maps to Result.retry()
}

class Converger(
    private val engine: SchedulerEngine,
    private val schedule: ScheduleRepository,
    private val device: DeviceRepository,
    private val settings: SettingsStore,
    private val creds: CredentialStore,
    private val log: ActionLogRepository,
    private val alarms: AlarmScheduler,
    private val notifier: Notifier,
    private val clock: Clock,
) {
    /** The whole app's heartbeat. See algorithm below. Safe to call from anywhere, any time. */
    suspend fun converge(trigger: String, pollFresh: Boolean = true): ConvergeOutcome
}
```

`converge` algorithm (frozen):
1. `gwId = creds.gwId ?: return Skipped("not onboarded")`.
2. `inputs = schedule.scheduleInputs(now)`; `res = engine.resolve(now, inputs)`.
3. Snapshot: if `pollFresh` → `device.refresh(30s)` else use cached; failures here → `Failed`.
4. **Manual-change detection:** let `applied` = runtime `lastAppliedState`. If `applied != null` and the
   snapshot contradicts it (`on`, `reqTemp` (±0.5), or `eco` differ) and `snapshot.at > lastAppliedAt + 3 min`
   (grace for slow bus) and no command was sent since: this is external. Policy RESPECT → set
   `manualHoldUntil = res.nextTransition?.at`, log MANUAL_CHANGE, notify, **skip step 5**. Policy REASSERT →
   proceed (log REASSERT). A transition-alarm trigger (`Triggers.ALARM`) always **clears `manualHoldUntil`
   first** — scheduled transitions outrank manual holds by definition.
5. If `manualHoldUntil != null && now < manualHoldUntil` → skip commands. Else
   `cmds = engine.plan(res.desired, snapshot)`; if non-empty → `device.execute(gwId, cmds)`, update
   `lastAppliedState/At`, log (kind chosen from `res.source` + transition reason), enqueue `VerifyWorker`
   (delay 90 s).
6. Persist `nextTransitionAt/Reason`; `alarms.armTransition(res.nextTransition.at)` (or cancel if null).
   If `config.maintainMode is Band` and desired is a WEEKLY_PLAN/DAY_OVERRIDE "on" state → also
   `armBandTick(now + band.pollEvery)`, else `cancelBandTick()`.
7. Mid-window check: when arming a WINDOW_START→WINDOW_END span and `config.midWindowCheck`, the transition
   for the window end is armed normally; the midpoint check is realized by arming `armBandTick(midpoint)`
   even in Simple mode (BandWorker with no band config = verify-only poll).
8. Return `Converged(cmds.size)`.

### 6.3 Receivers

```kotlin
class AlarmReceiver : android.content.BroadcastReceiver()      // zero I/O; enqueues work
class BootstrapReceiver : android.content.BroadcastReceiver()  // BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED, SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
```

`AlarmReceiver.onReceive`: map action → enqueue unique expedited work:
- `ACTION_TRANSITION` → `TransitionWorker` (unique name `"transition"`, REPLACE)
- `ACTION_BAND_TICK` → `BandWorker` (unique `"band"`, REPLACE)
- `ACTION_BOOST_TIMEOUT` → `BoostTimeoutWorker` (unique `"boostTimeout"`, REPLACE)

All requests: `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)`,
`Constraints(requiredNetworkType = CONNECTED)`, `BackoffCriteria(EXPONENTIAL, 30 s)`.
`BootstrapReceiver` enqueues `RescheduleWorker` for all five actions.

**Why worker, not `goAsync()`:** ~10 s receiver budget vs login-refresh + slow-bus round trips; WorkManager
gives persistence, network constraint and backoff for free. **Why no FGS for transitions:** background-FGS
caps and no added value.

### 6.4 Workers (all `CoroutineWorker`, get `AppContainer` via `applicationContext as App`)

| Worker | Behavior |
|---|---|
| `TransitionWorker` | `converge(Triggers.ALARM)`. `Failed` → if `runAttemptCount < 7` → `Result.retry()` (backoff 30s→1m→2m→4m→8m→16m→30m ≈ up to ~1 h); else `Result.failure()` + `notifier.actionFailed(...)` + FAILED log. Special case: if the failed desired state is OFF (window end — leaking money), also re-enqueue a fresh delayed retry chain every 30 min until superseded. `RateLimited` errors use `Result.retry()` too (backoff IS the 429 etiquette). |
| `VerifyWorker` | delay 90 s, non-expedited. `device.refresh(Duration.ZERO)`; compare against `lastAppliedState`: match → log VERIFY/SUCCESS; mismatch → one re-`converge(Triggers.VERIFY)`; if still mismatched → FAILED log + `actionFailed`. |
| `RescheduleWorker` | `converge(Triggers.BOOT, pollFresh = true)`. Covers reboot, update, clock/zone change, exact-alarm permission regrant. Vacation return survives reboot because it's a Room row + re-armed alarm. |
| `WatchdogWorker` | periodic 6 h, unique `"watchdog"`, KEEP, requires network. Checks: transition alarm armed & `nextTransitionAt` in future (else `converge(WATCHDOG)`); `canScheduleExact()`; notification permission; prunes logs/samples; if `nextTransitionAt < now` → missed alarm → converge. |
| `BandWorker` | advanced mode tick: `device.refresh(ZERO)`; if Band config active and desired source is window: `temp >= stopAt` → `SetPower(false)`; `temp <= restartAt` → `SetPower(true)`; then re-arm `armBandTick(now + pollEvery)` while still inside window. In Simple mode (mid-window check) it only polls + manual-change detection via `converge(Triggers.BAND, pollFresh=false)`-style verify. |
| `ServerSyncWorker` | History-server upload (design `docs/superpowers/specs/2026-10-18-boiler-history-mcp-ux-design.md` §5.1). One-time unique `"server_sync"` (REPLACE, 15 s delay, network) enqueued after every `converge()` outside the mutex, plus periodic 1 h `"server_sync_periodic"` (KEEP). Runs `ServerSync.run()`: `action_log` rows with `id > lastUploadedActionId` in batches of 200 to `POST api/v1/app/actions` (mark advances only after 2xx; the server is idempotent on `(install_id, local_id)`), then the latest intent (`Converger` stores `IntentCodec.encode(res, now)` via `SettingsStore.recordIntent`, deduplicated without its timestamp) to `POST api/v1/app/intent`. No server configured → no-op. Failures: `Result.retry()` up to 5 attempts, never a notification and never an `action_log` row; shown only under Settings → Server istorije. Sync state lives in DataStore keys `rt_install_id`, `rt_last_uploaded_action_id`, `rt_last_intent_json`, `rt_last_intent_uploaded_json`, `rt_last_sync_ok_at`, `rt_last_sync_error` (no Room change). |
| `LinkCheckWorker` | §7.5. Periodic 15 min, unique `"link_check"` (KEEP, network), plus one-time `"link_confirm"` (KEEP, 5 min delay) enqueued when an outage starts. `device.refresh(5 min)` while online, `refresh(ZERO)` during an outage. `DeviceOffline` → `linkMonitor.reportOffline(WATCHDOG)`; success that ends an outage → `converge(WATCHDOG)`; any other error is ignored (next period). Always `Result.success()`. |
| `BoostTimeoutWorker` | if active boost uncompleted → `schedule.completeBoost(id, "TIMEOUT")`, `converge(Triggers.BOOST)`, `notifier.boostTimeout(temp)`. Guards against a dead BoostService. |

### 6.5 BoostService (foreground, `specialUse`)

```kotlin
class BoostService : android.app.Service() {
    companion object {
        fun start(context: android.content.Context, targetTemp: Int)  // called from UI only (foreground)
        fun cancel(context: android.content.Context)
    }
}
```

Flow: `startBoost` row (timeout = now + `boostMaxDuration`) → `armBoostTimeout` → `converge(Triggers.BOOST)`
(asserts on/temp/eco=false) → `startForeground(3001, notifier.boostOngoing(...))` → loop: poll every **90 s**
(`device.refresh(ZERO)`), update notification ("Boost: 47°/60° — još ~52 min", ETA from learned rate,
`rmTm` shown when plausible). Completion when `temp >= target - 0.5` OR
(`!heatReq && reqTemp >= target && temp >= target - 2`): `completeBoost(DONE)` → `converge(Triggers.BOOST)`
(reverts to planner state) → `boostDone` notification → `cancelBoostTimeout()` → `stopSelf()`.
User cancel → `completeBoost(CANCELLED)` → same revert path. Starting a boost while active = cancel-then-start.
Manifest: `foregroundServiceType="specialUse"` + property `PROPERTY_SPECIAL_USE_FGS_SUBTYPE =
"boiler-boost-temperature-monitor"`. (`dataSync` rejected: 6 h/24 h caps, wrong semantics.)

### 6.6 Anti-legionella on vacation return

When a vacation with `resumeAt != null` and span > 7 days is created, UI offers (default ON) a hygiene cycle:
implemented as `Converger` special-case — at `VACATION_RESUME` transition, after asserting planner state,
call `device.triggerAntilegionella(true)` once and log it. No extra alarm needed.

---

## 7. Reliability policies (cross-cutting, binding)

### 7.1 Permissions & manifest (owned by scaffold)

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32"/>
<uses-permission android:name="android.permission.USE_EXACT_ALARM"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>
```

`USE_EXACT_ALARM` (API 33+) is install-granted and irrevocable — correct for a sideloaded personal app.
Runtime check `canScheduleExactAlarms()` still performed (covers API 31–32) → health card + notification.
Battery-optimization exemption requested once during onboarding (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`);
status shown in Settings → Pouzdanost. Manifest components: `.ui.MainActivity` (exported, launcher),
`.scheduling.AlarmReceiver` (exported=false, no filter), `.scheduling.BootstrapReceiver` (exported=false,
intent-filter with the 5 actions §6.3), `.scheduling.BoostService` (exported=false, specialUse + property).

### 7.2 Retry summary

| Layer | Policy |
|---|---|
| HTTP (api/) | 405 → re-login + 1 retry; 404 → NotFound; 429 → fail fast; other → 5 s sleep + 1 retry |
| Worker | exponential backoff from 30 s, up to 7 attempts (~1 h span), then FAILED + notification |
| Window-end OFF failure | extra 30-min retry chain until success or next transition supersedes; notify after 30 min |
| Watchdog | 6 h periodic re-convergence — nothing stays broken longer than 6 h |
| Boiler without power | `DeviceOffline` is retried like any failure, but the final `actionFailed` is skipped — the §7.5 alert covers it |

### 7.3 Time semantics

Window times are `LocalTime` in the configured zone; converted per-date at resolve time (never precomputed
beyond the single armed alarm). DST gap → first instant after gap; overlap → earlier offset. `HeatByTime`,
`VacationHold`, `BoostSession` use `Instant` — immune. `TIME_SET`/`TIMEZONE_CHANGED` → full reconverge.

### 7.4 Manual buttons (req 8)

Default `RESPECT_UNTIL_NEXT_TRANSITION` via `manualHoldUntil` (§6.2 step 4). Detected only opportunistically
(verify polls, mid-window/band polls, boost polls, app-open refresh) — simple mode does not poll all night.
Dashboard shows "Ručna promena — važi do %s" + button "Vrati na plan" (clears the hold, converges with
`Triggers.USER`). `REASSERT` policy re-applies desired state on detection.

### 7.5 Boiler without power (offline alert)

The boiler's power switch sometimes trips and must be flipped by hand. The cloud then answers every device
call with `500 ":-( M2M platform error from <gw> (Http status: 404 - Not Found)"` (observed for 48 h,
2026-10-27..29), which the client maps to `DeviceOffline` (§2.3).

- `scheduling/DeviceLinkMonitor` keeps `RuntimeState.deviceOfflineSince`; the pure `domain/DeviceLink`
  decides. First observation → start the clock, log `DEVICE_OFFLINE`/RETRYING, enqueue the confirm check.
  Observation ≥ `DeviceLink.CONFIRM_AFTER` (3 min) after the start → post `Notifier.deviceOffline`
  (an isolated M2M 404 happened once on 2026-10-25 with the boiler fine, hence the confirmation).
- The alert is ongoing, HIGH, not auto-cancelled, only-alert-once, and re-posted on every observation
  (`LinkCheckWorker` every 15 min), so "Obriši sve" never clears it and a swipe (allowed on Android 14+)
  only hides it until the next check. The dashboard shows the same text as a card with "Proveri ponovo".
- A successful read whose snapshot is newer than `deviceOfflineSince` ends the outage: clear the field
  and `lastAppliedState*` (post-outage state is drift, not a manual change), log `DEVICE_ONLINE`/SUCCESS with the duration, cancel id 1005, post a silent event, and converge (the
  Converger is converging already; `LinkCheckWorker` and the dashboard refresh call it) so the plan is
  re-asserted on a boiler that just got power back.
- Reporters: Converger (state poll and command batch), `LinkCheckWorker`, dashboard pull-to-refresh.
  Workers that give up because of `DeviceOffline` log FAILED but do not post `actionFailed`.

---

## 8. `ui/` — screens (Compose Material3, Serbian latinica)

Navigation: `NavHost` + bottom `NavigationBar` with 4 items: Početna, Planer, Preseti, Još.
Start destination: `login` if `!creds.isLoggedIn()`, else `dashboard`. Sheets/dialogs are
bottom-sheet/dialog destinations layered over Dashboard (≤3 taps for any override).

ViewModels are plain `ViewModel` subclasses constructed via `viewModelFactory` reading
`(LocalContext.current.applicationContext as App).container`. Every mutation that changes schedule inputs
ends with `container.converger.converge(Triggers.USER)` launched on `Dispatchers.IO` (fire-and-forget +
snackbar on failure).

| Route | Screen / ViewModel | UiState (StateFlow) |
|---|---|---|
| `login` | `LoginScreen` / `LoginViewModel` | `{email, password, isLoading, error, devices: List<VelisPlant>, step}` — login → `listVelisPlants()` → filter `sys==4`; if 1 device auto-select; onboarding steps: device → window (default 00:30–07:30) → permissions (notifications runtime request, exact-alarm check, battery exemption) |
| `dashboard` | `DashboardScreen` / `DashboardViewModel` | `{snapshot: DeviceSnapshot?, lastSyncAgo, activeSource: StateSource, sourceLabel, nextEventLabel, healthIssues: List<HealthIssue>, isRefreshing, manualHold: Boolean}` |
| sheet `boost` | `BoostSheet` / `BoostViewModel` | `{targetTemp, etaLabel, revertLabel, boostActive, currentTemp}` — Pokreni → `BoostService.start` |
| dialog `vacation` | `VacationDialog` / `VacationViewModel` | `{returnMode: PLANNER/DATETIME/MANUAL, returnDateTime, antiLegOnReturn, isSaving}` — "Ugasi" NEVER blindly off |
| sheet `override/day` | `DayOverrideSheet` / `DayOverrideViewModel` | `{next7Days: List<DayItem(date, plannerPresetName, selected)>, presets, replacement: Long?/SKIP, isSaving}` |
| sheet `override/time` | `HeatByTimeSheet` / `HeatByTimeViewModel` | `{targetTime, dayToggle(danas/sutra auto), targetTemp, estimatedStartLabel (live via engine.computedStart), isSaving}` |
| `planner` | `PlannerScreen` / `PlannerViewModel` | `{days: List<DayAssignment(dow, presetName?, overrideBadge?)>, presets}` + "Primeni na radne dane / vikend" |
| `presets`, `presets/edit/{id?}` | `PresetsScreen`, `PresetEditScreen` / VMs | list + edit `{name, temp, eco, windowOverride?, validationError}`; delete blocked while in plan |
| `overrides` | `OverridesScreen` / `OverridesViewModel` | `{active, upcoming, expiredRecent}` with cancel actions |
| `history` | `HistoryScreen` / `HistoryViewModel` | `Flow<List<HistoryEntry>>` grouped by day, icons ✓/⚠/✕ |
| `settings` (+ `settings/window`, `settings/advanced`, `settings/account`, `settings/health`, `settings/server` → `SettingsServerScreen` / `ServerSettingsViewModel`: URL, token, test connection, upload status) | `SettingsScreen` / `SettingsViewModel` | `{windowStart, windowEnd, outsideWindow, keepWarmTemp, bandEnabled, stopAt, restartAt, bandPollMinutes, heatRate, learnedRate?, learnedEcoRate?, safetyMarginPercent, boostMaxHours, manualPolicy, reassertMinutes, midWindowCheck, maxSetpoint, accountEmail, healthIssues, zoneId}` |

Dashboard composition (child composables, state hoisted from `DashboardViewModel`):
`PermissionsHealthCard` (only when issues; errorContainer), `TempDialSection` (big circle: `temp` large,
`/ reqTemp` below, ring = progress 40..75; label Greje/Održava/Isključen from `heatReq`/`on`; boost tint),
chips row (`avShw` tuševa, `rmTm` "još HH:mm", Eco badge), `ActiveSourceCard` (one line: why + what's next;
tap navigates to the source), `QuickActionsRow` (Boost / Ugasi / Temp stepper = mini HBT-style bump valid
until next transition — implemented as `DayReplacement`-free direct temp override: a `HeatByTime` with
`eventTime = nextTransition.at`, `holdMinutes = 0`, eco untouched), `+ Jednokratno…` entry.
Pull-to-refresh honors 30 s min interval (button disabled with countdown). "Ažurirano pre 42 s" caption.

`HealthIssue` enum (ui-local): `EXACT_ALARMS_OFF`, `BATTERY_OPTIMIZED`, `NOTIFICATIONS_BLOCKED`,
`CLOUD_STALE_24H` — each with a Popravi intent (`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`,
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, app notification settings, none).

Theme: Material3, dynamic color on API 31+ with fallback warm palette (seed `#D75A28` boiler-orange),
dark + light. Typography defaults; `displayLarge` for the dial temperature.

---

## 9. Serbian strings (`res/values/strings.xml`, owned by scaffold)

The complete table from the UX design is binding. Key list (values exactly as below):

| key | value |
|---|---|
| app_name | DobarBoiler |
| nav_home / nav_planner / nav_presets / nav_more | Početna / Planer / Preseti / Još |
| dash_heating / dash_maintaining / dash_off | Greje / Održava temperaturu / Isključen |
| dash_target | Cilj: %1$d °C |
| dash_showers | %1$d tuša |
| dash_time_left | još %1$s |
| dash_eco | Eco |
| dash_updated_ago | Ažurirano pre %1$s |
| source_planner | Planer: %1$s — „%2$s" %3$d °C |
| source_override_until | Override do %1$s: %2$d °C |
| source_vacation | Odmor do %1$s — isključen |
| source_vacation_manual | Odmor — isključen dok ga ne upališ |
| source_boost | Boost do %1$d °C — u toku |
| source_manual | Ručna promena — važi do %1$s |
| source_keep_warm | Održavanje %1$d °C (van prozora) |
| source_off | Isključen do sledećeg prozora |
| source_next_window | Sledeće: grejanje %1$s–%2$s |
| action_boost / action_power_off / action_temp_bump / action_one_time | Boost / Ugasi / Temp / Jednokratno… |
| action_return_to_plan / action_cancel_boost / action_cancel_vacation | Vrati na plan / Prekini boost / Prekini odmor |
| ot_title | Jednokratna izmena |
| ot_replace_days | Zameni dan(e) |
| ot_replace_days_sub | „ovaj vikend ne diži" |
| ot_hot_by | Toplo u... |
| ot_hot_by_sub | „danas u 21h hoću 60 °C" |
| ot_instead_of_planner | Umesto planera važi: |
| ot_skip_night | Preskoči (bez grejanja) |
| ot_auto_resume_note | Posle izabranih dana planer se sam nastavlja. |
| ot_start_estimate | Grejanje počinje oko %1$s (procena: %2$d °C/h, margina %3$d min). |
| ot_revert_note | Posle %1$s vraćam se na planer. |
| ot_today / ot_tomorrow | Danas / Sutra |
| ot_despite_vacation | Zagrejaće se uprkos odmoru. |
| btn_save / btn_schedule / btn_start / btn_cancel / btn_fix / btn_apply | Sačuvaj / Zakaži / Pokreni / Otkaži / Popravi / Primeni |
| vac_title | Isključi bojler |
| vac_when_back | Kada da se ponovo uključi? |
| vac_by_planner | Po planeru (sledeći prozor) |
| vac_pick_datetime | Izaberi datum i vreme… |
| vac_manual_only | Dok ga ja ne upalim |
| vac_antileg | Antibakterijski ciklus po povratku (preporučeno za duže od 7 dana) |
| vac_reboot_note | Uključenje je zapamćeno i preživljava restart telefona. |
| vac_do_off | Isključi |
| boost_title | Boost |
| boost_eco_note | Eco se privremeno isključuje (maksimalna snaga). |
| boost_eta | Procena: gotovo za ~%1$s |
| boost_revert_to | Kad stigne do cilja, vraćam na: %1$s |
| boost_notif | Boost: %1$d°/%2$d° — još ~%3$s |
| preset_title / preset_new / preset_name | Preseti / Novi preset / Naziv |
| preset_temp | Ciljna temperatura |
| preset_eco | Eco režim (jeftinije, sporije grejanje) |
| preset_window_override | Poseban prozor grejanja za ovaj preset |
| preset_in_use_error | Preset je u planeru — prvo izaberi zamenu. |
| planner_title / planner_weekdays / planner_weekend | Nedeljni planer / Radni dani / Vikend |
| planner_override_badge | Jednokratno: %1$s |
| planner_no_heating | Bez grejanja |
| day_mon..day_sun | Ponedeljak / Utorak / Sreda / Četvrtak / Petak / Subota / Nedelja |
| settings_title | Podešavanja |
| set_window | Prozor grejanja |
| set_window_start | Početak (jeftina struja + margina) |
| set_window_end | Kraj |
| set_outside_window | Van prozora |
| set_outside_off | Bojler isključen (podrazumevano) |
| set_outside_keep | Održavaj %1$d °C i preko dana (skuplja struja!) |
| set_advanced | Napredno |
| set_hysteresis | Napredno održavanje (histerezis u aplikaciji) |
| set_hysteresis_note | Podrazumevano bojler sam drži temperaturu. Uključi samo ako želiš širu traku — više komandi i potrošnje baterije. |
| set_stop_at | Stani na %1$d °C |
| set_restart_at | Ponovo grej od %1$d °C |
| set_heat_rate | Brzina grejanja |
| set_heat_rate_value | %1$d °C na sat |
| set_heat_rate_learned | Izmereno iz istorije: %1$.1f °C/h |
| set_manual_policy | Ručne promene na bojleru |
| set_manual_respect | Poštuj do sledećeg rasporeda (podrazumevano) |
| set_manual_reassert | Vrati na plan odmah |
| set_midwindow_check | Provera u sred noćnog prozora |
| set_max_setpoint | Maksimalna temperatura (bojler) |
| set_boost_timeout | Maksimalno trajanje boosta |
| set_account / set_health | Nalog / Pouzdanost |
| set_logout | Odjavi se |
| health_bad_title | Pouzdanost ugrožena |
| health_exact_alarms | Tačni alarmi |
| health_battery | Optimizacija baterije |
| health_notifications | Notifikacije |
| health_ok | Sve je u redu — automatika radi pouzdano. |
| health_stale | Nema kontakta sa cloud-om od %1$s |
| history_title | Istorija |
| hist_heat_on | Uključeno grejanje — %1$s %2$d °C |
| hist_heat_off | Isključeno grejanje |
| hist_retry | Ponovni pokušaj (%1$s) |
| hist_failed | Neuspešno — %1$s |
| hist_manual_detected | Otkrivena ručna promena na bojleru |
| hist_boost_done | Boost završen — %1$d °C |
| login_title | Prijava na Ariston nalog |
| login_email / login_password | Imejl / Lozinka |
| login_error | Prijava nije uspela. Proveri podatke. |
| onb_pick_device | Izaberi uređaj |
| onb_window_title | Kada je struja jeftina? |
| onb_permissions_title | Dozvole za pouzdan rad |
| onb_battery_note | Isključi optimizaciju baterije da bi noćno grejanje radilo dok telefon spava. |
| onb_oem_note | Na Samsung/Xiaomi telefonima ukloni DobarBoiler iz „aplikacija koje spavaju". |
| notif_ch_errors / notif_ch_boost / notif_ch_events | Greške / Boost / Događaji |
| notif_heat_started | Grejanje počelo (%1$s, cilj %2$d °C) |
| notif_heat_stopped | Grejanje završeno — bojler isključen |
| notif_action_failed | Zakazana akcija nije uspela: %1$s. Otvori aplikaciju. |
| notif_boost_done | Boost gotov — %1$d °C dostignuto |
| notif_boost_timeout | Boost prekinut posle %1$s — voda je na %2$d °C |
| notif_vacation_resumed | Bojler ponovo uključen (kraj odmora) |
| notif_manual_change | Ručna promena na bojleru — važi do %1$s |
| notif_hbt_late | Kasni start grejanja — voda možda neće stići do cilja (%1$d °C manjka) |
| notif_alarms_off | Tačni alarmi su isključeni — raspored neće raditi. |
| notif_device_offline_title | Bojler je van mreže |
| notif_device_offline | Bojler se ne javlja od %1$s — verovatno je isključen iz struje. Proveri prekidač. |
| notif_device_online | Bojler je ponovo na vezi (bio van mreže %1$s) |
| btn_check_again | Proveri ponovo |
| unit_c | °C |

(ui module may add layout-only strings; all user-visible text MUST be in `strings.xml`, Serbian latinica.)

---

## 10. Scaffold specifics

### 10.1 `App.kt`

```kotlin
package rs.novica.dobarboiler

class App : android.app.Application(), androidx.work.Configuration.Provider {
    lateinit var container: rs.novica.dobarboiler.di.AppContainer; private set
    override fun onCreate() {
        super.onCreate()
        container = rs.novica.dobarboiler.di.AppContainer(this)
        rs.novica.dobarboiler.notifications.Channels.ensure(this)
        container.enqueueWatchdog()   // periodic 6 h, KEEP
        container.enqueueServerSync() // periodic 1 h, KEEP
        container.enqueueLinkCheck()  // periodic 15 min, KEEP (§7.5)
    }
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()
}
```

### 10.2 `di/AppContainer.kt` — frozen wiring surface

```kotlin
package rs.novica.dobarboiler.di

class AppContainer(app: android.app.Application) {
    val zone: java.time.ZoneId                       // from SettingsStore, default Europe/Belgrade
    val clock: java.time.Clock                       // Clock.system(zone)
    val json: kotlinx.serialization.json.Json        // ignoreUnknownKeys = true
    val credentialStore: rs.novica.dobarboiler.data.CredentialStore
    val apiClient: rs.novica.dobarboiler.api.AristonApiClient        // AristonHttpClient(credentialStore, json)
    val db: rs.novica.dobarboiler.data.AppDatabase
    val settings: rs.novica.dobarboiler.data.SettingsStore
    val scheduleRepository: rs.novica.dobarboiler.data.ScheduleRepository
    val deviceRepository: rs.novica.dobarboiler.data.DeviceRepository
    val actionLog: rs.novica.dobarboiler.data.ActionLogRepository
    val engine: rs.novica.dobarboiler.domain.SchedulerEngine         // DefaultSchedulerEngine(zone)
    val alarmScheduler: rs.novica.dobarboiler.scheduling.AlarmScheduler
    val notifier: rs.novica.dobarboiler.notifications.Notifier
    val linkMonitor: rs.novica.dobarboiler.scheduling.DeviceLinkMonitor   // §7.5
    val converger: rs.novica.dobarboiler.scheduling.Converger
    fun enqueueWatchdog()
    fun enqueueServerSync()
    fun enqueueLinkCheck()
}
```

All lazily initialized (`by lazy`). Workers/receivers/services obtain it via
`(context.applicationContext as App).container`.

### 10.3 Gradle (versions catalog `gradle/libs.versions.toml`)

AGP 8.7.x, Kotlin 2.1.x + compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`),
`org.jetbrains.kotlin.plugin.serialization`, KSP matching Kotlin. Compose BOM 2025.01+,
material3, navigation-compose 2.8.x, lifecycle-viewmodel-compose, room-runtime/ktx/compiler (2.6.1, KSP),
work-runtime-ktx 2.10.x, datastore-preferences 1.1.x, security-crypto 1.1.0-alpha06, okhttp 4.12.x,
kotlinx-serialization-json 1.7.x, kotlinx-coroutines. Test: junit4, kotlinx-coroutines-test, mockwebserver,
robolectric optional (skip if heavy). `compileSdk 35`, `minSdk 26`, `targetSdk 35`, Java 17 toolchain.
NO Hilt, NO kapt.

---

## 11. Definition of done / acceptance checks

1. `gradlew assembleDebug` builds; unit tests in `domain` and `api` pass (`gradlew test`).
2. Resolver unit tests cover: precedence table (all 6 rules), midnight-crossing window, DST gap/overlap,
   two overlapping HBTs, HBT-during-vacation, boost revert inside vs outside window, empty plan → null transition.
3. Fresh install → login → onboarding → dashboard shows live state within 5 s.
4. Simple-mode night = ≤5 commands + ≤3 polls (assert via action_log in a simulated night).
5. Reboot with pending vacation-return re-arms the alarm (BootstrapReceiver path).
6. Every failure path lands in `action_log` and (when FAILED) in the `greske` channel.
