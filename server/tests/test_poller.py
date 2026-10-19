import json
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest

from boilerd.ariston import AristonError, CallRecord, RateLimited
from boilerd.config import Settings
from boilerd.poller import Poller

TZ = ZoneInfo("Europe/Belgrade")
FIX = Path(__file__).parent / "fixtures"
GW = "80646F26422C"


class FakeClient:
    def __init__(self):
        self.state_body = json.loads((FIX / "state_2026-10-18T0109.json").read_text())["body"]
        self.cons_body = json.loads((FIX / "cons_2026-10-18T0109.json").read_text())["body"]
        self.fail_state = 0
        self.rate_limited = False
        self.errors: list[dict] = []
        self.calls: list[str] = []
        self.last_call = None

    def _rec(self, name, status=200, error=None):
        self.calls.append(name)
        self.last_call = CallRecord(name, status, 5, error)

    async def plants(self):
        self._rec("plants")
        return [{"gw": GW}]

    async def state(self, gw):
        if self.rate_limited:
            self._rec("state", 429, "rate limited")
            raise RateLimited(0)
        if self.fail_state > 0:
            self.fail_state -= 1
            self._rec("state", 500, "boom")
            raise AristonError(500, "boom")
        self._rec("state")
        return dict(self.state_body)

    async def consumption(self, gw):
        self._rec("consumption")
        return self.cons_body

    async def plant_settings(self, gw):
        self._rec("plant_settings")
        return {"MedMaxSetpointTemperature": 75.0}

    async def bus_errors(self, gw):
        self._rec("bus_errors")
        return self.errors


class Clock:
    def __init__(self, s):
        self.t = datetime.fromisoformat(s)

    def __call__(self):
        return self.t


def settings():
    return Settings("u", "p", "tok", "dsn", "rodsn", TZ, 120, None)


@pytest.fixture
def clock():
    return Clock("2026-10-18T02:20:00+02:00")


async def events(db, kind):
    async with db.connection() as conn:
        return (await (await conn.execute("SELECT count(*) FROM event WHERE kind = %s", (kind,))).fetchone())[0]


async def test_first_tick_runs_bootstrap_jobs(db, clock):
    p = Poller(FakeClient(), db, settings(), clock)
    ran = await p.tick()
    assert set(ran) >= {"plants", "state", "backfill", "settings", "errors"}
    assert p.gw == GW


async def test_due_jobs_schedule(db, clock):
    p = Poller(FakeClient(), db, settings(), clock)
    await p.tick()
    assert "energy" in p.due_jobs(datetime.fromisoformat("2026-10-18T04:07:00+02:00"))
    assert "energy" not in p.due_jobs(datetime.fromisoformat("2026-10-18T04:03:00+02:00"))
    nxt = p.due_jobs(datetime.fromisoformat("2026-10-19T02:10:00+02:00"))
    assert "backfill" in nxt and "crosscheck" in nxt
    assert "backfill" not in p.due_jobs(datetime.fromisoformat("2026-10-19T02:05:00+02:00"))


async def test_state_every_120s(db, clock):
    p = Poller(FakeClient(), db, settings(), clock)
    await p.tick()
    assert "state" not in p.due_jobs(clock.t + timedelta(seconds=60))
    assert "state" in p.due_jobs(clock.t + timedelta(seconds=120))


async def test_offline_after_3_failures_and_online_again(db, clock):
    c = FakeClient()
    p = Poller(c, db, settings(), clock)
    await p.tick()
    c.fail_state = 3
    for _ in range(4):
        clock.t += timedelta(seconds=120)
        await p.tick()
    assert await events(db, "device_offline") == 1
    assert await events(db, "device_online") == 1


async def test_rate_limited_skips_cycle_without_crash(db, clock):
    c = FakeClient()
    p = Poller(c, db, settings(), clock)
    await p.tick()
    c.rate_limited = True
    clock.t = datetime.fromisoformat("2026-10-18T04:08:00+02:00")
    n = len(c.calls)
    ran = await p.tick()
    assert "state" in ran
    assert c.calls[n:] == ["state"]


async def test_poll_log_written_per_call(db, clock):
    c = FakeClient()
    p = Poller(c, db, settings(), clock)
    await p.tick()
    async with db.connection() as conn:
        n = (await (await conn.execute("SELECT count(*) FROM poll_log")).fetchone())[0]
    assert n == len(c.calls)


async def test_new_bus_error_becomes_event(db, clock):
    c = FakeClient()
    p = Poller(c, db, settings(), clock)
    await p.tick()
    c.errors = [{"code": "E12"}]
    clock.t += timedelta(hours=1)
    await p.tick()
    assert await events(db, "device_error") == 1


async def test_first_tick_reads_consumption_once(db, clock):
    c = FakeClient()
    p = Poller(c, db, settings(), clock)
    await p.tick()
    assert c.calls.count("consumption") == 1
