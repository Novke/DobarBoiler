"""MCP server for agents (design §4.7): read-only tools over the history database."""
import re
from datetime import date, datetime, timedelta, timezone
from typing import Any
from zoneinfo import ZoneInfo

import psycopg
from mcp.server.mcpserver import MCPServer

from . import analytics
from .api import health_snapshot, parse_step
from .ingest import latest_state

MAX_ROWS = 1000

SCHEMA_MD = """# DobarBoiler history database (PostgreSQL, read-only for agents)

All times are timestamptz; wall-clock logic uses Europe/Belgrade. Boiler: Ariston Velis Tech Wifi 80 (80 L).

| Table | Key | Meaning |
|---|---|---|
| state_sample | at | Boiler state every ~2 min: "on", mode (1 manual, 5 program, 9 boost), temp (°C), req_temp (target), proc_req_temp, eco, heat_req (element on), anti_leg, rm_tm (remaining heat-up), av_shw (showers), raw jsonb |
| energy_2h | bucket_start | kWh per closed two-hour bucket aligned to even LOCAL hours (bucket_end may differ by 1 h on DST days) |
| energy_day | day | kWh per local day from the cloud (source 'p3') |
| energy_month | month | kWh per month from the cloud (source 'p4'); history since June 2025 |
| plant_settings | at | Snapshot of plant settings (max setpoint, anti-legionella) when they change |
| device_error | (code, first_seen_at) | Bus errors reported by the cloud, cleared_at when gone |
| app_action | (install_id, local_id) | Copy of the phone app's action_log (kind, trig, detail, outcome SUCCESS/RETRYING/FAILED/SKIPPED) |
| app_intent | (install_id, at) | What the app wanted after each convergence: source (BOOST, HEAT_BY_TIME, VACATION, DAY_OVERRIDE, WEEKLY_PLAN, KEEP_WARM, DEFAULT_OFF), desired {power_on, target_temp, eco} |
| event | id | Derived events: power_on/off, target_changed, eco_changed, mode_changed, heating_started/stopped, expensive_heating_started/stopped, water_draw, anti_legionella, device_offline/online, device_error. attribution: app, external (official Ariston app or buttons), firmware (thermostat), firmware_eco (eco/ECO EVO heating), unknown (phone silent) |
| tariff | valid_from | Cheap window and prices in din/kWh (default 00–08 h local all year; blue zone 3.6051 / 14.4203) |
| poll_log | id | Every Ariston call (endpoint, status, ms, error), kept 30 days |

A 2 h bucket is cheap when it lies inside 00–08 h local time; cost = cheap kWh × cheap price + expensive kWh × expensive price.
"""


def _dt(s: str, tz: ZoneInfo) -> datetime:
    """ISO time; without an offset it is Europe/Belgrade local time (what a person means by "14:02")."""
    d = datetime.fromisoformat(s)
    return d if d.tzinfo else d.replace(tzinfo=tz)


def _fmt(x) -> str:
    return f"{x:.2f}".replace(".", ",") if x is not None else "?"


def _check_select(query: str) -> str:
    q = query.strip().rstrip(";").strip()
    if ";" in q:
        raise ValueError("samo jedan SQL upit je dozvoljen")
    if not re.match(r"(?is)^(select|with)\b", q):
        raise ValueError("dozvoljeni su samo SELECT upiti (ili WITH ... SELECT)")
    return q


