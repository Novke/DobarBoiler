# Boiler history server + MCP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A read-only service on novica-dev that records the Ariston Velis state every 2 min and the cloud's energy buckets, splits kWh into cheap (00–08 h) and expensive tariff, attributes every change, and serves the data over REST (for the phone app), MCP (for agents) and Grafana.

**Architecture:** One Python 3.12 ASGI process (`server/`, package `boilerd`) in a Docker container: an asyncio poller started in the app lifespan writes to Postgres database `dobarboiler` in `shared-postgres`; FastAPI serves `/api/v1/*`; the MCP Python SDK's streamable-HTTP app is mounted at `/mcp`. Pure logic (bucket alignment, event detection, attribution, analytics math) lives in small modules with no I/O so it is unit-testable; SQL lives next to the module that owns the table.

**Tech Stack:** Python 3.12, FastAPI + uvicorn, httpx (async), psycopg 3 (+ psycopg_pool), `mcp` (official SDK, pin the version you install), pytest + pytest-asyncio + respx, Docker Compose, Grafana (existing observability stack), restic (existing).

**Spec:** `docs/superpowers/specs/2026-10-18-boiler-history-mcp-ux-design.md` (Serbian). Sections referenced as §N.

## Global Constraints

- **Never send a command to the boiler.** Allowed Ariston calls: `POST accounts/login`, `GET velis/plants`, `GET velis/medPlantData/{gw}`, `GET velis/medPlantData/{gw}/plantSettings`, `GET remote/reports/{gw}/consSequencesApi8?usages=Dhw`, `GET busErrors?gatewayId={gw}&blockingOnly=False&culture=en-US`. Nothing else.
- Ariston etiquette (§4.2): headers `User-Agent: RestSharp/106.11.7.0`, `Content-Type: application/json`, `ar.authToken: <token>`; login body `{"usr","pwd","imp":false,"notTrack":true,"appInfo":{"os":2,"appVer":"5.6.772.40151","appId":"com.remotethermo.aristonnet"}}`; HTTP 405 = expired token → re-login once and retry once; 429 → pause **all** calls 15 min; other errors → one retry after 5 s, then skip the cycle; never two calls in parallel.
- Schedule (§4.2): state 120 s (config `STATE_INTERVAL_S`, minimum 60); energy every 2 h at :07 past each even local hour; `p=3`/`p=4` backfill daily 02:10 and on start; `plantSettings` 6 h; `busErrors` 1 h; `velis/plants` on start and every 24 h.
- Time: store `timestamptz`; all wall-clock logic in `ZoneInfo("Europe/Belgrade")`.
- Tariff (§5.4): cheap window **00:00–08:00 local, all year**. Default prices blue zone: cheap **3.6051**, expensive **14.4203** din/kWh (zone `plava`); green zone 2.4034 / 9.6136.
- Boiler constants: volume **80 L**, **1.163 Wh/(L·°C)**, comfort threshold **40 °C**, target coverage **95 %**.
- Network: container port bound to `127.0.0.1:8020` only; reached from the tailnet as `http://novica-dev/boiler/` via `preview add boiler 8020` (nginx strips `/boiler`).
- Secrets: never print, never commit. Ariston credentials come from `/data/secrets/dobarboiler.env` (keys `ARISTON_USER`, `ARISTON_PASS`; optional `SERVER_ARISTON_USER`/`SERVER_ARISTON_PASS` override them). Server secrets come from a **separate** SSM parameter `/dev/dobarboiler-server/env` → `/data/secrets/dobarboiler-server.env` (keys `BOILER_API_TOKEN`, `DOBARBOILER_DB_PASSWORD`, `DOBARBOILER_RO_PASSWORD`). Per the repo CLAUDE.md, agents must not source `dobarboiler.env`; it is only handed to the container via `env_file`.
- All user-facing summary strings (MCP text, report markdown) in Serbian latinica.

## Review Focus

- **Fetching `p=1` right at an even hour before the cloud shifts the array** would mislabel every bucket by 2 h → `p1_safe_to_read()` refuses minutes 0–4 past an even hour, and the daily check compares `sum(energy_2h)` per day with `energy_day` (Task 4 test `test_daily_crosscheck_flags_mismatch`).
- **DST days** (2026-10-25 has a 3 h `02–04` bucket; 2027-03-28 has a 1 h one) → Task 2 tests pin both.
- **Phone session or rate limit hurt by the server's login** → login only at start and on 405, one call at a time, 429 pauses everything (Task 3 tests `test_429_pauses_all_calls`, `test_405_relogin_once`).
- **The same `action_log` batch uploaded twice** (phone retries) must not duplicate rows or events → Task 8 test `test_ingest_actions_idempotent`.
- **MCP behind nginx with `Host: novica-dev`** must not be rejected by the SDK's DNS-rebinding Host allowlist (421) → Task 9 test `test_mcp_accepts_proxied_host`.

