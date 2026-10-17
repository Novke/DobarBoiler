#!/usr/bin/env bash
# Read-only access to the live boiler for Claude Code on novica-dev.
# Loads ARISTON_USER/ARISTON_PASS from /data/secrets/dobarboiler.env (ssm-env dobarboiler)
# only inside this process and runs a fixed, read-only script - never anything that sends
# commands to the boiler. Claude's permission rule allows exactly this wrapper.
#
#   bash tools/ariston-read.sh [test]    # discovery + features + state (tools/ariston_test.py)
set -euo pipefail

ENV_FILE=/data/secrets/dobarboiler.env
VENV=/data/tmp/dobarboiler-venv
TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

[ -r "$ENV_FILE" ] || { echo "missing $ENV_FILE - run: ssm-env dobarboiler" >&2; exit 1; }
if [ ! -x "$VENV/bin/python" ]; then
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install -q ariston
fi

case "${1:-test}" in
  test) script="$TOOLS/ariston_test.py" ;;
  *) echo "usage: $0 [test]" >&2; exit 2 ;;
esac

set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

cd /data/tmp
exec "$VENV/bin/python" -I "$script"
