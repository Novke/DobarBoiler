# App ↔ history server sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Android app build and test on novica-dev, then have it upload its `action_log` and its current intent to the history server, with a settings screen for the server URL and token. Without a configured or reachable server the app behaves exactly as today.

**Architecture:** A new `data/ServerSync` reads `action_log` rows above a high-water mark kept in DataStore (no Room schema change) plus the last intent that `Converger` stores in `RuntimeState`, and posts them through a small OkHttp client `api/HistoryApiClient`. `scheduling/ServerSyncWorker` runs it: a one-time unique job enqueued at the end of every converge and a 1 h periodic job. Server credentials live in the existing EncryptedSharedPreferences file.

**Tech Stack:** Kotlin, OkHttp, kotlinx.serialization, WorkManager, DataStore, Compose Material 3; JUnit + MockWebServer + kotlinx-coroutines-test. Android SDK + Gradle 8.14.3 on the box.

**Spec:** `docs/superpowers/specs/2026-10-18-boiler-history-mcp-ux-design.md` §5.1–5.2; server contract in `docs/superpowers/plans/2026-10-18-boiler-history-server.md` Task 8.

## Global Constraints

- **No Room schema change** (DB stays version 1). Adding a `@Query` method to a DAO is allowed; changing an entity is not.
- `domain/` stays pure Kotlin. Layering: `api` depends on nothing; `data` → `domain` + `api`; `scheduling` → `domain`, `data`, `notifications`; `ui` → all.
- `converge` stays serialized by its `Mutex`; nothing network-bound runs inside it except what already does. Sync is only *enqueued* from it.
- Sync failures never notify on `greske` and never write to `action_log` (that would feed the log back into itself); they surface only in the settings health row.
- Default server URL `http://novica-dev/boiler/`; cleartext allowed **only** for host `novica-dev` via `network_security_config`.
- Upload batch size 200; server accepts max 500 per request.
- Every user-visible string in `res/values/strings.xml`, Serbian latinica.
- Payload JSON must match the server exactly: `POST api/v1/app/actions` `{"install_id","actions":[{"local_id","at","kind","trig","detail","outcome","attempt","state_json"}]}`; `POST api/v1/app/intent` `{"install_id","at","source","desired":{"power_on","target_temp","eco"},"next_transition_at"}`; `at` values ISO-8601 with offset; header `Authorization: Bearer <token>`.

## Review Focus

- **Server URL entered without a trailing slash** (`http://novica-dev/boiler`) must still hit `/boiler/api/v1/...`, not `/api/v1/...` → Task 2 test `normalizesBaseUrlWithoutTrailingSlash`.
- **Upload succeeds on the server but the response is lost** (tailnet drops) → the next run re-sends the same rows; the high-water mark advances only after a 2xx, and the server is idempotent → Task 3 test `markAdvancesOnlyOn2xx`.
- **Rows pruned after 90 days while the phone was offline longer** → the sync just continues from the oldest remaining id above the mark, no crash → Task 3 test `gapInIdsIsFine`.
- **App upgraded with existing DataStore lacking the new keys** → defaults apply (`installId` created on first read, mark 0) → Task 3 test `firstRunGeneratesInstallIdOnce`.
- **No server configured** → worker returns success immediately without network → Task 3 test `unconfiguredIsNoop`.

---

### Task 1: Android toolchain on the box

