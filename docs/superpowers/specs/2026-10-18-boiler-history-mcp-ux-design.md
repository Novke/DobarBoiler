# DobarBoiler: istorija, analitika, MCP za agente i novi UX — dizajn

Datum: 2026-10-18 · Status: predlog, čeka Novicino odobrenje · Autor: Claude (na osnovu razgovora sa Novicom)

## 1. Zašto

Aplikacija DobarBoiler upravlja bojlerom (Ariston Velis Tech Wifi 80) preko Ariston cloud-a, ali Novica ne vidi
šta se zaista dešavalo: ponekad izgleda kao da zvanična Ariston aplikacija ili nešto drugo pregazi odluke naše
aplikacije, i nema načina da se to potvrdi ili opovrgne. Telefon vidi bojler samo kad se probudi, a cloud ne
čuva istoriju stanja.

Cilj je da imamo **nezavisnog svedoka**: server koji stalno beleži stanje bojlera i potrošnju, spaja to sa
odlukama aplikacije i odgovara na pitanja:

1. Kako se kretala temperatura i kada je grejač radio, u svakom trenutku.
2. Koliko je struje potrošeno u **jeftinoj tarifi (00–08 h)** a koliko u skupoj, i šta je to koštalo.
3. Ko je promenio stanje: naša aplikacija, nešto spolja (zvanična app ili dugme), ili sam bojler.
4. Koliko tople vode Novica stvarno troši, leti i zimi, i koja je najniža temperatura koja je dovoljna.
5. Mesečni izveštaji: kWh, dinari, udeo jeftine struje, poređenje sa prošlom godinom.

Uz to: **MCP server** da agenti (Claude Code na ovoj mašini i laptopu) lako čitaju sve te podatke, i
**osveženi UX aplikacije** koji te podatke prikazuje i koji je gladak, moderan i intuitivan.

Rupe u istoriji su prihvatljive (Novica: „koliko god zabeležimo, zabeležimo“).

## 2. Šta je potvrđeno uživo (2026-10-18, samo čitanje)

| Izvor | Sadržaj | Istorija u cloud-u |
|---|---|---|
| `GET velis/medPlantData/{gw}` | `on, mode, temp, reqTemp, procReqTemp, eco, heatReq, antiLeg, rmTm, avShw, pwrOpt, eco_version` | nema, samo trenutak |
| `GET remote/reports/{gw}/consSequencesApi8?usages=Dhw` | `k=2` (DHW ukupna energija, kWh, korak 0.025) | vidi ispod |
| `GET velis/medPlantData/{gw}/plantSettings` | `MedHeatingRate`, antilegionela, max setpoint i granice | nema |
| `GET busErrors?gatewayId={gw}&blockingOnly=False&culture=en-US` | greške uređaja | trenutno `[]` |
| `GET remote/plants/{gw}/features` | `hasMetering: true`, `dhwProgSupported: true` | — |
| `GET remote/reports/{gw}/energyAccount` | HTTP 599 gateway error | ne radi na ovom uređaju |

Nizovi potrošnje (`p` = period, poslednji element = najnoviji **zatvoreni** bucket):

| `p` | Bucket | Broj | Pokrivenost | Napomena |
|---|---|---|---|---|
| 1 | 2 sata, poravnato na parne lokalne sate | 24 | 48 h | niz se pomera na vrhu svakog parnog sata; tekući bucket se ne vidi |
| 2 | dan | 7 | 7 dana | zadnji = juče |
| 3 | dan | promenljivo (47 na dan 17.10.) | prethodni kalendarski mesec + tekući do juče | zadnji = juče; 30 dana septembra daju tačno vrednost septembra iz `p=4` |
| 4 | mesec | 21 | jan 2025 – poslednji završen mesec | zadnji = septembar 2026 (24.975 kWh); potrošnja počinje u junu 2025 |

Dokaz za 2 h: u 01:09 zbir poslednjih 12 vrednosti `p=1` (2.7 kWh) jednak je poslednjoj vrednosti `p=2`
(juče); u 02:00 niz se pomerio za jedno mesto i dobio 2.25 kWh za 00–02 h, dok je grejač radio od pre 01:09.
Python biblioteka `ariston` pogrešno pretpostavlja da je `p=1` „poslednji dan po satu“.

Posmatranja iz probnog logera (`/data/tmp/boiler-probe/log.jsonl`, stanje na 2 min od 01:09):

- Temperatura u koracima od 1 °C; eco grejanje je dizalo vodu oko 8 °C/h (aplikacija podrazumeva 15 °C/h).
- U 01:27–01:31 temperatura je pala 45 → 41 °C dok je grejač radio: potrošnja tople vode se vidi u podacima.
- Subota 17.10. 12–16 h: 1.075 kWh u skupoj tarifi (0.425 + 0.65). Petak 16.10. 10–14 h: 0.325 kWh. Prozor
  aplikacije je 00:30–07:30.
