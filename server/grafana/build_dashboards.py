"""Generates the DobarBoiler energy dashboards (potrosnja.json, usteda.json, izvestaji.json).

Run `python3 -I server/grafana/build_dashboards.py` after editing; commit the generated JSON next to this file.
All SQL is plain SELECT over the history tables (dobarboiler_ro), so nothing on the server has to change.
"""
from __future__ import annotations

import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
DS = {"type": "grafana-postgresql-datasource", "uid": "dobarboiler"}
TZ = "'Europe/Belgrade'"

# Colours validated with the dataviz palette checker (dark and light surface).
CHEAP = "#3987e5"
EXPENSIVE = "#d95926"
UNKNOWN = "#8e8c84"
ACTUAL = "#199e70"
BASELINE = "#9085e9"
GOOD, WARN, BAD = "#1a9e5a", "#c98500", "#e34948"
BASE_SERIES = "na stalnoj temp."  # series name of the constant-temperature baseline (no variable: overrides match it)

MONTHS = "(ARRAY['jan','feb','mar','apr','maj','jun','jul','avg','sep','okt','nov','dec'])"

LINKS = [{"title": "DobarBoiler", "type": "dashboards", "tags": ["dobarboiler"], "asDropdown": False,
          "includeVars": False, "keepTime": True}]

# ------------------------------------------------------------------------------------------------ shared SQL
# Tariff periods, with the end of each period.
TAR = f"""tar AS (
  SELECT valid_from, coalesce(lead(valid_from) OVER (ORDER BY valid_from), 'infinity'::date) AS valid_to,
         cheap_start, cheap_end, cheap_price::float8 AS cp, expensive_price::float8 AS ep
  FROM tariff)"""

# Two-hour buckets with their local start, cheap flag and tariff prices. A bucket is cheap when it starts
# inside the cheap window (Ariston buckets start on even local hours, the window is 00-08).
BK = f"""bk AS (
  SELECT e.bucket_start, e.bucket_end, e.kwh::float8 AS kwh, e.bucket_start AT TIME ZONE {TZ} AS ls,
         ((e.bucket_start AT TIME ZONE {TZ})::time >= t.cheap_start
          AND (e.bucket_start AT TIME ZONE {TZ})::time < t.cheap_end) AS cheap,
         t.cp, t.ep
  FROM energy_2h e
  JOIN tar t ON (e.bucket_start AT TIME ZONE {TZ})::date >= t.valid_from
            AND (e.bucket_start AT TIME ZONE {TZ})::date < t.valid_to)"""

# Days: total from Ariston's daily figure (or the bucket sum for today), cheap kWh only when the day's buckets
# are complete (scaled to the daily total, like boilerd.analytics). cheap IS NULL = split unknown.
DY = f"""bd AS (
  SELECT ls::date AS d, count(*) AS nb, sum(kwh) AS tot, coalesce(sum(kwh) FILTER (WHERE cheap), 0) AS ch
  FROM bk GROUP BY 1),
dy0 AS (
  SELECT coalesce(ed.day, bd.d) AS d, coalesce(ed.kwh::float8, bd.tot) AS kwh, ed.day IS NULL AS partial,
         bd.nb, bd.tot, bd.ch
  FROM energy_day ed FULL JOIN bd ON bd.d = ed.day),
dy AS (
  SELECT dy0.d, dy0.kwh,
         CASE WHEN dy0.partial THEN dy0.ch
              WHEN dy0.nb >= 11 THEN CASE WHEN dy0.tot > 0 THEN dy0.ch * dy0.kwh / dy0.tot ELSE 0 END
         END AS cheap,
         t.cp * $porez AS cp, t.ep * $porez AS ep
  FROM dy0 JOIN tar t ON dy0.d >= t.valid_from AND dy0.d < t.valid_to)"""

DAY_RANGE = (f"d BETWEEN ($__timeFrom()::timestamptz AT TIME ZONE {TZ})::date "
             f"AND (($__timeTo()::timestamptz - interval '1 second') AT TIME ZONE {TZ})::date")

# Months: Ariston's monthly figure (completed months) or the sum of days (current month); split = known days.
MO = f"""mo AS (
  SELECT date_trunc('month', d)::date AS m, sum(kwh) AS kwh_days, sum(cheap) AS ch, sum(kwh - cheap) AS ex,
         sum(cheap * cp + (kwh - cheap) * ep) AS din, sum((kwh - cheap) * (ep - cp)) AS kazna,
         count(*) AS nd, count(cheap) AS nsplit
  FROM dy GROUP BY 1),
mm AS (
  SELECT coalesce(em.month, mo.m) AS m, coalesce(em.kwh::float8, mo.kwh_days) AS kwh,
         coalesce(mo.ch, 0) AS ch, coalesce(mo.ex, 0) AS ex, mo.din, mo.kazna, coalesce(mo.nsplit, 0) AS nsplit
  FROM energy_month em FULL JOIN mo ON mo.m = em.month
  WHERE coalesce(em.month, mo.m) >= (SELECT min(month) FROM energy_month WHERE kwh > 0))"""

