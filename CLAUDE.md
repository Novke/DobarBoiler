# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Working context

Claude runs on Novica's dev box `novica-dev` (see the global `~/.claude/CLAUDE.md`), not on the phone. Its job here
is to help Novica **operate and improve their own water heater** — an Ariston Velis Tech Wifi 80 — through this
Android app, which replaces the official Ariston NET app. Novica writes in Serbian; the app UI is Serbian latinica.

- The boiler has **no local API**; everything goes through the Ariston cloud
  (`https://www.ariston-net.remotethermo.com/api/v2/`), so it is reachable from this box as well as from the phone.
- The app runs on Novica's phone (sideloaded). Nothing in this repo is deployed on the box.
- Ariston credentials live in SSM `/dev/dobarboiler/env` (keys `ARISTON_USER`, `ARISTON_PASS`;
  since 2026-10-18), rendered by `ssm-env dobarboiler` to `/data/secrets/dobarboiler.env`.
  Claude reads the live boiler only through `bash /data/dev/DobarBoiler/tools/ariston-read.sh [test]`, which loads
  the env inside its own process and runs a fixed read-only script; it is allowed in the gitignored
  `.claude/settings.local.json`. Do not source the env file in other commands. Every command sent changes a real
  boiler in a real home — read state freely, ask before switching power/temperature/modes.

## Device facts (verified live, `docs/api-findings.md`)

Velis Tech Wifi 80 reports as `SystemType 4` (VELIS), **`WheType 8` = Lux** (not Evo2), so every device path
uses the `medPlantData` segment. Temperature range 40–75 °C, step 1; live cap is the plant setting
`MedMaxSetpointTemperature` (often 65). Physical buttons cannot be remapped — manual changes are only detectable
by polling.

## Commands

The Gradle wrapper (8.14.3) is committed. On novica-dev the Android SDK lives in `/data/android-sdk`
(`platforms;android-35`, `build-tools;35.0.0`, `platform-tools`, installed 2026-10-19); each checkout or worktree
needs a gitignored `local.properties` with `sdk.dir=/data/android-sdk`. `~/.gradle` is a symlink to
`/data/gradle-home` because the root disk is small. JDK 21. A cold build takes ~10 min; use `--no-daemon` for
one-off runs.

```bash
./gradlew assembleDebug                      # debug APK
./gradlew :app:testDebugUnitTest             # all JVM unit tests (domain + api)
./gradlew :app:testDebugUnitTest --tests 'rs.novica.dobarboiler.domain.ResolverTest'
./gradlew :app:testDebugUnitTest --tests '*ResolverTest.someTestName'
```

Live API smoke test (Python reference library `ariston`, use a venv — PEP 668):
`ARISTON_USER=... ARISTON_PASS=... python tools/ariston_test.py`; on novica-dev `bash tools/ariston-read.sh`
(venv `/data/tmp/dobarboiler-venv`, created on first run).

No lint config, no instrumented tests, no CI.

Release to the phone: `bash tools/release-apk.sh` (origin/master, tests, then `http://novica-dev/boiler-apk/`;
GitGud button **Izdaj APK**). APKs must be signed with the stable key `/data/keys/dobarboiler/signing.keystore`
(configured in `~/.gradle/gradle.properties`), or the phone refuses them as an update; versions come from git.
Details and key restore: `docs/release.md`.

## Where the truth lives

- `docs/SPEC.md` — **authoritative implementation spec**. Its Kotlin signatures are *frozen contracts*; §0 lists
  binding conflict resolutions (R1–R18) explaining most non-obvious choices. Update the spec when you change a
  contract or a policy.
- `docs/protocol.md` — complete Ariston HTTP protocol (endpoints, bodies, error semantics), extracted from
  `python-ariston-api`.
- `docs/api-findings.md` — what was confirmed against the actual device.

## Architecture

Single module `:app`, package `rs.novica.dobarboiler`. Manual DI (`di/AppContainer`, all `by lazy`; workers,
receivers and the service reach it via `(applicationContext as App).container`). **No Hilt, no kapt, no Retrofit.**

Layering (compile-time): `domain` and `api` depend on nothing; `data` → `domain` + `api`; `scheduling` → `domain`,
`data`, `notifications`; `ui` → all. `domain/` must stay pure Kotlin (no `android.*` imports) — that is what keeps
it unit-testable.