---

## File Structure

```
server/
  pyproject.toml                 deps + pytest config (asyncio_mode = "auto")
  Dockerfile                     python:3.12-slim, non-root uid 1000, uvicorn boilerd.app:app --port 8020
  docker-compose.yml             project "dobarboiler", service "boilerd"
  boilerd/
    __init__.py
    config.py                    Settings from env (frozen dataclass)
    db.py                        pool, migrate(), conn helpers
    migrations/001_init.sql      all tables from §4.3
    buckets.py                   pure: p1/p3/p4 alignment, tariff classification
    ariston.py                   AristonClient (httpx async) + etiquette
    ingest.py                    parse + upsert: state, energy, settings, errors, probe import
    events.py                    pure detector + attribution; persist events
    poller.py                    scheduler loop, poll_log, offline detection
    analytics.py                 tariff split, reports, insights
    api.py                       FastAPI router /api/v1
    mcp_tools.py                 MCP server + tools + boiler://schema resource
    app.py                       ASGI composition + lifespan
  grafana/boiler.json            dashboard
  ops/create-db.sh               creates DB + roles (prod and test)
  ops/pytest.sh                  runs pytest with the test DSN (no secrets printed)
  ops/add-secrets.ps1            for Novica's Windows laptop: creates /dev/dobarboiler-server/env
  ops/dobarboiler-backup         pg_dump → restic dev-box/dobarboiler
  ops/dobarboiler-backup.service / .timer
  tests/
    fixtures/                    real responses copied from /data/tmp/boiler-probe/log.jsonl
    conftest.py                  test DB fixture (migrate + truncate per test)
    test_*.py                    one file per module
```

---

### Task 0: Secrets and databases (needs Novica once)

**Files:**
- Create: `server/ops/add-secrets.ps1`, `server/ops/create-db.sh`, `server/ops/pytest.sh`

- [ ] **Step 1: Write `server/ops/add-secrets.ps1`** (Windows PowerShell 5.1 compatible). It generates three hex secrets with `RNGCryptoServiceProvider` (`BOILER_API_TOKEN` 32 bytes, `DOBARBOILER_DB_PASSWORD` 24, `DOBARBOILER_RO_PASSWORD` 24), writes them as `KEY=value` lines to a temp file, runs `aws --profile novica-dev ssm put-parameter --name /dev/dobarboiler-server/env --type SecureString --value file://<tmp>` (no `--overwrite`: refuses to clobber an existing parameter), then appends `DOBARBOILER_RO_PASSWORD=<same value>` to `/dev/observability/env` (get → append → put with `--overwrite`), deletes the temp file, and prints only key names.
- [ ] **Step 2: Ask Novica to run it on the laptop** (`powershell -File server\ops\add-secrets.ps1` from the repo), then on the box: `ssm-env dobarboiler-server` and `ssm-env observability /data/srv/observability/.env`. Verify with `grep -oE '^[A-Z0-9_]+=' /data/secrets/dobarboiler-server.env` → the three key names.
- [ ] **Step 3: Write `server/ops/create-db.sh`**: loads `dobarboiler-server.env` inside its own process, then via `docker exec -i shared-postgres psql -U postgres -v ON_ERROR_STOP=1` (passwords passed as psql `-v` variables, never echoed) creates idempotently: role `dobarboiler` (LOGIN, password `DOBARBOILER_DB_PASSWORD`), database `dobarboiler` owned by it, role `dobarboiler_ro` (LOGIN, password `DOBARBOILER_RO_PASSWORD`, `default_transaction_read_only = on`, `statement_timeout = '5s'`), `GRANT CONNECT`, `USAGE ON SCHEMA public`, `SELECT ON ALL TABLES` + `ALTER DEFAULT PRIVILEGES FOR ROLE dobarboiler ... GRANT SELECT TO dobarboiler_ro`. With `--test`: role `dobarboiler_test` with a freshly generated password, database `dobarboiler_test`, and writes the DSN `postgresql://dobarboiler_test:<pw>@127.0.0.1:5432/dobarboiler_test` to `/data/tmp/dobarboiler-test-dsn.txt` (mode 0600). Same pattern as `/data/tmp/cvgen-pytest.sh`.
- [ ] **Step 4: Write `server/ops/pytest.sh`**: `cd` to `server/` of the current checkout, create venv `/data/tmp/boilerd-venv` with `pip install -e .[dev]` if missing, export `BOILERD_TEST_DSN="$(cat /data/tmp/dobarboiler-test-dsn.txt)"`, `exec pytest -p no:cacheprovider "$@"`.
- [ ] **Step 5: Run** `bash server/ops/create-db.sh && bash server/ops/create-db.sh --test`. Expected: `docker exec shared-postgres psql -U postgres -lqt | cut -d'|' -f1 | grep -w dobarboiler` lists `dobarboiler` and `dobarboiler_test`.
- [ ] **Step 6: Commit** `git add server/ops && git commit -m "feat(server): secrets bootstrap and database setup scripts"`

