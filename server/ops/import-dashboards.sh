#!/usr/bin/env bash
# Imports every dashboard in server/grafana/*.json into the "DobarBoiler" folder of the shared observability Grafana.
# Idempotent (overwrite). The admin password is read inside this process and never printed.
set -euo pipefail
OBS=/data/srv/observability
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

pw="$(grep -E '^GRAFANA_ADMIN_PASSWORD=' "$OBS/.env" | cut -d= -f2-)"
bind="$(grep -E '^GRAFANA_HOST_BIND=' "$OBS/.env" | cut -d= -f2- || true)"
url="http://${bind:-127.0.0.1:3300}"
auth=(-u "admin:$pw")

if ! curl -sf "${auth[@]}" "$url/api/folders/dobarboiler" >/dev/null; then
  curl -sf "${auth[@]}" -H 'Content-Type: application/json' -X POST "$url/api/folders" \
    --data '{"uid": "dobarboiler", "title": "DobarBoiler"}' >/dev/null
fi

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
for f in "$HERE"/../grafana/*.json; do
  python3 -I - "$f" > "$tmp" <<'PY'
import json, sys
print(json.dumps({"dashboard": json.load(open(sys.argv[1])), "folderUid": "dobarboiler", "overwrite": True}))
PY
  status="$(curl -sf "${auth[@]}" -H 'Content-Type: application/json' -X POST "$url/api/dashboards/db" \
    --data-binary @"$tmp" | python3 -I -c 'import sys,json; d=json.load(sys.stdin); print(d.get("status"), d.get("url"))')"
  echo "$(basename "$f"): $status"
done
unset pw auth
