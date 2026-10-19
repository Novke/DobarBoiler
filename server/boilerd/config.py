"""Runtime settings, read once from the environment (see server/docker-compose.yml)."""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from zoneinfo import ZoneInfo


def _dsn(user: str, password: str, host: str, port: str, name: str) -> str:
    return f"postgresql://{user}:{password}@{host}:{port}/{name}"


@dataclass(frozen=True)
class Settings:
    ariston_user: str
    ariston_pass: str
    api_token: str
    db_dsn: str
    db_ro_dsn: str
    tz: ZoneInfo
    state_interval_s: int = 120
    probe_log: Path | None = None

    @classmethod
    def from_env(cls) -> "Settings":
        env = os.environ
        user = env.get("SERVER_ARISTON_USER") or env["ARISTON_USER"]
        password = env.get("SERVER_ARISTON_PASS") or env["ARISTON_PASS"]
        host = env.get("DB_HOST", "shared-postgres")
        port = env.get("DB_PORT", "5432")
        name = env.get("DB_NAME", "dobarboiler")
        probe = env.get("BOILERD_PROBE_LOG")
        return cls(
            ariston_user=user,
            ariston_pass=password,
            api_token=env["BOILER_API_TOKEN"],
            db_dsn=_dsn(env.get("DB_USER", "dobarboiler"), env["DOBARBOILER_DB_PASSWORD"], host, port, name),
            db_ro_dsn=_dsn("dobarboiler_ro", env["DOBARBOILER_RO_PASSWORD"], host, port, name),
            tz=ZoneInfo(env.get("BOILERD_TZ", "Europe/Belgrade")),
            state_interval_s=max(60, int(env.get("STATE_INTERVAL_S", "120"))),
            probe_log=Path(probe) if probe else None,
        )
