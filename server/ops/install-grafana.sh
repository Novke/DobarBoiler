#!/usr/bin/env bash
# Adds the DobarBoiler Postgres datasource to the shared observability Grafana and imports the dashboards.
# Idempotent. Needs DOBARBOILER_RO_PASSWORD in /data/srv/observability/.env
# (ops/add-secrets.ps1 puts it into SSM /dev/observability/env; then: ssm-env observability /data/srv/observability/.env).
# Secrets are read inside this process and never printed.
set -euo pipefail
OBS=/data/srv/observability
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STAMP="bak-dobarboiler-$(date +%F)"

grep -q '^DOBARBOILER_RO_PASSWORD=' "$OBS/.env" || { echo "DOBARBOILER_RO_PASSWORD missing in $OBS/.env" >&2; exit 1; }
for f in grafana-datasources.yml docker-compose.yml; do
  [ -e "$OBS/$f.$STAMP" ] || cp -p "$OBS/$f" "$OBS/$f.$STAMP"
done

python3 -I - "$OBS" <<'PY'
import sys
from pathlib import Path
obs = Path(sys.argv[1])

ds = obs / "grafana-datasources.yml"
text = ds.read_text()
if "uid: dobarboiler" not in text:
    text = text.rstrip("\n") + """

  - name: DobarBoiler
    uid: dobarboiler
    type: grafana-postgresql-datasource
    access: proxy
    url: shared-postgres:5432
    user: dobarboiler_ro
    jsonData:
      database: dobarboiler
      sslmode: disable
      postgresVersion: 1700
    secureJsonData:
      password: $DOBARBOILER_RO_PASSWORD
"""
    ds.write_text(text)

comp = obs / "docker-compose.yml"
c = comp.read_text()
if "DOBARBOILER_RO_PASSWORD" not in c:
    anchor = "      - GF_SERVER_ROOT_URL=http://novica-dev:3300\n"
    assert anchor in c, "grafana environment anchor not found"
    c = c.replace(anchor, anchor + "      - DOBARBOILER_RO_PASSWORD=${DOBARBOILER_RO_PASSWORD:-}\n", 1)
if "      - dev-shared\n" not in c:
    anchor = "      - grafana-data:/var/lib/grafana\n"
    assert anchor in c, "grafana volumes anchor not found"
    c = c.replace(anchor, anchor + "    networks:\n      - default\n      - dev-shared\n", 1)
    assert c.rstrip().endswith("name: sila-obs-photo"), "networks block is not last; edit by hand"
    c = c.rstrip("\n") + "\n  dev-shared:\n    external: true\n"
comp.write_text(c)
PY

docker compose -f "$OBS/docker-compose.yml" --project-directory "$OBS" config -q
docker compose -f "$OBS/docker-compose.yml" --project-directory "$OBS" up -d grafana

pw="$(grep -E '^GRAFANA_ADMIN_PASSWORD=' "$OBS/.env" | cut -d= -f2-)"
bind="$(grep -E '^GRAFANA_HOST_BIND=' "$OBS/.env" | cut -d= -f2- || true)"
url="http://${bind:-127.0.0.1:3300}"
for _ in $(seq 1 30); do curl -sf "$url/api/health" >/dev/null && break; sleep 2; done

bash "$HERE/import-dashboards.sh"
curl -s -u "admin:$pw" "$url/api/datasources/uid/dobarboiler/health" | python3 -I -c 'import sys,json; print("datasource:", json.load(sys.stdin).get("status"))'
unset pw
echo "Grafana: $url/d/dobarboiler"
