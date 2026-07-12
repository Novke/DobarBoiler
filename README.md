# DobarBoiler

Android aplikacija za kontrolu Ariston Velis Tech Wifi 80 bojlera — bez zvanične Ariston NET aplikacije.

Radi direktno preko reverse-engineered Ariston cloud REST API-ja
(`https://www.ariston-net.remotethermo.com/api/v2/`), istog koji koristi zvanična aplikacija.
Kao referenca protokola služi [python-ariston-api](https://github.com/fustom/python-ariston-api).

## Ključne funkcije (plan)

- **Pametni noćni scheduler** — greje isključivo u jeftinoj tarifi (default 00:30–07:30):
  na ulasku u jeftin period kreće da greje do ciljne temperature i održava je histerezom
  (npr. start ispod 52°C, stop na 56°C — konfigurabilno). Bez "cilja u tačno vreme" — cilj je minimalna potrošnja.
- **Preseti** — imenovane konfiguracije (npr. "Sam" 55°C, "Gosti" 65°C).
- **Nedeljni planer** — preseti po danima (radni dani / vikend), sa **one-time override**:
  "ovaj vikend ipak ne diži na 65" ili "danas u 21h hoću 60°C" — važi jednom, pa se vraća default.
- **Odmor / auto-uključenje** — isključi odmah, sam se uključuje zadatog datuma.
- **Boost do temperature** — boost sa ciljnom temperaturom, po dostizanju se vraća prethodni režim.

## Struktura

- `docs/` — nalazi istraživanja API-ja, specifikacija
- `tools/` — pomoćne skripte (test API konekcije)
- `app/` — Android aplikacija (Kotlin)

## Bezbednost

Ariston kredencijali se nikad ne commituju — čuvaju se lokalno na telefonu (EncryptedSharedPreferences),
a test skripta ih čita iz env promenljivih (`ARISTON_USER` / `ARISTON_PASS`).
