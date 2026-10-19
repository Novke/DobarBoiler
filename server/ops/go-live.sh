#!/usr/bin/env bash
# One-shot go-live of the history server on novica-dev, after Novica ran ops/add-secrets.ps1 on the laptop.
# Idempotent; safe to re-run. Prints no secret values.
#   bash server/ops/go-live.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER="$(cd "$HERE/.." && pwd)"

echo "== secrets"
ssm-env dobarboiler >/dev/null
ssm-env dobarboiler-server >/dev/null
ssm-env observability /data/srv/observability/.env >/dev/null
grep -oE '^[A-Z0-9_]+=' /data/secrets/dobarboiler-server.env | tr '\n' ' '; echo

echo "== database"
bash "$HERE/create-db.sh"

echo "== container"
[ "$(ss -ltn | grep -c ':8020 ' || true)" = "0" ] || docker ps --format '{{.Names}} {{.Ports}}' | grep -q 'dobarboiler-boilerd' \
  || { echo "port 8020 is taken by something else" >&2; exit 1; }
docker compose -f "$SERVER/docker-compose.yml" up -d --build
for _ in $(seq 1 30); do
  curl -sf 127.0.0.1:8020/api/v1/health | grep -q '"ok":true' && break
  sleep 3
done
curl -s 127.0.0.1:8020/api/v1/health; echo

echo "== tailnet preview"
preview ls | grep -q '^boiler ' || preview add boiler 8020

echo "== grafana"
bash "$HERE/install-grafana.sh"

echo "== backup"
sudo install -m 0755 "$HERE/dobarboiler-backup" /usr/local/bin/dobarboiler-backup
sudo install -m 0644 "$HERE/dobarboiler-backup.service" "$HERE/dobarboiler-backup.timer" /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now dobarboiler-backup.timer
/usr/local/bin/dobarboiler-backup >/dev/null && echo "backup ok: $(cat /data/tmp/dobarboiler-backup.last-ok)"

echo "== MCP for Claude Code"
if ! claude mcp list 2>/dev/null | grep -q '^dobarboiler'; then
  token="$(grep -E '^BOILER_API_TOKEN=' /data/secrets/dobarboiler-server.env | cut -d= -f2-)"
  claude mcp add --scope user --transport http dobarboiler http://127.0.0.1:8020/mcp \
    --header "Authorization: Bearer $token" >/dev/null
  unset token
fi
claude mcp list 2>/dev/null | grep '^dobarboiler' || true
echo "done"