- Višestruko logovanje sa servera tokom noći nije pokvarilo ništa vidljivo (telefon još treba potvrditi, §10).

**Hipoteza H1 (glavni sumnjivac za „pregazilo me“):** Ariston „eco“ na Velis-u je funkcija ECO EVO koja uči
navike i **sama prilagođava raspored grejanja**. Aplikacija tretira eco kao „sporije, štedljivije grejanje“
(`ecoPenaltyFactor`) i pali ga po defaultu (fallback preset `eco = true`, `KEEP_WARM` sa `eco = true`). Ako je
bojler uključen sa eco van prozora, firmware može da greje kad sam proceni. Server mora da potvrdi ili odbaci
H1: svaki `heatReq = true` van jeftine tarife se beleži sa `eco` flagom i poslednjom akcijom aplikacije.

## 3. Ciljevi, ne-ciljevi, anti-ciljevi

**Ciljevi**

- G1. Stanje bojlera na svaka 2 min trajno u bazi; potrošnja po 2 h, danu i mesecu bez rupa dok god je server
  bio dole kraće od 48 h (cloud čuva 48 h, pa server dopuni sam).
- G2. Odmah pri prvom pokretanju: uvoz 47 dana dnevne i 21 mesec mesečne istorije iz cloud-a.
- G3. Podela potrošnje na jeftinu i skupu tarifu, sa cenom u dinarima, po danu, mesecu, godini.
- G4. Događaji: svaka promena stanja, svaka akcija aplikacije (sa telefona), svako grejanje u skupoj tarifi,
  i atribucija: aplikacija / spolja / termostat bojlera.
- G5. Analitika: sezonske potrebe, gubitak u mirovanju, potrošnja tople vode, brzina grejanja, predlog
  najniže dovoljne temperature, procena moguće uštede.
- G6. MCP server sa alatima samo za čitanje, za agente.
- G7. Grafana dashboard za dubinsku analizu.
- G8. Novi UX aplikacije: nova informaciona arhitektura, tab „Potrošnja“ sa graficima, „Dnevnik“ kao
  transparentna vremenska linija, uglađena početna, raspored kao 24-časovne trake.

**Ne-ciljevi**

- Server **ne šalje komande** bojleru. Odluke ostaju u aplikaciji na telefonu. Server je svedok i analitičar.
- MCP nema alate za upis ili upravljanje bojlerom.
- Javni pristup sa interneta: sve ide samo kroz tailnet.
- Visoka dostupnost: dev mašina je spot instanca, rupe su prihvatljive.
- Tačna EPS zelena/plava/crvena zona: bojler je samo deo računa domaćinstva, pa se koristi jedan
  konfigurabilan par cena (§5.4).
- Widget za početni ekran i Quick Settings pločica: lepo bi bilo, ali posle ovoga (§9).

**Anti-ciljevi (neuspeh čak i ako tehnički radi)**

- Server ili MCP koji preopterete Ariston cloud i izazovu 429 ili blokadu naloga, pa telefon prestane da radi.
- Server koji svojim logovanjem izbacuje telefon iz sesije.
- Aplikacija koja postane sporija ili zavisna od servera: bez tailnet-a mora da radi tačno kao danas.
- UX koji izgleda moderno a sakrije bitno: Novica mora za 2 sekunde da vidi šta bojler radi, zašto, i šta sledi.
- Bilo koja tajna u git-u ili u logu.

## 4. Arhitektura

```
Ariston cloud ──(GET, 2 min / 2 h)──▶ boiler-history servis (Python, kontejner, novica-dev)
                                          │  ├─ poller (stanje, potrošnja, podešavanja, greške)
telefon (DobarBoiler) ──POST akcije──────▶│  ├─ REST API  /api/v1/...   (ingest + čitanje za app)
        ◀──GET agregati za tab Potrošnja──│  ├─ MCP       /mcp           (agenti, samo čitanje)
                                          │  └─ analitika (pogledi + periodični proračuni)
                                          ▼
                              shared-postgres, baza `dobarboiler`
                                          ▲
                         Grafana (observability stack) — Postgres izvor, read-only uloga
```

### 4.1 Gde živi i kako se pokreće

- Kod u ovom repou, novi direktorijum `server/` (Python 3.12). Jedan repo drži sve znanje o Ariston protokolu.
- `server/docker-compose.yml`, projekat `dobarboiler`, pokreće se iz checkout-a `/data/dev/DobarBoiler`
  (isti obrazac kao cv-generator). `restart: unless-stopped`, CPU i memorijski limit (npr. 0.5 CPU, 256 MB).
- Port `127.0.0.1:8020` (slobodan 2026-10-18; proveriti pre upotrebe). Mreže: `dev-shared` (do
  `shared-postgres`) i `sila-observability` nije potrebna.
