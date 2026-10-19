import json
from datetime import date, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from zoneinfo import ZoneInfo

from boilerd.ingest import (
    daily_crosscheck, import_probe_log, parse_state, store_bus_errors, store_consumption,
    store_plant_settings, store_state,
)

TZ = ZoneInfo("Europe/Belgrade")
FIX = Path(__file__).parent / "fixtures"


def rec(name):
    return json.loads((FIX / name).read_text())


async def count(conn, table):
    return (await (await conn.execute(f"SELECT count(*) FROM {table}")).fetchone())[0]


def test_parse_state_fixture():
    r = rec("state_2026-10-18T0109.json")
    s = parse_state(r["body"], datetime.fromisoformat(r["ts"]))
    assert (s.temp, s.req_temp, s.eco, s.heat_req, s.on, s.mode) == (44.0, 55.0, True, True, True, 1)
    assert s.rm_tm == timedelta(hours=1, minutes=5)
    assert s.raw["gw"] == "80646F26422C"


async def test_store_state_returns_previous(db):
    seq = rec("states_draw_0121_0135.json")
    async with db.connection() as conn:
        first = parse_state(seq[0]["body"], datetime.fromisoformat(seq[0]["ts"]))
        assert await store_state(conn, first) is None
        second = parse_state(seq[1]["body"], datetime.fromisoformat(seq[1]["ts"]))
        prev = await store_state(conn, second)
        assert prev == first
        assert await count(conn, "state_sample") == 2


async def test_store_consumption_upserts(db):
    async with db.connection() as conn:
        for name in ["cons_2026-10-18T0109.json", "cons_2026-10-18T0210.json"]:
            r = rec(name)
            res = await store_consumption(conn, r["body"], datetime.fromisoformat(r["ts"]), TZ)
            assert res.buckets == 24 and res.days == 47 and res.months == 21
        assert await count(conn, "energy_2h") == 25
        kwh = (await (await conn.execute(
            "SELECT kwh FROM energy_2h WHERE bucket_start = %s",
            (datetime.fromisoformat("2026-10-18T00:00:00+02:00"),))).fetchone())[0]
        assert kwh == Decimal("2.250")
        row = await (await conn.execute("SELECT kwh, source FROM energy_month WHERE month = '2026-09-01'")).fetchone()
        assert row == (Decimal("24.975"), "p4")


async def test_store_consumption_skips_p1_near_boundary(db):
    r = rec("cons_2026-10-18T0210.json")
    async with db.connection() as conn:
        res = await store_consumption(conn, r["body"], datetime.fromisoformat("2026-10-18T02:03:00+02:00"), TZ)
        assert res.buckets == 0 and res.days == 47
        assert await count(conn, "energy_2h") == 0


async def test_daily_crosscheck_flags_mismatch(db):
    r = rec("cons_2026-10-18T0109.json")
    async with db.connection() as conn:
        await store_consumption(conn, r["body"], datetime.fromisoformat(r["ts"]), TZ)
        assert await daily_crosscheck(conn, date(2026, 10, 17), TZ) is None
        await conn.execute("UPDATE energy_2h SET kwh = kwh + 0.1 WHERE bucket_start = %s",
                           (datetime.fromisoformat("2026-10-17T12:00:00+02:00"),))
        warning = await daily_crosscheck(conn, date(2026, 10, 17), TZ)
        assert warning is not None and "2026-10-17" in warning


async def test_plant_settings_change_only(db):
    raw = {"MedMaxSetpointTemperature": 75.0, "MedAntilegionellaOnOff": 0.0}
    t = datetime.fromisoformat("2026-10-18T03:00:00+02:00")
    async with db.connection() as conn:
        assert await store_plant_settings(conn, raw, t) is True
        assert await store_plant_settings(conn, dict(raw), t + timedelta(hours=6)) is False
        assert await store_plant_settings(conn, {**raw, "MedMaxSetpointTemperature": 65.0},
                                          t + timedelta(hours=12)) is True
        assert await count(conn, "plant_settings") == 2


async def test_bus_errors_new_and_cleared(db):
    t = datetime.fromisoformat("2026-10-18T03:00:00+02:00")
    err = {"code": "E12", "description": "sensor"}
    async with db.connection() as conn:
        assert await store_bus_errors(conn, [err], t) == [err]
        assert await store_bus_errors(conn, [err], t + timedelta(hours=1)) == []
        assert await store_bus_errors(conn, [], t + timedelta(hours=2)) == []
        row = await (await conn.execute("SELECT cleared_at FROM device_error WHERE code = 'E12'")).fetchone()
        assert row[0] == t + timedelta(hours=2)


async def test_import_probe_log_idempotent(db):
    async with db.connection() as conn:
        n1 = await import_probe_log(conn, FIX / "probe_sample.jsonl", TZ)
        counts = (await count(conn, "state_sample"), await count(conn, "energy_2h"))
        n2 = await import_probe_log(conn, FIX / "probe_sample.jsonl", TZ)
        assert n1 >= 2 and n2 == n1
        assert (await count(conn, "state_sample"), await count(conn, "energy_2h")) == counts
        assert counts[0] >= 1 and counts[1] == 24
