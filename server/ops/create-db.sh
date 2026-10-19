#!/usr/bin/env bash
# Creates the DobarBoiler history databases and roles in shared-postgres (idempotent).
#   bash server/ops/create-db.sh          # prod: roles dobarboiler + dobarboiler_ro, DB dobarboiler
#   bash server/ops/create-db.sh --test   # test: role/DB dobarboiler_test, DSN -> /data/tmp/dobarboiler-test-dsn.txt
# Passwords are passed to psql as variables and never echoed.
set -euo pipefail
PSQL=(docker exec -i shared-postgres psql -U postgres -v ON_ERROR_STOP=1 -q)

if [ "${1:-}" = "--test" ]; then
  DSN_FILE=/data/tmp/dobarboiler-test-dsn.txt
  if [ -s "$DSN_FILE" ]; then
    pw="$(sed -E 's#^postgresql://[^:]+:([^@]+)@.*#\1#' "$DSN_FILE")"
  else
    pw="$(openssl rand -hex 24)"
  fi
  "${PSQL[@]}" -v pw="$pw" <<'SQL'
SELECT format('CREATE ROLE dobarboiler_test LOGIN PASSWORD %L', :'pw')
  WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dobarboiler_test') \gexec
ALTER ROLE dobarboiler_test PASSWORD :'pw';
SELECT 'CREATE DATABASE dobarboiler_test OWNER dobarboiler_test'
  WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'dobarboiler_test') \gexec
SQL
  umask 077
  printf 'postgresql://dobarboiler_test:%s@127.0.0.1:5432/dobarboiler_test\n' "$pw" > "$DSN_FILE"
  echo "test database ready, DSN in $DSN_FILE"
  exit 0
fi

ENV_FILE=/data/secrets/dobarboiler-server.env
[ -r "$ENV_FILE" ] || { echo "missing $ENV_FILE - run: ssm-env dobarboiler-server" >&2; exit 1; }
db_pw="$(grep -E '^DOBARBOILER_DB_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)"
ro_pw="$(grep -E '^DOBARBOILER_RO_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)"
[ -n "$db_pw" ] && [ -n "$ro_pw" ] || { echo "DB passwords missing in $ENV_FILE" >&2; exit 1; }

"${PSQL[@]}" -v db_pw="$db_pw" -v ro_pw="$ro_pw" <<'SQL'
SELECT format('CREATE ROLE dobarboiler LOGIN PASSWORD %L', :'db_pw')
  WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dobarboiler') \gexec
ALTER ROLE dobarboiler PASSWORD :'db_pw';
SELECT format('CREATE ROLE dobarboiler_ro LOGIN PASSWORD %L', :'ro_pw')
  WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dobarboiler_ro') \gexec
ALTER ROLE dobarboiler_ro PASSWORD :'ro_pw';
ALTER ROLE dobarboiler_ro SET default_transaction_read_only = on;
ALTER ROLE dobarboiler_ro SET statement_timeout = '5s';
SELECT 'CREATE DATABASE dobarboiler OWNER dobarboiler'
  WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'dobarboiler') \gexec
GRANT CONNECT ON DATABASE dobarboiler TO dobarboiler_ro;
\connect dobarboiler
GRANT USAGE ON SCHEMA public TO dobarboiler_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO dobarboiler_ro;
ALTER DEFAULT PRIVILEGES FOR ROLE dobarboiler IN SCHEMA public GRANT SELECT ON TABLES TO dobarboiler_ro;
SQL
echo "prod database ready"
