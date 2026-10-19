"""REST API for the phone app (design §4.6). Every route except /health needs the bearer token."""
from __future__ import annotations

import re
import secrets
import time as _time
from datetime import date, datetime, timedelta, timezone
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from psycopg.types.json import Jsonb
from pydantic import AwareDatetime, BaseModel, Field

from . import analytics
from .events import reattribute_recent

router = APIRouter(prefix="/api/v1")
MAX_BATCH = 500
STALE_AFTER = timedelta(minutes=10)


def require_token(request: Request) -> None:
    expected = request.app.state.settings.api_token
    header = request.headers.get("authorization", "")
    given = header[7:] if header.lower().startswith("bearer ") else ""
    if not given or not secrets.compare_digest(given.encode(), expected.encode()):
        raise HTTPException(status_code=401, detail="invalid token")


Auth = Depends(require_token)


class ActionIn(BaseModel):
    local_id: int
    at: AwareDatetime
    kind: str
    trig: str
    detail: str
    outcome: str
    attempt: int = 1
    state_json: str | None = None


class ActionsBatch(BaseModel):
    install_id: str = Field(min_length=1, max_length=64)
    actions: list[ActionIn] = Field(max_length=MAX_BATCH)


class DesiredIn(BaseModel):
    """The app's DesiredState; attribution and plan-vs-actual read power_on."""
    power_on: bool
    target_temp: int | None = None
    eco: bool | None = None


class IntentIn(BaseModel):
    install_id: str = Field(min_length=1, max_length=64)
    at: AwareDatetime
    source: str
    desired: DesiredIn
    next_transition_at: AwareDatetime | None = None


def parse_step(step: str) -> timedelta:
    m = re.fullmatch(r"(\d+)([smhd])", step.strip())
    if not m:
        raise HTTPException(status_code=422, detail="step must look like 30s, 5m, 1h or 1d")
    n, unit = int(m.group(1)), m.group(2)
    return timedelta(**{{"s": "seconds", "m": "minutes", "h": "hours", "d": "days"}[unit]: n})


def as_local(d: datetime | None, tz) -> datetime | None:
    """Times without an offset are local (Europe/Belgrade), never UTC."""
    if d is None or d.tzinfo is not None:
        return d
    return d.replace(tzinfo=tz)


def _range(frm: datetime | None, to: datetime | None, default: timedelta, tz=timezone.utc) -> tuple[datetime, datetime]:
    frm, to = as_local(frm, tz), as_local(to, tz)
    to = to or datetime.now(timezone.utc)
    return (frm or to - default), to


@router.post("/app/actions", dependencies=[Auth])
async def post_actions(body: ActionsBatch, request: Request) -> dict:
    accepted = 0
    async with request.app.state.pool.connection() as conn:
        for a in body.actions:
            res = await conn.execute(
                "INSERT INTO app_action (install_id, local_id, at, kind, trig, detail, outcome, attempt, state_json) "
                "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING",
                (body.install_id, a.local_id, a.at, a.kind, a.trig, a.detail, a.outcome, a.attempt, a.state_json))
            accepted += res.rowcount
        if body.actions:
            await reattribute_recent(conn, min(a.at for a in body.actions))
    return {"accepted": accepted, "duplicates": len(body.actions) - accepted}


@router.post("/app/intent", dependencies=[Auth], status_code=204)
async def post_intent(body: IntentIn, request: Request) -> Response:
    async with request.app.state.pool.connection() as conn:
        await conn.execute(
            "INSERT INTO app_intent (install_id, at, source, desired, next_transition_at) VALUES (%s,%s,%s,%s,%s) "
            "ON CONFLICT DO NOTHING",
            (body.install_id, body.at, body.source, Jsonb(body.desired.model_dump()), body.next_transition_at))
    return Response(status_code=204)


@router.get("/now", dependencies=[Auth])
async def get_now(request: Request) -> dict:
    async with request.app.state.pool.connection() as conn:
        return await analytics.now_summary(conn, request.app.state.settings.tz)


@router.get("/series/state", dependencies=[Auth])
async def get_series(request: Request, frm: Annotated[datetime | None, Query(alias="from")] = None,
                     to: datetime | None = None, step: str = "5m") -> list[dict]:
    a, b = _range(frm, to, timedelta(hours=24), request.app.state.settings.tz)
    async with request.app.state.pool.connection() as conn:
        return await analytics.state_series(conn, a, b, parse_step(step))


@router.get("/energy", dependencies=[Auth])
async def get_energy(request: Request, frm: Annotated[datetime | None, Query(alias="from")] = None,
                     to: datetime | None = None,
                     granularity: Literal["2h", "day", "month"] = "day") -> list[dict]:
    a, b = _range(frm, to, timedelta(days=30), request.app.state.settings.tz)
    async with request.app.state.pool.connection() as conn:
        rows = await analytics.energy(conn, a, b, granularity, request.app.state.settings.tz)
    return [r.as_dict() for r in rows]


@router.get("/events", dependencies=[Auth])
async def get_events(request: Request, frm: Annotated[datetime | None, Query(alias="from")] = None,
                     to: datetime | None = None, kinds: str | None = None,
                     attribution: str | None = None) -> list[dict]:
    a, b = _range(frm, to, timedelta(days=7), request.app.state.settings.tz)
    sql = "SELECT id, at, kind, attribution, severity, detail FROM event WHERE at >= %s AND at < %s"
    args: list = [a, b]
    if kinds:
        sql += " AND kind = ANY(%s)"
        args.append([k.strip() for k in kinds.split(",") if k.strip()])
    if attribution:
        sql += " AND attribution = %s"
        args.append(attribution)
    async with request.app.state.pool.connection() as conn:
        rows = await (await conn.execute(sql + " ORDER BY at, id", args)).fetchall()
    return [{"id": r[0], "at": r[1].isoformat(), "kind": r[2], "attribution": r[3], "severity": r[4],
             "detail": r[5]} for r in rows]


@router.get("/insights", dependencies=[Auth])
async def get_insights(request: Request,
                       season: Literal["zima", "prolece", "leto", "jesen"] | None = None) -> dict:
    async with request.app.state.pool.connection() as conn:
        return await analytics.insights(conn, season, request.app.state.settings.tz)


@router.get("/report", dependencies=[Auth])
async def get_report(request: Request, month: str) -> dict:
    try:
        m = date.fromisoformat(month + "-01")
    except ValueError:
        raise HTTPException(status_code=422, detail="month must look like 2026-10")
    async with request.app.state.pool.connection() as conn:
        rep = await analytics.month_report(conn, m, request.app.state.settings.tz)
    return {"json": rep.json, "markdown": rep.markdown}


async def health_snapshot(pool, poller) -> dict:
    async with pool.connection() as conn:
        last_state = (await (await conn.execute("SELECT max(at) FROM state_sample")).fetchone())[0]
        last_energy = (await (await conn.execute("SELECT max(last_seen_at) FROM energy_2h")).fetchone())[0]
    paused = None
    if poller is not None:
        left = poller.client.paused_until - _time.monotonic()
        if left > 0:
            paused = (datetime.now(timezone.utc) + timedelta(seconds=left)).isoformat()
    now = datetime.now(timezone.utc)
    return {"ok": last_state is not None and now - last_state < STALE_AFTER,
            "last_state_at": last_state.isoformat() if last_state else None,
            "last_energy_at": last_energy.isoformat() if last_energy else None,
            "paused_until": paused}


@router.get("/health")
async def get_health(request: Request) -> dict:
    return await health_snapshot(request.app.state.pool, getattr(request.app.state, "poller", None))