- Pristup preko tailnet-a kroz postojeći preview mehanizam: `preview add boiler 8020` daje
  `http://novica-dev/boiler/`. Nema javnog vhost-a.
- Tajne: Ariston kredencijali ostaju u postojećem SSM `/dev/dobarboiler/env`. Tajne servera idu u **poseban**
  parametar `/dev/dobarboiler-server/env` (`BOILER_API_TOKEN`, `DOBARBOILER_DB_PASSWORD`,
  `DOBARBOILER_RO_PASSWORD`), jer agenti po pravilu iz CLAUDE.md ne smeju da učitavaju `dobarboiler.env`.
  Novica ga pravi jednom skriptom sa laptopa (`server/ops/add-secrets.ps1`), ovde `ssm-env dobarboiler-server`.
  Compose dobija oba fajla kao `env_file`.
- Biblioteke (izbor graditelja u okviru ovoga): `httpx` za Ariston, `psycopg` 3, `FastAPI` + `uvicorn`,
  zvanični MCP Python SDK (`mcp`, FastMCP, streamable HTTP montiran u isti ASGI app), `pytest`.
  Bez ORM-a; SQL migracije kao numerisani `.sql` fajlovi koje servis primeni na startu.

### 4.2 Poller i etiketa prema Ariston-u

| Zadatak | Učestalost | Napomena |
|---|---|---|
| stanje `medPlantData` | 120 s (konfig, minimum 60 s) | jedini izvor bez istorije |
| potrošnja `consSequencesApi8` | na 2 h, u :07 posle parnog sata | uzima ceo niz, upsert svih 24 bucket-a |
| dnevna i mesečna dopuna (`p=3`, `p=4`) | jednom dnevno u 02:10 + na startu | upsert |
| `plantSettings` | 6 h | beleži promene max setpoint-a i antilegionele |
| `busErrors` | 1 h | nova greška = događaj |
| `velis/plants` | na startu i 24 h | `isOffline48H`, `lnk`, firmware |

- Jedan token, ponovo se loguje samo na HTTP 405, kao u `AristonHttpClient`. Isti headeri (`User-Agent:
  RestSharp/106.11.7.0`). 429 → pauza 15 min za sve pozive. Druge greške → jedan retry posle 5 s, pa preskoči
  ciklus. Nikad više od jednog paralelnog poziva.
- Svaki poziv se meri (trajanje, status) i ide u tabelu `poll_log`; MCP i Grafana pokazuju zdravlje.
- Na startu: ako postoji `/data/tmp/boiler-probe/log.jsonl`, jednokratni uvoz tih uzoraka (seed istorije od
  2026-10-18 01:09). Posle uspešnog prvog dana rada servisa, probni loger se gasi.

### 4.3 Model podataka (Postgres, baza `dobarboiler`)

Sva vremena `timestamptz`; prikaz u `Europe/Belgrade`.

| Tabela | Ključ | Kolone (suština) |
|---|---|---|
| `state_sample` | `at` | `on, mode, temp, req_temp, proc_req_temp, eco, heat_req, anti_leg, rm_tm interval, av_shw, pwr_opt, raw jsonb` |
| `energy_2h` | `bucket_start` | `kwh numeric(6,3), first_seen_at, last_seen_at` |
| `energy_day` | `day date` | `kwh, source ('p3'\|'sum_2h')` |
| `energy_month` | `month date` | `kwh, source` |
| `plant_settings` | `at` | snapshot samo kad se promeni |
| `device_error` | `(code, first_seen_at)` | `raw jsonb, cleared_at` |
| `app_action` | `(install_id, local_id)` | kopija `action_log` sa telefona: `at, kind, trig, detail, outcome, attempt, state_json` + `received_at` |
| `app_intent` | `(install_id, at)` | šta je aplikacija htela posle svakog converge-a: izvor (`BOOST`, `PLAN`…), željeno stanje, sledeća tranzicija |
| `event` | `id` | izvedeni događaji: `at, kind, attribution, severity, detail jsonb` |
| `tariff` | `valid_from` | `cheap_start, cheap_end, cheap_price, expensive_price, zone` |
| `poll_log` | `id` | `at, endpoint, status, ms, error` (čuva se 30 dana) |

Bucket logika za tarifu: bucket `energy_2h` je jeftin ako ceo leži u jeftinom prozoru. Ako bi prozor sekao bucket
(sa 00–08 se to ne dešava), kWh bucket-a se deli srazmerno minutima sa `heat_req = true` iz `state_sample` sa obe
strane granice; bez uzoraka, srazmerno vremenu. Prozor je konfiguracija, podrazumevano 00:00–08:00 lokalno.

`energy_day` iz `p=3` je istina za dane; za dane iz poslednjih 48 h može se uporediti sa zbirom `energy_2h`
(razlika > 0.05 kWh → upozorenje u `poll_log`, pomaže da se uhvati pogrešna pretpostavka o bucket-ima).

