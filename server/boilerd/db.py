"""Connection pool and forward-only SQL migrations (numbered files in migrations/)."""
from __future__ import annotations

from pathlib import Path

from psycopg_pool import AsyncConnectionPool

MIGRATIONS = Path(__file__).parent / "migrations"


def create_pool(dsn: str, *, max_size: int = 5) -> AsyncConnectionPool:
    return AsyncConnectionPool(dsn, min_size=1, max_size=max_size, open=False)


async def migrate(pool: AsyncConnectionPool) -> list[str]:
    """Apply pending migrations in name order; returns the names applied now."""
    applied: list[str] = []
    async with pool.connection() as conn:
        await conn.execute(
            "CREATE TABLE IF NOT EXISTS schema_migrations "
            "(name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())")
        done = {r[0] for r in await (await conn.execute("SELECT name FROM schema_migrations")).fetchall()}
        for path in sorted(MIGRATIONS.glob("*.sql")):
            if path.name in done:
                continue
            async with conn.transaction():
                await conn.execute(path.read_text(encoding="utf-8"))
                await conn.execute("INSERT INTO schema_migrations (name) VALUES (%s)", (path.name,))
            applied.append(path.name)
    return applied
