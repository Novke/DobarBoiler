import os

import pytest
from psycopg_pool import AsyncConnectionPool

from boilerd import db as dbmod

KEEP = {"schema_migrations", "tariff"}


@pytest.fixture
async def db():
    dsn = os.environ["BOILERD_TEST_DSN"]
    pool = AsyncConnectionPool(dsn, min_size=1, max_size=4, open=False)
    await pool.open()
    await dbmod.migrate(pool)
    async with pool.connection() as conn:
        rows = await (await conn.execute(
            "SELECT tablename FROM pg_tables WHERE schemaname = 'public'")).fetchall()
        names = [r[0] for r in rows if r[0] not in KEEP]
        if names:
            await conn.execute("TRUNCATE " + ", ".join(names) + " RESTART IDENTITY")
    yield pool
    await pool.close()