# Savings model, per two-hour bucket that is fully covered by state samples:
#   store (kWh)  = change of heat stored in the 80 L tank (t0 -> t1)
#   voda         = heat that left with hot water = measured kWh - store - standby loss at the measured temperature
#   base         = baseline kWh = voda + standby loss at a constant ${tb} °C, bought at that bucket's price
#   act, act_din = measured kWh / cost minus the stored heat; heat still missing at the end of a period is
#                  bought back at the cheap price (the app reheats at night), heat stored in advance was paid already.
# So base - act = standby difference only, and the money difference is mostly *when* the heat is bought.
# Over consecutive buckets the store term telescopes, so sums over days are exact up to sensor resolution.
SV = f"""sv0 AS (
  SELECT bk.*, extract(epoch FROM bk.bucket_end - bk.bucket_start) / 3600.0 AS h,
         (SELECT count(*) FROM state_sample s WHERE s.at >= bk.bucket_start AND s.at < bk.bucket_end) AS n,
         (SELECT avg(s.temp) FROM state_sample s WHERE s.at >= bk.bucket_start AND s.at < bk.bucket_end) AS tavg,
         (SELECT s.temp FROM state_sample s WHERE s.at <= bk.bucket_start AND s.at > bk.bucket_start - interval '10 minutes'
          ORDER BY s.at DESC LIMIT 1) AS t0,
         (SELECT s.temp FROM state_sample s WHERE s.at <= bk.bucket_end AND s.at > bk.bucket_end - interval '10 minutes'
          ORDER BY s.at DESC LIMIT 1) AS t1
  FROM bk WHERE bk.bucket_start >= $__timeFrom() AND bk.bucket_end <= $__timeTo()),
sv AS (
  SELECT bucket_start, bucket_end, ls, h, tavg, kwh, store,
         kwh - store AS act,
         (kwh * price - store * cp) * $porez AS act_din,
         kwh - store - $ua / 1000.0 * (tavg - $amb) * h AS voda,
         kwh - store - $ua / 1000.0 * (tavg - $amb) * h + $ua / 1000.0 * ($tb - $amb) * h AS base,
         price * $porez AS price
  FROM (SELECT *, 0.09304 * (t1 - t0) AS store, CASE WHEN cheap THEN cp ELSE ep END AS price FROM sv0) x
  WHERE t0 IS NOT NULL AND t1 IS NOT NULL AND n >= 22 * h)"""


def with_(*ctes: str) -> str:
    return "WITH " + ",\n".join(ctes) + "\n"


# ------------------------------------------------------------------------------------------------ panel helpers
class Grid:
    def __init__(self) -> None:
        self.y = 0
        self.next_id = 1

    def take(self, h: int) -> int:
        y = self.y
        self.y += h
        return y


def target(sql: str, fmt: str = "table", ref: str = "A") -> dict:
    return {"refId": ref, "datasource": DS, "rawQuery": True, "editorMode": "code", "format": fmt, "rawSql": sql}


def fixed(name: str, color: str) -> dict:
    return {"matcher": {"id": "byName", "options": name},
            "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": color}}]}


def prop(name: str, pid: str, value) -> dict:
    return {"matcher": {"id": "byName", "options": name}, "properties": [{"id": pid, "value": value}]}


def panel(kind: str, title: str, sql: str, pos: tuple[int, int, int, int], *, desc: str = "", fmt: str = "table",
          defaults: dict | None = None, overrides: list | None = None, options: dict | None = None,
          transformations: list | None = None) -> dict:
    x, y, w, h = pos
    p = {"type": kind, "title": title, "datasource": DS, "gridPos": {"x": x, "y": y, "w": w, "h": h},
         "targets": [target(sql, fmt)],
         "fieldConfig": {"defaults": defaults or {}, "overrides": overrides or []},
         "options": options or {}}
    if desc:
        p["description"] = desc
    if transformations:
        p["transformations"] = transformations
    return p


def stat(title: str, sql: str, pos, unit: str, *, desc: str = "", decimals: int | None = None,
         color: str | None = None, thresholds: list | None = None) -> dict:
    d: dict = {"unit": unit, "noValue": "nema podataka"}
    if decimals is not None:
        d["decimals"] = decimals
    if thresholds:
        d["color"] = {"mode": "thresholds"}
        d["thresholds"] = {"mode": "absolute", "steps": thresholds}
    else:
        d["color"] = {"mode": "fixed", "fixedColor": color or "text"}
    return panel("stat", title, sql, pos, desc=desc, defaults=d, options={
        "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
        "colorMode": "value" if (thresholds or color) else "none", "graphMode": "none",
        "textMode": "value", "justifyMode": "center", "orientation": "auto", "wideLayout": True,
        "showPercentChange": False})