### Task 1: Package skeleton, config, schema

**Files:**
- Create: `server/pyproject.toml`, `server/boilerd/{__init__,config,db}.py`, `server/boilerd/migrations/001_init.sql`, `server/tests/conftest.py`, `server/tests/test_db.py`

**Interfaces:**
- Produces: `Settings.from_env() -> Settings` with fields `ariston_user, ariston_pass, api_token, db_dsn, db_ro_dsn, tz: ZoneInfo, state_interval_s: int = 120, probe_log: Path | None`. `db.create_pool(dsn) -> AsyncConnectionPool`; `db.migrate(pool) -> list[str]` (applied file names; tracked in table `schema_migrations(name text pk, applied_at timestamptz)`). DSN is assembled from `DB_HOST` (default `shared-postgres`), `DB_PORT` 5432, `DB_NAME` `dobarboiler`, `DB_USER` `dobarboiler`, `DOBARBOILER_DB_PASSWORD`; RO DSN the same with `dobarboiler_ro`.
- Test fixture `db` (conftest): pool on `BOILERD_TEST_DSN`, `migrate()`, `TRUNCATE` all tables except `schema_migrations` before each test.

- [ ] **Step 1: Write failing tests** in `test_db.py`:
  - `test_migrate_is_idempotent`: first `migrate()` returns `["001_init.sql"]`, second returns `[]`.
  - `test_schema_has_tables`: `information_schema.tables` contains exactly `state_sample, energy_2h, energy_day, energy_month, plant_settings, device_error, app_action, app_intent, event, tariff, poll_log, schema_migrations`.
  - `test_default_tariff_seeded`: one `tariff` row with `cheap_start = '00:00'`, `cheap_end = '08:00'`, `cheap_price = 3.6051`, `expensive_price = 14.4203`, `zone = 'plava'`, `valid_from = '2025-01-01'`.
  - `test_settings_from_env_falls_back_to_phone_credentials`: with only `ARISTON_USER/PASS` set, `ariston_user` equals it; with `SERVER_ARISTON_USER/PASS` set, those win.
- [ ] **Step 2: Run** `bash server/ops/pytest.sh tests/test_db.py -v` → FAIL (module missing).
- [ ] **Step 3: Implement** `001_init.sql` with the columns of §4.3 exactly (keys: `state_sample(at)`, `energy_2h(bucket_start)` + `bucket_end`, `energy_day(day)`, `energy_month(month)`, `plant_settings(at)`, `device_error(code, first_seen_at)`, `app_action(install_id, local_id)`, `app_intent(install_id, at)`, `event(id bigserial)` + index on `(at)` and `(kind, at)`, `tariff(valid_from)`, `poll_log(id bigserial)` + index on `at`). `state_sample` also gets `raw jsonb not null`. `event.attribution` is `text check (attribution in ('app','external','firmware','firmware_eco','unknown') or attribution is null)`. Seed the default tariff row. Implement `config.py` and `db.py`.
- [ ] **Step 4: Run tests** → PASS.
- [ ] **Step 5: Commit** `feat(server): package skeleton, settings and schema`

### Task 2: Bucket alignment and tariff classification (pure)

**Files:**
- Create: `server/boilerd/buckets.py`, `server/tests/test_buckets.py`

**Interfaces:**
- Produces:
  - `p1_bucket_bounds(fetched_at: datetime, n: int, tz: ZoneInfo) -> list[tuple[datetime, datetime]]` — `(start, end)` aware datetimes, oldest first, last bucket ends at the latest even local hour ≤ `fetched_at`.
  - `p1_safe_to_read(now: datetime, tz: ZoneInfo) -> bool` — False when local hour is even and minute < 5.
  - `p3_days(fetched_at: datetime, n: int, tz: ZoneInfo) -> list[date]` — n consecutive dates ending with local yesterday.
  - `p3_expected_len(fetched_at: datetime, tz: ZoneInfo) -> int` — days in previous month + (local day − 1).
  - `p4_months(fetched_at: datetime, n: int, tz: ZoneInfo) -> list[date]` — first-of-month dates ending with the previous month.
  - `@dataclass(frozen=True) class Tariff: cheap_start: time; cheap_end: time; cheap_price: Decimal; expensive_price: Decimal; zone: str`
  - `cheap_fraction(start: datetime, end: datetime, tariff: Tariff, tz: ZoneInfo) -> float` — share of the bucket's wall-clock duration inside the cheap window (1.0 / 0.0 for aligned buckets).

