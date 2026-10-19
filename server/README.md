# boilerd: DobarBoiler history server

Read-only witness for the Ariston Velis: polls the Ariston cloud (state every 2 min, energy buckets every 2 h),
stores everything in Postgres `dobarboiler` (shared-postgres), splits kWh into cheap (00–08 h) and expensive
tariff, attributes every change (app / external / firmware) and serves REST (`/api/v1`) for the phone app and
MCP (`/mcp`) for agents. It never sends a command to the boiler.

Design: `docs/superpowers/specs/2026-10-18-boiler-history-mcp-ux-design.md`.
Plan: `docs/superpowers/plans/2026-10-18-boiler-history-server.md`.

## Run

```bash
ssm-env dobarboiler && ssm-env dobarboiler-server     # secrets (Novica creates the second with ops/add-secrets.ps1)
bash server/ops/create-db.sh                           # roles + database, idempotent
docker compose -f server/docker-compose.yml up -d --build
curl -s 127.0.0.1:8020/api/v1/health
preview add boiler 8020                                # http://novica-dev/boiler/ on the tailnet
```

## Test

```bash
bash server/ops/create-db.sh --test    # once: disposable DB dobarboiler_test, DSN in /data/tmp (0600)
bash server/ops/pytest.sh -q
```

## Endpoints

- REST (bearer `BOILER_API_TOKEN`): `POST /api/v1/app/actions`, `POST /api/v1/app/intent`, `GET /api/v1/now`,
  `/series/state`, `/energy`, `/events`, `/insights`, `/report`; `GET /api/v1/health` without token.
- MCP (same bearer): `http://127.0.0.1:8020/mcp` on the box, `http://novica-dev/boiler/mcp` from the tailnet.
  Tools `boiler_now`, `boiler_state_series`, `boiler_energy`, `boiler_events`, `boiler_report`, `boiler_insights`,
  `boiler_explain`, `boiler_health`, `boiler_sql`; resource `boiler://schema`.