def bars(title: str, sql: str, pos, unit: str, colors: dict[str, str], *, desc: str = "", stacked: bool = True,
         decimals: int | None = None, rotate: int = -45, show_values: str = "never") -> dict:
    d: dict = {"unit": unit, "custom": {"fillOpacity": 90, "lineWidth": 0, "gradientMode": "none"}}
    if decimals is not None:
        d["decimals"] = decimals
    return panel("barchart", title, sql, pos, desc=desc, defaults=d,
                 overrides=[fixed(n, c) for n, c in colors.items()],
                 options={"stacking": "normal" if stacked else "none", "xTickLabelRotation": rotate,
                          "xTickLabelSpacing": 0, "showValue": show_values, "groupWidth": 0.8, "barWidth": 0.9,
                          "barRadius": 0.1, "fullHighlight": False,
                          "legend": {"showLegend": True, "displayMode": "list", "placement": "bottom"},
                          "tooltip": {"mode": "multi", "sort": "none"}})


def table(title: str, sql: str, pos, *, desc: str = "", overrides: list | None = None) -> dict:
    return panel("table", title, sql, pos, desc=desc,
                 defaults={"custom": {"align": "auto", "cellOptions": {"type": "auto"}, "inspect": False,
                                      "minWidth": 60},
                           "noValue": "—"},
                 overrides=overrides or [],
                 options={"showHeader": True, "cellHeight": "sm", "footer": {"show": False}})


def text(title: str, md: str, pos) -> dict:
    x, y, w, h = pos
    return {"type": "text", "title": title, "gridPos": {"x": x, "y": y, "w": w, "h": h},
            "options": {"mode": "markdown", "content": md}}


def row(title: str, y: int) -> dict:
    return {"type": "row", "title": title, "collapsed": False, "gridPos": {"x": 0, "y": y, "w": 24, "h": 1},
            "panels": []}


NULL_DASH = [{"type": "special", "options": {"match": "null", "result": {"text": "—", "color": "text"}}}]


PCT_THRESHOLDS = [{"color": BAD, "value": None}, {"color": WARN, "value": 70}, {"color": GOOD, "value": 90}]


def custom_var(name: str, label: str, options: list[tuple[str, str]], current: int, desc: str) -> dict:
    opts = [{"text": t, "value": v, "selected": i == current} for i, (t, v) in enumerate(options)]
    return {"type": "custom", "name": name, "label": label, "description": desc,
            "query": ", ".join(f"{t} : {v}" for t, v in options), "options": opts,
            "current": {"text": options[current][0], "value": options[current][1]},
            "hide": 0, "multi": False, "includeAll": False}


PRICE_VAR = custom_var("porez", "Cene", [("bez poreza", "1"), ("sa PDV-om i akcizom", "1.29")], 0,
                       "Cene iz tabele tarifa su bez PDV-a (20 %) i akcize (7.5 %). Sa porezima: × 1.29.")


def dashboard(uid: str, title: str, panels: list, *, time_from: str, variables: list | None = None,
              desc: str = "") -> dict:
    for i, p in enumerate(panels, 1):
        p["id"] = i
    return {"uid": uid, "title": title, "description": desc, "tags": ["dobarboiler"], "timezone": "Europe/Belgrade",
            "schemaVersion": 41, "version": 1, "refresh": "10m", "editable": True, "graphTooltip": 1,
            "time": {"from": time_from, "to": "now"},
            "timepicker": {"refresh_intervals": ["5m", "10m", "30m", "1h"]},
            "templating": {"list": variables or []}, "links": LINKS, "panels": panels}


