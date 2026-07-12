# Ariston Cloud HTTP Protocol — Velis Lux Water Heater

**Scope:** Complete, implementation-ready HTTP protocol specification for controlling an Ariston **Velis Lux** electric water heater (`SystemType = 4` VELIS, `WheType = 8` Lux) via the Ariston cloud API.

**Source of truth:** Reverse-engineered reference implementation — the `ariston` Python library (files: `ariston_api.py`, `const.py`, `velis_base_device.py`, `velis_device.py`, `evo_device.py`, `evo_lydos_device.py`, `lux_device.py`, `base_device.py`, `__init__.py`).

---

## 0. Base URL and global conventions

```
Base URL: https://www.ariston-net.remotethermo.com/api/v2/
```

All endpoint paths below are relative to this base URL (note the trailing `/api/v2/`).

### Headers sent on EVERY request (including login)

| Header         | Value                              | Notes |
|----------------|------------------------------------|-------|
| `User-Agent`   | `RestSharp/106.11.7.0`             | The library impersonates the official app's HTTP client. Send exactly this string. |
| `ar.authToken` | `<token>`                          | Auth token from login. On the login request itself the library sends this header with an **empty string** value (harmless). |
| `Content-Type` | `application/json`                 | Implicit — the library serializes bodies as JSON (`requests`/`aiohttp` `json=` parameter). Send `application/json; charset=utf-8`. |

- All request/response bodies are JSON.
- No cookies, no `Authorization: Bearer` header — auth is **only** the custom `ar.authToken` header.
- The library sets a client-side timeout; use something generous (30–60 s) since the cloud can be slow.
- **Key device identifier:** `gw` (gateway ID) — a string like `"F0AD4E056C99"`, obtained from device discovery. It is the `{gwId}` path segment in all device-specific URLs.

### CRITICAL Retrofit note: raw JSON boolean bodies

Several SET endpoints (`/switch`, `/switchEco`, `/switchPowerOption`, `/boost`) take a request body that is the **bare JSON literal `true` or `false`** — NOT an object like `{"value": true}`. In Retrofit/kotlinx-serialization/Moshi this means the `@Body` parameter must be a plain `Boolean` serialized to the literal `true`/`false` (with `Content-Type: application/json`).

---

## 1. Authentication

### 1.1 Login

```
POST /accounts/login
Full URL: https://www.ariston-net.remotethermo.com/api/v2/accounts/login
```

**Request body:**

```json
{
  "usr": "pejovic.vuki@gmail.com",
  "pwd": "your-password"
}
```

**Response `200 OK` (body):**

```json
{
  "token": "8c3a5b1e-....-long-opaque-string",
  ...
}
```

The only field the library reads is `token` (top-level string). Other fields may be present (account info) — ignore them.

### 1.2 Using the token

Every subsequent request carries the token in the custom header:

```
ar.authToken: 8c3a5b1e-....-long-opaque-string
```

### 1.3 Token expiry / renewal

- There is **no refresh endpoint** and no expiry timestamp in the login response.
- The cloud signals an expired/invalid token by responding **`405 Method Not Allowed`** to any request.
- Renewal strategy (exactly what the library does): on a `405`, re-run the login (`POST /accounts/login` with stored credentials), store the new token, and **retry the original request once**. If the retried request also gets `405`, fail hard ("invalid token"). If re-login itself fails, fail hard ("login failed — password changed?").

---

## 2. Device discovery (remote plants list)

Two discovery lists exist; a Velis Lux water heater appears in the **Velis** list. (The library queries both and concatenates; for a Lux-only client you only need 2.1.)

### 2.1 Velis devices (the one you need)

```
GET /velis/plants
Full URL: https://www.ariston-net.remotethermo.com/api/v2/velis/plants
```

**Response `200 OK`:** JSON **array** of device objects. Fields (from `DeviceAttribute` + `VelisDeviceAttribute` constants):

```json
[
  {
    "gw": "F0AD4E056C99",
    "name": "Bojler kupatilo",
    "sn": "1234567890",
    "sys": 4,
    "wheType": 8,
    "wheModelType": 1,
    "lnk": true,
    "isOffline48H": false,
    "loc": "sr-RS",
    "utcOft": 2,
    "tcByGuest": false,
    "hpmpSys": false,
    "mqttApiVersion": null,
    "consumptionsSettings": null,
    "geofenceConfig": null,
    "weatherProvider": 0,
    "notifyOnCondensateTankFull": false,
    "notifyOnErrors": true,
    "notifyOnReadyShowers": false
  }
]
```

Field semantics:

| Field | Meaning |
|-------|---------|
| `gw` | **Gateway ID — use as `{gwId}` in every device URL.** |
| `name` | User-assigned plant name. |
| `sn` | Gateway serial number. |
| `sys` | System type: `1`=GALILEO1, `2`=GALILEO2, `3`=GALEVO, `4`=**VELIS**, `5`=BSB. |
| `wheType` | Water-heater type: `1`=Evo, `2`=LydosHybrid, `3`=Lydos, `4`=NuosSplit, `5`=Andris2, `6`=Evo2, `7`=Lux2, `8`=**Lux**. |
| `wheModelType` | Model sub-type int. |
| `lnk` | Gateway link/online flag. |
| `isOffline48H` | True if unreachable for 48 h. |

**Select your device:** filter `sys == 4 && wheType == 8`, take its `gw`.

### 2.2 Non-Velis devices (boilers/thermostats — not needed for Lux)

```
GET /remote/plants          → detailed list (Galevo etc.)
GET /remote/plants/lite     → lite list
```

---

## 3. Velis plant-data path segment (IMPORTANT)

Velis endpoints embed a **plant-data discriminator** in the path that depends on device family (`PlantData` enum):

| Path segment  | Device family |
|---------------|---------------|
| `medPlantData` | Evo, Evo2, Andris2, **Lux2, Lux** ← use this |
| `sePlantData`  | Lydos Hybrid |
| `slpPlantData` | Nuos Split |
| `plantData`    | Evo "One" (shower-count model) |
| `bsbPlantData` | BSB (under `/remote/`, not `/velis/`) |

**A Lux device (WheType 8) uses `medPlantData` for everything** (the Lux device class inherits from the Evo/Med device class). All URLs below are hard-coded to `medPlantData`.

---

## 4. Get state (Velis Lux plant data)

```
GET /velis/medPlantData/{gwId}
Full URL example:
https://www.ariston-net.remotethermo.com/api/v2/velis/medPlantData/F0AD4E056C99
```

**Response `200 OK`:**

```json
{
  "gw": "F0AD4E056C99",
  "on": true,
  "mode": 1,
  "temp": 54.0,
  "reqTemp": 65.0,
  "procReqTemp": 65.0,
  "eco": false,
  "pwrOpt": false,
  "antiLeg": false,
  "heatReq": true,
  "avShw": 2,
  "rmTm": "01:23:00"
}
```

Field reference (from `VelisBaseDeviceProperties` / `VelisDeviceProperties` / `EvoLydosDeviceProperties` / `EvoDeviceProperties`):

| Field | Type | Meaning |
|-------|------|---------|
| `gw` | string | Gateway ID (echo). |
| `on` | bool | Power state (on/off). |
| `mode` | int | Operation mode — see LuxPlantMode enum (§7): `1`/`5`/`9`. |
| `temp` | float | **Current** water temperature (°C). |
| `reqTemp` | float | **Target** (requested) temperature (°C). In BOOST mode (mode=9) the effective target is the max-setpoint maximum from plant settings, regardless of `reqTemp`. |
| `procReqTemp` | float or null | Temperature currently being processed toward (in-progress requested temp). |
| `eco` | bool | Eco mode active. |
| `pwrOpt` | bool | Power option (used by Lux2; may be present/false on Lux). |
| `antiLeg` | bool | Anti-legionella cycle currently **running**. |
| `heatReq` | bool | Heating element currently active (is heating). |
| `avShw` | int or null | Available (average) showers count. |
| `rmTm` | string "HH:MM:SS" or null | Remaining heat-up time. |

Poll this endpoint for state refresh. Temperature UI constraints (library hard-codes for Velis): min 40 °C, max = current `MedMaxSetpointTemperature` (§5.6/§6), step 1 °C, 0 decimals, unit °C.

### 4.1 Get plant settings

```
GET /velis/medPlantData/{gwId}/plantSettings
```

**Response `200 OK`** (keys from `MedDeviceSettings`; booleans come back as booleans, temps as floats):

```json
{
  "MedAntilegionellaOnOff": true,
  "MedHeatingRate": 1,
  "MedMaxSetpointTemperature": 65.0,
  "MedMaxSetpointTemperatureMin": 40.0,
  "MedMaxSetpointTemperatureMax": 80.0
}
```

| Key | Meaning |
|-----|---------|
| `MedAntilegionellaOnOff` | Anti-legionella feature enabled (setting, not the running-cycle flag). |
| `MedHeatingRate` | Heating rate / power level setting. |
| `MedMaxSetpointTemperature` | Current max-setpoint limit (also the effective max target temp). |
| `MedMaxSetpointTemperatureMin` / `...Max` | Allowed range for the max-setpoint setting. `...Max` is also the effective target in BOOST mode. |

