"""ASGI composition: REST under /api/v1, MCP at /mcp, poller in the lifespan.

Run with: uvicorn boilerd.app:create_app_from_env --factory --host 0.0.0.0 --port 8020
"""
from __future__ import annotations

import asyncio
import contextlib
import logging
import secrets

from fastapi import FastAPI
from mcp.server.transport_security import TransportSecuritySettings
from starlette.types import ASGIApp, Receive, Scope, Send

from .api import router
from .ariston import AristonClient
from .config import Settings
from .db import create_pool, migrate
from .mcp_tools import build_mcp
from .poller import Poller

ALLOWED_HOSTS = ["127.0.0.1", "127.0.0.1:8020", "localhost", "localhost:8020", "novica-dev", "novica-dev:80",
                 "100.116.228.19", "100.116.228.19:8020", "100.116.228.19:80"]
ALLOWED_ORIGINS = ["http://novica-dev", "http://127.0.0.1:8020", "http://localhost:8020"]


class BearerGuard:
    """Rejects HTTP requests without the API token before they reach the MCP transport."""

    def __init__(self, app: ASGIApp, token: str):
        self.app, self.token = app, token.encode()

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http":
            auth = dict(scope.get("headers") or []).get(b"authorization", b"")
            given = auth[7:] if auth.lower().startswith(b"bearer ") else b""
            if not given or not secrets.compare_digest(given, self.token):
                await send({"type": "http.response.start", "status": 401,
                            "headers": [(b"content-type", b"application/json")]})
                await send({"type": "http.response.body", "body": b'{"detail":"invalid token"}'})
                return
        await self.app(scope, receive, send)


def create_app(settings: Settings, *, pool=None, start_poller: bool = True) -> FastAPI:
    own_pool = pool is None
    pool = pool or create_pool(settings.db_dsn)
    holder: dict = {}
    mcp = build_mcp(pool, settings.db_ro_dsn, settings.tz, poller=lambda: holder.get("poller"))
    mcp_app = mcp.streamable_http_app(
        streamable_http_path="/mcp", json_response=True, stateless_http=True,
        transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=True,
                                                     allowed_hosts=ALLOWED_HOSTS, allowed_origins=ALLOWED_ORIGINS))

    @contextlib.asynccontextmanager
    async def lifespan(app: FastAPI):
        if own_pool:
            await pool.open()
            await migrate(pool)
        task = None
        if start_poller:
            client = AristonClient(settings.ariston_user, settings.ariston_pass)
            poller = Poller(client, pool, settings)
            app.state.poller = poller
            holder["poller"] = poller
            task = asyncio.create_task(poller.run_forever())
        async with mcp.session_manager.run():
            yield
        if task:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
            await app.state.poller.client.aclose()
        if own_pool:
            await pool.close()

    app = FastAPI(title="DobarBoiler history", lifespan=lifespan)
    app.state.pool = pool
    app.state.settings = settings
    app.state.poller = None
    app.include_router(router)
    app.mount("/", BearerGuard(mcp_app, settings.api_token))
    return app


def create_app_from_env() -> FastAPI:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    return create_app(Settings.from_env())