# ------------------------------------------------------------------------------------------------ Potrošnja
def potrosnja() -> dict:
    g = Grid()
    ps: list = []
    y = g.take(4)
    kpi = with_(TAR, BK, DY) + f"SELECT {{}} FROM dy WHERE {DAY_RANGE}"
    split_note = ("Računa se samo za dane za koje postoji podela po 2 h (od 16. 10. 2026). "
                  "Jeftino = 00–08 h po lokalnom vremenu.")
    ps += [
        stat("Potrošeno", kpi.format("sum(kwh) AS v"), (0, y, 4, 4), "kwatth", decimals=1,
             desc="Ukupna potrošnja bojlera u izabranom periodu (Ariston, dnevni zbir)."),
        stat("Jeftina struja", kpi.format("100 * sum(cheap) / nullif(sum(kwh) FILTER (WHERE cheap IS NOT NULL), 0) AS v"),
             (4, y, 4, 4), "percent", decimals=0, thresholds=PCT_THRESHOLDS,
             desc="Udeo kWh potrošenih između ponoći i 8 h. Cilj je 100 %. " + split_note),
        stat("Plaćeno", kpi.format("sum(cheap * cp + (kwh - cheap) * ep) AS v"), (8, y, 4, 4), "suffix: din",
             decimals=0, desc="Cena struje za bojler u izabranom periodu. " + split_note),
        stat("Preplaćeno",
             kpi.format("coalesce(sum((kwh - cheap) * (ep - cp)), 0) AS v"), (12, y, 4, 4), "suffix: din",
             decimals=0, thresholds=[{"color": GOOD, "value": None}, {"color": WARN, "value": 10},
                                     {"color": BAD, "value": 50}],
             desc="Koliko je skuplje nego da je ista struja potrošena noću: skupi kWh × (skupa − jeftina cena). "
                  + split_note),
        stat("Prosečno dnevno", kpi.format("avg(kwh) AS v"), (16, y, 4, 4), "kwatth", decimals=2,
             desc="Prosečna dnevna potrošnja u izabranom periodu."),
        stat("Skupa struja", kpi.format("sum(kwh - cheap) AS v"), (20, y, 4, 4), "kwatth", decimals=2,
             color=EXPENSIVE, desc="kWh potrošeni između 8 h i ponoći. " + split_note),
    ]

    y = g.take(9)
    ps.append(bars(
        "Dnevna potrošnja: jeftino i skupo",
        with_(TAR, BK, DY) + f"""SELECT to_char(d, 'DD.MM.') AS "dan", cheap AS "jeftino (00–08)",
  kwh - cheap AS "skupo (08–24)", CASE WHEN cheap IS NULL THEN kwh END AS "bez podele"
FROM dy WHERE {DAY_RANGE} ORDER BY d""",
        (0, y, 16, 9), "kwatth", {"jeftino (00–08)": CHEAP, "skupo (08–24)": EXPENSIVE, "bez podele": UNKNOWN},
        decimals=2,
        desc="„Bez podele“ = dani pre 16. 10. 2026, za koje Ariston čuva samo dnevni zbir, bez satnice."))
    ps.append(bars(
        "U koje doba dana se troši",
        with_(TAR, BK) + f"""SELECT to_char(ls, 'HH24') || '–' || to_char(ls + interval '2 hours', 'HH24') AS "sati",
  sum(kwh) FILTER (WHERE cheap) AS "jeftino", sum(kwh) FILTER (WHERE NOT cheap) AS "skupo"
FROM bk WHERE bucket_start >= $__timeFrom() AND bucket_start < $__timeTo()
GROUP BY extract(hour FROM ls), 1 ORDER BY extract(hour FROM ls)""",
        (16, y, 8, 9), "kwatth", {"jeftino": CHEAP, "skupo": EXPENSIVE}, decimals=2,
        desc="Zbir potrošnje po dvosatnim periodima u izabranom periodu. Idealno je sve levo od 08 h."))

    y = g.take(9)
    ps.append(bars(
        "Dnevni trošak u dinarima",
        with_(TAR, BK, DY) + f"""SELECT to_char(d, 'DD.MM.') AS "dan", cheap * cp AS "jeftina struja",
  (kwh - cheap) * ep AS "skupa struja"
FROM dy WHERE {DAY_RANGE} AND cheap IS NOT NULL ORDER BY d""",
        (0, y, 12, 9), "suffix: din", {"jeftina struja": CHEAP, "skupa struja": EXPENSIVE}, decimals=1,
        desc="Samo dani sa podelom po 2 h. Cene: tabela tarifa (plava zona), promenljiva „Cene“ dodaje porez."))
    slots = ", ".join(
        f"""sum(kwh) FILTER (WHERE extract(hour FROM ls) = {h}) AS "{h:02d}–{h + 2:02d}{' jeftino' if h < 8 else ''}\""""
        for h in range(22, -1, -2))
    ps.append(panel(
        "status-history", "Mapa potrošnje: dan × doba dana",
        with_(TAR, BK) + f"""SELECT date_trunc('day', ls) AT TIME ZONE {TZ} AS "time", {slots}
FROM bk WHERE bucket_start >= $__timeFrom() AND bucket_start < $__timeTo() GROUP BY 1 ORDER BY 1""",
        (12, y, 12, 9),
        desc="Svaka kolona je jedan dan, svaki red dvosatni period. Svetlije = više kWh.",
        defaults={"unit": "kwatth", "decimals": 2, "noValue": "",
                  "color": {"mode": "continuous-purples"}, "min": 0,
                  "custom": {"fillOpacity": 100, "lineWidth": 1}},
        options={"showValue": "never", "rowHeight": 0.9, "colWidth": 0.95,
                 "legend": {"showLegend": False}, "tooltip": {"mode": "single"}}))

    y = g.take(9)
    ps.append(bars(
        "Mesečna potrošnja (cela istorija)",
        with_(TAR, BK, DY, MO) + f"""SELECT {MONTHS}[extract(month FROM m)::int] || ' ' || to_char(m, 'YY') AS "mesec",
  ch AS "jeftino", ex AS "skupo",
  greatest(kwh - ch - ex, 0) AS "bez podele"
FROM mm ORDER BY m""",
        (0, y, 14, 9), "kwatth", {"jeftino": CHEAP, "skupo": EXPENSIVE, "bez podele": UNKNOWN}, decimals=1,
        desc="Ne zavisi od izabranog perioda. Ariston čuva mesečne zbirove od juna 2025; podela na jeftino/skupo "
             "postoji od 16. 10. 2026."))
    ps.append(table(
        "Grejanje u skupoj tarifi",
        f"""SELECT to_char(at AT TIME ZONE {TZ}, 'DD.MM. HH24:MI') AS "vreme",
  CASE attribution WHEN 'app' THEN 'aplikacija' WHEN 'external' THEN 'spolja (Ariston app / dugme)'
    WHEN 'firmware' THEN 'bojler, termostat' WHEN 'firmware_eco' THEN 'bojler, eco' ELSE 'nepoznato' END AS "uzrok",
  detail->>'temp' AS "voda °C", detail->>'target' AS "cilj °C",
  CASE WHEN (detail->>'eco')::boolean THEN 'da' ELSE 'ne' END AS "eco"
FROM event
WHERE $__timeFilter(at) AND kind = 'expensive_heating_started'
ORDER BY at DESC LIMIT 200""",
        (14, y, 10, 9),
        desc="Svaki početak grejanja između 8 h i ponoći (i grejanje koje se produži posle 8 h), sa procenom ko ga je "
             "izazvao. Prazno = sve je bilo noću."))
    return dashboard("dobarboiler-potrosnja", "DobarBoiler: potrošnja i tarife", ps, time_from="now-30d/d",
                     variables=[PRICE_VAR],
                     desc="Koliko struje troši bojler i koliko od toga u jeftinoj tarifi (00–08 h).")


