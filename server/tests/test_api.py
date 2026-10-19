import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

import httpx
import pytest
from fastapi import FastAPI

from boilerd.api import router
from boilerd.config import Settings
from boilerd.events import EventDraft, persist_events
from boilerd.ingest import store_consumption

TZ = ZoneInfo("Europe/Belgrade")
FIX = Path(__file__).parent / "fixtures"
AUTH = {"Authorization": "Bearer tok"}


@pytest.fixture
async def client(db):
    app = FastAPI()
    app.include_router(router)
    app.state.pool = db
    app.state.settings = Settings("u", "p", "tok", "dsn", "rodsn", TZ, 120, None)
    app.state.poller = None
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as c:
        yield c


def batch(ids, at="2026-10-18T14:01:00+02:00"):
    return {"install_id": "inst", "actions": [
        {"local_id": i, "at": at, "kind": "CONVERGE", "trig": "USER", "detail": "temp 65",
         "outcome": "SUCCESS", "attempt": 1, "state_json": None} for i in ids]}


async def test_requires_token(client):
    assert (await client.get("/api/v1/now")).status_code == 401
    assert (await client.get("/api/v1/now", headers={"Authorization": "Bearer nope"})).status_code == 401
    assert (await client.get("/api/v1/now", headers=AUTH)).status_code == 200


async def test_ingest_actions_idempotent(client, db):
    r1 = await client.post("/api/v1/app/actions", json=batch([1, 2]), headers=AUTH)
    r2 = await client.post("/api/v1/app/actions", json=batch([1, 2]), headers=AUTH)
    assert r1.json() == {"accepted": 2, "duplicates": 0}
    assert r2.json() == {"accepted": 0, "duplicates": 2}
    async with db.connection() as conn:
        assert (await (await conn.execute("SELECT count(*) FROM app_action")).fetchone())[0] == 2


async def test_ingest_rejects_oversized_batch(client):
    r = await client.post("/api/v1/app/actions", json=batch(range(501)), headers=AUTH)
    assert r.status_code == 422


async def test_ingest_triggers_reattribution(client, db):
    async with db.connection() as conn:
        await persist_events(conn, [EventDraft(datetime.fromisoformat("2026-10-18T14:02:00+02:00"),
                                               "target_changed", {"from": 55, "to": 65})], lambda d: "external")
    await client.post("/api/v1/app/actions", json=batch([5]), headers=AUTH)
    async with db.connection() as conn:
        assert (await (await conn.execute("SELECT attribution FROM event")).fetchone())[0] == "app"


async def test_intent_stored(client, db):
    body = {"install_id": "inst", "at": "2026-10-18T07:30:00+02:00", "source": "DEFAULT_OFF",
            "desired": {"power_on": False, "target_temp": None, "eco": None}, "next_transition_at": None}
    assert (await client.post("/api/v1/app/intent", json=body, headers=AUTH)).status_code == 204
    assert (await client.post("/api/v1/app/intent", json=body, headers=AUTH)).status_code == 204
    async with db.connection() as conn:
        assert (await (await conn.execute("SELECT count(*) FROM app_intent")).fetchone())[0] == 1


async def test_energy_endpoint_shape(client, db):
    r = json.loads((FIX / "cons_2026-10-18T0109.json").read_text())
    async with db.connection() as conn:
        await store_consumption(conn, r["body"], datetime.fromisoformat(r["ts"]), TZ)
    res = await client.get("/api/v1/energy", params={"from": "2026-10-17T00:00:00+02:00",
                                                     "to": "2026-10-18T00:00:00+02:00", "granularity": "2h"},
                           headers=AUTH)
    rows = res.json()
    assert len(rows) == 12
    assert set(rows[0]) == {"start", "kwh", "cheap_kwh", "expensive_kwh", "cost_din", "waste_din"}
    assert rows[7]["expensive_kwh"] == 0.65


async def test_report_endpoint(client, db):
    r = json.loads((FIX / "cons_2026-10-18T0109.json").read_text())
    async with db.connection() as conn:
        await store_consumption(conn, r["body"], datetime.fromisoformat(r["ts"]), TZ)
    res = (await client.get("/api/v1/report", params={"month": "2026-10"}, headers=AUTH)).json()
    assert res["json"]["month"] == "2026-10" and "Bojler" in res["markdown"]


async def test_health_without_token(client, db):
    body = (await client.get("/api/v1/health")).json()
    assert body["ok"] is False and body["last_state_at"] is None
    async with db.connection() as conn:
        await conn.execute('INSERT INTO state_sample (at, "on", mode, temp, req_temp, eco, heat_req, anti_leg, raw) '
                           "VALUES (%s, true, 1, 50, 55, true, false, false, '{}')", (datetime.now(timezone.utc),))
    assert (await client.get("/api/v1/health")).json()["ok"] is True


async def test_series_endpoint_and_step(client, db):
    async with db.connection() as conn:
        for i, temp in enumerate([44.0, 45.0, 46.0]):
            await conn.execute('INSERT INTO state_sample (at, "on", mode, temp, req_temp, eco, heat_req, anti_leg, raw) '
                               "VALUES (%s, true, 1, %s, 55, true, true, false, '{}')",
                               (datetime.fromisoformat("2026-10-18T01:00:00+02:00") + timedelta(minutes=2 * i), temp))
    res = await client.get("/api/v1/series/state", params={"from": "2026-10-18T00:00:00+02:00",
                                                           "to": "2026-10-18T02:00:00+02:00", "step": "1h"},
                           headers=AUTH)
    assert res.json() == [{"at": "2026-10-17T23:00:00+00:00", "temp": 45.0, "target": 55.0, "heating": True,
                           "on": True, "eco": True}]
    bad = await client.get("/api/v1/series/state", params={"step": "5 minutes"}, headers=AUTH)
    assert bad.status_code == 422
