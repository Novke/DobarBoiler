"""Tariff split, reports and insights over the history tables (design §4.5)."""
from __future__ import annotations

import bisect
import statistics
from dataclasses import asdict, dataclass
from datetime import date, datetime, time, timedelta
from decimal import ROUND_HALF_UP, Decimal
from typing import Literal, NamedTuple
from zoneinfo import ZoneInfo

from psycopg import AsyncConnection

from .buckets import Tariff, cheap_fraction
from .ingest import StateSample, latest_state, recent_states

VOLUME_L = 80
WH_PER_L_C = 1.163
COMFORT_C = 40.0
COVERAGE = 0.95
MIN_DAYS = 14
COLD_WATER_C = 12.0   # assumed mains temperature for "litres of 40 °C water"
AMBIENT_C = 20.0      # assumed room temperature for standby-loss scaling
MONTHS_SR = ["januar", "februar", "mart", "april", "maj", "jun", "jul", "avgust", "septembar", "oktobar",
             "novembar", "decembar"]
SEASONS = {"zima": {12, 1, 2}, "prolece": {3, 4, 5}, "leto": {6, 7, 8}, "jesen": {9, 10, 11}}

K3 = Decimal("0.001")
K2 = Decimal("0.01")


def q3(x: Decimal) -> Decimal:
    return x.quantize(K3, rounding=ROUND_HALF_UP)


def q2(x: Decimal) -> Decimal:
    return x.quantize(K2, rounding=ROUND_HALF_UP)


@dataclass(frozen=True)
class EnergyRow:
    start: datetime
    kwh: Decimal
    cheap_kwh: Decimal | None
    expensive_kwh: Decimal | None
    cost_din: Decimal | None
    waste_din: Decimal | None

    def as_dict(self) -> dict:
        d = asdict(self)
        d["start"] = self.start.isoformat()
        return {k: (float(v) if isinstance(v, Decimal) else v) for k, v in d.items()}


@dataclass(frozen=True)
class Report:
    json: dict
    markdown: str


# ---------------------------------------------------------------------------------------- helpers
def _midnight(d: date, tz: ZoneInfo) -> datetime:
    return datetime.combine(d, time(0), tz)


def _month_start(d: date) -> date:
    return d.replace(day=1)


