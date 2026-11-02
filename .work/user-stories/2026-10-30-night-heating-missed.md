---
id: 2026-10-30-night-heating-missed
reported: 2026-10-30
status: new
feature: null
kind: bug
---

# Noćno grejanje se nije uključilo posle ručnog gašenja

**Kao** korisnik koji je uveče ručno ugasio bojler, **želim** da se bojler noću ipak upali po planu,
**da bih** ujutru imao toplu vodu zagrejanu po jeftinoj tarifi.

## Šta se desilo

Posle ručnog gašenja 29. 10. u 17:41 (vidi
[2026-10-29-boost-cancel](2026-10-29-boost-cancel.md)) bojler se noću nije uključio. Ujutru 30. 10. voda je
bila na 31 °C. Pitao sam da li je opet ostao bez struje. Nije.

## Prihvatni kriterijumi

- Posle ručnog gašenja (sa ili bez aktivnog boosta) sledeći prozor iz plana uključuje bojler.
- Ako zakazano uključenje ne uspe ili se ne desi, dobijam obaveštenje (`greske`), ne saznajem tek ujutru
  po hladnoj vodi.

## Tehničke beleške

- History server: state samples every 2 min all night (29. 10. 18:00 → 30. 10. 11:00), no offline events,
  `on=false` throughout, temperature 31 °C. The boiler was online and reachable; the app never sent a
  command. Target stayed 42 °C / eco off, the boost values. A plan converge would have set 45 °C / eco on.
- Previous nights (25.–27. 10.) the window started at ~00:30 local. 27. 10. 15:00 → 29. 10. 16:00 the
  boiler was without power (M2M 404), so those nights tell us nothing.
- Expected path: boost timeout (~21:15, `BoostTimeoutWorker` → `converge(BOOST)` clears `manualHoldUntil`,
  arms the next window) → window start `ALARM` → power on. One of these steps did not happen. Candidates
  (unverified): the boost-timeout alarm or the window alarm did not fire (Doze/app killed), the manual hold
  survived, or a converge failed silently. The phone's `action_log` (Istorija in the app) is needed to tell.
  The phone does not sync to the history server yet (`app_action` / `app_intent` are empty), so it cannot be
  checked from novica-dev.
- A server-side watchdog (history server notices "plan window started, boiler still off") would catch this
  class of failure regardless of the phone.