- [ ] **Step 1: Write failing tests** (all with `TZ = ZoneInfo("Europe/Belgrade")`):
  - `test_p1_at_0109`: fetched `2026-10-18T01:09+02:00`, n=24 → first start `2026-10-16T00:00+02:00`, last `(2026-10-17T22:00+02:00, 2026-10-18T00:00+02:00)`.
  - `test_p1_at_0200`: fetched `2026-10-18T02:00:30+02:00` → last start `2026-10-18T00:00+02:00`.
  - `test_p1_fall_back_bucket_is_3h`: fetched `2026-10-25T05:07+01:00` → bucket starting at `2026-10-25T00:00Z` (02:00 CEST) ends at `2026-10-25T03:00Z` (04:00 CET).
  - `test_p1_spring_forward_bucket_is_1h`: fetched `2027-03-28T05:07+02:00` → bucket `02–04` wall is `2027-03-28T01:00Z`–`02:00Z`.
  - `test_p1_safe_to_read`: `02:03` → False, `02:07` → True, `03:02` → True.
  - `test_p3_days_and_expected_len`: fetched `2026-10-18T02:10+02:00`, expected len 47, n=47 → first `2026-09-01`, last `2026-10-17`.
  - `test_p4_months`: fetched `2026-10-18`, n=21 → first `2025-01-01`, last `2026-09-01`.
  - `test_cheap_fraction`: default tariff; `00–02` → 1.0, `06–08` → 1.0, `08–10` → 0.0, a hypothetical `07–09` → 0.5.
  - `test_real_fixture_sums`: load `tests/fixtures/cons_2026-10-18T0109.json` (copy of the 01:09 `cons` record); day 2026-10-17 from `p=1` sums to 2.7 and equals `p=3` last value; September from `p=3` sums to `p=4` last value 24.975 (use `Decimal`/rounding to 3 places).
- [ ] **Step 2: Run** `bash server/ops/pytest.sh tests/test_buckets.py -v` → FAIL.
- [ ] **Step 3: Implement.** Walk back in naive wall-clock time in 2 h steps from the floored even hour, then attach the zone with `fold=0`; a non-existent wall time resolves forward per `zoneinfo` (02:00 on spring-forward = 03:00 CEST). Bucket end = next bucket's start.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(server): bucket alignment for Ariston consumption sequences`

### Task 3: Ariston client with etiquette

**Files:**
- Create: `server/boilerd/ariston.py`, `server/tests/test_ariston.py`

**Interfaces:**
- Produces: `class AristonClient(user: str, password: str, *, base_url: str = "https://www.ariston-net.remotethermo.com/api/v2/", transport: httpx.AsyncBaseTransport | None = None, sleep: Callable[[float], Awaitable[None]] = asyncio.sleep, clock: Callable[[], float] = time.monotonic)` with async methods `plants() -> list[dict]`, `state(gw) -> dict`, `plant_settings(gw) -> dict`, `consumption(gw) -> list[dict]`, `bus_errors(gw) -> list[dict]`. Each returns parsed JSON. Exceptions: `RateLimited(paused_until: float)`, `AristonError(status: int | None, message: str)`, `AuthFailed`. Attribute `last_call: CallRecord(endpoint: str, status: int | None, ms: int, error: str | None)` for `poll_log`.

