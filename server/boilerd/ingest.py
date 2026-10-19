"""Parse Ariston responses and upsert them into the history tables (design §4.3)."""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from zoneinfo import ZoneInfo

from psycopg import AsyncConnection
from psycopg.types.json import Jsonb

from .buckets import p1_bucket_bounds, p1_safe_to_read, p3_days, p3_expected_len, p4_months

ENERGY_KIND = 2  # DOMESTIC_HOT_WATER_TOTAL_ENERGY
CROSSCHECK_TOLERANCE = Decimal("0.05")


@dataclass(frozen=True)
class StateSample:
    at: datetime
    on: bool
    mode: int
    temp: float
    req_temp: float
    proc_req_temp: float | None
    eco: bool
    heat_req: bool
    anti_leg: bool
    rm_tm: timedelta | None
    av_shw: int | None
    pwr_opt: bool | None
    raw: dict = field(compare=False, hash=False)


@dataclass(frozen=True)
class ConsumptionResult:
    buckets: int
    days: int
    months: int
    warnings: list[str]


def _hms(value: str | None) -> timedelta | None:
    if not value:
        return None
    h, m, s = (int(x) for x in value.split(":"))
    return timedelta(hours=h, minutes=m, seconds=s)


def parse_state(raw: dict, at: datetime) -> StateSample:
    return StateSample(
        at=at, on=bool(raw["on"]), mode=int(raw["mode"]), temp=float(raw["temp"]),
        req_temp=float(raw["reqTemp"]),
        proc_req_temp=None if raw.get("procReqTemp") is None else float(raw["procReqTemp"]),
        eco=bool(raw.get("eco", False)), heat_req=bool(raw.get("heatReq", False)),
        anti_leg=bool(raw.get("antiLeg", False)), rm_tm=_hms(raw.get("rmTm")),
        av_shw=raw.get("avShw"), pwr_opt=raw.get("pwrOpt"), raw=raw,
    )


_STATE_COLS = "at, \"on\", mode, temp, req_temp, proc_req_temp, eco, heat_req, anti_leg, rm_tm, av_shw, pwr_opt, raw"


def _row_to_state(row) -> StateSample:
    return StateSample(*row[:12], raw=row[12])


async def latest_state(conn: AsyncConnection, before: datetime | None = None) -> StateSample | None:
    if before is None:
        cur = await conn.execute(f"SELECT {_STATE_COLS} FROM state_sample ORDER BY at DESC LIMIT 1")
    else:
        cur = await conn.execute(
            f"SELECT {_STATE_COLS} FROM state_sample WHERE at < %s ORDER BY at DESC LIMIT 1", (before,))
    row = await cur.fetchone()
    return _row_to_state(row) if row else None


async def recent_states(conn: AsyncConnection, since: datetime) -> list[StateSample]:
    cur = await conn.execute(f"SELECT {_STATE_COLS} FROM state_sample WHERE at >= %s ORDER BY at", (since,))
    return [_row_to_state(r) for r in await cur.fetchall()]


async def store_state(conn: AsyncConnection, s: StateSample) -> StateSample | None:
    """Insert a sample (ignored if that instant exists); returns the sample before it, if any."""
    prev = await latest_state(conn, before=s.at)
    await conn.execute(
        f"INSERT INTO state_sample ({_STATE_COLS}) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) "
        "ON CONFLICT (at) DO NOTHING",
        (s.at, s.on, s.mode, s.temp, s.req_temp, s.proc_req_temp, s.eco, s.heat_req, s.anti_leg,
         s.rm_tm, s.av_shw, s.pwr_opt, Jsonb(s.raw)),
    )
    return prev


def _seq(raw: list[dict]) -> dict[int, list]:
    return {x["p"]: x["v"] for x in raw if x.get("k") == ENERGY_KIND}


