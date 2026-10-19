"""The polling loop: decides which Ariston reads are due, stores results, derives events (§4.2)."""
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone
from typing import Awaitable, Callable

from psycopg_pool import AsyncConnectionPool

from .ariston import AristonError, AuthFailed, RateLimited
from .config import Settings
from .events import EventDraft, actions_around, attribute, detect, latest_intent, persist_events
from .ingest import (
    daily_crosscheck, import_probe_log, parse_state, recent_states, store_bus_errors, store_consumption,
    store_plant_settings, store_state, tariff_at,
)

log = logging.getLogger("boilerd.poller")

JOB_ORDER = ["plants", "state", "energy", "backfill", "crosscheck", "settings", "errors"]
OFFLINE_AFTER = 3
PLANTS_RETRY = timedelta(minutes=10)
DAILY_AT = (2, 10)
POLL_LOG_DAYS = 30


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Poller:
    def __init__(self, client, pool: AsyncConnectionPool, settings: Settings,
                 now: Callable[[], datetime] = _utcnow):
        self.client, self.pool, self.settings, self.now = client, pool, settings, now
        self.tz = settings.tz
        self.gw: str | None = None
        self.last_run: dict[str, datetime] = {}
        self.state_failures = 0
        self.offline = False
        self.bootstrapped = False

    # ------------------------------------------------------------------ schedule (pure)
    def _even_slot(self, t: datetime) -> datetime:
        local = t.astimezone(self.tz).replace(minute=0, second=0, microsecond=0)
        return local - timedelta(hours=local.hour % 2)

    def _daily_due(self, job: str, t: datetime) -> bool:
        local = t.astimezone(self.tz)
        if (local.hour, local.minute) < DAILY_AT:
            return job not in self.last_run
        last = self.last_run.get(job)
        return last is None or last.astimezone(self.tz).date() < local.date()

    def due_jobs(self, t: datetime) -> list[str]:
        def since(job: str) -> timedelta | None:
            last = self.last_run.get(job)
            return None if last is None else t - last

        due = []
        for job in JOB_ORDER:
            s = since(job)
            if job == "plants":
                ok = s is None or s >= timedelta(hours=24)
            elif job == "state":
                ok = s is None or s >= timedelta(seconds=self.settings.state_interval_s)
            elif job == "energy":
                slot = self._even_slot(t)
                last = self.last_run.get(job)
                ok = (t.astimezone(self.tz) - slot >= timedelta(minutes=7)
                      and (last is None or self._even_slot(last) != slot))
            elif job in ("backfill", "crosscheck"):
                ok = self._daily_due(job, t)
            elif job == "settings":
                ok = s is None or s >= timedelta(hours=6)
            else:  # errors
                ok = s is None or s >= timedelta(hours=1)
            if ok:
                due.append(job)
        return due

    # ------------------------------------------------------------------ execution
    async def tick(self) -> list[str]:
        t = self.now()
        ran: list[str] = []
        if not self.bootstrapped:
            await self._bootstrap()
        for job in self.due_jobs(t):
            if job != "plants" and self.gw is None:
                continue
            ran.append(job)
            self.last_run[job] = t
            try:
                await getattr(self, f"_job_{job}")(t)
            except RateLimited:
                log.warning("rate limited, skipping the rest of this cycle")
                break
            except AuthFailed as e:
                log.error("Ariston login rejected (%s); calls paused, skipping the rest of this cycle", e)
                break
            except AristonError as e:
                log.warning("job %s failed: %s", job, e)
            except Exception:  # never let one job kill the loop
                log.exception("job %s crashed", job)
            finally:
                if job == "plants" and self.gw is None:
                    # retry discovery soon instead of in 24 h: nothing else can run without the gateway id
                    self.last_run[job] = t - timedelta(hours=24) + PLANTS_RETRY
        return ran

    async def run_forever(self, granularity_s: float = 15) -> None:
        while True:
            try:
                await self.tick()
            except Exception:
                log.exception("tick crashed")
            await asyncio.sleep(granularity_s)

    async def _bootstrap(self) -> None:
        self.bootstrapped = True
        probe = self.settings.probe_log
        if probe is None or not probe.exists():
            return
        async with self.pool.connection() as conn:
            empty = (await (await conn.execute("SELECT NOT EXISTS (SELECT 1 FROM state_sample)")).fetchone())[0]
            if empty:
                n = await import_probe_log(conn, probe, self.tz)
                log.info("imported %d probe-log records", n)

    async def _call(self, fn: Callable[..., Awaitable], *args):
        try:
            return await fn(*args)
        finally:
            rec = self.client.last_call
            if rec is not None:
                async with self.pool.connection() as conn:
                    await conn.execute("INSERT INTO poll_log (at, endpoint, status, ms, error) VALUES (%s,%s,%s,%s,%s)",
                                       (self.now(), rec.endpoint, rec.status, rec.ms, rec.error))

    async def _note(self, endpoint: str, message: str) -> None:
        async with self.pool.connection() as conn:
            await conn.execute("INSERT INTO poll_log (at, endpoint, status, ms, error) VALUES (%s,%s,NULL,0,%s)",
                               (self.now(), endpoint, message))

    async def _job_plants(self, t: datetime) -> None:
        plants = await self._call(self.client.plants)
        if plants:
            self.gw = plants[0]["gw"]

    async def _job_state(self, t: datetime) -> None:
        try:
            raw = await self._call(self.client.state, self.gw)
        except RateLimited:
            raise
        except AristonError:
            self.state_failures += 1
            if self.state_failures >= OFFLINE_AFTER and not self.offline:
                self.offline = True
                async with self.pool.connection() as conn:
                    await persist_events(conn, [EventDraft(t, "device_offline", {}, "warning")], lambda d: None)
            raise
        self.state_failures = 0
        async with self.pool.connection() as conn:
            if self.offline:
                self.offline = False
                await persist_events(conn, [EventDraft(t, "device_online")], lambda d: None)
            cur = parse_state(raw, t)
            prev = await store_state(conn, cur)
            recent = await recent_states(conn, t - timedelta(minutes=10))
            recent = [s for s in recent if s.at < t]
            row = await (await conn.execute("SELECT max(at) FROM event WHERE kind = 'water_draw'")).fetchone()
            tariff = await tariff_at(conn, t, self.tz)
            drafts = detect(prev, cur, recent, tariff, self.tz, last_draw_at=row[0])
            if not drafts:
                return
            actions, last_seen = await actions_around(conn, t, prev.at if prev else None)
            intent = await latest_intent(conn, t)
            await persist_events(conn, drafts, lambda d: attribute(d, actions, last_seen, intent, cur))

    async def _store_consumption(self, t: datetime) -> None:
        raw = await self._call(self.client.consumption, self.gw)
        async with self.pool.connection() as conn:
            res = await store_consumption(conn, raw, t, self.tz)
        for w in res.warnings:
            await self._note("consumption", w)

    async def _job_energy(self, t: datetime) -> None:
        await self._store_consumption(t)

    async def _job_backfill(self, t: datetime) -> None:
        if self.last_run.get("energy") == t:  # the same response already carried p=3/p=4 this tick
            return
        await self._store_consumption(t)

    async def _job_crosscheck(self, t: datetime) -> None:
        yesterday = t.astimezone(self.tz).date() - timedelta(days=1)
        async with self.pool.connection() as conn:
            warning = await daily_crosscheck(conn, yesterday, self.tz)
            await conn.execute("DELETE FROM poll_log WHERE at < %s", (t - timedelta(days=POLL_LOG_DAYS),))
        if warning:
            await self._note("crosscheck", warning)

    async def _job_settings(self, t: datetime) -> None:
        raw = await self._call(self.client.plant_settings, self.gw)
        async with self.pool.connection() as conn:
            await store_plant_settings(conn, raw, t)

    async def _job_errors(self, t: datetime) -> None:
        raw = await self._call(self.client.bus_errors, self.gw)
        async with self.pool.connection() as conn:
            new = await store_bus_errors(conn, raw or [], t)
            if new:
                await persist_events(conn, [EventDraft(t, "device_error", {"error": e}, "warning") for e in new],
                                     lambda d: None)