- [ ] **Step 1: Write failing tests** with `respx`:
  - `test_login_once_then_reuse_token`: two `state()` calls → exactly one login request; second call carries the same `ar.authToken`.
  - `test_405_relogin_once`: first `GET` 405, login, retry 200 → result returned, 2 logins total; a second consecutive 405 after re-login raises `AuthFailed`.
  - `test_429_pauses_all_calls`: `state()` gets 429 → raises `RateLimited`; any call within 15 min (fake clock) raises `RateLimited` **without** an HTTP request; after 15 min calls go through.
  - `test_other_error_retries_once_after_5s`: 500 then 200 → returns, `sleep` called with 5; 500 then 500 → `AristonError`.
  - `test_headers`: `User-Agent: RestSharp/106.11.7.0` on every request.
  - `test_calls_are_serialized`: two concurrent `state()` calls never overlap (respx side effect asserts an in-flight counter ≤ 1).
  - `test_only_allowed_paths`: the client exposes no method that issues `POST` except login (assert by introspecting the recorded routes after calling every public method).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** with one `asyncio.Lock` around every request (serialization) and a `paused_until` timestamp. **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(server): read-only Ariston client with rate etiquette`

### Task 4: Ingest (state, energy, settings, errors, probe import)

**Files:**
- Create: `server/boilerd/ingest.py`, `server/tests/test_ingest.py`, `server/tests/fixtures/*.json`

**Interfaces:**
- Consumes: Task 1 pool, Task 2 functions.
- Produces:
  - `@dataclass(frozen=True) class StateSample: at: datetime; on: bool; mode: int; temp: float; req_temp: float; proc_req_temp: float | None; eco: bool; heat_req: bool; anti_leg: bool; rm_tm: timedelta | None; av_shw: int | None; pwr_opt: bool | None; raw: dict`
  - `parse_state(raw: dict, at: datetime) -> StateSample` (`rmTm` `"HH:MM:SS"` → timedelta)
  - `async store_state(conn, s: StateSample) -> StateSample | None` (returns the previous sample, for the detector)
  - `async store_consumption(conn, raw: list[dict], fetched_at: datetime, tz) -> ConsumptionResult(buckets: int, days: int, months: int, warnings: list[str])` — uses only `k == 2`; upserts `energy_2h` (`last_seen_at` updated, `first_seen_at` kept), `energy_day` (`source='p3'`), `energy_month` (`source='p4'`); skips `p=1` when `p1_safe_to_read` is False; adds a warning when `len(p3) != p3_expected_len`.
  - `async daily_crosscheck(conn, day: date, tz) -> str | None` — warning when `abs(sum(energy_2h for day) − energy_day.kwh) > 0.05`.
  - `async store_plant_settings(conn, raw: dict, at) -> bool` (inserts only when different from the latest row)
  - `async store_bus_errors(conn, raw: list[dict], at) -> list[dict]` (returns newly seen errors; marks missing ones `cleared_at`)
  - `async import_probe_log(conn, path: Path, tz) -> int` — imports `kind == "state"` (status 200) and `kind == "cons"` lines, idempotent.

- [ ] **Step 1: Write failing tests:** `test_parse_state_fixture` (01:09 record: temp 44.0, req_temp 55.0, eco True, heat_req True, rm_tm 1:05:00); `test_store_consumption_upserts` (store 01:09 then 02:10 fixtures → 25 distinct `energy_2h` rows, `2026-10-18T00:00+02:00` = 2.25); `test_store_consumption_skips_p1_near_boundary` (fetched 02:03 → `buckets == 0`, days still stored); `test_daily_crosscheck_flags_mismatch` (tamper one bucket by +0.1 → warning string mentions the day); `test_plant_settings_change_only` (same payload twice → 1 row); `test_bus_errors_new_and_cleared`; `test_import_probe_log_idempotent` (import a 5-line fixture twice → same row counts).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(server): ingest state, energy buckets, settings, errors and probe log`

### Task 5: Event detection and attribution

**Files:**
- Create: `server/boilerd/events.py`, `server/tests/test_events.py`

**Interfaces:**
- Consumes: `StateSample`, `Tariff`, `cheap_fraction`.
- Produces:
  - `@dataclass(frozen=True) class EventDraft: at: datetime; kind: str; detail: dict; severity: str = "info"`
  - `detect(prev: StateSample | None, cur: StateSample, recent: list[StateSample], tariff: Tariff, tz) -> list[EventDraft]` — kinds exactly as §4.4: `power_on, power_off, target_changed, eco_changed, mode_changed, heating_started, heating_stopped, expensive_heating_started, expensive_heating_stopped, water_draw, anti_legionella`; `mode_changed` to anything ≠ 1 has severity `warning`; `water_draw` when `max(temp over recent within 6 min) − cur.temp ≥ 2` and no `water_draw` among `recent` events in the last 10 min (pass previous draw time in `recent`-derived state; keep the function pure).
  - `@dataclass(frozen=True) class AppAction: install_id: str; local_id: int; at: datetime; kind: str; trig: str; detail: str; outcome: str; state_json: str | None`
  - `@dataclass(frozen=True) class AppIntent: install_id: str; at: datetime; source: str; desired: dict; next_transition_at: datetime | None`
  - `attribute(ev: EventDraft, actions: list[AppAction], last_action_seen_at: datetime | None, intent: AppIntent | None, cur: StateSample) -> str | None` — rules of §4.4: for `power_*/target_changed/eco_changed/mode_changed`: `app` if a `SUCCESS` action exists in `[ev.at − 3 min, ev.at + 5 min]`; `unknown` if `last_action_seen_at` is None or older than 6 h; else `external`. For `heating_started`/`expensive_heating_started` outside the cheap window: `app` if `intent.source in {"BOOST","HEAT_BY_TIME","KEEP_WARM"}`; `firmware_eco` if `cur.eco`; else `firmware`. Other kinds → None.
  - `async persist_events(conn, drafts, attribution_fn) -> int`
  - `async reattribute_recent(conn, since: datetime) -> int` — re-runs attribution for events with `attribution in ('unknown','external')` after a late action upload (used by Task 8).
- [ ] **Step 1: Write failing tests:** one per kind (`test_detect_target_changed`, `test_detect_heating_outside_window_opens_expensive`, `test_detect_water_draw_from_fixture` using the real 01:27→01:31 drop 45→41, `test_water_draw_not_repeated_within_10_min`, `test_mode_9_is_warning`); attribution: `test_attr_app_within_window`, `test_attr_external_without_action`, `test_attr_unknown_when_phone_silent_6h`, `test_attr_firmware_eco_daytime_heating`, `test_reattribute_after_late_upload`.
- [ ] **Step 2–4:** run (FAIL) → implement → run (PASS).
- [ ] **Step 5: Commit** `feat(server): change events with app/external/firmware attribution`

### Task 6: Poller

**Files:**
- Create: `server/boilerd/poller.py`, `server/tests/test_poller.py`

**Interfaces:**
- Consumes: `AristonClient`, ingest functions, `detect`/`persist_events`.
- Produces: `class Poller(client: AristonClient, pool, settings: Settings, now: Callable[[], datetime])` with `async run_forever()`, `async tick() -> list[str]` (runs whatever jobs are due at `now()`, returns their names), and `due_jobs(now) -> list[str]` (pure). Job names: `plants, state, energy, backfill, settings, errors, crosscheck`. Offline: after 3 consecutive failed `state` calls write event `device_offline` (once), first success after that writes `device_online`. Each newly seen bus error from `store_bus_errors` becomes a `device_error` event (severity `warning`). Every call writes a `poll_log` row; rows older than 30 days are deleted by `crosscheck`. On first tick: `plants`, `backfill` (`p=3/p=4` via `consumption`), `import_probe_log` if `settings.probe_log` exists and `state_sample` is empty.
- [ ] **Step 1: Write failing tests** with a fake client and fake clock: `test_due_jobs_schedule` (at `02:07` → contains `energy`; at `02:03` → no `energy`; at `02:10` daily → `backfill` and `crosscheck`); `test_state_every_120s`; `test_offline_after_3_failures_and_online_again`; `test_new_bus_error_becomes_event`; `test_rate_limited_skips_cycle_without_crash`; `test_poll_log_written_per_call`.
- [ ] **Step 2–4:** FAIL → implement (single task loop, `asyncio.sleep` until the next due time, every exception caught and logged) → PASS.
- [ ] **Step 5: Commit** `feat(server): poller with schedule, offline detection and poll log`

### Task 7: Analytics

**Files:**
- Create: `server/boilerd/analytics.py`, `server/tests/test_analytics.py`

**Interfaces:**
- Produces (all `async`, first arg `conn`):
  - `energy(conn, frm: datetime, to: datetime, granularity: Literal["2h","day","month"]) -> list[EnergyRow]`; `EnergyRow(start: datetime, kwh: Decimal, cheap_kwh: Decimal | None, expensive_kwh: Decimal | None, cost_din: Decimal | None, waste_din: Decimal | None)`. Split fields are `None` when the period has no `energy_2h` coverage (old history). `waste_din = expensive_kwh × (expensive_price − cheap_price)`. Tariff row chosen by `valid_from ≤ bucket start`.
  - `state_series(conn, frm, to, step: timedelta) -> list[dict]` (time-bucketed avg temp, max req_temp, any heat_req)
  - `now_summary(conn) -> dict` (`state`, `age_s`, `today` energy split, open `expensive_heating`, last intent)
  - `month_report(conn, month: date) -> Report(json: dict, markdown: str)` — kWh, cheap %, din, waste din, kWh/day, same month last year, top 5 expensive events with attribution.
  - `insights(conn, season: Literal["zima","prolece","leto","jesen"] | None) -> dict` with keys `kwh_per_day_by_month`, `standby_loss_w`, `heating_rate_c_per_h` (`{"eco": x, "no_eco": y}`), `kwh_per_c`, `hot_water_l40_per_day`, `recommended_target` (`{"temp": int, "coverage": float, "days": int}` or None when < 14 days), `savings_kwh_per_month`.
  - `plan_vs_actual(conn, day: date) -> dict` (§4.5 item 7): from `app_intent` and `state_sample`, planned heating intervals vs actual `heat_req` intervals, kWh outside the plan split by attribution.
  - Pure helpers with unit tests: `standby_loss_w(c_per_h: float) -> float` (`c_per_h × 80 × 1.163`), `recommend_target(day_minima: list[tuple[float, float]], comfort=40.0, coverage=0.95) -> int | None` where each tuple is `(min_temp, target_then)`; the smallest integer `T ≤ target_then` with `min_temp − (target_then − T) ≥ comfort` on ≥ 95 % of days.
- [ ] **Step 1: Write failing tests:** `test_energy_day_split_2026_10_17` (insert the real 17.10 buckets → cheap 1.625, expensive 1.075, cost = 1.625×3.6051 + 1.075×14.4203 rounded to 2 dp = 21.36, waste = 1.075×10.8152 = 11.63); `test_energy_month_old_history_has_no_split`; `test_standby_loss_w` (`0.5 °C/h → 46.52 W`); `test_recommend_target` (minima `[(48,55)]*19 + [(43,55)]`: coverage 0.95 → 47, coverage 1.0 → 52; only 10 days → None); `test_plan_vs_actual_counts_offplan_kwh` (intent says off after 07:30, real 12–16 h buckets 1.075 kWh with a `firmware_eco` event → `offplan_kwh == 1.075`, `by_attribution == {"firmware_eco": 1.075}`); `test_month_report_markdown_mentions_waste` (Serbian text contains `din` and `jeftino`).
- [ ] **Step 2–4:** FAIL → implement (SQL with `date_trunc(... AT TIME ZONE 'Europe/Belgrade')`) → PASS.
- [ ] **Step 5: Commit** `feat(server): tariff split, reports and insights`

### Task 8: REST API

**Files:**
- Create: `server/boilerd/api.py`, `server/tests/test_api.py`

**Interfaces:**
- Consumes: analytics, events (`reattribute_recent`).
- Produces: `router: APIRouter` (prefix `/api/v1`) with dependency `require_token` (`Authorization: Bearer <BOILER_API_TOKEN>`, `secrets.compare_digest`, 401 otherwise). Routes and payloads exactly §4.6:
  - `POST /app/actions` body `{"install_id": str, "actions": [{"local_id": int, "at": iso, "kind", "trig", "detail", "outcome", "attempt": int, "state_json": str|null}]}` (max 500) → `{"accepted": n, "duplicates": m}`; `ON CONFLICT DO NOTHING`; then `reattribute_recent(at of oldest action)`.
  - `POST /app/intent` body `{"install_id", "at", "source", "desired": {...}, "next_transition_at": iso|null}` → 204.
  - `GET /now`, `GET /series/state?from&to&step=5m`, `GET /energy?from&to&granularity`, `GET /events?from&to&kinds=a,b&attribution=external`, `GET /insights?season=`, `GET /report?month=2026-10` → JSON from analytics.
  - `GET /health` (no token) → `{"ok": bool, "last_state_at": iso|null, "last_energy_at": iso|null, "paused_until": iso|null}`; `ok` false when last state is older than 10 min.
- [ ] **Step 1: Write failing tests** (FastAPI `TestClient` on a test app with the router + test DB): `test_requires_token`; `test_ingest_actions_idempotent` (same batch twice → `accepted 2` then `duplicates 2`, 2 rows); `test_ingest_triggers_reattribution` (event `external` becomes `app`); `test_energy_endpoint_shape`; `test_health_without_token`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(server): REST API for the app`