# ------------------------------------------------------------------------------------------------ Ušteda
SAVINGS_EXPLAINER = """
**Pitanje:** koliko bi struje i novca otišlo da bojler stalno drži **${tb} °C**, umesto da greje noću po jeftinoj
struji i bude ugašen preko dana?

**Kako se računa** (za svaka 2 h za koja postoje i potrošnja sa Ariston servera i temperatura vode na 2 min):

1. Bojler troši struju na dve stvari: na **toplu vodu koju potrošiš** i na **gubitak toplote** kroz izolaciju,
   koji raste sa temperaturom vode.
2. Toplota odneta vodom = izmerena struja − promena toplote u bojleru (80 L × 1.163 Wh po °C) − gubitak na
   izmerenoj temperaturi.
3. Bojler na stalnih ${tb} °C bi potrošio **istu toplu vodu**, dogrejao je **odmah** (po ceni tog doba dana) i
   gubio bi toplotu na ${tb} °C, svaki sat.
4. Stvarni bojler se svodi na istu zalihu toplote: ako je na kraju perioda hladniji nego na početku, dodaje se
   dogrevanje po jeftinoj ceni (radi se noću).

Razlika u **kWh** je zato samo razlika u gubitku toplote (toplija voda = veći gubitak), a razlika u **dinarima**
dolazi najviše od toga **kada** se struja kupuje.

**Pretpostavke** (menjaju se gore): gubitak **${ua} W po °C** razlike između vode i sobe (Ariston Velis 80:
fabrički ≈ 1.3 kWh dnevno na 65 °C, tj. ≈ 1.2 W/°C), soba **${amb} °C**, cene iz tabele tarifa (plava zona).

**Šta ovo ne meri:** ako voda padne ispod ${tb} °C (npr. posle tuširanja), u stvarnosti imaš manje tople vode
nego zamišljeni bojler; računa se kao da je potrošena ista toplota. Temperatura sa bojlera je zaokružena na 1 °C,
pa pojedinačni dvosatni periodi šetaju; dnevni i mesečni zbirovi su pouzdani.
"""


