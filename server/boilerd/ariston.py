"""Read-only Ariston NET cloud client with the etiquette of design §4.2.

Only the GET endpoints below plus the login POST exist here; this module must never grow a
method that changes the boiler. One request at a time (asyncio.Lock); 405 = expired token
(re-login once); 429 pauses every call for 15 minutes; other errors retry once after 5 s.
"""
from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from typing import Any, Awaitable, Callable

import httpx

BASE_URL = "https://www.ariston-net.remotethermo.com/api/v2/"
HEADERS = {"User-Agent": "RestSharp/106.11.7.0", "Content-Type": "application/json"}
APP_INFO = {"os": 2, "appVer": "5.6.772.40151", "appId": "com.remotethermo.aristonnet"}
PAUSE_ON_429_S = 15 * 60
RETRY_DELAY_S = 5


class AristonError(Exception):
    def __init__(self, status: int | None, message: str):
        super().__init__(f"{status}: {message}")
        self.status = status
        self.message = message


class AuthFailed(AristonError):
    def __init__(self, message: str = "authentication failed"):
        super().__init__(None, message)


class RateLimited(AristonError):
    def __init__(self, paused_until: float):
        super().__init__(429, "rate limited")
        self.paused_until = paused_until


@dataclass(frozen=True)
class CallRecord:
    endpoint: str
    status: int | None
    ms: int
    error: str | None


class AristonClient:
    def __init__(
        self,
        user: str,
        password: str,
        *,
        base_url: str = BASE_URL,
        transport: httpx.AsyncBaseTransport | None = None,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
        clock: Callable[[], float] = time.monotonic,
    ):
        self._user, self._password = user, password
        self._http = httpx.AsyncClient(base_url=base_url, headers=HEADERS, transport=transport, timeout=30)
        self._sleep, self._clock = sleep, clock
        self._lock = asyncio.Lock()
        self._token: str | None = None
        self.paused_until: float = 0.0
        self.last_call: CallRecord | None = None

    async def aclose(self) -> None:
        await self._http.aclose()

    # ------------------------------------------------------------------ public, read-only
    async def plants(self) -> list[dict]:
        return await self._get("plants", "velis/plants")

    async def state(self, gw: str) -> dict:
        return await self._get("state", f"velis/medPlantData/{gw}")

    async def plant_settings(self, gw: str) -> dict:
        return await self._get("plant_settings", f"velis/medPlantData/{gw}/plantSettings")

    async def consumption(self, gw: str) -> list[dict]:
        return await self._get("consumption", f"remote/reports/{gw}/consSequencesApi8?usages=Dhw")

    async def bus_errors(self, gw: str) -> list[dict]:
        return await self._get("bus_errors", f"busErrors?gatewayId={gw}&blockingOnly=False&culture=en-US")

    # ------------------------------------------------------------------ internals
    async def _login(self) -> None:
        body = {"usr": self._user, "pwd": self._password, "imp": False, "notTrack": True, "appInfo": APP_INFO}
        resp = await self._http.post("accounts/login", json=body)
        if resp.status_code == 429:
            self.paused_until = self._clock() + PAUSE_ON_429_S
            raise RateLimited(self.paused_until)
        if resp.status_code != 200:
            raise AuthFailed(f"login returned {resp.status_code}")
        self._token = resp.json()["token"]

    async def _send(self, path: str) -> httpx.Response:
        return await self._http.get(path, headers={"ar.authToken": self._token or ""})

    async def _get(self, endpoint: str, path: str) -> Any:
        async with self._lock:
            if self._clock() < self.paused_until:
                raise RateLimited(self.paused_until)
            started = self._clock()
            status: int | None = None
            try:
                if self._token is None:
                    await self._login()
                resp = await self._send_with_retry(path)
                status = resp.status_code
                if status == 405:
                    await self._login()
                    resp = await self._send_with_retry(path)
                    status = resp.status_code
                    if status == 405:
                        self._token = None
                        raise AuthFailed("token rejected after re-login")
                if status == 429:
                    self.paused_until = self._clock() + PAUSE_ON_429_S
                    raise RateLimited(self.paused_until)
                if status >= 400:
                    raise AristonError(status, resp.text[:200])
                result = resp.json()
                self._record(endpoint, status, started, None)
                return result
            except AristonError as e:
                self._record(endpoint, e.status if e.status is not None else status, started, str(e))
                raise
            except httpx.HTTPError as e:
                self._record(endpoint, status, started, repr(e))
                raise AristonError(None, repr(e)) from e

    async def _send_with_retry(self, path: str) -> httpx.Response:
        """One retry after 5 s for transport errors and 5xx; 405/429 are handled by the caller."""
        try:
            resp = await self._send(path)
            if resp.status_code < 500:
                return resp
        except httpx.TransportError:
            pass
        await self._sleep(RETRY_DELAY_S)
        return await self._send(path)

    def _record(self, endpoint: str, status: int | None, started: float, error: str | None) -> None:
        ms = int(max(0.0, self._clock() - started) * 1000)
        self.last_call = CallRecord(endpoint, status, ms, error)
