import os
from datetime import date, time
from decimal import Decimal

from psycopg_pool import AsyncConnectionPool

from boilerd import db as dbmod
from boilerd.config import Settings

TABLES = {
    "state_sample", "energy_2h", "energy_day", "energy_month", "plant_settings", "device_error",
    "app_action", "app_intent", "event", "tariff", "poll_log", "schema_migrations",
}


async def test_migrate_is_idempotent():
    pool = AsyncConnectionPool(os.environ["BOILERD_TEST_DSN"], min_size=1, open=False)
    await pool.open()
    async with pool.connection() as conn:
        await conn.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public")
    assert await dbmod.migrate(pool) == ["001_init.sql"]
    assert await dbmod.migrate(pool) == []
    await pool.close()


async def test_schema_has_tables(db):
    async with db.connection() as conn:
        rows = await (await conn.execute(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")).fetchall()
    assert {r[0] for r in rows} == TABLES


async def test_default_tariff_seeded(db):
    async with db.connection() as conn:
        rows = await (await conn.execute(
            "SELECT valid_from, cheap_start, cheap_end, cheap_price, expensive_price, zone FROM tariff")).fetchall()
    assert rows == [(date(2025, 1, 1), time(0, 0), time(8, 0), Decimal("3.6051"), Decimal("14.4203"), "plava")]


def test_settings_from_env_falls_back_to_phone_credentials(monkeypatch):
    for k in ["SERVER_ARISTON_USER", "SERVER_ARISTON_PASS"]:
        monkeypatch.delenv(k, raising=False)
    monkeypatch.setenv("ARISTON_USER", "phone@x")
    monkeypatch.setenv("ARISTON_PASS", "p1")
    monkeypatch.setenv("BOILER_API_TOKEN", "t")
    monkeypatch.setenv("DOBARBOILER_DB_PASSWORD", "dbpw")
    monkeypatch.setenv("DOBARBOILER_RO_PASSWORD", "ropw")
    s = Settings.from_env()
    assert (s.ariston_user, s.ariston_pass) == ("phone@x", "p1")
    assert s.db_dsn == "postgresql://dobarboiler:dbpw@shared-postgres:5432/dobarboiler"
    assert s.db_ro_dsn == "postgresql://dobarboiler_ro:ropw@shared-postgres:5432/dobarboiler"
    assert s.state_interval_s == 120
    monkeypatch.setenv("SERVER_ARISTON_USER", "guest@x")
    monkeypatch.setenv("SERVER_ARISTON_PASS", "p2")
    s = Settings.from_env()
    assert (s.ariston_user, s.ariston_pass) == ("guest@x", "p2")