**Files:**
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` (generated), `local.properties` (gitignored)
- Modify: `CLAUDE.md` (Commands section)

- [ ] **Step 1: Install SDK** to `/data/android-sdk` (root disk is small): download the current Linux `commandlinetools` zip from `https://developer.android.com/studio#command-tools` into a fresh directory under `/data/tmp`, unzip to `/data/android-sdk/cmdline-tools/latest`, then `yes | sdkmanager --sdk_root=/data/android-sdk --licenses` and `sdkmanager --sdk_root=/data/android-sdk "platforms;android-35" "build-tools;35.0.0" "platform-tools"`.
- [ ] **Step 2: Gradle wrapper**: download Gradle 8.14.3 distribution once into `/data/tmp`, run `gradle wrapper --gradle-version 8.14.3` in the repo, write `local.properties` with `sdk.dir=/data/android-sdk`.
- [ ] **Step 3: Verify**: `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL with all existing tests passing; `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` exists. If anything fails, fix only toolchain issues here; report code failures instead of changing code.
- [ ] **Step 4:** Update `CLAUDE.md`: remove "no Android SDK and no Gradle yet", document `/data/android-sdk` and that the wrapper is committed.
- [ ] **Step 5: Commit** `build: commit Gradle wrapper, document SDK on novica-dev`

### Task 2: Server config store and HTTP client

**Files:**
- Create: `app/src/main/java/rs/novica/dobarboiler/api/HistoryApiClient.kt`, `app/src/main/java/rs/novica/dobarboiler/api/HistoryDtos.kt`, `app/src/test/java/rs/novica/dobarboiler/api/HistoryApiClientTest.kt`
- Modify: `app/src/main/java/rs/novica/dobarboiler/data/CredentialStore.kt` (server URL + token keys)

**Interfaces:**
- Produces:
  - `api/HistoryDtos.kt`: `@Serializable data class ActionDto(@SerialName("local_id") val localId: Long, val at: String, val kind: String, val trig: String, val detail: String, val outcome: String, val attempt: Int, @SerialName("state_json") val stateJson: String?)`, `@Serializable data class ActionsBatch(@SerialName("install_id") val installId: String, val actions: List<ActionDto>)`, `@Serializable data class ActionsResult(val accepted: Int, val duplicates: Int)`, `@Serializable data class DesiredDto(@SerialName("power_on") val powerOn: Boolean, @SerialName("target_temp") val targetTemp: Int?, val eco: Boolean?)`, `@Serializable data class IntentDto(@SerialName("install_id") val installId: String, val at: String, val source: String, val desired: DesiredDto, @SerialName("next_transition_at") val nextTransitionAt: String?)`, `@Serializable data class HealthDto(val ok: Boolean, @SerialName("last_state_at") val lastStateAt: String?)`.
  - `class HistoryApiClient(baseUrl: String, token: String, json: Json, client: OkHttpClient = OkHttpClient())` with `suspend fun postActions(batch: ActionsBatch): ActionsResult`, `suspend fun postIntent(intent: IntentDto)`, `suspend fun health(): HealthDto`. Throws `HistoryApiException(status: Int?, message: String)` on non-2xx or I/O. 10 s call timeout. Base URL normalized to end with `/`; paths are relative (`api/v1/app/actions`).
  - `CredentialStore`: `var historyServerUrl: String?`, `var historyServerToken: String?` (same encrypted file, keys `history_url`, `history_token`).

- [ ] **Step 1: Write failing tests** (MockWebServer): `postsActionsWithBearerAndExactJson` (asserts path `/boiler/api/v1/app/actions`, header `Authorization: Bearer t0k`, body field names `install_id`, `local_id`, `state_json`); `normalizesBaseUrlWithoutTrailingSlash`; `non2xxThrows` (401 → `HistoryApiException(401, …)`); `healthParses`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*HistoryApiClientTest*'` → FAIL.
- [ ] **Step 3: Implement** (reuse the `Dispatchers.IO` + `execute()` style of `AristonHttpClient`).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(app): history server HTTP client and credentials`

### Task 3: Sync logic, worker, intent capture

**Files:**
- Create: `app/src/main/java/rs/novica/dobarboiler/data/ServerSync.kt`, `app/src/main/java/rs/novica/dobarboiler/scheduling/ServerSyncWorker.kt`, `app/src/test/java/rs/novica/dobarboiler/data/ServerSyncTest.kt`
- Modify: `data/db/Daos.kt` (`ActionLogDao`), `data/SettingsStore.kt` (`RuntimeState` + keys), `scheduling/Converger.kt` (store intent, enqueue sync), `di/AppContainer.kt` (wiring + periodic enqueue), `App.kt` (call periodic enqueue next to `enqueueWatchdog()`)

**Interfaces:**
- Consumes: Task 2.
- Produces:
  - `ActionLogDao`: `@Query("SELECT * FROM action_log WHERE id > :afterId ORDER BY id ASC LIMIT :limit") suspend fun after(afterId: Long, limit: Int): List<ActionLogEntity>`.
  - `RuntimeState` gains `lastIntentJson: String?`, `lastUploadedActionId: Long` (default 0), `lastIntentUploadedAt: Instant?`, `installId: String?`, `lastSyncOkAt: Instant?`, `lastSyncError: String?` with DataStore keys `rt_last_intent_json`, `rt_last_uploaded_action_id`, `rt_last_intent_uploaded_at`, `rt_install_id`, `rt_last_sync_ok_at`, `rt_last_sync_error`.
  - `object IntentCodec { fun encode(res: Resolution, at: Instant): String; fun decode(raw: String): IntentDto? }` in `data/ServerSync.kt` (installId filled at upload time).
  - `class ServerSync(private val fetchAfter: suspend (Long, Int) -> List<ActionLogEntity>, private val settings: SettingsStore, private val clientFactory: () -> HistoryApiClient?, private val clock: Clock)` with `suspend fun run(): SyncResult` where `sealed interface SyncResult { object NotConfigured; data class Ok(val actions: Int, val intent: Boolean); data class Failed(val error: Throwable) }`. Loop: fetch 200 after the mark → post → on 2xx advance mark to the max id of the batch → repeat until empty; then post the intent if `lastIntentJson` changed since `lastIntentUploadedAt`. Records `lastSyncOkAt` / `lastSyncError` in `RuntimeState`.
  - `class ServerSyncWorker : CoroutineWorker` → `NotConfigured`/`Ok` = `Result.success()`, `Failed` = `Result.retry()` while `runAttemptCount < 5`, else `success()` (the periodic job picks it up later). Companion: `UNIQUE_NAME = "server_sync"`, `PERIODIC_NAME = "server_sync_periodic"`, `fun enqueueSoon(context: Context)` (one-time, network required, 15 s initial delay, `ExistingWorkPolicy.REPLACE`), `fun enqueuePeriodic(context: Context)` (1 h, network required, `KEEP`).
  - `Converger.convergeLocked`: right after resolving, `settings.updateRuntime { it.copy(lastIntentJson = IntentCodec.encode(res, now)) }` only when the encoded value differs; at the end of `converge()` (after the lock is released) call `ServerSyncWorker.enqueueSoon(alarms.context)`. No constructor change.

- [ ] **Step 1: Write failing tests** in `ServerSyncTest` (fake fetch lambda, MockWebServer-backed client, `SettingsStore` replaced by a small interface or an in-memory DataStore via `PreferenceDataStoreFactory.create` with a temp file): `uploadsInBatchesOf200` (450 rows → 3 requests, mark = 450); `markAdvancesOnlyOn2xx` (second batch 500 → mark stays at 200, result `Failed`); `gapInIdsIsFine` (ids 900..950 after mark 10 → one request, mark 950); `firstRunGeneratesInstallIdOnce` (two runs → same `install_id` in both bodies); `unconfiguredIsNoop` (factory returns null → `NotConfigured`, zero requests); `intentSentOnlyWhenChanged` (same intent twice → one `/app/intent` call); `intentCodecRoundTrip` (BOOST, desired on/60/false, next transition → decode equals).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*ServerSyncTest*'` → FAIL.
- [ ] **Step 3: Implement**, wire `AppContainer.serverSync` (`by lazy`, `clientFactory` builds `HistoryApiClient` from `credentialStore` or returns null when URL or token is blank) and `enqueuePeriodic` from `App.onCreate`.
- [ ] **Step 4: Run all unit tests** `./gradlew :app:testDebugUnitTest` → PASS (existing resolver/converger-related tests unchanged).
- [ ] **Step 5: Commit** `feat(app): upload action log and intent to the history server`