### 4.4 Događaji i atribucija (srce transparentnosti)

Detektor radi posle svakog novog `state_sample` i upoređuje ga sa prethodnim:

| Događaj | Okidač |
|---|---|
| `power_on` / `power_off` | promena `on` |
| `target_changed` | promena `req_temp` |
| `eco_changed`, `mode_changed` | promena `eco` / `mode` (mode ≠ 1 je uvek sumnjiv: aplikacija nikad ne šalje 5 ni 9) |
| `heating_started` / `heating_stopped` | promena `heat_req` |
| `expensive_heating` | grejanje van jeftinog prozora, zatvara se kad prestane; nosi kWh iz `energy_2h` |
| `water_draw` | pad temperature ≥ 2 °C za ≤ 6 min (procena potrošnje tople vode) |
| `anti_legionella` | `anti_leg` postane true |
| `device_offline` / `device_online` | 3 neuspela poziva zaredom / prvi uspeh posle toga |
| `device_error` | nova stavka iz `busErrors` |

Atribucija za promene `on/req_temp/eco/mode`:

- `app` — postoji `app_action` sa uspešnom odgovarajućom komandom u prozoru −3 min … +5 min.
- `external` — nema takve akcije (zvanična Ariston app ili dugme na bojleru; ne mogu se razlikovati).
- `unknown` — telefon nije slao akcije poslednjih 6 h (nema podataka za zaključak).

Atribucija za `heating_started` van prozora: `app` ako je aktivna namera aplikacije (boost, HBT, keep-warm),
`firmware_eco` ako je `eco = true` i nema promene stanja (kandidat za H1), `firmware` inače
(termostat dogreva jer je bojler uključen).

### 4.5 Analitika

Sve kao SQL pogledi ili Python funkcije nad bazom, dostupne kroz REST, MCP i Grafanu.

1. **Tarifni izveštaj:** po danu/mesecu/godini: kWh jeftino, kWh skupo, % jeftino, dinari, „kazna“ =
   skupi kWh × (skupa − jeftina cena).
2. **Sezonske potrebe:** prosečno kWh/dan po mesecu, 21 mesec unazad; poređenje sa istim mesecom prošle godine.
3. **Gubitak u mirovanju:** periodi `on = true`, `heat_req = false`, bez `water_draw`, dužine ≥ 2 h →
   °C/h hlađenja → W = °C/h × 80 L × 1.163 Wh/(L·°C), kao funkcija razlike temperature vode i okoline.
4. **Brzina grejanja:** °C/h tokom `heat_req`, posebno za eco i bez eco; kWh po °C iz `energy_2h`
   (kalibracija stvarnog kapaciteta bojlera). Rezultat se može vratiti u aplikaciju umesto lokalnog EWMA.
5. **Topla voda:** zbir `water_draw` po danu i dobu dana, u ekvivalentnim litrima vode na 40 °C. Jedan senzor
   i raslojavanje vode čine ovo procenom: prikazuje se kao trend, ne kao tačna brojka.
6. **Najniža dovoljna temperatura:** za svaki dan minimum temperature između dva prozora; za sezonu
   najmanji target `T` takav da bi voda ostala ≥ prag udobnosti (podrazumevano 40 °C) u ≥ 95 % dana, uz
   aproksimaciju „niži target pomera celu krivu naniže za istu razliku“. Ušteda = smanjenje gubitka u mirovanju.
7. **Plan vs. stvarnost:** dnevno: da li je grejanje počelo i stalo kad je aplikacija htela (iz `app_intent`),
   koliko kWh je otišlo van plana i zbog čega (atribucija iz §4.4).

### 4.6 REST API (za aplikaciju)

Bearer `BOILER_API_TOKEN` na svim rutama. JSON, vremena ISO 8601 sa offset-om.

| Metoda | Ruta | Svrha |
|---|---|---|
| POST | `/api/v1/app/actions` | batch `action_log` redova; idempotentno po `(install_id, local_id)` |
| POST | `/api/v1/app/intent` | trenutna namera posle converge-a |
| GET | `/api/v1/now` | poslednje stanje, starost, današnja potrošnja jeftino/skupo, aktivni događaji |
| GET | `/api/v1/series/state?from&to&step` | temperatura, target, grejanje (downsample) |
| GET | `/api/v1/energy?from&to&granularity=2h\|day\|month` | kWh + tarifna podela + dinari |
| GET | `/api/v1/events?from&to&kinds` | događaji sa atribucijom |
| GET | `/api/v1/insights?season` | rezultati §4.5 |
| GET | `/api/v1/health` | zdravlje pollera (bez tokena, samo status) |

### 4.7 MCP server za agente

