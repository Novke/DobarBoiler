import json
from dataclasses import replace
from datetime import datetime, time, timedelta
from decimal import Decimal
from pathlib import Path
from zoneinfo import ZoneInfo

from psycopg.types.json import Jsonb

from boilerd.buckets import Tariff
from boilerd.events import AppAction, AppIntent, EventDraft, attribute, detect, persist_events, reattribute_recent
from boilerd.ingest import parse_state

TZ = ZoneInfo("Europe/Belgrade")
FIX = Path(__file__).parent / "fixtures"
TARIFF = Tariff(time(0), time(8), Decimal("3.6051"), Decimal("14.4203"), "plava")
BASE = parse_state(json.loads((FIX / "state_2026-10-18T0109.json").read_text())["body"],
                   datetime.fromisoformat("2026-10-18T01:09:17+02:00"))


def st(at: str, **kw):
    return replace(BASE, at=datetime.fromisoformat(at), **kw)


def kinds(evs):
    return [e.kind for e in evs]


def test_detect_target_changed():
    prev = st("2026-10-18T14:00:00+02:00", heat_req=False)
    cur = st("2026-10-18T14:02:00+02:00", heat_req=False, req_temp=65.0)
    evs = detect(prev, cur, [prev], TARIFF, TZ)
    assert kinds(evs) == ["target_changed"]
    assert evs[0].detail == {"from": 55.0, "to": 65.0}


def test_detect_heating_outside_window_opens_expensive():
    prev = st("2026-10-17T11:58:00+02:00", heat_req=False)
    cur = st("2026-10-17T12:00:00+02:00", heat_req=True)
    evs = detect(prev, cur, [prev], TARIFF, TZ)
    assert kinds(evs) == ["heating_started", "expensive_heating_started"]
    assert evs[0].detail["cheap"] is False
    stop = detect(cur, st("2026-10-17T16:00:00+02:00", heat_req=False), [cur], TARIFF, TZ)
    assert kinds(stop) == ["heating_stopped", "expensive_heating_stopped"]


def test_heating_crossing_into_expensive_window():
    prev = st("2026-10-18T07:59:00+02:00", heat_req=True)
    cur = st("2026-10-18T08:01:00+02:00", heat_req=True)
    assert kinds(detect(prev, cur, [prev], TARIFF, TZ)) == ["expensive_heating_started"]


def test_detect_water_draw_from_fixture():
    seq = json.loads((FIX / "states_draw_0121_0135.json").read_text())
    samples = [parse_state(r["body"], datetime.fromisoformat(r["ts"])) for r in seq]
    found = []
    last_draw = None
    for i in range(1, len(samples)):
        evs = detect(samples[i - 1], samples[i], samples[:i], TARIFF, TZ, last_draw_at=last_draw)
        for e in evs:
            if e.kind == "water_draw":
                found.append(e)
                last_draw = e.at
    assert [e.at.strftime("%H:%M") for e in found] == ["01:29"]
    assert found[0].detail["drop"] == 3.0


def test_water_draw_not_repeated_within_10_min():
    prev = st("2026-10-18T10:00:00+02:00", temp=50.0, heat_req=False)
    cur = st("2026-10-18T10:02:00+02:00", temp=47.0, heat_req=False)
    assert "water_draw" in kinds(detect(prev, cur, [prev], TARIFF, TZ))
    assert "water_draw" not in kinds(detect(prev, cur, [prev], TARIFF, TZ, last_draw_at=prev.at))


def test_mode_9_is_warning():
    prev = st("2026-10-18T10:00:00+02:00")
    evs = detect(prev, st("2026-10-18T10:02:00+02:00", mode=9), [prev], TARIFF, TZ)
    assert kinds(evs) == ["mode_changed"] and evs[0].severity == "warning"


def ev(kind="target_changed", at="2026-10-18T14:02:00+02:00", **detail):
    return EventDraft(datetime.fromisoformat(at), kind, detail)


def act(at, outcome="SUCCESS", local_id=1):
    return AppAction("inst", local_id, datetime.fromisoformat(at), "CONVERGE", "USER", "x", outcome, None)


def test_attr_app_within_window():
    cur = st("2026-10-18T14:02:00+02:00")
    assert attribute(ev(), [act("2026-10-18T14:00:30+02:00")], datetime.fromisoformat("2026-10-18T14:00:30+02:00"),
                     None, cur) == "app"


def test_attr_external_without_action():
    cur = st("2026-10-18T14:02:00+02:00")
    assert attribute(ev(), [act("2026-10-18T13:50:00+02:00", outcome="FAILED")],
                     datetime.fromisoformat("2026-10-18T13:50:00+02:00"), None, cur) == "external"


def test_attr_unknown_when_phone_silent_6h():
    cur = st("2026-10-18T14:02:00+02:00")
    assert attribute(ev(), [], datetime.fromisoformat("2026-10-18T07:00:00+02:00"), None, cur) == "unknown"
    assert attribute(ev(), [], None, None, cur) == "unknown"


def test_attr_firmware_eco_daytime_heating():
    cur = st("2026-10-17T12:00:00+02:00", eco=True, heat_req=True)
    off = AppIntent("inst", cur.at, "DEFAULT_OFF", {"power_on": False}, None)
    e = ev("expensive_heating_started", "2026-10-17T12:00:00+02:00")
    assert attribute(e, [], cur.at, off, cur) == "firmware_eco"
    assert attribute(e, [], cur.at, off, replace(cur, eco=False)) == "firmware"
    boost = AppIntent("inst", cur.at, "BOOST", {"power_on": True, "target_temp": 60, "eco": False}, None)
    assert attribute(e, [], cur.at, boost, cur) == "app"
    assert attribute(ev("heating_started", "2026-10-17T03:00:00+02:00", cheap=True), [], cur.at, off, cur) is None


async def test_reattribute_after_late_upload(db):
    async with db.connection() as conn:
        e = ev()
        n = await persist_events(conn, [e], lambda d: "external")
        assert n == 1
        await conn.execute(
            "INSERT INTO app_action (install_id, local_id, at, kind, trig, detail, outcome) "
            "VALUES ('inst', 7, %s, 'CONVERGE', 'USER', 'temp 65', 'SUCCESS')",
            (datetime.fromisoformat("2026-10-18T14:01:00+02:00"),))
        changed = await reattribute_recent(conn, datetime.fromisoformat("2026-10-18T13:00:00+02:00"))
        assert changed == 1
        row = await (await conn.execute("SELECT attribution FROM event")).fetchone()
        assert row[0] == "app"