async def store_consumption(conn: AsyncConnection, raw: list[dict], fetched_at: datetime,
                            tz: ZoneInfo) -> ConsumptionResult:
    seq = _seq(raw)
    warnings: list[str] = []
    buckets = days = months = 0
    if 1 in seq and p1_safe_to_read(fetched_at, tz):
        for (start, end), v in zip(p1_bucket_bounds(fetched_at, len(seq[1]), tz), seq[1]):
            await conn.execute(
                "INSERT INTO energy_2h (bucket_start, bucket_end, kwh, first_seen_at, last_seen_at) "
                "VALUES (%s,%s,%s,%s,%s) ON CONFLICT (bucket_start) DO UPDATE "
                "SET kwh = EXCLUDED.kwh, bucket_end = EXCLUDED.bucket_end, last_seen_at = EXCLUDED.last_seen_at",
                (start, end, Decimal(str(v)), fetched_at, fetched_at))
            buckets += 1
    if 3 in seq:
        expected = p3_expected_len(fetched_at, tz)
        if len(seq[3]) != expected:
            warnings.append(f"p=3 has {len(seq[3])} days, expected {expected}; aligned from the end")
        for d, v in zip(p3_days(fetched_at, len(seq[3]), tz), seq[3]):
            await conn.execute(
                "INSERT INTO energy_day (day, kwh, source, last_seen_at) VALUES (%s,%s,'p3',%s) "
                "ON CONFLICT (day) DO UPDATE SET kwh = EXCLUDED.kwh, source = 'p3', last_seen_at = EXCLUDED.last_seen_at",
                (d, Decimal(str(v)), fetched_at))
            days += 1
    if 4 in seq:
        for m, v in zip(p4_months(fetched_at, len(seq[4]), tz), seq[4]):
            await conn.execute(
                "INSERT INTO energy_month (month, kwh, source, last_seen_at) VALUES (%s,%s,'p4',%s) "
                "ON CONFLICT (month) DO UPDATE SET kwh = EXCLUDED.kwh, source = 'p4', last_seen_at = EXCLUDED.last_seen_at",
                (m, Decimal(str(v)), fetched_at))
            months += 1
    return ConsumptionResult(buckets, days, months, warnings)


async def daily_crosscheck(conn: AsyncConnection, day: date, tz: ZoneInfo) -> str | None:
    """Warn when the 2 h buckets of a fully covered day disagree with the cloud's daily value."""
    start = datetime.combine(day, datetime.min.time(), tz)
    end = datetime.combine(day + timedelta(days=1), datetime.min.time(), tz)
    cur = await conn.execute(
        "SELECT count(*), coalesce(sum(kwh), 0) FROM energy_2h WHERE bucket_start >= %s AND bucket_start < %s",
        (start, end))
    n, total = await cur.fetchone()
    row = await (await conn.execute("SELECT kwh FROM energy_day WHERE day = %s", (day,))).fetchone()
    if row is None or n < 12:
        return None
    if abs(total - row[0]) > CROSSCHECK_TOLERANCE:
        return f"{day.isoformat()}: sum of 2 h buckets {total} != daily {row[0]}"
    return None


async def store_plant_settings(conn: AsyncConnection, raw: dict, at: datetime) -> bool:
    row = await (await conn.execute("SELECT raw FROM plant_settings ORDER BY at DESC LIMIT 1")).fetchone()
    if row is not None and row[0] == raw:
        return False
    await conn.execute("INSERT INTO plant_settings (at, raw) VALUES (%s,%s) ON CONFLICT DO NOTHING",
                       (at, Jsonb(raw)))
    return True


def _error_code(err: dict) -> str:
    for key in ("code", "errCode", "errorCode"):
        if err.get(key) is not None:
            return str(err[key])
    return json.dumps(err, sort_keys=True)


async def store_bus_errors(conn: AsyncConnection, raw: list[dict], at: datetime) -> list[dict]:
    """Returns errors not open before; closes open errors that are no longer reported."""
    cur = await conn.execute("SELECT code FROM device_error WHERE cleared_at IS NULL")
    open_codes = {r[0] for r in await cur.fetchall()}
    current = {_error_code(e): e for e in raw}
    new = []
    for code, err in current.items():
        if code not in open_codes:
            await conn.execute("INSERT INTO device_error (code, first_seen_at, raw) VALUES (%s,%s,%s)",
                               (code, at, Jsonb(err)))
            new.append(err)
    for code in open_codes - current.keys():
        await conn.execute("UPDATE device_error SET cleared_at = %s WHERE code = %s AND cleared_at IS NULL",
                           (at, code))
    return new


async def import_probe_log(conn: AsyncConnection, path: Path, tz: ZoneInfo) -> int:
    """Import the JSONL written by the 2026-10-18 probe logger; idempotent. Returns records processed."""
    n = 0
    with path.open(encoding="utf-8") as f:
        for line in f:
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue
            if r.get("status") != 200:
                continue
            at = datetime.fromisoformat(r["ts"]).astimezone(timezone.utc)
            if r.get("kind") == "state" and isinstance(r.get("body"), dict):
                await store_state(conn, parse_state(r["body"], at))
                n += 1
            elif r.get("kind") == "cons" and isinstance(r.get("body"), list):
                await store_consumption(conn, r["body"], at, tz)
                n += 1
    return n