Isti servis, ruta `/mcp`, streamable HTTP, isti bearer token. Samo čitanje. Alati vraćaju i strukturisan
JSON i kratak tekstualni sažetak na srpskom, da agent ne mora da računa.

| Alat | Ulaz | Vraća |
|---|---|---|
| `boiler_now` | — | stanje, starost podatka, šta aplikacija trenutno hoće, današnji kWh jeftino/skupo |
| `boiler_state_series` | `from, to, step` | serija temperature/targeta/grejanja |
| `boiler_energy` | `from, to, granularity` | kWh, tarifna podela, dinari |
| `boiler_events` | `from, to, kinds?, attribution?` | događaji, npr. sva `external` grejanja ovog meseca |
| `boiler_report` | `month` | mesečni izveštaj (markdown + JSON) |
| `boiler_insights` | `season?` | gubitak u mirovanju, brzina grejanja, preporučena temperatura, ušteda |
| `boiler_explain` | `at` | „šta se desilo u ovom trenutku“: stanje pre/posle, akcije aplikacije ±30 min, atribucija |
| `boiler_health` | — | poslednji uspešan poll po endpoint-u, greške, 429 |
| `boiler_sql` | `query` | SELECT preko read-only uloge, `statement_timeout` 5 s, max 1000 redova |

Resurs `boiler://schema` opisuje tabele i semantiku bucket-a, da agent koji piše SQL ne pogađa.

Registracija: `claude mcp add --scope user --transport http dobarboiler http://127.0.0.1:8020/mcp --header
"Authorization: Bearer …"` na ovoj mašini (token se ne štampa; ide kao kod ostalih MCP-ova u
`~/.claude.json`); sa laptopa ista komanda na `http://novica-dev/boiler/mcp`. Opis u globalnom CLAUDE.md.

### 4.8 Grafana

- Nov Postgres izvor u `/data/srv/observability/grafana-datasources.yml` sa ulogom `dobarboiler_ro`
  (lozinka iz SSM `/dev/observability/env`, novi ključ), Grafana kontejner dobija mrežu do `shared-postgres`.
- Dashboard kao JSON u `server/grafana/boiler.json`, provisioning ili uvoz kroz API: temperatura + target +
  grejanje sa zatamnjenim jeftinim prozorom, 2 h stubići jeftino/skupo, dnevni i mesečni stubići, tabela
  događaja sa atribucijom, zdravlje pollera.
- Energetski dashboardi (2026-10-20), generisani iz `server/grafana/build_dashboards.py`, samo SELECT nad istim
  tabelama: **potrošnja i tarife** (kWh, % jeftino, dinari, „preplaćeno“, doba dana, mapa dan × 2 h, meseci),
  **ušteda prema stalnoj temperaturi** (model ispod) i **izveštaji** (mesečna/dnevna/godišnja tabela, sezona).
  Promenljiva „Cene“ množi cene sa 1.29 (PDV 20 % + akciza 7.5 %).
- Model uštede, po 2 h periodu pokrivenom i potrošnjom i stanjem: zaliha = 0.09304 kWh/°C × (t_kraj − t_početak);
  topla voda = kWh − zaliha − UA·(T_sr − T_sobe)·h; bojler na stalnih T_b = topla voda + UA·(T_b − T_sobe)·h po
  ceni tog perioda; stvarni = kWh − zaliha, uz dogrevanje zalihe po jeftinoj ceni. UA podrazumevano 1.2 W/°C
  (Velis Evo 80: 1.32 kWh/24 h na 65 °C), soba 20 °C; oba su promenljive na dashboardu. Razlika u kWh je tako samo
  razlika u gubitku toplote, a razlika u dinarima dolazi od toga kada se struja kupuje.

### 4.9 Rezervna kopija

`dobarboiler-backup.timer` u 03:50: `pg_dump` baze `dobarboiler` u restic repo `dev-box/dobarboiler`,
isti obrazac i politika čuvanja kao `cvgen-backup`. Godine istorije su vrednije od same aplikacije.

## 5. Promene u aplikaciji (bez UX-a)

### 5.1 Slanje akcija i namere na server

- Nova klasa `data/ServerSync` + `scheduling/ServerSyncWorker` (WorkManager, mreža obavezna, backoff).
- Posle svakog `converge` (na kraju, van `Mutex`-a) enqueue jednokratnog posla; plus periodični na 1 h.
- Šalje `action_log` redove sa `id` većim od `lastUploadedActionId` (novi ključ u `SettingsStore`), u
  batch-evima po 200. **Bez promene Room šeme**, nema migracije. Server je idempotentan.
- `install_id`: slučajni UUID, generiše se jednom, čuva se u DataStore.
- Šalje i `app_intent` (izvor, željeno stanje, sledeća tranzicija) iz rezultata converge-a.
- Ako server nije podešen ili nije dostupan (nema tailnet-a), ništa se ne menja u ponašanju aplikacije;
  zaostali redovi idu kad veza proradi. Neuspeh slanja **ne** ide u `greske` kanal, samo u zdravlje u
  podešavanjima („Server: poslednje slanje pre 3 h“).