---

## 5. SET operations (Lux)

All are `POST`, all return `200 OK` (body irrelevant/empty — treat any 2xx as success). After a successful set, the library optimistically updates local state; the cloud may take seconds to reflect it in GET responses.

### 5.1 Power on/off

```
POST /velis/medPlantData/{gwId}/switch
```

**Request body — bare JSON boolean:**

```json
true
```

(`true` = on, `false` = off.)

### 5.2 Target temperature

```
POST /velis/medPlantData/{gwId}/temperature
```

**Request body:**

```json
{ "new": 60.0 }
```

Float °C. Valid range: 40.0 … `MedMaxSetpointTemperature`, step 1.

### 5.3 Operation mode

```
POST /velis/medPlantData/{gwId}/mode
```

**Request body:**

```json
{ "new": 1 }
```

`new` is an int from LuxPlantMode (§7): `1` MANUAL, `5` PROGRAM, `9` BOOST.

### 5.4 Eco mode

```
POST /velis/medPlantData/{gwId}/switchEco
```

**Request body — bare JSON boolean:**

```json
true
```

### 5.5 Anti-legionella on/off (plant setting)

```
POST /velis/medPlantData/{gwId}/plantSettings
```

**Request body — old/new pattern, values sent as floats 1.0/0.0:**

```json
{
  "MedAntilegionellaOnOff": { "new": 1.0, "old": 0.0 }
}
```

`new` = desired (1.0 enable, 0.0 disable), `old` = current value you last read from `GET .../plantSettings` (1.0 if it was `true`, else 0.0). You must send the correct `old` value.

### 5.6 Max setpoint temperature (plant setting)

```
POST /velis/medPlantData/{gwId}/plantSettings
```

**Request body:**

```json
{
  "MedMaxSetpointTemperature": { "new": 75.0, "old": 65.0 }
}
```

`new` must lie within [`MedMaxSetpointTemperatureMin`, `MedMaxSetpointTemperatureMax`]; `old` = current value from GET plantSettings.

(General form of this endpoint: `{ "<SettingKey>": { "new": <float>, "old": <float> } }` — one setting key per call as used by the library.)

### 5.7 Power option (Lux2 feature; harmless to expose, may be no-op on Lux)

```
POST /velis/medPlantData/{gwId}/switchPowerOption
```

**Request body — bare JSON boolean:** `true` / `false`. Mirrors the `pwrOpt` state field.

---

## 6. Energy / consumption endpoints (Velis)

### 6.1 Consumption sequences (the one the library actually uses for Velis)

```
GET /remote/reports/{gwId}/consSequencesApi8?usages=Dhw
```