### Task 4: Settings screen and network config

**Files:**
- Create: `app/src/main/res/xml/network_security_config.xml`
- Modify: `app/src/main/AndroidManifest.xml` (`android:networkSecurityConfig`), `ui/settings/SettingsScreens.kt` (new entry + screen), `ui/Nav.kt` (route `settings/server`), `res/values/strings.xml`, `ui/common/ViewModelFactory.kt` if a new ViewModel needs wiring

**Interfaces:**
- Consumes: `CredentialStore.historyServerUrl/Token`, `HistoryApiClient.health()`, `RuntimeState.lastSyncOkAt/lastSyncError`, `ServerSyncWorker.enqueueSoon`.
- Produces: route `Routes.SETTINGS_SERVER = "settings/server"`; `SettingsServerScreen()`.

- [ ] **Step 1:** `network_security_config.xml`: base config cleartext **false**; `<domain-config cleartextTrafficPermitted="true"><domain includeSubdomains="false">novica-dev</domain></domain-config>`.
- [ ] **Step 2:** Screen content (strings, Serbian): title `Server istorije`; fields `Adresa servera` (prefilled `http://novica-dev/boiler/`) and `Token` (password field with show/hide); buttons `Sačuvaj` and `Testiraj vezu`; status line one of `Poslednje slanje: pre %1$s`, `Server nije podešen`, `Greška: %1$s`; help text `Server radi preko Tailscale mreže. Bez nje aplikacija radi kao i do sada, a zapisi se šalju kad veza proradi.` `Sačuvaj` stores both values and calls `ServerSyncWorker.enqueueSoon`. `Testiraj vezu` calls `health()` and shows `Veza radi, poslednje stanje bojlera pre %1$s` or the error.
- [ ] **Step 3:** Settings root gets a `ListItem` `Server istorije` with the status line as supporting text; the health screen lists `Server istorije` as a non-blocking item (never a red issue).
- [ ] **Step 4: Verify**: `./gradlew :app:testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `feat(app): history server settings and cleartext rule for novica-dev`

### Task 5: End-to-end check with the live server

- [ ] **Step 1:** Requires server plan Tasks 0–10 deployed. Give Novica the debug APK the usual way, the token is entered in `Podešavanja → Server istorije` (Novica reads it with `ticu`).
- [ ] **Step 2:** After Novica taps `Testiraj vezu` and triggers any converge (pull-to-refresh), verify on the box: `docker exec shared-postgres psql -U postgres -d dobarboiler -c "select count(*), max(at) from app_action"` → rows present; `select source, at from app_intent order by at desc limit 1` → current source.
- [ ] **Step 3:** Turn Tailscale off on the phone for a converge, turn it back on, wait up to 1 h (or tap `Sačuvaj` to enqueue) → the missed rows arrive, no duplicates (`select install_id, local_id, count(*) from app_action group by 1,2 having count(*) > 1` → empty).
- [ ] **Step 4:** Update `docs/SPEC.md` (§4.4 runtime keys, §6 sync worker, §8 settings route) and commit `docs(spec): history server sync`; open the PR.
