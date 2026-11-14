---
id: 2026-10-29-boost-cancel
reported: 2026-10-29
status: new
feature: null
kind: ux
---

# Boost ne može da se prekine kad je bojler ručno ugašen

**Kao** korisnik koji se istuširao pre nego što je boost završio, **želim** da na početnom ekranu vidim
jasno dugme „Prekini boost”, **da bih** zaustavio grejanje po skupoj tarifi bez gašenja bojlera rukom i
bez brige da li aplikacija i dalje „pamti” boost.

## Šta se desilo

29. 10. oko 17:15 uključio sam grejanje do ~40 °C (boost). Na 36 °C sam ušao pod tuš, voda je pala na
~33 °C i nisam hteo da bojler nastavi da greje, pa sam ga ugasio rukom (17:41). U aplikaciji je pisalo da je
bojler **isključen** i da je **boost zakazan / u toku**, ali nisam našao opciju da prekinem boost.
Nisam bio siguran da li će se bojler uredno upaliti noću po planu.

## Prihvatni kriterijumi

- Dok je boost aktivan, početni ekran ima vidljivo dugme „Prekini boost” (ne samo u listu koji se otvara
  dodirom na karticu).
- Ako je bojler ugašen dok boost traje (ručno ili na drugi način), aplikacija to jasno kaže, npr.
  „Bojler je ugašen — boost neće stići do 42 °C”, i nudi „Prekini boost” / „Nastavi boost”.
- Posle prekida ekran kaže šta sledi („Sledeće: grejanje 00:30–…”), da znam da noćni plan i dalje važi.
- Grejanje do cilja koje sam prekinuo tušem ne treba da me tera da gasim bojler rukom.

## Tehničke beleške

- History server (17:15 local): power on, target 45 → 42, eco on → off in one poll. That is exactly the
  Boost assertion (`converge(BOOST)`), so this was almost certainly an app boost. 17:39 water draw 36 → 34 °C,
  17:41 power off at 33 °C. The phone does not sync `app_action`, so the server cannot confirm.
- The cancel button exists only in `ui/dashboard/BoostSheet.kt` (`action_cancel_boost`), opened by tapping
  `ActiveSourceCard` when `activeSource == BOOST` (`DashboardScreen.kt`). The card does not read as tappable,
  so in practice it is not discoverable.
- No string says "zakazan"; the user probably saw `source_boost` („Boost do %d °C — u toku”) or
  `source_manual` next to the off state. Worth checking what the dashboard shows when boost is active and a
  manual hold is set at the same time.
- With the boiler off, `BoostService.isComplete` can never be true, so the boost runs to `boostMaxDuration`
  (default 4 h) and only then reverts to the plan. A detected manual power-off during a boost could end the
  boost (or ask the user) instead of waiting for the timeout.
- Related: [2026-10-30-night-heating-missed](2026-10-30-night-heating-missed.md). The night after this boost
  the planned window did not start, possibly because of the boost/manual-hold interaction.
