# Ariston API — verifikovani nalazi (2026-07-12)

Testirano uživo sa `python-ariston-api` v0.19.9 (`pip install ariston`) i stvarnim uređajem.

## Uređaj

| Polje | Vrednost |
|---|---|
| Model | Ariston Velis Tech Wifi 80 |
| Ime u cloud-u | <ime uređaja> |
| Gateway (MAC) | `<gateway-id>` |
| Serijski broj | `<serial>` |
| SystemType | 4 = VELIS |
| WheType | **8 = Lux** (ne Evo2!) → klasa `AristonLuxDevice` |
| wheModelType | 27 |
| mqttApiVersion | 8 |
| Firmware | FwVer1 03.02.00 / FwVer2 014.115.26 |

## API

- Base URL: `https://www.ariston-net.remotethermo.com/api/v2/`
- Autentifikacija: Ariston NET nalog (isti kredencijali kao zvanična aplikacija).
  Login vraća token; python biblioteka je referenca za tačne endpoint-e i payload-e.
- Nema lokalnog API-ja — bojler komunicira isključivo sa Ariston cloud-om,
  pa aplikacija radi sa bilo koje mreže (i van kuće).

## Stanje (state) — polja za Velis Lux

```json
{
  "on": true,
  "mode": 1,
  "temp": 45.0,
  "reqTemp": 58.0,
  "procReqTemp": 58.0,
  "antiLeg": false,
  "heatReq": true,
  "eco": true,
  "rmTm": "01:10:00",
  "avShw": 1,
  "pwrOpt": false
}
```

- Opseg temperature: **40–75°C**, korak 1°C
- Režimi (`mode` options): `[1, 5, 9]` — mapirati tačna imena iz `ariston.const` enum-a
- `rmTm` = preostalo vreme grejanja, `avShw` = broj raspoloživih tuševa

## Dostupne komande (potvrđeno na uređaju)

| Metoda | Efekat |
|---|---|
| `set_power(bool)` | uključi/isključi bojler |
| `set_water_heater_temperature(float)` | zadaj ciljnu temperaturu |
| `set_water_heater_operation_mode(mode)` | promeni režim rada |
| `set_eco_mode(bool)` | eco on/off |
| `set_antilegionella(bool)` | antilegionela ciklus |
| `set_max_setpoint_temp(float)` | maksimalna dozvoljena temperatura |

Sve postoje i kao `async_*` varijante. Čitanja: `update_state()`, `update_energy()`, `get_features()`.

## Ograničenja

- **Fizički dugmići na bojleru se NE mogu remapirati** — vezani su za firmware.
  Može se samo detektovati promena stanja koju izazovu (polling) i reagovati.
- API je nezvaničan; Ariston ga može promeniti (HA integracija na njemu radi godinama stabilno).
- Bus ka bojleru je spor — ne slati komande/upite agresivno (HA integracija koristi
  poll interval od ~30–60s).

## Test

```powershell
$env:ARISTON_USER='...'; $env:ARISTON_PASS='...'
python tools\ariston_test.py
```