### Task 9: MCP server

**Files:**
- Create: `server/boilerd/mcp_tools.py`, `server/boilerd/app.py`, `server/tests/test_mcp.py`

**Interfaces:**
- Consumes: analytics, API pool, `Settings.db_ro_dsn`.
- Produces: `build_mcp(pool, ro_dsn) -> <SDK server>` exposing tools with these exact names and parameters (§4.7): `boiler_now()`, `boiler_state_series(from_: str, to: str, step: str = "5m")`, `boiler_energy(from_: str, to: str, granularity: str = "day")`, `boiler_events(from_: str, to: str, kinds: list[str] | None = None, attribution: str | None = None)`, `boiler_report(month: str)`, `boiler_insights(season: str | None = None)`, `boiler_explain(at: str)` (state before/after, actions ±30 min, events ±30 min, attribution, one Serbian paragraph), `boiler_health()`, `boiler_sql(query: str)` (new connection on `ro_dsn`, rejects anything but a single statement starting with `SELECT`/`WITH`, `fetchmany(1000)`, returns `{"columns", "rows", "truncated"}`). Every tool returns structured data plus a short Serbian `summary` string. Resource `boiler://schema` = markdown description of every table, the bucket semantics of §2 and the tariff rules.
  `app.py`: `app = FastAPI(lifespan=...)`; lifespan creates the pool, runs `migrate`, starts `Poller.run_forever()` as a task, and enters the MCP session manager (`async with mcp.session_manager.run()`); routes: `/api/v1` router first, then the MCP app mounted so the final endpoint is exactly `/mcp`; bearer token required on `/mcp` too (ASGI middleware). Configure the SDK's transport security to allow hosts `127.0.0.1:8020`, `localhost:8020`, `novica-dev`, `100.116.228.19`. Use stateless streamable HTTP with JSON responses. Check the installed SDK major version: v1 exposes `FastMCP`, v2 renamed it `MCPServer` and moved transport options to `streamable_http_app(...)`.
