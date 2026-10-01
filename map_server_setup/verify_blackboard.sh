#!/usr/bin/env bash
# Copyright 2026 Scout Project Contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Verify the Scout blackboard on the map-server hub: health, key-authorized
# onboarding round-trip, manager moderation (/v1/audit), and per-device
# presence. Run from map_server_setup/ on the hub. Exits non-zero on failure.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

if ! ROOT="$(resolve_repo_root)"; then
  fail "Could not resolve the repo root; run from map_server_setup/ inside the clone."
fi

SCOUT_CREW_ROOT="${SCOUT_CREW_ROOT:-$HOME/Desktop/scout_crew}"
BB_ENV="${BB_ENV:-$HOME/.config/scout/blackboard.env}"
BB_DIR="${BB_DIR:-$HOME/.scout/blackboard}"
BB_HOST="127.0.0.1"
BB_PORT="${SCOUT_BLACKBOARD_PORT:-8765}"
MESH_CIDR="10.66.0.0"
BB_URL="http://$BB_HOST:$BB_PORT"
MGR_TOKEN="$BB_DIR/tokens/manager.token"
FAILURES=0

[[ -f "$BB_ENV" ]] || fail "Missing $BB_ENV; run ./setup_blackboard.sh first."

check() {
  local name="$1" result="$2"
  if [[ "$result" == "0" ]]; then
    ok "$name"
  else
    warn "$name"
    FAILURES=$((FAILURES + 1))
  fi
}

# --- 1. health ---------------------------------------------------------------
health="$(curl -sS -o /dev/null -w '%{http_code}' "$BB_URL/health" 2>/dev/null || true)"
check "blackboard /health returns 200" "$([ "$health" = "200" ] && echo 0 || echo 1)"

# --- 2. key-authorization round trip: authorize -> write -> read -------------
export BB_ENV SCOUT_CREW_ROOT BB_URL PYTHONPATH="$SCOUT_CREW_ROOT/src"
AUTH_JSON="$("$SCOUT_CREW_ROOT/.venv-bb/bin/python" - <<'PY' 2>/dev/null || true
import json, os
from pathlib import Path

env_vals = {}
for line in Path(os.environ["BB_ENV"]).read_text().splitlines():
    if "=" in line and not line.startswith("#"):
        k, v = line.split("=", 1)
        env_vals[k] = v
env_vals["SCOUT_BLACKBOARD_URL"] = os.environ["BB_URL"]
os.environ.update(env_vals)
from scout_crew.blackboard.client import BlackboardClient
c = BlackboardClient(base_url=os.environ["SCOUT_BLACKBOARD_URL"])
r = c.authorize(device_id="verify-probe", role="alert", categories=["pipeline"],
                ttl=600, entry_token=env_vals["SCOUT_BLACKBOARD_ENTRY_TOKEN"])
tok = r["token"]
c = BlackboardClient(base_url=os.environ["SCOUT_BLACKBOARD_URL"], token=tok)
w = c.write(category="pipeline", role="alert", title="verify-probe",
            body="blackboard health write")
rd = c.read(category="pipeline", limit=5, role="alert")
print(json.dumps({"auth": bool(r.get("ok")), "write": bool(w.get("id")), "read": len(rd)}))
PY
)"
if grep -q '"auth": *true, "write": *true' <<< "$AUTH_JSON"; then
  check "key authorization round trip (authorize->write->read)" 0
else
  check "key authorization round trip (authorize->write->read)" 1
fi

# --- 3. manager moderation (/v1/audit) ----------------------------------------
if [[ -s "$MGR_TOKEN" ]]; then
  AUDIT_CODE="$(curl -sS -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $(tr -d '\n' < "$MGR_TOKEN")" \
    "$BB_URL/v1/audit?limit=50" 2>/dev/null || true)"
  check "manager /v1/audit authorized (200)" "$([ "$AUDIT_CODE" = "200" ] && echo 0 || echo 1)"
else
  check "manager /v1/audit authorized (200)" 1
fi

# --- 4. per-device presence (observed_ip within mesh CIDR) ---------------------
dev_ok=0
for djson in "$BB_DIR"/devices/*.json; do
  [[ -f "$djson" ]] || continue
  dev="$(basename "$djson" .json)"
  ip="$(grep -o '"observed_ip": *"[^"]*"' "$djson" | head -n1 | sed 's/.*"\([^"]*\)"$/\1/')"
  if [[ "$ip" == ${MESH_CIDR}.* ]]; then
    ok "device '$dev' presence: observed_ip=$ip (mesh)"
  else
    warn "device '$dev' presence: observed_ip=$ip NOT in $MESH_CIDR/16"
    dev_ok=1
  fi
done
if compgen -G "$BB_DIR/devices/*.json" >/dev/null; then
  check "per-device IP presence within mesh" "$dev_ok"
else
  check "per-device IP presence within mesh" 0
fi

echo ""
if ((FAILURES > 0)); then
  fail "$FAILURES check(s) failed."
else
  ok "All blackboard checks passed on $BB_URL"
fi