### 5.2 Čitanje sa servera

- Nov `api/HistoryApiClient` (isti OkHttp, poseban base URL i token).
- Tab „Potrošnja“, kartica potrošnje i mini grafik na početnoj čitaju sa servera; poslednji odgovori se keširaju
  (DataStore ili fajl) da ekran ne bude prazan bez veze, uz oznaku starosti.
- Server URL podrazumevano `http://novica-dev/boiler/`; Android zahteva `network_security_config` koji dozvoljava
  cleartext samo za `novica-dev` (saobraćaj je već šifrovan kroz WireGuard tunel Tailscale-a).
- Podešavanja → „Server istorije“: URL, token (EncryptedSharedPreferences, kao Ariston lozinka), „Testiraj vezu“.

### 5.3 Odluka o eco (zavisi od H1)

Ako podaci potvrde H1, aplikacija prestaje da pali eco po defaultu (preseti i `KEEP_WARM` → `eco = false`), a u
UI-ju eco dobija pošten opis: „Ariston sam uči navike i može da greje i van tvog rasporeda“. To je odluka posle
dve nedelje podataka, ne deo prve isporuke.

### 5.4 Tarife

Beograd: niža tarifa od 00 do 08 h (N1, 19. 8. 2026: termini se određuju po distributivnoj oblasti). Cene iz istog
izvora, din/kWh bez PDV-a i akcize:

| Zona | Jeftina | Skupa |
|---|---|---|
| zelena (do 350 kWh) | 2.4034 | 9.6136 |
| plava (preko 350 kWh) | 3.6051 | 14.4203 |

Podrazumevano se koristi **plava zona**, jer je bojler marginalna potrošnja: njegovi kWh se plaćaju po
najvišoj zoni koju domaćinstvo dostigne u mesecu. Novica može da prebaci na zelenu.

Tarifni prozor je **00:00–08:00 lokalnog vremena cele godine** (Novica potvrdio 2026-10-18). Granice se
poklapaju sa dvosatnim bucket-ima, pa deljenje bucket-a iz §4.3 ostaje samo kao opšti mehanizam za slučaj da se
prozor ikad promeni.

Zonu Novica ne zna sa računa; ostaje plava. Na EPS računu se vidi u tabeli obračuna po zonama (redovi „zelena“,
„plava“, „crvena“ sa kWh): ako postoji red „plava“ sa kWh većim od nule, marginalni kWh je plavi. Tabela `tariff` sa `valid_from` omogućava promenu cena bez gubitka
istorijskih proračuna. Menja se kroz REST i iz podešavanja aplikacije.

## 6. Novi UX

### 6.1 Principi

1. **Dve sekunde do istine.** Početna odmah kaže: koliko je vruće, da li greje, zašto (koji plan/izuzetak), šta
   sledi i kada.
2. **Vreme je glavna osa.** Dan je traka od 24 h sa zatamnjenim jeftinim prozorom; ista traka se pojavljuje na
   početnoj, u rasporedu, u potrošnji i u dnevniku. Novica jednom nauči jedan vizuelni jezik.
3. **Odmah reaguje, iskreno čeka.** Cloud je spor (sekunde do minuta). Svaka komanda odmah prikazuje stanje
   „šaljem → bojler potvrdio“ na kontroli koju je dirala, umesto tihog čekanja.
4. **Novac je vidljiv.** Jeftino je uvek iste zelenkaste boje, skupo uvek ćilibarno, u svim graficima i oznakama.
5. **Bez servera radi kao danas.** Sve što zavisi od servera ima stanje „nema veze sa serverom“ koje ne smeta.

### 6.2 Nova informaciona arhitektura

Donja traka sa 4 taba, podešavanja preko ikonice zupčanika u gornjem desnom uglu početne:

| Tab | Sadržaj | Odakle dolazi |
|---|---|---|
| **Početna** | stanje, razlog, sledeći korak, danas, brze akcije | današnja Početna |
| **Raspored** | nedelja kao 7 traka, preseti, zakazani izuzeci (dan, „topla do“, odmor) | Planer + Preseti + Izuzeci |
| **Potrošnja** | grafici, tarifna podela, uvidi i predlozi | novo, server |
| **Dnevnik** | jedna vremenska linija: akcije aplikacije + događaji sa servera, sa atribucijom | Istorija + server |

Rute za sheet-ove i dijaloge ostaju (Boost, Odmor, Dan, Topla do). Podešavanja zadržavaju podekrane, plus
„Server istorije“ i „Tarife“.

### 6.3 Ekrani

**Početna**

