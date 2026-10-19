#!/usr/bin/env bash
# Runs the server test suite from the checkout this script lives in, against the disposable
# test database (bash server/ops/create-db.sh --test creates it). The DSN is never printed.
set -euo pipefail
SERVER="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV=/data/tmp/boilerd-venv
if [ ! -x "$VENV/bin/pytest" ]; then
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install -q "$SERVER[dev]"
fi
cd "$SERVER"
export BOILERD_TEST_DSN="$(cat /data/tmp/dobarboiler-test-dsn.txt)"
export TMPDIR=/data/tmp
exec "$VENV/bin/pytest" -p no:cacheprovider "$@"
