import contextlib
import json
import os
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import httpx
import pytest
from mcp import Client

from boilerd.app import create_app
from boilerd.config import Settings
from boilerd.ingest import store_consumption
from boilerd.mcp_tools import build_mcp

TZ = ZoneInfo("Europe/Belgrade")
FIX = Path(__file__).parent / "fixtures"
NAMES = {"boiler_now", "boiler_state_series", "boiler_energy", "boiler_events", "boiler_report",
         "boiler_insights", "boiler_explain", "boiler_health", "boiler_sql"}
INIT = {"jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                   "clientInfo": {"name": "t", "version": "1"}}}
MCP_HEADERS = {"Accept": "application/json, text/event-stream", "Content-Type": "application/json"}


def server(db):
    return build_mcp(db, os.environ["BOILERD_TEST_DSN"], TZ, poller=None)


async def load(db):
    r = json.loads((FIX / "cons_2026-10-18T0109.json").read_text())
    async with db.connection() as conn:
        await store_consumption(conn, r["body"], datetime.fromisoformat(r["ts"]), TZ)


async def test_list_tools_names(db):
    async with Client(server(db)) as c:
        tools = await c.list_tools()
    assert {t.name for t in tools.tools} == NAMES


async def test_boiler_energy_matches_api(db):
    await load(db)
    async with Client(server(db)) as c:
        res = await c.call_tool("boiler_energy", {"from_": "2026-10-17T00:00:00+02:00",
                                                  "to": "2026-10-18T00:00:00+02:00", "granularity": "day"})
    data = res.structured_content
    assert data["rows"][0]["kwh"] == 2.7 and data["rows"][0]["expensive_kwh"] == 1.075
    assert "kWh" in data["summary"]


async def test_boiler_sql_rejects_writes(db):
    await load(db)
    async with Client(server(db)) as c:
        res = await c.call_tool("boiler_sql", {"query": "DELETE FROM energy_2h"})
        assert res.is_error
        res2 = await c.call_tool("boiler_sql", {"query": "WITH d AS (DELETE FROM energy_2h RETURNING 1) SELECT * FROM d"})
        assert res2.is_error
    async with db.connection() as conn:
        assert (await (await conn.execute("SELECT count(*) FROM energy_2h")).fetchone())[0] == 24


async def test_boiler_sql_truncates_at_1000(db):
    async with Client(server(db)) as c:
        res = await c.call_tool("boiler_sql", {"query": "SELECT generate_series(1, 1500) AS n"})
    data = res.structured_content
    assert data["columns"] == ["n"] and len(data["rows"]) == 1000 and data["truncated"] is True


async def test_schema_resource(db):
    async with Client(server(db)) as c:
        res = await c.read_resource("boiler://schema")
    text = res.contents[0].text
    assert "energy_2h" in text and "00–08" in text


def settings():
    return Settings("u", "p", "tok", "dsn", os.environ["BOILERD_TEST_DSN"], TZ, 120, None)


@contextlib.asynccontextmanager
async def http(db):
    # lifespan entered and exited inside the test's own task (anyio cancel scopes require it)
    app = create_app(settings(), pool=db, start_poller=False)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://127.0.0.1:8020") as c:
            yield c


async def test_mcp_requires_token(db):
    async with http(db) as c:
        r = await c.post("/mcp", json=INIT, headers=MCP_HEADERS)
    assert r.status_code == 401


async def test_mcp_accepts_proxied_host(db):
    async with http(db) as c:
        r = await c.post("/mcp", json=INIT, headers={**MCP_HEADERS, "Authorization": "Bearer tok", "Host": "novica-dev"})
        bad = await c.post("/mcp", json=INIT, headers={**MCP_HEADERS, "Authorization": "Bearer tok", "Host": "evil.example"})
    assert r.status_code == 200, r.text
    assert r.json()["result"]["serverInfo"]["name"] == "dobarboiler"
    assert bad.status_code == 421


async def test_rest_still_served_next_to_mcp(db):
    async with http(db) as c:
        r = await c.get("/api/v1/health")
    assert r.status_code == 200 and "ok" in r.json()