- [ ] **Step 1: Write failing tests** with the SDK's client (`streamablehttp_client` against the ASGI app via `httpx.ASGITransport`, or the SDK's in-memory client session): `test_list_tools_names` (exact set of 9 names); `test_boiler_energy_matches_api`; `test_boiler_sql_rejects_writes` (`DELETE FROM event` → error, table unchanged); `test_boiler_sql_truncates_at_1000`; `test_mcp_requires_token`; `test_mcp_accepts_proxied_host` (request with `Host: novica-dev` → not 421).
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(server): MCP server for agents`

### Task 10: Container, deploy, go live

**Files:**
- Create: `server/Dockerfile`, `server/docker-compose.yml`, `server/README.md`

- [ ] **Step 1: Write the compose file**: service `boilerd`, `build: .`, `restart: unless-stopped`, `ports: ["127.0.0.1:8020:8020"]`, `env_file: [/data/secrets/dobarboiler.env, /data/secrets/dobarboiler-server.env]`, `environment: DB_HOST=shared-postgres, BOILERD_PROBE_LOG=/probe/log.jsonl`, volume `/data/tmp/boiler-probe:/probe:ro`, network `dev-shared` (external), `cpus: 0.5`, `mem_limit: 256m`, user 1000. Healthcheck `GET /api/v1/health`.
- [ ] **Step 2: Verify port free**: `ss -ltn | grep -c ':8020 '` → `0`.
- [ ] **Step 3: Build and start**: `docker compose -f server/docker-compose.yml up -d --build`. Expected within 30 s: `curl -s 127.0.0.1:8020/api/v1/health` → `"ok": true`.
- [ ] **Step 4: Check data**: via `docker exec shared-postgres psql -U postgres -d dobarboiler -c "select count(*) from state_sample"` → > 40 (probe import); `energy_month` → 21 rows; `energy_day` ≥ 47 rows.
- [ ] **Step 5: Expose on the tailnet**: `preview add boiler 8020`; `curl -s http://novica-dev/boiler/api/v1/health` → ok.
- [ ] **Step 6: After 24 h of clean polling**, stop the probe logger (`pkill -f boiler-probe/logger.py` after checking with `pgrep -af`) and ask Novica whether the phone app stayed logged in (design §10). If not: the fallback guest account goes into `SERVER_ARISTON_USER/PASS`.
- [ ] **Step 7: Commit** `feat(server): container and deployment`