def usteda() -> dict:
    g = Grid()
    ps: list = []
    base = with_(TAR, BK, SV)
    y = g.take(4)
    ps += [
        stat("Stvarno potrošeno", base + "SELECT sum(act) AS v FROM sv", (0, y, 4, 4), "kwatth", decimals=2,
             color=ACTUAL, desc="Izmerena potrošnja u pokrivenim periodima, svedena na istu zalihu toplote: ako je bojler na kraju "
                  "hladniji nego na početku, dodaje se struja za dogrevanje (po jeftinoj ceni, noću), a ako je "
                  "topliji, oduzima se toplota koja je već plaćena unapred."),
        stat("Na ${tb} °C bi bilo", base + "SELECT sum(base) AS v FROM sv", (4, y, 4, 4), "kwatth",
             decimals=2, color=BASELINE, desc="Procena za bojler koji stalno drži ${tb} °C (model ispod)."),
        stat("Razlika u struji", base + "SELECT sum(base) - sum(act) AS v FROM sv", (8, y, 4, 4), "kwatth",
             decimals=2, color="text",
             desc="Pozitivno = potrošio si manje kWh nego što bi na stalnih ${tb} °C. Negativno = više kWh "
                  "(jer noću greješ toplije), ali to ne znači da je skuplje: vidi dinare."),
        stat("Stvarno plaćeno", base + "SELECT sum(act_din) AS v FROM sv", (12, y, 4, 4), "suffix: din",
             decimals=0, color=ACTUAL),
        stat("Na ${tb} °C bi platio", base + "SELECT sum(base * price) AS v FROM sv", (16, y, 4, 4),
             "suffix: din", decimals=0, color=BASELINE),
        stat("Ušteda", base + "SELECT sum(base * price) - sum(act_din) AS v FROM sv", (20, y, 4, 4),
             "suffix: din", decimals=0, thresholds=[{"color": BAD, "value": None}, {"color": GOOD, "value": 0}],
             desc="Razlika u dinarima za pokriveni period, sa svedenom zalihom toplote."),
    ]
    y = g.take(4)
    ps += [
        stat("Ušteda u procentima",
             base + "SELECT 100 * (sum(base * price) - sum(act_din)) / nullif(sum(base * price), 0) AS v FROM sv",
             (0, y, 6, 4), "percent", decimals=0,
             thresholds=[{"color": BAD, "value": None}, {"color": GOOD, "value": 0}]),
        stat("Procena uštede za 30 dana",
             base + "SELECT (sum(base * price) - sum(act_din)) / nullif(sum(h), 0) * 24 * 30 AS v FROM sv",
             (6, y, 6, 4), "suffix: din", decimals=0,
             thresholds=[{"color": BAD, "value": None}, {"color": GOOD, "value": 0}],
             desc="Ušteda iz pokrivenog perioda preračunata na 30 dana. Malo podataka = gruba procena."),
        stat("Procena uštede za godinu",
             base + "SELECT (sum(base * price) - sum(act_din)) / nullif(sum(h), 0) * 24 * 365 AS v FROM sv",
             (12, y, 6, 4), "suffix: din", decimals=0,
             thresholds=[{"color": BAD, "value": None}, {"color": GOOD, "value": 0}],
             desc="Grubo: potrošnja zimi je veća nego leti, pa je godišnja procena pouzdana tek posle više meseci."),
        stat("Pokriveno podacima", base + "SELECT sum(h) AS v FROM sv", (18, y, 6, 4), "suffix: h", decimals=0,
             desc="Sati u izabranom periodu za koje postoje i potrošnja (2 h) i temperatura (na 2 min). "
                  "Rupe nastaju kad server ne radi ili Ariston ne odgovara."),
    ]

    y = g.take(9)
    daily = base + """SELECT to_char(ls::date, 'DD.MM.') AS "dan", {} FROM sv GROUP BY ls::date ORDER BY ls::date"""
    ps.append(bars("Struja po danu: stvarno i na stalnih ${tb} °C",
                   daily.format('sum(act) AS "stvarno", sum(base) AS "na stalnoj temp."'),
                   (0, y, 12, 9), "kwatth", {"stvarno": ACTUAL, BASE_SERIES: BASELINE}, stacked=False,
                   decimals=2, desc="Delimično pokriveni dani (rupe u podacima) su manji; vidi tabelu ispod."))
    ps.append(bars("Novac po danu: stvarno i na stalnih ${tb} °C",
                   daily.format('sum(act_din) AS "stvarno", sum(base * price) AS "na stalnoj temp."'),
                   (12, y, 12, 9), "suffix: din", {"stvarno": ACTUAL, BASE_SERIES: BASELINE}, stacked=False,
                   decimals=1))

    y = g.take(9)
    ps.append(panel(
        "timeseries", "Ukupna ušteda kroz vreme",
        base + """SELECT bucket_end AS "time",
  sum(base * price - act_din) OVER (ORDER BY bucket_start) AS "ušteda din"
FROM sv ORDER BY bucket_start""",
        (0, y, 12, 9), fmt="time_series",
        desc="Raste kad bi bojler na ${tb} °C dogrevao vodu po skupoj ceni (posle tuširanja preko dana), a "
             "stvarni bojler to dogreje noću po jeftinoj.",
        defaults={"unit": "suffix: din", "decimals": 0, "color": {"mode": "fixed", "fixedColor": GOOD},
                  "custom": {"lineWidth": 2, "fillOpacity": 12, "lineInterpolation": "stepAfter",
                             "spanNulls": False, "showPoints": "never"}},
        options={"legend": {"showLegend": False}, "tooltip": {"mode": "single"}}))
    ps.append(panel(
        "timeseries", "Temperatura vode prema ${tb} °C",
        f"""SELECT at AS "time", temp AS "voda", $tb::float8 AS "{BASE_SERIES}"
FROM state_sample WHERE $__timeFilter(at) ORDER BY at""",
        (12, y, 12, 9), fmt="time_series",
        desc="Dok je voda toplija od ${tb} °C, bojler gubi više toplote nego zamišljeni; dok je hladnija, manje.",
        defaults={"unit": "celsius", "custom": {"lineWidth": 2, "fillOpacity": 0, "spanNulls": 600000,
                                                "showPoints": "never"}},
        overrides=[fixed("voda", ACTUAL), fixed(BASE_SERIES, BASELINE),
                   prop(BASE_SERIES, "custom.lineStyle", {"fill": "dash", "dash": [10, 10]})],
        options={"legend": {"showLegend": True, "displayMode": "list"}, "tooltip": {"mode": "multi"}}))

    y = g.take(9)
    ps.append(table(
        "Po danima",
        base + """SELECT to_char(ls::date, 'DD.MM.YYYY') AS "dan", sum(h) AS "sati sa podacima",
  round(avg(tavg)::numeric, 1) AS "prosečna voda °C",
  round(sum(kwh)::numeric, 2) AS "izmereno kWh", round(sum(act)::numeric, 2) AS "stvarno kWh",
  round(sum(base)::numeric, 2) AS "na ${tb} °C kWh",
  round(sum(voda)::numeric, 2) AS "topla voda kWh",
  round(sum(act_din)::numeric, 1) AS "stvarno din", round(sum(base * price)::numeric, 1) AS "na ${tb} °C din",
  round((sum(base * price) - sum(act_din))::numeric, 1) AS "ušteda din"
FROM sv GROUP BY ls::date ORDER BY ls::date DESC""",
        (0, y, 24, 9),
        desc="„Izmereno“ = Ariston, „stvarno“ = izmereno svedeno na istu zalihu toplote. „Topla voda kWh“ = "
             "procena toplote odnete toplom vodom (isti broj u oba scenarija)."))
    y = g.take(13)
    ps.append(text("Kako se računa", SAVINGS_EXPLAINER, (0, y, 24, 13)))

    variables = [
        custom_var("tb", "Stalna temperatura (°C)", [(str(t), str(t)) for t in (40, 45, 50, 55, 60, 65)], 1,
                   "Temperatura koju bi bojler držao ceo dan."),
        custom_var("ua", "Gubitak bojlera (W/°C)",
                   [(v, v) for v in ("0.8", "1.0", "1.2", "1.5", "2.0")], 2,
                   "Koliko vati bojler gubi po °C razlike između vode i sobe. Fabrički podatak za Velis 80: ≈ 1.2."),
        custom_var("amb", "Soba (°C)", [(str(t), str(t)) for t in (15, 18, 20, 22)], 2,
                   "Temperatura prostorije u kojoj je bojler."),
        PRICE_VAR,
    ]
    return dashboard("dobarboiler-usteda", "DobarBoiler: ušteda prema stalnoj temperaturi", ps,
                     time_from="now-30d/d", variables=variables,
                     desc="Koliko struje i novca štedi grejanje noću u odnosu na bojler koji stalno drži istu "
                          "temperaturu.")


