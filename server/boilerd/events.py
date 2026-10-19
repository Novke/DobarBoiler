"""Change events derived from consecutive state samples, and their attribution (design §4.4).

Attribution answers "who did this": the app (a matching action uploaded by the phone),
something external (official Ariston app or the buttons on the boiler — indistinguishable),
the boiler firmware itself (thermostat, or eco/ECO EVO), or unknown (phone silent).
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Callable
from zoneinfo import ZoneInfo

from psycopg import AsyncConnection
from psycopg.types.json import Jsonb

from .buckets import Tariff, cheap_fraction
from .ingest import StateSample

CHANGE_KINDS = {"power_on", "power_off", "target_changed", "eco_changed", "mode_changed"}
HEATING_KINDS = {"heating_started", "expensive_heating_started"}
ACTION_BEFORE = timedelta(minutes=3)
ACTION_AFTER = timedelta(minutes=5)
PHONE_SILENT = timedelta(hours=6)
DRAW_WINDOW = timedelta(minutes=6)
DRAW_COOLDOWN = timedelta(minutes=10)
DRAW_MIN_DROP = 2.0
INTENT_MAX_AGE = timedelta(hours=24)
# action_log rows that are notes, not commands sent to the boiler
NON_COMMAND_KINDS = {"MANUAL_CHANGE_DETECTED", "VERIFY_POLL"}


@dataclass(frozen=True)
class EventDraft:
    at: datetime
    kind: str
    detail: dict = field(default_factory=dict)
    severity: str = "info"


@dataclass(frozen=True)
class AppAction:
    install_id: str
    local_id: int
    at: datetime
    kind: str
    trig: str
    detail: str
    outcome: str
    state_json: str | None


@dataclass(frozen=True)
class AppIntent:
    install_id: str
    at: datetime
    source: str
    desired: dict
    next_transition_at: datetime | None


def _cheap_at(t: datetime, tariff: Tariff, tz: ZoneInfo) -> bool:
    return cheap_fraction(t, t + timedelta(seconds=1), tariff, tz) == 1.0


def detect(prev: StateSample | None, cur: StateSample, recent: list[StateSample], tariff: Tariff,
           tz: ZoneInfo, last_draw_at: datetime | None = None) -> list[EventDraft]:
    """Events between `prev` and `cur`. `recent` = samples before `cur` (for water-draw detection)."""
    if prev is None:
        return []
    out: list[EventDraft] = []
    at = cur.at
    since = {"prev_at": prev.at.isoformat()}  # the change happened somewhere in (prev.at, cur.at]
    if prev.on != cur.on:
        out.append(EventDraft(at, "power_on" if cur.on else "power_off", dict(since)))
    if abs(prev.req_temp - cur.req_temp) >= 0.5:
        out.append(EventDraft(at, "target_changed", {"from": prev.req_temp, "to": cur.req_temp, **since}))
    if prev.eco != cur.eco:
        out.append(EventDraft(at, "eco_changed", {"from": prev.eco, "to": cur.eco, **since}))
    if prev.mode != cur.mode:
        out.append(EventDraft(at, "mode_changed", {"from": prev.mode, "to": cur.mode, **since},
                              "warning" if cur.mode != 1 else "info"))
    cheap_now = _cheap_at(at, tariff, tz)
    if prev.heat_req != cur.heat_req:
        kind = "heating_started" if cur.heat_req else "heating_stopped"
        out.append(EventDraft(at, kind, {"cheap": cheap_now, "eco": cur.eco, "temp": cur.temp,
                                         "target": cur.req_temp}))
    prev_exp = prev.heat_req and not _cheap_at(prev.at, tariff, tz)
    cur_exp = cur.heat_req and not cheap_now
    if cur_exp and not prev_exp:
        out.append(EventDraft(at, "expensive_heating_started", {"eco": cur.eco, "temp": cur.temp,
                                                                 "target": cur.req_temp}, "warning"))
    elif prev_exp and not cur_exp:
        out.append(EventDraft(at, "expensive_heating_stopped", {"temp": cur.temp}))
    window = [s.temp for s in recent if at - DRAW_WINDOW <= s.at < at] + [cur.temp]
    drop = max(window) - cur.temp
    if drop >= DRAW_MIN_DROP and (last_draw_at is None or at - last_draw_at > DRAW_COOLDOWN):
        out.append(EventDraft(at, "water_draw", {"drop": drop, "from": max(window), "to": cur.temp}))
    if cur.anti_leg and not prev.anti_leg:
        out.append(EventDraft(at, "anti_legionella"))
    return out


def _window_start(ev: EventDraft) -> datetime:
    prev_at = ev.detail.get("prev_at")
    start = datetime.fromisoformat(prev_at) if isinstance(prev_at, str) else ev.at
    return min(start, ev.at) - ACTION_BEFORE


def _applied(a: AppAction) -> dict | None:
    """The state the app asserted with this action, if it sent commands at all."""
    if a.kind in NON_COMMAND_KINDS or a.state_json is None:
        return None
    if not (a.outcome == "SUCCESS" or a.kind == "COMMAND_FAILED"):  # a failed batch may be half-applied
        return None
    try:
        applied = json.loads(a.state_json)
    except ValueError:
        return None
    return applied if isinstance(applied, dict) and "powerOn" in applied else None


def _matches(ev: EventDraft, applied: dict) -> bool:
    if ev.kind == "power_on":
        return applied.get("powerOn") is True
    if ev.kind == "power_off":
        return applied.get("powerOn") is False
    if ev.kind == "target_changed":
        t = applied.get("targetTemp")
        return t is not None and abs(float(t) - float(ev.detail.get("to", -999))) <= 0.5
    if ev.kind == "eco_changed":
        return applied.get("eco") is not None and applied.get("eco") == ev.detail.get("to")
    if ev.kind == "mode_changed":  # the app only ever normalizes to mode 1
        return ev.detail.get("to") == 1 and applied.get("powerOn") is True
    return False


def _intent_current(intent: AppIntent | None, at: datetime) -> bool:
    if intent is None or at - intent.at > INTENT_MAX_AGE:
        return False
    return intent.next_transition_at is None or at <= intent.next_transition_at + ACTION_AFTER


def attribute(ev: EventDraft, actions: list[AppAction], last_action_seen_at: datetime | None,
              intent: AppIntent | None, cur: StateSample) -> str | None:
    if ev.kind in CHANGE_KINDS:
        lo, hi = _window_start(ev), ev.at + ACTION_AFTER
        for a in actions:
            applied = _applied(a)
            if applied is not None and lo <= a.at <= hi and _matches(ev, applied):
                return "app"
        if last_action_seen_at is None or ev.at - last_action_seen_at > PHONE_SILENT:
            return "unknown"
        return "external"
    if ev.kind in HEATING_KINDS:
        if ev.kind == "heating_started" and ev.detail.get("cheap", False):
            return None
        if _intent_current(intent, ev.at) and intent.desired.get("power_on"):
            return "app"
        return "firmware_eco" if cur.eco else "firmware"
    return None


async def persist_events(conn: AsyncConnection, drafts: list[EventDraft],
                         attribution_fn: Callable[[EventDraft], str | None]) -> int:
    for d in drafts:
        await conn.execute(
            "INSERT INTO event (at, kind, attribution, severity, detail) VALUES (%s,%s,%s,%s,%s)",
            (d.at, d.kind, attribution_fn(d), d.severity, Jsonb(d.detail)))
    return len(drafts)


async def actions_around(conn: AsyncConnection, at: datetime,
                         since: datetime | None = None) -> tuple[list[AppAction], datetime | None]:
    """Actions in [since or at, at + 5 min] (minus 3 min) and the latest action logged up to `at` + 5 min."""
    lo = min(since or at, at) - ACTION_BEFORE
    cur = await conn.execute(
        "SELECT install_id, local_id, at, kind, trig, detail, outcome, state_json FROM app_action "
        "WHERE at BETWEEN %s AND %s ORDER BY at", (lo, at + ACTION_AFTER))
    actions = [AppAction(*r) for r in await cur.fetchall()]
    row = await (await conn.execute("SELECT max(at) FROM app_action WHERE at <= %s",
                                    (at + ACTION_AFTER,))).fetchone()
    return actions, row[0]


async def latest_intent(conn: AsyncConnection, at: datetime | None = None) -> AppIntent | None:
    if at is None:
        cur = await conn.execute("SELECT install_id, at, source, desired, next_transition_at FROM app_intent "
                                 "ORDER BY at DESC LIMIT 1")
    else:
        cur = await conn.execute("SELECT install_id, at, source, desired, next_transition_at FROM app_intent "
                                 "WHERE at <= %s ORDER BY at DESC LIMIT 1", (at,))
    row = await cur.fetchone()
    return AppIntent(*row) if row else None


async def reattribute_recent(conn: AsyncConnection, since: datetime) -> int:
    """Re-run attribution for change events still marked unknown/external (after a late upload)."""
    cur = await conn.execute(
        "SELECT id, at, kind, detail, severity FROM event WHERE at >= %s AND kind = ANY(%s) "
        "AND attribution IN ('unknown', 'external')", (since - ACTION_AFTER, list(CHANGE_KINDS)))
    changed = 0
    for ev_id, at, kind, detail, severity in await cur.fetchall():
        draft = EventDraft(at, kind, detail, severity)
        actions, last_seen = await actions_around(conn, at, _window_start(draft) + ACTION_BEFORE)
        new = attribute(draft, actions, last_seen, None, None)  # type: ignore[arg-type]
        res = await conn.execute("UPDATE event SET attribution = %s WHERE id = %s AND attribution IS DISTINCT FROM %s",
                                 (new, ev_id, new))
        changed += res.rowcount
    return changed
