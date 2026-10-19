import json
from datetime import date, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from zoneinfo import ZoneInfo

from psycopg.types.json import Jsonb

from boilerd.analytics import (
    energy, insights, month_report, plan_vs_actual, recommend_target, standby_loss_w,
)
from boilerd.ingest import store_consumption

TZ = ZoneInfo("Europe/Belgrade")
FIX = Path(__file__).parent / "fixtures"


def at(s):
    return datetime.fromisoformat(s)


async def load_cons(conn, name="cons_2026-10-18T0109.json"):
    r = json.loads((FIX / name).read_text())
    await store_consumption(conn, r["body"], at(r["ts"]), TZ)


async def add_state(conn, t, heat, temp=50.0, on=True, eco=True, req=55.0):
    await conn.execute(
        'INSERT INTO state_sample (at, "on", mode, temp, req_temp, eco, heat_req, anti_leg, raw) '
        "VALUES (%s,%s,1,%s,%s,%s,%s,false,'{}')", (t, on, temp, req, eco, heat))


async def test_energy_day_split_2026_10_17(db):
    async with db.connection() as conn:
        await load_cons(conn)
        rows = await energy(conn, at("2026-10-17T00:00:00+02:00"), at("2026-10-18T00:00:00+02:00"), "day", TZ)
    assert len(rows) == 1
    r = rows[0]
    assert (r.kwh, r.cheap_kwh, r.expensive_kwh) == (Decimal("2.700"), Decimal("1.625"), Decimal("1.075"))
    assert r.cost_din == Decimal("21.36")
    assert r.waste_din == Decimal("11.63")


async def test_energy_2h_rows(db):
    async with db.connection() as conn:
        await load_cons(conn)
        rows = await energy(conn, at("2026-10-17T12:00:00+02:00"), at("2026-10-17T16:00:00+02:00"), "2h", TZ)
    assert [(r.kwh, r.cheap_kwh) for r in rows] == [(Decimal("0.425"), Decimal("0.000")), (Decimal("0.650"), Decimal("0.000"))]


async def test_energy_month_old_history_has_no_split(db):
    async with db.connection() as conn:
        await load_cons(conn)
        rows = await energy(conn, at("2026-01-01T00:00:00+01:00"), at("2026-02-01T00:00:00+01:00"), "month", TZ)
    assert len(rows) == 1
    assert rows[0].kwh == Decimal("72.175") and rows[0].cheap_kwh is None and rows[0].cost_din is None


def test_standby_loss_w():
    assert standby_loss_w(0.5) == 46.52


def test_recommend_target():
    minima = [(48.0, 55.0)] * 19 + [(43.0, 55.0)]
    assert recommend_target(minima) == 47
    assert recommend_target(minima, coverage=1.0) == 52
    assert recommend_target([(48.0, 55.0)] * 10) is None


async def test_plan_vs_actual_counts_offplan_kwh(db):
    async with db.connection() as conn:
        await load_cons(conn)
        await conn.execute("INSERT INTO app_intent (install_id, at, source, desired) VALUES "
                           "('i', %s, 'WEEKLY_PLAN', %s), ('i', %s, 'DEFAULT_OFF', %s)",
                           (at("2026-10-17T00:30:00+02:00"), Jsonb({"power_on": True, "target_temp": 55}),
                            at("2026-10-17T07:30:00+02:00"), Jsonb({"power_on": False})))
        t = at("2026-10-17T12:00:00+02:00")
        while t < at("2026-10-17T16:00:00+02:00"):
            await add_state(conn, t, True)
            t += timedelta(minutes=10)
        await add_state(conn, t, False)
        await conn.execute("INSERT INTO event (at, kind, attribution, severity) VALUES (%s, 'expensive_heating_started', "
                           "'firmware_eco', 'warning')", (at("2026-10-17T12:00:00+02:00"),))
        res = await plan_vs_actual(conn, date(2026, 10, 17), TZ)
    assert res["offplan_kwh"] == Decimal("1.075")
    assert res["by_attribution"] == {"firmware_eco": Decimal("1.075")}


async def test_month_report_markdown_mentions_waste(db):
    async with db.connection() as conn:
        await load_cons(conn)
        rep = await month_report(conn, date(2026, 10, 1), TZ)
    assert "din" in rep.markdown and "jeftin" in rep.markdown
    assert rep.json["month"] == "2026-10"


async def test_insights_on_sparse_data(db):
    async with db.connection() as conn:
        await load_cons(conn)
        res = await insights(conn, None, TZ)
    assert res["recommended_target"] is None
    assert res["standby_loss_w"] is None
    assert res["kwh_per_c"] is None
    assert any(m["month"] == "2026-01" for m in res["kwh_per_day_by_month"])


async def test_insights_scales_to_a_year_of_samples(db):
    import time as _t
    async with db.connection() as conn:
        await conn.execute(
            'INSERT INTO state_sample (at, "on", mode, temp, req_temp, eco, heat_req, anti_leg, raw) '
            "SELECT ts, true, 1, 45 + (mod(extract(minute FROM ts)::int, 10)), 55, true, (extract(hour FROM ts)::int < 4), false, "
            "'{\"pad\": \"" + "x" * 300 + "\"}' FROM generate_series(now() - interval '300 days', now(), interval '2 minutes') ts")
        await conn.execute(
            "INSERT INTO energy_2h (bucket_start, bucket_end, kwh, first_seen_at, last_seen_at) "
            "SELECT ts, ts + interval '2 hours', 0.3, now(), now() FROM generate_series(now() - interval '300 days', now(), interval '2 hours') ts")
        started = _t.monotonic()
        res = await insights(conn, None, TZ)
        elapsed = _t.monotonic() - started
    assert elapsed < 8, elapsed
    assert res["heating_rate_c_per_h"] is not None