**Core principle: idempotent state convergence.** `SchedulerEngine.resolve(now, inputs)` (pure, in
`domain/DefaultSchedulerEngine`) computes what the boiler should be doing *now* plus the next transition instant.
Every entry point — exact alarm, boot/time change, 6 h watchdog, verify poll, band tick, boost, UI edit — calls
`scheduling/Converger.converge(trigger)`, which: loads inputs → resolves → polls the device once → detects manual
changes → `plan()` diffs desired vs observed and sends only the delta → logs to `action_log` → arms exactly
**one** exact alarm at the next transition. State is converged, never replayed, so missed alarms, reboots and
process death self-heal. Nothing in memory is load-bearing; state lives in Room + DataStore. `converge` is
serialized by a `Mutex` — keep it that way.

Key rules that are easy to break:

- **Precedence** (first active wins): Boost > Heat-by-time > Vacation > Day override > Weekly plan > Default
  (`OFF` or `KEEP_WARM`). Boost and HBT deliberately beat vacation. Table in SPEC §3.4.
- **Device mode is always 1 (MANUAL).** The engine normalizes to it and never emits mode 5 or 9; the app's
  "Boost" is power on + target temp + eco off + 90 s polling in `BoostService` (a `specialUse` foreground service),
  with an exact alarm at `timeoutAt` as backstop (`BoostTimeoutWorker`).
- **Default maintain mode is "simple"**: window start = on + temp + eco, window end = off; the firmware thermostat
  holds the setpoint. App-side hysteresis ("Napredno održavanje", `BandWorker`, 15 min alarm chain) is opt-in.
- **Manual changes** (someone pressed buttons on the boiler) default to RESPECT_UNTIL_NEXT_TRANSITION via the
  runtime flag `manualHoldUntil`; transition alarms (`Triggers.ALARM`) and boost clear it. Detection compares the
  snapshot with `lastAppliedState` after a 3 min grace.
- Alarms: `setExactAndAllowWhileIdle` only, fixed request codes so re-arming replaces. `AlarmReceiver` does zero
  I/O and only enqueues expedited WorkManager jobs; retries are WorkManager backoff (7 attempts), plus an extra
  30 min chain when a window-end OFF fails (that one costs money).
- **Boiler without power** (its switch trips): the cloud answers `500 "M2M platform error … (Http status: 404)"`,
  mapped to `AristonException.DeviceOffline`. `DeviceLinkMonitor` + `LinkCheckWorker` (15 min) turn it into an
  ongoing "Bojler je van mreže" alert after 3 min and clear it on the first fresh read (SPEC §7.5).
- Window times are `LocalTime` converted per date in the configured zone (default `Europe/Belgrade`; DST gap →
  first valid instant, overlap → earlier offset). HBT, vacation and boost use `Instant`.

Ariston API etiquette (`api/AristonHttpClient`): headers `User-Agent: RestSharp/106.11.7.0` and
`ar.authToken`; **HTTP 405 means expired token** → re-login and retry once; 404 → no data; 429 → fail fast (the
worker backoff is the etiquette); other errors → 5 s sleep + one retry. `/switch` and `/switchEco` take a **bare
JSON `true`/`false`** body. The bus to the boiler is slow: never poll state more often than every 30–60 s.

Persistence: Room `dobarboiler.db` (plans, presets, overrides, vacation, boost sessions, action log, rate samples, snapshots)
is at **version 1 with no migrations** — any entity change needs a version bump and a migration, otherwise the
installed app crashes on update. Settings and runtime state in Preferences DataStore (`data/SettingsStore`);
email/password/token in EncryptedSharedPreferences (`data/CredentialStore`).

Build gotcha: Room 2.6.1 crashes under KSP2, so `gradle.properties` pins `ksp.useKSP2=false` until Room is bumped
to 2.7+.

## Conventions

- All user-visible text lives in `res/values/strings.xml`, Serbian latinica (SPEC §9 lists the canonical strings).
- Notification channels: `greske` (HIGH), `boost`, `dogadjaji` (silent). Every failure path must land in
  `action_log` and, when FAILED, in `greske`.
- Unit tests cover `domain` (resolver precedence, DST, HBT math, rate learning, `plan()` diff) and `api`
  (MockWebServer); add resolver cases to `ResolverTest` when changing precedence or transitions.