Note: this lives under `/remote/`, **not** `/velis/`. For Lux the `usages` query value is the literal string `Dhw` (the Evo/Lux device's consumption type).

**Response `200 OK`:** JSON array of sequence objects:

```json
[
  {
    "k": 21,
    "p": 1,
    "v": [0.5, 0.7, 0.3, 1.1, 0.9, 0.6, 0.8, 0.4]
  },
  {
    "k": 21,
    "p": 2,
    "v": [4.2, 5.1, 3.9, 4.8, 5.0, 4.4, 4.7]
  }
]
```

| Field | Meaning |
|-------|---------|
| `k` | Consumption **kind** (`ConsumptionType`): for a Lux you care about `21` = DOMESTIC_HOT_WATER_ELECTRICITY; other possible values: `1` CH total energy, `2` DHW total energy, `3` central cooling, `7` CH gas, `8` DHW heat-pump electricity, `9` DHW resistor electricity, `10` DHW gas, `20` CH electricity. |
| `p` | Time-interval/**p**eriod bucket (`ConsumptionTimeInterval`): `1` = last day, `2` = last week, `3` = last month, `4` = last year (library's own comment says this mapping is a best guess). |
| `v` | Array of numeric consumption values (kWh); **last element = most recent bucket**. The library reads `v[-1]`. |

"Today's energy use" = the `v[-1]` of the object with `k == 21 && p == 1`.

### 6.2 Energy account (aggregate report; available but not Velis-specific)

```
GET /remote/reports/{gwId}/energyAccount
```

Returns a dict of energy account data (the library passes it through untyped). Optional for a Lux client.

### 6.3 Consumption settings (tariffs/currency — optional)

```
POST /remote/plants/{gwId}/getConsumptionsSettings   (body: {})   → read
POST /remote/plants/{gwId}/consumptionsSettings      (body: settings dict) → write
```

Settings dict keys (`ConsumptionProperties`): `currency` (int, `Currency` enum: 2=EUR, 16=RUB, 19=GBP, 20=USD, …), `gasType` (int), `gasEnergyUnit` (int), `elecCost` (float), `gasCost` (float).

---

## 7. LuxPlantMode enum (mode ints for `mode` field and `/mode` SET)

From `const.py` `LuxPlantMode` (a `WaterHeaterMode` subclass):

| Value | Name    | Meaning |
|-------|---------|---------|
| `1`   | MANUAL  | Manual — heats to `reqTemp`. |
| `5`   | PROGRAM | Time-program schedule. |
| `9`   | BOOST   | Boost — heats at max; effective target = `MedMaxSetpointTemperatureMax` (the library overrides displayed target temp with this value while mode = 9). |

(For reference, related-family enums that are NOT Lux: `EvoPlantMode` {1,5}, `VelisPlantMode` {1,5,8 NIGHT}, `LydosPlantMode` {1,2,6,7}, `NuosSplitOperativeMode` {0..3}. Do not use these for WheType 8.)

---

## 8. Error handling and retry policy

Client-side behavior copied from the reference implementation (`__request` in `ariston_api.py`):

| HTTP status | Meaning | Required client behavior |
|-------------|---------|--------------------------|
| `2xx` | Success | If body non-empty → parse JSON; if empty body → success with no payload (typical for SET calls). |
| `405` | **Auth token invalid/expired** (yes, the cloud abuses 405 for this) | If this is the first attempt: re-login (`POST /accounts/login`), replace token, retry the request **once**. If retry also 405s → permanent auth failure. If re-login fails → credentials invalid ("password changed?"). |
| `404` | Resource not found | Treat as "no data": return null/empty, do **not** retry, do **not** error out. |
| `429` | Rate limited | Fail immediately with the response body text as the error message. **No retry** — surface to caller/backoff at a higher level. |
| any other non-2xx (500, 502, 503, timeout-ish statuses…) | Transient cloud error | Sleep **5 seconds**, retry the request **once**. If the retry also fails → raise error with the status code. |

Additional notes:

- The retry flag is per-request (max 1 retry, of either the auth-refresh kind or the 5-second-sleep kind).
- Network-level failures during login are wrapped as a connection exception; there is no automatic re-login loop — implement your own top-level backoff.
- Do not hammer the API: the cloud rate-limits (429). The HA integration polls state roughly once per minute; keep GET state polling ≥ 30–60 s apart and energy polling much rarer (e.g. hourly).

---

## 9. Endpoint summary (quick reference)

| # | Method | Path (relative to `https://www.ariston-net.remotethermo.com/api/v2/`) | Body | Purpose |
|---|--------|------------------------------------------------------------------------|------|---------|
| 1 | POST | `accounts/login` | `{"usr","pwd"}` | Login → `{"token"}` |
| 2 | GET  | `velis/plants` | — | Discover Velis devices |
| 3 | GET  | `velis/medPlantData/{gwId}` | — | Lux state |
| 4 | GET  | `velis/medPlantData/{gwId}/plantSettings` | — | Lux settings (antileg, max setpoint) |
| 5 | POST | `velis/medPlantData/{gwId}/switch` | `true`/`false` (bare) | Power on/off |
| 6 | POST | `velis/medPlantData/{gwId}/temperature` | `{"new": 60.0}` | Set target temp |
| 7 | POST | `velis/medPlantData/{gwId}/mode` | `{"new": 1}` | Set mode (1/5/9) |
| 8 | POST | `velis/medPlantData/{gwId}/switchEco` | `true`/`false` (bare) | Eco on/off |
| 9 | POST | `velis/medPlantData/{gwId}/switchPowerOption` | `true`/`false` (bare) | Power option (Lux2) |
| 10 | POST | `velis/medPlantData/{gwId}/plantSettings` | `{"MedAntilegionellaOnOff":{"new":1.0,"old":0.0}}` | Anti-legionella on/off |
| 11 | POST | `velis/medPlantData/{gwId}/plantSettings` | `{"MedMaxSetpointTemperature":{"new":75.0,"old":65.0}}` | Max setpoint |
| 12 | GET  | `remote/reports/{gwId}/consSequencesApi8?usages=Dhw` | — | Energy consumption sequences |
| 13 | GET  | `remote/reports/{gwId}/energyAccount` | — | Energy account (optional) |

Headers on all: `User-Agent: RestSharp/106.11.7.0`, `ar.authToken: <token>` (empty on login), JSON bodies.