### Task 11: Grafana

**Files:**
- Create: `server/grafana/boiler.json`
- Modify (outside the repo, keep a `.bak`): `/data/srv/observability/grafana-datasources.yml`, `/data/srv/observability/docker-compose.yml`

- [ ] **Step 1:** Add datasource `{name: DobarBoiler, uid: dobarboiler, type: grafana-postgresql-datasource, url: shared-postgres:5432, user: dobarboiler_ro, jsonData: {database: dobarboiler, sslmode: disable, timescaledb: false}, secureJsonData: {password: $DOBARBOILER_RO_PASSWORD}}`; give the `grafana` service the env var (already in `.env` from Task 0) and the external network `dev-shared`.
- [ ] **Step 2:** Dashboard panels (timezone `Europe/Belgrade`): temperature + target time series with heating as a state band and the 00–08 window as a region; 2 h stacked bars cheap/expensive (colors light `#4152B8` / `#C27C0E`, from the validated palette in the mockups); daily and monthly bars; events table with attribution; poller health (last success per endpoint, 429 count). Import via the Grafana HTTP API (admin password from the rendered observability `.env`, read inside the command, never printed) or provisioning — implementer's choice; commit the JSON either way.
- [ ] **Step 3: Verify**: `docker compose -f /data/srv/observability/docker-compose.yml up -d grafana`; Grafana datasource health API returns OK; dashboard renders (`http://novica-dev:3300`).
- [ ] **Step 4: Commit** `feat(server): Grafana dashboard`

### Task 12: Backup, MCP registration, docs

**Files:**
- Create: `server/ops/dobarboiler-backup`, `server/ops/dobarboiler-backup.service`, `server/ops/dobarboiler-backup.timer`
- Modify: `CLAUDE.md` (repo), `/home/ubuntu/.claude/CLAUDE.md` (global, "Running applications" section), `~/.claude/infra/novica-dev.md`

- [ ] **Step 1:** Backup script modeled on `/usr/local/bin/cvgen-backup`: `docker exec shared-postgres pg_dump -U postgres -Fc dobarboiler` → restic repo `dev-box/dobarboiler` (password from `/data/secrets/restic.env`), keep 7 daily / 4 weekly / 6 monthly, write `/data/tmp/dobarboiler-backup.last-ok`. Timer 03:50 daily. Install to `/usr/local/bin` and `/etc/systemd/system`, `systemctl enable --now dobarboiler-backup.timer`. Run once by hand; `restic snapshots` shows one snapshot.
- [ ] **Step 2:** Register MCP for Claude Code on the box: `claude mcp add --scope user --transport http dobarboiler http://127.0.0.1:8020/mcp --header "Authorization: Bearer $BOILER_API_TOKEN"` run inside a subshell that loads only `dobarboiler-server.env`. Verify `claude mcp list` shows `dobarboiler` connected. Give Novica the laptop command with `http://novica-dev/boiler/mcp`.
- [ ] **Step 3:** Docs: repo `CLAUDE.md` gets a "History server" section (paths, port, preview, commands, test wrapper, never-send-commands rule); global CLAUDE.md gets a bullet under "Running applications" and the new SSM parameter in the list.
- [ ] **Step 4: Commit** `docs: history server operations` and open the PR.