def _next_month(d: date) -> date:
    return date(d.year + d.month // 12, d.month % 12 + 1, 1)


async def _tariffs(conn: AsyncConnection) -> list[tuple[date, Tariff]]:
    rows = await (await conn.execute(
        "SELECT valid_from, cheap_start, cheap_end, cheap_price, expensive_price, zone FROM tariff "
        "ORDER BY valid_from")).fetchall()
    return [(r[0], Tariff(*r[1:])) for r in rows]


def _tariff_for(tariffs: list[tuple[date, Tariff]], d: date) -> Tariff:
    chosen = tariffs[0][1]
    for valid_from, t in tariffs:
        if valid_from <= d:
            chosen = t
    return chosen


def _row(start: datetime, kwh: Decimal, cheap: Decimal | None, t: Tariff) -> EnergyRow:
    if cheap is None:
        return EnergyRow(start, q3(kwh), None, None, None, None)
    cheap = q3(min(cheap, kwh))
    exp = q3(kwh - cheap)
    cost = q2(cheap * t.cheap_price + exp * t.expensive_price)
    waste = q2(exp * (t.expensive_price - t.cheap_price))
    return EnergyRow(start, q3(kwh), cheap, exp, cost, waste)


async def _buckets(conn: AsyncConnection, frm: datetime, to: datetime) -> list[tuple[datetime, datetime, Decimal]]:
    cur = await conn.execute("SELECT bucket_start, bucket_end, kwh FROM energy_2h WHERE bucket_start >= %s "
                             "AND bucket_start < %s ORDER BY bucket_start", (frm, to))
    return [tuple(r) for r in await cur.fetchall()]


# ---------------------------------------------------------------------------------------- energy
async def energy(conn: AsyncConnection, frm: datetime, to: datetime,
                 granularity: Literal["2h", "day", "month"], tz: ZoneInfo = ZoneInfo("Europe/Belgrade")) -> list[EnergyRow]:
    tariffs = await _tariffs(conn)
    if granularity == "2h":
        out = []
        for start, end, kwh in await _buckets(conn, frm, to):
            t = _tariff_for(tariffs, start.astimezone(tz).date())
            out.append(_row(start, kwh, kwh * Decimal(str(cheap_fraction(start, end, t, tz))), t))
        return out
    if granularity == "day":
        return await _energy_days(conn, frm, to, tz, tariffs)
    return await _energy_months(conn, frm, to, tz, tariffs)


async def _energy_days(conn, frm, to, tz, tariffs) -> list[EnergyRow]:
    first, last = frm.astimezone(tz).date(), (to - timedelta(microseconds=1)).astimezone(tz).date()
    day_rows = dict(await (await conn.execute(
        "SELECT day, kwh FROM energy_day WHERE day BETWEEN %s AND %s", (first, last))).fetchall())
    buckets = await _buckets(conn, _midnight(first, tz), _midnight(last + timedelta(days=1), tz))
    by_day: dict[date, list] = {}
    for b in buckets:
        by_day.setdefault(b[0].astimezone(tz).date(), []).append(b)
    out = []
    d = first
    while d <= last:
        t = _tariff_for(tariffs, d)
        bs = by_day.get(d, [])
        total = sum((b[2] for b in bs), Decimal(0))
        cheap = sum((b[2] * Decimal(str(cheap_fraction(b[0], b[1], t, tz))) for b in bs), Decimal(0))
        if d in day_rows:
            kwh = day_rows[d]
            if len(bs) >= 12:
                split = cheap if total == 0 or total == kwh else cheap * kwh / total
            else:
                split = None
            out.append(_row(_midnight(d, tz), kwh, split, t))
        elif bs:
            out.append(_row(_midnight(d, tz), total, cheap, t))
        d += timedelta(days=1)
    return out


async def _energy_months(conn, frm, to, tz, tariffs) -> list[EnergyRow]:
    first = _month_start(frm.astimezone(tz).date())
    last = (to - timedelta(microseconds=1)).astimezone(tz).date()
    month_rows = dict(await (await conn.execute(
        "SELECT month, kwh FROM energy_month WHERE month BETWEEN %s AND %s", (first, last))).fetchall())
    out = []
    m = first
    while m <= last:
        nxt = _next_month(m)
        days = await _energy_days(conn, _midnight(m, tz), _midnight(nxt, tz), tz, tariffs)
        t = _tariff_for(tariffs, m)
        if m in month_rows:
            kwh = month_rows[m]
        elif days:
            kwh = sum((r.kwh for r in days), Decimal(0))
        else:
            m = nxt
            continue
        known = days and all(r.cheap_kwh is not None for r in days)
        cheap = sum((r.cheap_kwh for r in days), Decimal(0)) if known else None
        out.append(_row(_midnight(m, tz), kwh, cheap, t))
        m = nxt
    return out


# ---------------------------------------------------------------------------------------- series / now
async def state_series(conn: AsyncConnection, frm: datetime, to: datetime, step: timedelta) -> list[dict]:
    cur = await conn.execute(
        "SELECT date_bin(%s, at, '2000-01-01T00:00:00Z'::timestamptz) AS b, avg(temp), max(req_temp), "
        'bool_or(heat_req), bool_or("on"), bool_or(eco) FROM state_sample WHERE at >= %s AND at < %s '
        "GROUP BY b ORDER BY b", (step, frm, to))
    return [{"at": r[0].isoformat(), "temp": round(float(r[1]), 1), "target": float(r[2]), "heating": r[3],
             "on": r[4], "eco": r[5]} for r in await cur.fetchall()]


async def now_summary(conn: AsyncConnection, tz: ZoneInfo, now: datetime | None = None) -> dict:
    now = now or datetime.now(tz)
    s = await latest_state(conn)
    today = _midnight(now.astimezone(tz).date(), tz)
    rows = await energy(conn, today, today + timedelta(days=1), "day", tz)
    row = await (await conn.execute(
        "SELECT kind FROM event WHERE kind LIKE 'expensive_heating_%%' ORDER BY at DESC, id DESC LIMIT 1")).fetchone()
    intent = await (await conn.execute(
        "SELECT source, desired, next_transition_at, at FROM app_intent ORDER BY at DESC LIMIT 1")).fetchone()
    return {
        "state": None if s is None else _state_dict(s),
        "age_s": None if s is None else int((now - s.at).total_seconds()),
        "today": rows[0].as_dict() if rows else None,
        "expensive_heating_open": bool(row and row[0] == "expensive_heating_started"),
        "intent": None if intent is None else {
            "source": intent[0], "desired": intent[1],
            "next_transition_at": intent[2].isoformat() if intent[2] else None, "at": intent[3].isoformat()},
    }


def _state_dict(s: StateSample) -> dict:
    return {"at": s.at.isoformat(), "on": s.on, "mode": s.mode, "temp": s.temp, "target": s.req_temp,
            "eco": s.eco, "heating": s.heat_req, "anti_legionella": s.anti_leg,
            "remaining_min": None if s.rm_tm is None else int(s.rm_tm.total_seconds() // 60),
            "showers": s.av_shw}


# ---------------------------------------------------------------------------------------- intervals
Interval = tuple[datetime, datetime]


def _merge(xs: list[Interval]) -> list[Interval]:
    out: list[Interval] = []
    for a, b in sorted(xs):
        if out and a <= out[-1][1]:
            out[-1] = (out[-1][0], max(out[-1][1], b))
        elif b > a:
            out.append((a, b))
    return out


def _subtract(xs: list[Interval], ys: list[Interval]) -> list[Interval]:
    out = []
    for a, b in xs:
        parts = [(a, b)]
        for c, d in ys:
            nxt = []
            for p, q in parts:
                if d <= p or c >= q:
                    nxt.append((p, q))
                    continue
                if c > p:
                    nxt.append((p, c))
                if d < q:
                    nxt.append((d, q))
            parts = nxt
        out.extend(parts)
    return out


def _overlap_s(xs: list[Interval], a: datetime, b: datetime) -> float:
    return sum(max(0.0, (min(y, b) - max(x, a)).total_seconds()) for x, y in xs)


async def _heating_intervals(conn, frm: datetime, to: datetime) -> list[Interval]:
    samples = await recent_states(conn, frm - timedelta(minutes=10))
    samples = [s for s in samples if s.at < to]
    xs = []
    for s, n in zip(samples, samples[1:]):
        if s.heat_req:
            xs.append((max(s.at, frm), min(n.at, to)))
    return _merge([x for x in xs if x[1] > x[0]])


async def plan_vs_actual(conn: AsyncConnection, day: date, tz: ZoneInfo) -> dict:
    start, end = _midnight(day, tz), _midnight(day + timedelta(days=1), tz)
    cur = await conn.execute(
        "(SELECT at, desired FROM app_intent WHERE at < %s ORDER BY at DESC LIMIT 1) "
        "UNION ALL (SELECT at, desired FROM app_intent WHERE at >= %s AND at < %s) ORDER BY at", (start, start, end))
    intents = await cur.fetchall()
    planned = []
    for (t, desired), nxt in zip(intents, intents[1:] + [(end, None)]):
        if desired.get("power_on"):
            planned.append((max(t, start), min(nxt[0], end)))
    planned = _merge(planned)
    actual = await _heating_intervals(conn, start, end)
    offplan = _subtract(actual, planned)
    ev = await (await conn.execute(
        "SELECT at, attribution FROM event WHERE kind IN ('heating_started', 'expensive_heating_started') "
        "AND at >= %s AND at < %s ORDER BY at", (start - timedelta(hours=12), end))).fetchall()

    def attribution_for(iv: Interval) -> str:
        best = "unknown"
        for at, attr in ev:
            if at <= iv[0] + timedelta(minutes=2) and attr:
                best = attr
        return best

    by_attr: dict[str, Decimal] = {}
    total = Decimal(0)
    for b_start, b_end, kwh in await _buckets(conn, start, end):
        heat = _overlap_s(actual, b_start, b_end)
        if heat <= 0:
            continue
        for iv in offplan:
            off = _overlap_s([iv], b_start, b_end)
            if off > 0:
                share = kwh * Decimal(str(off)) / Decimal(str(heat))
                key = attribution_for(iv)
                by_attr[key] = by_attr.get(key, Decimal(0)) + share
                total += share
    iso = lambda xs: [[a.isoformat(), b.isoformat()] for a, b in xs]  # noqa: E731
    return {"day": day.isoformat(), "planned": iso(planned), "actual": iso(actual), "offplan": iso(offplan),
            "offplan_kwh": q3(total), "by_attribution": {k: q3(v) for k, v in by_attr.items()}}


# ---------------------------------------------------------------------------------------- report
async def month_report(conn: AsyncConnection, month: date, tz: ZoneInfo) -> Report:
    m = _month_start(month)
    nxt = _next_month(m)
    rows = await energy(conn, _midnight(m, tz), _midnight(nxt, tz), "month", tz)
    days = await energy(conn, _midnight(m, tz), _midnight(nxt, tz), "day", tz)
    last_year = await (await conn.execute("SELECT kwh FROM energy_month WHERE month = %s",
                                          (date(m.year - 1, m.month, 1),))).fetchone()
    known = [d for d in days if d.cheap_kwh is not None]
    k_cheap = sum((d.cheap_kwh for d in known), Decimal(0))
    k_exp = sum((d.expensive_kwh for d in known), Decimal(0))
    k_cost = sum((d.cost_din for d in known), Decimal(0))
    k_waste = sum((d.waste_din for d in known), Decimal(0))
    top = await (await conn.execute(
        "SELECT at, attribution, detail FROM event WHERE kind = 'expensive_heating_started' AND at >= %s AND at < %s "
        "ORDER BY at DESC LIMIT 5", (_midnight(m, tz), _midnight(nxt, tz)))).fetchall()
    kwh = rows[0].kwh if rows else Decimal(0)
    per_day = q3(kwh / len(days)) if days else None
    data = {
        "month": m.strftime("%Y-%m"), "kwh": float(kwh), "kwh_per_day": float(per_day) if per_day is not None else None,
        "days_with_data": len(days), "days_with_split": len(known),
        "cheap_kwh": float(k_cheap), "expensive_kwh": float(k_exp), "cost_din": float(k_cost), "waste_din": float(k_waste),
        "cheap_pct": round(float(k_cheap / (k_cheap + k_exp) * 100), 1) if (k_cheap + k_exp) > 0 else None,
        "last_year_kwh": float(last_year[0]) if last_year else None,
        "expensive_heating": [{"at": a.isoformat(), "attribution": attr, "detail": det} for a, attr, det in top],
    }
    name = f"{MONTHS_SR[m.month - 1]} {m.year}"
    md = [f"# Bojler, {name}", "",
          f"- Potrošnja: {kwh} kWh" + (f", prosečno {per_day} kWh dnevno ({len(days)} dana sa podacima)." if per_day else "."),
          ]
    if known:
        md.append(f"- Za {len(known)} dana sa podelom: jeftina struja {q3(k_cheap)} kWh, skupa {q3(k_exp)} kWh"
                  f" ({data['cheap_pct']} % jeftino).")
        md.append(f"- Cena tih dana: {q2(k_cost)} din, od toga {q2(k_waste)} din više nego da je sve bilo noću.")
    else:
        md.append("- Podela na jeftinu i skupu struju još ne postoji za ovaj mesec, pa ni cena u din.")
    if last_year:
        md.append(f"- Isti mesec prošle godine: {last_year[0]} kWh.")
    if top:
        md.append(f"- Grejanje u skupoj tarifi: {len(top)} puta (poslednjih 5 u JSON-u, sa uzrokom).")
    return Report(data, "\n".join(md) + "\n")


# ---------------------------------------------------------------------------------------- insights
def standby_loss_w(c_per_h: float) -> float:
    return round(c_per_h * VOLUME_L * WH_PER_L_C, 2)


def recommend_target(day_minima: list[tuple[float, float]], comfort: float = COMFORT_C,
                     coverage: float = COVERAGE) -> int | None:
    """Smallest integer target keeping water >= comfort on >= coverage of days.
    Each tuple is (minimum water temperature between windows, target in force that day); a lower target is
    assumed to shift the whole curve down by the same difference."""
    if len(day_minima) < MIN_DAYS:
        return None
    hi = int(max(t for _, t in day_minima))
    best = None
    for cand in range(hi, int(comfort) - 1, -1):
        ok = sum(1 for mn, tgt in day_minima if mn - (tgt - cand) >= comfort)
        if ok / len(day_minima) >= coverage - 1e-9:
            best = cand
        else:
            break
    return best


def _in_season(d: date, season: str | None) -> bool:
    return season is None or d.month in SEASONS[season]


class _S(NamedTuple):
    at: datetime
    on: bool
    temp: float
    req_temp: float
    eco: bool
    heat_req: bool
    local: datetime


INSIGHT_DAYS = 365


async def _light_samples(conn: AsyncConnection, season: str | None, tz: ZoneInfo) -> list[_S]:
    """Only the columns insights need, last year only, season filtered in SQL (no raw JSON)."""
    sql = ('SELECT at, "on", temp, req_temp, eco, heat_req FROM state_sample '
           "WHERE at > now() - make_interval(days => %s)")
    args: list = [INSIGHT_DAYS]
    if season is not None:
        sql += " AND extract(month FROM at AT TIME ZONE %s)::int = ANY(%s)"
        args += [str(tz), sorted(SEASONS[season])]
    cur = await conn.execute(sql + " ORDER BY at", args)
    return [_S(*r, r[0].astimezone(tz)) for r in await cur.fetchall()]


def _segments(samples: list, pred) -> list[list]:
    segs, cur = [], []
    for s in samples:
        if pred(s):
            cur.append(s)
        else:
            if len(cur) > 1:
                segs.append(cur)
            cur = []
    if len(cur) > 1:
        segs.append(cur)
    return segs


async def insights(conn: AsyncConnection, season: str | None, tz: ZoneInfo) -> dict:
    months = await (await conn.execute("SELECT month, kwh FROM energy_month ORDER BY month")).fetchall()
    started = False
    by_month = []
    for m, kwh in months:
        started = started or kwh > 0
        if started and _in_season(m, season):
            ndays = (_next_month(m) - m).days
            by_month.append({"month": m.strftime("%Y-%m"), "kwh": float(kwh), "kwh_per_day": round(float(kwh) / ndays, 2)})

    samples = await _light_samples(conn, season, tz)
    draws = await (await conn.execute(
        "SELECT at, detail FROM event WHERE kind = 'water_draw' AND at > now() - make_interval(days => %s) "
        "ORDER BY at", (INSIGHT_DAYS,))).fetchall()
    draw_times = [a for a, _ in draws]

    rest_rates = []
    for seg in _segments(samples, lambda s: s.on and not s.heat_req):
        hours = (seg[-1].at - seg[0].at).total_seconds() / 3600
        i = bisect.bisect_left(draw_times, seg[0].at)
        has_draw = i < len(draw_times) and draw_times[i] <= seg[-1].at
        if hours >= 2 and not has_draw:
            rest_rates.append((seg[0].temp - seg[-1].temp) / hours)
    standby = standby_loss_w(statistics.median(rest_rates)) if rest_rates else None

    heat = {"eco": [], "no_eco": []}
    for seg in _segments(samples, lambda s: s.on and s.heat_req):
        hours = (seg[-1].at - seg[0].at).total_seconds() / 3600
        if hours >= 0.5 and seg[-1].temp > seg[0].temp:
            heat["eco" if seg[0].eco else "no_eco"].append((seg[-1].temp - seg[0].temp) / hours)
    rate = {k: (round(statistics.median(v), 1) if v else None) for k, v in heat.items()}

    kwh_per_c_vals = []
    if samples:
        times = [s.at for s in samples]
        for b_start, b_end, kwh in await _buckets(conn, samples[0].at, samples[-1].at):
            if kwh < Decimal("0.2"):
                continue
            lo, hi = bisect.bisect_left(times, b_start), bisect.bisect_left(times, b_end)
            inside = samples[lo:hi]
            rise = sum(max(0.0, n.temp - s.temp) for s, n in zip(inside, inside[1:]) if s.heat_req)
            if rise >= 2:
                kwh_per_c_vals.append(float(kwh) / rise)
    kwh_per_c = round(statistics.median(kwh_per_c_vals), 3) if kwh_per_c_vals else None

    days_with_samples = {s.local.date() for s in samples}
    hot_water = None
    if days_with_samples:
        litres = sum(VOLUME_L * float(det.get("drop", 0)) / (40 - COLD_WATER_C) for a, det in draws
                     if a.astimezone(tz).date() in days_with_samples)
        hot_water = round(litres / len(days_with_samples), 1)

    minima = []
    by_day: dict[date, list[_S]] = {}
    for s in samples:
        by_day.setdefault(s.local.date(), []).append(s)
    for d, ss in by_day.items():
        window = [s for s in ss if s.local.time() < time(8) and s.on]
        rest = [s for s in ss if s.local.time() >= time(8)]
        if window and len(rest) >= 200:
            minima.append((min(s.temp for s in rest), max(s.req_temp for s in window)))
    rec = recommend_target(minima)
    current = statistics.median([t for _, t in minima]) if minima else None
    savings = None
    if rec is not None and standby is not None and current is not None and current > rec and current > AMBIENT_C:
        saved_w = standby * (current - rec) / (current - AMBIENT_C)
        savings = round(saved_w * 24 * 30 / 1000, 1)
    return {
        "season": season,
        "kwh_per_day_by_month": by_month,
        "standby_loss_w": standby,
        "heating_rate_c_per_h": rate,
        "kwh_per_c": kwh_per_c,
        "hot_water_l40_per_day": hot_water,
        "recommended_target": None if rec is None else {"temp": rec, "coverage": COVERAGE, "days": len(minima)},
        "savings_kwh_per_month": savings,
    }