- Merač u obliku luka (zadržava postojeći `DialRing` koncept): boja luka ide od hladne ka toploj po temperaturi,
  oznaka targeta na luku, animirana vrednost (`animateFloatAsState`), blag puls dok `heatReq = true`.
- Ispod broja: „Greje · gotovo za ~15 min“ (iz `rmTm`) ili „Drži 55°“ ili „Isključen do 00:30“; „1 tuš spreman“.
- **Traka dana**: 24 h, jeftini prozor zatamnjen, planirani prozor grejanja popunjen bojom preseta, stvarno
  grejanje danas (sa servera) kao tanka linija ispod, marker „sad“. Tap otvara Dnevnik za danas.
- **Kartica razloga**: jedna rečenica „Radni dan · greje do 55° do 07:30, zatim isključeno“ + „Vrati na plan“
  kad je aktivan ručni izuzetak.
- **Upozorenje o spoljnoj promeni** (sa servera ili lokalne detekcije): „U 14:02 neko je podigao temperaturu na
  65° (zvanična app ili dugme)“ sa akcijama „Vrati na plan“ i „Zadrži do 07:30“.
- **Danas**: „2.3 kWh · 98 % jeftino · 7 din“ sa malom podeljenom trakom; skriveno bez servera.
- **Brze akcije**: Boost, Topla do…, Odmor, Ugasi (i dalje otvara odmor, nikad slepo gašenje). Velika
  dugmad, haptički odziv, stanje „šaljem“ na samoj akciji.
- Pull-to-refresh i „ažurirano pre 1 min“ ostaju.

**Raspored**

- Nedelja kao 7 redova, svaki red je traka dana sa prozorom u boji preseta i nazivom preseta.
- Tap na dan → sheet sa presetima (čipovi) i „samo ovog datuma“ (dnevni izuzetak) vs „svake srede“.
- Preseti kao horizontalni red kartica iznad nedelje; dugi pritisak → izmena; „+“ → nov.
- „Zakazano“ na vrhu: dnevni izuzeci, „topla do“, odmor, sa swipe-to-delete i undo.

**Potrošnja**

- Segmentni izbor perioda: Dan / Nedelja / Mesec / Godina; strelice za pomeranje.
- Naslovni brojevi: kWh, dinari, % jeftino, „bačeno na skupu struju: 38 din“.
- Stubičasti grafik: jeftino (zelenkasto) i skupo (ćilibar) složeno; za Dan su to 2 h bucket-i.
- Za Dan i Nedelju: linija temperature sa targetom, zatamnjenim jeftinim prozorom i periodima grejanja.
- Za Godinu: meseci ove godine pored meseci prošle godine.
- **Uvidi** kao kartice: „Zimi ti treba ~2.3 kWh dnevno, leti ~0.9“, „Bojler gubi ~X W kad miruje“,
  „52° umesto 55° bilo bi dovoljno 96 % dana: ~Y kWh mesečno manje“ sa dugmetom „Primeni na preset“ (otvara
  izmenu preseta sa predloženom vrednošću; ništa se ne menja bez potvrde).
- Grafici: biblioteka Vico (Compose, Material 3) ili ručni `Canvas`, po izboru graditelja; boje i oblici
  prema `dataviz` veštini; tap na stubić prikazuje vrednost.

**Dnevnik**

- Vremenska linija grupisana po danima; zaglavlje dana nosi mini tarifnu traku i kWh.
- Stavke: ikona i boja po atribuciji: Aplikacija, Spolja, Bojler (termostat/eco), Greška. Filter čipovi.
- Tap širi stavku: stanje pre/posle, razlog aplikacije (trigger, preset), pokušaji.
- Bez servera: samo lokalni `action_log`, uz traku „Događaji sa servera nisu dostupni“.

### 6.4 Vizuelni jezik i pokret

- Material 3, Compose BOM na najnoviju stabilnu verziju u trenutku izrade. Dinamičke boje ostaju za okvir
  aplikacije, ali **semantičke boje su fiksne** (jeftino, skupo, grejanje, spolja, greška), ne prate wallpaper.
- Topla narandžasta osnova (`#D75A28`) ostaje kao identitet.
- Tipografija: tabularne cifre za temperature i kWh (`fontFeatureSettings = "tnum"`), veliki broj na meraču.
- Oblici: velike zaobljene kartice, bez okvira, nivoi preko tonaliteta površine.
- Pokret: `AnimatedContent` za promene brojeva i statusa, `animateContentSize` za kartice, shared-element
  prelaz traka dana → Dnevnik, predictive back, edge-to-edge. Ništa ne treperi; trajanja 150–300 ms.
- Tamna tema je ravnopravna. Svi tekstovi ostaju u `strings.xml` na latinici.
- Pristupačnost: kontrast ≥ 4.5:1 za tekst, boja nikad jedina nosilac značenja (ikone/šare na trakama),
  `contentDescription` na grafikama.