def build_mcp(pool, ro_dsn: str, tz: ZoneInfo, poller=None) -> MCPServer:
    mcp = MCPServer(
        "dobarboiler",
        instructions=("Read-only history of Novica's water heater (Ariston Velis Tech Wifi 80): state every 2 min, "
                      "energy per 2 h/day/month, cheap (00–08 h) vs expensive tariff, events with attribution "
                      "(app / external / firmware). Read boiler://schema before writing SQL. These tools never "
                      "change the boiler."),
    )

    @mcp.tool(structured_output=True)
    async def boiler_now() -> dict[str, Any]:
        """Current boiler state, data age, what the app wants now, and today's energy split."""
        async with pool.connection() as conn:
            d = await analytics.now_summary(conn, tz)
        s, today = d["state"], d["today"]
        parts = []
        if s:
            parts.append(f"Voda {s['temp']:.0f} °C, cilj {s['target']:.0f} °C, "
                         + ("greje" if s["heating"] else ("uključen" if s["on"] else "isključen")) + ".")
        if today:
            parts.append(f"Danas {_fmt(today['kwh'])} kWh"
                         + (f", jeftino {_fmt(today['cheap_kwh'])}" if today["cheap_kwh"] is not None else "") + ".")
        d["summary"] = " ".join(parts) or "Nema podataka."
        return d

    @mcp.tool(structured_output=True)
    async def boiler_state_series(from_: str, to: str, step: str = "5m") -> dict[str, Any]:
        """Temperature, target and heating over time, averaged per step (30s, 5m, 1h, 1d). Times are ISO 8601."""
        async with pool.connection() as conn:
            rows = await analytics.state_series(conn, _dt(from_, tz), _dt(to, tz), parse_step(step))
        temps = [r["temp"] for r in rows]
        summary = (f"{len(rows)} tačaka, temperatura od {min(temps)} do {max(temps)} °C." if rows else "Nema uzoraka.")
        return {"rows": rows, "summary": summary}

    @mcp.tool(structured_output=True)
    async def boiler_energy(from_: str, to: str, granularity: str = "day") -> dict[str, Any]:
        """kWh per 2h/day/month with cheap/expensive split and cost in dinars (split null for old history)."""
        if granularity not in ("2h", "day", "month"):
            raise ValueError("granularity: 2h, day ili month")
        async with pool.connection() as conn:
            rows = [r.as_dict() for r in await analytics.energy(conn, _dt(from_, tz), _dt(to, tz), granularity, tz)]
        total = sum(r["kwh"] for r in rows)
        known = [r for r in rows if r["cheap_kwh"] is not None]
        summary = f"Ukupno {_fmt(total)} kWh u {len(rows)} perioda."
        if known:
            summary += (f" Sa podelom: jeftino {_fmt(sum(r['cheap_kwh'] for r in known))} kWh, skupo "
                        f"{_fmt(sum(r['expensive_kwh'] for r in known))} kWh, "
                        f"{_fmt(sum(r['cost_din'] for r in known))} din.")
        return {"rows": rows, "summary": summary}

    @mcp.tool(structured_output=True)
    async def boiler_events(from_: str, to: str, kinds: list[str] | None = None,
                            attribution: str | None = None) -> dict[str, Any]:
        """Events with attribution (app, external, firmware, firmware_eco, unknown), optionally filtered."""
        sql = "SELECT id, at, kind, attribution, severity, detail FROM event WHERE at >= %s AND at < %s"
        args: list = [_dt(from_, tz), _dt(to, tz)]
        if kinds:
            sql += " AND kind = ANY(%s)"
            args.append(kinds)
        if attribution:
            sql += " AND attribution = %s"
            args.append(attribution)
        async with pool.connection() as conn:
            rows = await (await conn.execute(sql + " ORDER BY at, id", args)).fetchall()
        evs = [{"id": r[0], "at": r[1].isoformat(), "kind": r[2], "attribution": r[3], "severity": r[4],
                "detail": r[5]} for r in rows]
        ext = sum(1 for e in evs if e["attribution"] == "external")
        return {"events": evs, "summary": f"{len(evs)} događaja, od toga {ext} spolja."}

    @mcp.tool(structured_output=True)
    async def boiler_report(month: str) -> dict[str, Any]:
        """Monthly report (month like 2026-10): kWh, cheap share, dinars, waste, last year, expensive heating."""
        async with pool.connection() as conn:
            rep = await analytics.month_report(conn, date.fromisoformat(month + "-01"), tz)
        return {"report": rep.json, "markdown": rep.markdown, "summary": rep.markdown.splitlines()[2]}

    @mcp.tool(structured_output=True)
    async def boiler_insights(season: str | None = None) -> dict[str, Any]:
        """Seasonal needs, standby loss, heating rate, hot water use, recommended target (zima/prolece/leto/jesen)."""
        if season not in (None, "zima", "prolece", "leto", "jesen"):
            raise ValueError("season: zima, prolece, leto, jesen ili prazno")
        async with pool.connection() as conn:
            d = await analytics.insights(conn, season, tz)
        rec = d["recommended_target"]
        d["summary"] = (f"Preporučena temperatura {rec['temp']} °C ({rec['days']} dana podataka)." if rec
                        else "Još nema dovoljno dana za preporuku temperature.")
        return d

    @mcp.tool(structured_output=True)
    async def boiler_explain(at: str) -> dict[str, Any]:
        """What happened around a moment: state before/after, app actions and events within ±30 min."""
        t = _dt(at, tz)
        lo, hi = t - timedelta(minutes=30), t + timedelta(minutes=30)
        async with pool.connection() as conn:
            before = await latest_state(conn, before=t + timedelta(seconds=1))
            row = await (await conn.execute(
                'SELECT at, "on", req_temp, temp, eco, heat_req, mode FROM state_sample WHERE at > %s '
                "ORDER BY at LIMIT 1", (t,))).fetchone()
            actions = await (await conn.execute(
                "SELECT at, kind, trig, detail, outcome FROM app_action WHERE at BETWEEN %s AND %s ORDER BY at",
                (lo, hi))).fetchall()
            events = await (await conn.execute(
                "SELECT at, kind, attribution, detail FROM event WHERE at BETWEEN %s AND %s ORDER BY at, id",
                (lo, hi))).fetchall()
        after = None if row is None else {"at": row[0].isoformat(), "on": row[1], "target": row[2], "temp": row[3],
                                          "eco": row[4], "heating": row[5], "mode": row[6]}
        bef = None if before is None else {"at": before.at.isoformat(), "on": before.on, "target": before.req_temp,
                                           "temp": before.temp, "eco": before.eco, "heating": before.heat_req,
                                           "mode": before.mode}
        acts = [{"at": a.isoformat(), "kind": k, "trig": tr, "detail": d, "outcome": o} for a, k, tr, d, o in actions]
        evs = [{"at": a.isoformat(), "kind": k, "attribution": at_, "detail": d} for a, k, at_, d in events]
        if evs:
            who = {"app": "aplikacija", "external": "nešto spolja (zvanična app ili dugme)",
                   "firmware": "termostat bojlera", "firmware_eco": "bojler sam, eco režim",
                   "unknown": "nepoznato (telefon nije slao zapise)"}
            lines = [f"{datetime.fromisoformat(e['at']).astimezone(tz):%H:%M} {e['kind']}: "
                     f"{who.get(e['attribution'], 'bez uzroka')}" for e in evs]
            summary = "Oko tog trenutka: " + "; ".join(lines) + "."
        else:
            summary = "U ±30 min nema zabeleženih događaja."
        if acts:
            summary += f" Aplikacija je tada zabeležila {len(acts)} akcija."
        return {"before": bef, "after": after, "app_actions": acts, "events": evs, "summary": summary}

    @mcp.tool(structured_output=True)
    async def boiler_health() -> dict[str, Any]:
        """Poller health: last state and energy reads, rate-limit pause, recent Ariston call errors."""
        d = await health_snapshot(pool, poller() if callable(poller) else poller)
        async with pool.connection() as conn:
            rows = await (await conn.execute(
                "SELECT endpoint, count(*) FILTER (WHERE error IS NOT NULL), count(*), max(at) FROM poll_log "
                "WHERE at > now() - interval '24 hours' GROUP BY endpoint ORDER BY endpoint")).fetchall()
        d["last_24h"] = [{"endpoint": r[0], "errors": r[1], "calls": r[2], "last": r[3].isoformat()} for r in rows]
        d["summary"] = "Server radi." if d["ok"] else "Server ne dobija sveže stanje bojlera."
        return d

    @mcp.tool(structured_output=True)
    async def boiler_sql(query: str) -> dict[str, Any]:
        """Run one read-only SELECT (see boiler://schema). 5 s timeout, at most 1000 rows."""
        q = _check_select(query)
        async with await psycopg.AsyncConnection.connect(ro_dsn) as conn:
            await conn.execute("SET default_transaction_read_only = on")
            await conn.execute("SET statement_timeout = '5s'")
            await conn.commit()
            async with conn.transaction(force_rollback=True):
                # the LIMIT runs on the server, so a huge result never reaches this process
                cur = await conn.execute(f"SELECT * FROM ({q}) AS _agent_query LIMIT {MAX_ROWS + 1}")
                cols = [c.name for c in cur.description or []]
                rows = await cur.fetchmany(MAX_ROWS + 1)
        truncated = len(rows) > MAX_ROWS
        rows = [[v.isoformat() if hasattr(v, "isoformat") else (float(v) if hasattr(v, "as_tuple") else v)
                 for v in r] for r in rows[:MAX_ROWS]]
        return {"columns": cols, "rows": rows, "truncated": truncated,
                "summary": f"{len(rows)} redova" + (" (skraćeno na 1000)" if truncated else "") + "."}

    @mcp.resource("boiler://schema", name="schema", mime_type="text/markdown",
                  description="Tables, bucket semantics and tariff rules of the history database")
    def schema() -> str:
        return SCHEMA_MD

    return mcp