# ------------------------------------------------------------------------------------------------ Izveštaji
def izvestaji() -> dict:
    g = Grid()
    ps: list = []
    y = g.take(13)
    ps.append(table(
        "Mesečni izveštaj",
        with_(TAR, BK, DY, MO) + """, ly AS (SELECT m, kwh FROM mm)
SELECT to_char(mm.m, 'MM.YYYY') AS "mesec", round(mm.kwh::numeric, 1) AS "kWh",
  round((mm.kwh / CASE WHEN mm.m = date_trunc('month', now() AT TIME ZONE 'Europe/Belgrade')::date
            THEN greatest(extract(day FROM now() AT TIME ZONE 'Europe/Belgrade') - 1, 1)
            ELSE extract(day FROM (mm.m + interval '1 month' - interval '1 day')) END)::numeric, 2) AS "kWh/dan",
  CASE WHEN mm.nsplit > 0 THEN round(mm.ch::numeric, 1) END AS "jeftino kWh",
  CASE WHEN mm.nsplit > 0 THEN round(mm.ex::numeric, 1) END AS "skupo kWh",
  CASE WHEN mm.nsplit > 0 AND mm.ch + mm.ex > 0 THEN round((100 * mm.ch / (mm.ch + mm.ex))::numeric) END AS "% jeftino",
  round(mm.din::numeric) AS "din", round(mm.kazna::numeric) AS "preplaćeno din",
  mm.nsplit AS "dana sa podelom",
  round(ly.kwh::numeric, 1) AS "isti mesec lani kWh",
  CASE WHEN ly.kwh > 0 THEN round((100 * (mm.kwh - ly.kwh) / ly.kwh)::numeric) END AS "promena %"
FROM mm LEFT JOIN ly ON ly.m = (mm.m - interval '1 year')::date
ORDER BY mm.m DESC""",
        (0, y, 24, 13),
        desc="Cela istorija, ne zavisi od izabranog perioda. Dinari i podela samo za dane sa podacima po 2 h. "
             "Tekući mesec: kWh/dan do juče.",
        overrides=[prop("% jeftino", "custom.cellOptions", {"type": "color-text"}),
                   prop("% jeftino", "thresholds", {"mode": "absolute", "steps": PCT_THRESHOLDS}),
                   prop("% jeftino", "color", {"mode": "thresholds"}),
                   prop("% jeftino", "unit", "percent"), prop("% jeftino", "mappings", NULL_DASH),
                   prop("promena %", "unit", "percent")]))

    y = g.take(11)
    ps.append(table(
        "Dnevni izveštaj (izabrani period)",
        with_(TAR, BK, DY) + f""", st AS (
  SELECT (at AT TIME ZONE {TZ})::date AS d, round(avg(temp)::numeric, 1) AS tavg, max(temp) AS tmax,
         min(temp) AS tmin, count(*) AS n FROM state_sample GROUP BY 1),
ev AS (
  SELECT (at AT TIME ZONE {TZ})::date AS d, count(*) FILTER (WHERE kind = 'heating_started') AS grejanja,
         count(*) FILTER (WHERE kind = 'water_draw') AS tocenja,
         count(*) FILTER (WHERE kind = 'expensive_heating_started') AS skupa
  FROM event GROUP BY 1)
SELECT (ARRAY['ned','pon','uto','sre','čet','pet','sub'])[extract(dow FROM dy.d)::int + 1]
  || ' ' || to_char(dy.d, 'DD.MM.YYYY') AS "dan", round(dy.kwh::numeric, 2) AS "kWh",
  round(dy.cheap::numeric, 2) AS "jeftino kWh", round((dy.kwh - dy.cheap)::numeric, 2) AS "skupo kWh",
  CASE WHEN dy.kwh > 0 THEN round((100 * dy.cheap / dy.kwh)::numeric) END AS "% jeftino",
  round((dy.cheap * dy.cp + (dy.kwh - dy.cheap) * dy.ep)::numeric, 1) AS "din",
  round(((dy.kwh - dy.cheap) * (dy.ep - dy.cp))::numeric, 1) AS "preplaćeno din",
  st.tmin AS "min °C", st.tavg AS "prosek °C", st.tmax AS "max °C",
  ev.grejanja AS "grejanja", ev.skupa AS "u skupoj tarifi", ev.tocenja AS "točenja vode"
FROM dy LEFT JOIN st ON st.d = dy.d LEFT JOIN ev ON ev.d = dy.d
WHERE {DAY_RANGE.replace('d BETWEEN', 'dy.d BETWEEN')}
ORDER BY dy.d DESC""",
        (0, y, 24, 11),
        desc="Temperature i događaji postoje od 18. 10. 2026 (početak beleženja stanja).",
        overrides=[prop("% jeftino", "custom.cellOptions", {"type": "color-text"}),
                   prop("% jeftino", "thresholds", {"mode": "absolute", "steps": PCT_THRESHOLDS}),
                   prop("% jeftino", "color", {"mode": "thresholds"}),
                   prop("% jeftino", "unit", "percent"), prop("% jeftino", "mappings", NULL_DASH),
                   prop("u skupoj tarifi", "custom.cellOptions", {"type": "color-text"}),
                   prop("u skupoj tarifi", "thresholds", {"mode": "absolute", "steps": [
                       {"color": "text", "value": None}, {"color": BAD, "value": 1}]}),
                   prop("u skupoj tarifi", "color", {"mode": "thresholds"}),
                   prop("u skupoj tarifi", "mappings", NULL_DASH)]))

    y = g.take(9)
    ps.append(bars(
        "Dnevni prosek po mesecu: ova i prošla godina",
        f"""WITH m AS (SELECT month, kwh::float8 / extract(day FROM (month + interval '1 month' - interval '1 day')) AS pd
           FROM energy_month WHERE month >= (SELECT min(month) FROM energy_month WHERE kwh > 0))
SELECT {MONTHS}[mon] AS "mesec",
  max(pd) FILTER (WHERE yr = extract(year FROM now())::int - 1) AS "prošla godina",
  max(pd) FILTER (WHERE yr = extract(year FROM now())::int) AS "ova godina"
FROM (SELECT extract(month FROM month)::int AS mon, extract(year FROM month)::int AS yr, pd FROM m) x
GROUP BY mon ORDER BY mon""",
        (0, y, 14, 9), "kwatth", {"prošla godina": UNKNOWN, "ova godina": ACTUAL}, stacked=False, decimals=2,
        rotate=0, desc="kWh po danu, iz Ariston mesečnih zbirova (završeni meseci). Pokazuje sezonske potrebe."))
    ps.append(table(
        "Po godinama",
        with_(TAR, BK, DY, MO) + """SELECT extract(year FROM m)::int AS "godina", count(*) AS "meseci",
  round(sum(kwh)::numeric, 1) AS "kWh", round(avg(kwh)::numeric, 1) AS "kWh/mesec",
  round(sum(din)::numeric) AS "din (deo sa podelom)", round(sum(kazna)::numeric) AS "preplaćeno din"
FROM mm GROUP BY 1 ORDER BY 1 DESC""",
        (14, y, 10, 9), desc="2025. počinje u junu (prvi mesec u Ariston istoriji)."))
    return dashboard("dobarboiler-izvestaji", "DobarBoiler: izveštaji", ps, time_from="now-30d/d",
                     variables=[PRICE_VAR], desc="Mesečni, dnevni i godišnji izveštaji potrošnje bojlera.")


def main() -> None:
    for name, build in (("potrosnja", potrosnja), ("usteda", usteda), ("izvestaji", izvestaji)):
        out = HERE / f"{name}.json"
        out.write_text(json.dumps(build(), ensure_ascii=False, indent=1) + "\n")
        print("wrote", out.name)


if __name__ == "__main__":
    main()