### 6.5 Provera UX-a bez telefona

Na ovoj mašini još nema Android SDK-a. Prvi korak implementacije je instalacija SDK-a i Gradle wrapper-a da se
build i testovi vrte ovde. Za izgled: **Roborazzi** snimci ekrana (JVM, Robolectric) za svaki ekran u svetloj i
tamnoj temi, sa lažnim podacima. Snimci se mogu pogledati preko `preview` na telefonu pre nego što APK stigne.
Statički HTML maketi u `docs/superpowers/mockups/` služe samo za dogovor o izgledu.

## 7. Faze isporuke

| Faza | Sadržaj | Vrednost odmah |
|---|---|---|
| 0 | Android SDK + Gradle wrapper na mašini, build i postojeći testovi zeleni | sve kasnije se proverava ovde |
| 1 | Servis: poller, šema, backfill, uvoz probnog loga, događaji bez atribucije, `/health` | istorija počinje da se puni |
| 2 | Tarifna analitika + Grafana dashboard + backup | grafik temperature i jeftino/skupo |
| 3 | MCP server + registracija | agenti analiziraju podatke |
| 4 | Aplikacija: slanje akcija i namere, podešavanja servera; atribucija na serveru | odgovor na „ko me pregazio“ |
| 5 | UX: nova navigacija, Početna, Dnevnik | transparentnost u aplikaciji |
| 6 | UX: Potrošnja sa graficima i uvidima, Raspored | analize na telefonu |
| 7 | Odluka o eco (H1) i predlog temperature po sezoni | ušteda |

Faze 1–3 ne diraju aplikaciju i mogu krenuti odmah. Faze 5 i 6 zavise od REST API-ja iz faze 2 i 4.

## 8. Testiranje

- Server: `pytest` sa snimljenim pravim odgovorima (iz probnog loga) kao fiksture; posebno bucket poravnanje,
  DST prelaz (25. 10. 2026, poslednji bucket „00–02“ traje 3 h), deljenje bucket-a na granici tarife,
  idempotentan ingest, atribucija, detektor `water_draw`.
- MCP: test klijent iz MCP SDK-a koji poziva svaki alat nad test bazom.
- Aplikacija: postojeći JVM testovi ostaju zeleni; novi testovi za `ServerSync` (MockWebServer) i za
  formatiranje podataka za grafike; Roborazzi snimci za nove ekrane.
- Ručna provera na telefonu: Novica instalira APK kao i do sada.

## 9. Kasnije (nije u ovom dizajnu)

Widget za početni ekran (temperatura + današnji udeo jeftine struje), Quick Settings pločica za Boost,
obaveštenje „skupo grejanje u toku“ sa servera kroz push, prediktivno grejanje po naučenim navikama
(sopstveni ECO EVO, ali u jeftinoj tarifi).

## 10. Otvoreno i pretpostavke

**Pretpostavke (moje odluke, Novica može da promeni):**

- Servis živi na novica-dev, kod u ovom repou, baza u `shared-postgres`, pristup samo kroz tailnet.
- Stanje na 120 s; potrošnja iz cloud izveštaja, ne iz procene.
- Akcije se šalju preko oznake `lastUploadedActionId`, bez migracije Room baze.
- Jedan par cena za ceo bojler (plava zona); prozor 00–08 cele godine, konfigurabilan.
- Dinamičke boje ostaju za okvir, semantičke boje fiksne.
- Raspored spaja Planer, Presete i Izuzetke; podešavanja idu iza zupčanika.

**Rizik deljenog naloga.** Server koristi isti Ariston nalog kao telefon. Probni loger drži sopstvenu sesiju od
2026-10-18 01:09 i poziva cloud na 2 min, pa je to već test u stvarnom radu. Ako Novica primeti da je telefon
izbačen iz sesije ili dobija 429, rezerva je **drugi Ariston nalog pozvan kao gost na isti uređaj** (polje
`tcByGuest` postoji u odgovoru `velis/plants`). Servis zato čita kredencijale samo iz svojih ključeva
(`SERVER_ARISTON_USER`/`SERVER_ARISTON_PASS`, sa fallback-om na `ARISTON_USER`/`ARISTON_PASS`), pa je prelazak samo
promena SSM parametra. Etiketa iz §4.2 drži ukupno opterećenje ispod jednog poziva u minuti.

**Kapija pre UX-a.** Detaljan implementacioni plan pokriva faze 0–4. Faze 5 i 6 se planiraju tek kad Novica
odobri izgled na HTML maketama (`docs/superpowers/mockups/index.html`, preview `http://novica-dev/boiler-ux/`).
Novi UX menja zamrznutu navigaciju iz SPEC §8, pa se SPEC ažurira u istom koraku. Faza 7 je odluka posle dve
nedelje podataka.

**Graditelj proverava pre početka:**

- Da li je port 8020 i dalje slobodan.
