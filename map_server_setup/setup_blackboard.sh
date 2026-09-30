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

# Bootstrap the Scout blackboard on the map-server hub. Generates the master
# secret + entry token, creates a scout_crew venv, installs the
# scout-blackboard user service, mints the manager token, and optionally
# issues per-device tokens. Run from map_server_setup/ on the hub.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

if ! ROOT="$(resolve_repo_root)"; then
  fail "Could not resolve the repo root; run from map_server_setup/ inside the clone."
fi
ENV_FILE="$ROOT/stack/config/vehicle_stack.env"
[[ -f "$ENV_FILE" ]] || fail "Missing config file: $ENV_FILE (is this the monorepo clone?)"

SCOUT_CREW_ROOT="${SCOUT_CREW_ROOT:-$HOME/Desktop/scout_crew}"
BB_ENV="${BB_ENV:-$HOME/.config/scout/blackboard.env}"
BB_DIR="${BB_DIR:-$HOME/.scout/blackboard}"
BB_HOST="0.0.0.0"
BB_PORT="8765"
HUB_IP="${SCOUT_MESH_HUB_ADDRESS:-10.66.2.3}"
INSTALL_SYSTEMD=1
DEVICE_ROLE="alert"
DEVICES=()

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Options:
  --hub-ip IP          advertised hub mesh IP for SCOUT_BLACKBOARD_URL
                       (default: \$SCOUT_MESH_HUB_ADDRESS or 10.66.2.3)
  --host IP            server bind address (default: 0.0.0.0)
  --port PORT          server port (default: 8765)
  --device <id>        also mint a per-device token via key authorization
                       (repeatable; id used as the device identifier, NOT an IMEI)
  --device-role ROLE   pipeline role for device tokens (default: alert, i.e. the
                       sandbox writer role; intel/vet/rank/core/dev also valid)
  --no-systemd         prepare config/venv/tokens only; do not install the service
  --help               show this help

Environment: SCOUT_CREW_ROOT (scout_crew checkout), BB_ENV, BB_DIR.
The master secret is generated once and kept only in \$BB_ENV (0600).
Run AFTER ./configure_map_server.sh so the stack config exists.
EOF
}

ARGS=("$@")
i=0
while ((i < ${#ARGS[@]})); do
  arg="${ARGS[$i]}"
  case "$arg" in
    --hub-ip=*)      HUB_IP="${arg#*=}"; i=$((i + 1)) ;;
    --host=*)        BB_HOST="${arg#*=}"; i=$((i + 1)) ;;
    --port=*)        BB_PORT="${arg#*=}"; i=$((i + 1)) ;;
    --device=*)      DEVICES+=("${arg#*=}"); i=$((i + 1)) ;;
    --device-role=*) DEVICE_ROLE="${arg#*=}"; i=$((i + 1)) ;;
    --hub-ip)        HUB_IP="${ARGS[$((i + 1))]:-}"; i=$((i + 2)) ;;
    --host)          BB_HOST="${ARGS[$((i + 1))]:-}"; i=$((i + 2)) ;;
    --port)          BB_PORT="${ARGS[$((i + 1))]:-}"; i=$((i + 2)) ;;
    --device)        DEVICES+=("${ARGS[$((i + 1))]:-}"); i=$((i + 2)) ;;
    --device-role)   DEVICE_ROLE="${ARGS[$((i + 1))]:-}"; i=$((i + 2)) ;;
    --no-systemd)    INSTALL_SYSTEMD=0; i=$((i + 1)) ;;
    -h|--help)       usage; exit 0 ;;
    *) fail "unknown argument: $arg (see --help)" ;;
  esac
done

[[ -f "$SCOUT_CREW_ROOT/src/scout_crew/blackboard/server.py" ]] \
  || fail "scout_crew source not found at $SCOUT_CREW_ROOT (set SCOUT_CREW_ROOT)"

echo ""
info "Scout blackboard bootstrap (map-server hub $HUB_IP)"

# --- 1. interpreter / venv -------------------------------------------------
info "Python interpreter..."
VENV_PY=""
for c in "$SCOUT_CREW_ROOT/.venv-bb/bin/python" "$SCOUT_CREW_ROOT/.venv/bin/python"; do
  if [[ -x "$c" ]]; then VENV_PY="$c"; break; fi
done
if [[ -z "$VENV_PY" ]]; then
  require_cmd python3
  VENV_PY="$SCOUT_CREW_ROOT/.venv-bb/bin/python"
  info "Creating venv $SCOUT_CREW_ROOT/.venv-bb (blackboard is pure-stdlib)"
  python3 -m venv "${VENV_PY%/bin/python}"
fi
ok "Interpreter: $VENV_PY"

# --- 2. secrets + env (generated once, 0600) -------------------------------
mkdir -p "$(dirname "$BB_ENV")" "$BB_DIR/tokens" "$BB_DIR/devices"
if [[ -f "$BB_ENV" ]]; then
  info "Existing $BB_ENV found; keeping current secrets."
else
  info "Generating master secret + entry token -> $BB_ENV"
  {
    echo "SCOUT_BLACKBOARD_HOST=$BB_HOST"
    echo "SCOUT_BLACKBOARD_PORT=$BB_PORT"
    echo "SCOUT_BLACKBOARD_PATH=$BB_DIR/scout_blackboard.db"
    echo "SCOUT_BLACKBOARD_MEMORY=0"
    echo "SCOUT_BLACKBOARD_SANDBOX_WRITERS=${SCOUT_BLACKBOARD_SANDBOX_WRITERS:-alert}"
    echo "SCOUT_BLACKBOARD_TOKEN_SECRET=$("$VENV_PY" - <<'PY'
import secrets
print(secrets.token_hex(32))
PY
)"
    echo "SCOUT_BLACKBOARD_ENTRY_TOKEN=$("$VENV_PY" - <<'PY'
import secrets
print(secrets.token_urlsafe(24))
PY
)"
  } > "$BB_ENV"
  chmod 600 "$BB_ENV"
fi

# --- 3. stack exports (endpoints + sandbox writers; secrets stay in BB_ENV) --
EXPORT_MARKER="# --- scout blackboard exports (managed by setup_blackboard.sh) ---"
if grep -qF -- "$EXPORT_MARKER" "$ENV_FILE"; then
  head_line="$(grep -nF -- "$EXPORT_MARKER" "$ENV_FILE" | head -n 1 | cut -d: -f1)"
  head -n "$((head_line - 1))" "$ENV_FILE" > "$ENV_FILE.tmp"
  mv "$ENV_FILE.tmp" "$ENV_FILE"
fi
{
  echo ""
  echo "$EXPORT_MARKER"
  echo "export SCOUT_BLACKBOARD_URL=http://$HUB_IP:$BB_PORT"
  echo "export SCOUT_BLACKBOARD_HOST=$BB_HOST"
  echo "export SCOUT_BLACKBOARD_PORT=$BB_PORT"
  echo "export SCOUT_BLACKBOARD_SANDBOX_WRITERS=${SCOUT_BLACKBOARD_SANDBOX_WRITERS:-alert}"
} >> "$ENV_FILE"
ok "Stack exports appended to $ENV_FILE (secret stays in $BB_ENV)"

# --- 4. systemd service -----------------------------------------------------
if ((INSTALL_SYSTEMD)); then
  info "Installing scout-blackboard user service..."
  (
    cd "$ROOT/stack/deployment"
    SCOUT_CREW_ROOT="$SCOUT_CREW_ROOT" BB_ENV="$BB_ENV" BB_DIR="$BB_DIR" \
      ./install_blackboard_service.sh
  )
  systemctl --user start scout-blackboard.service
  info "Waiting for blackboard health..."
  for _ in $(seq 1 30); do
    if curl -fsS "http://127.0.0.1:$BB_PORT/health" >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  curl -fsS "http://127.0.0.1:$BB_PORT/health" >/dev/null \
    || fail "blackboard did not come up; check: journalctl --user -u scout-blackboard.service -n 50"
  ok "Blackboard serving on http://$HUB_IP:$BB_PORT"
else
  warn "--no-systemd: service not installed."
  warn "Run manually with: $(dirname "$VENV_PY")/python -m scout_crew.blackboard.server"
fi

# --- 5. manager token -------------------------------------------------------
MGR_TOKEN="$BB_DIR/tokens/manager.token"
BBHOST="127.0.0.1:$BB_PORT"
(
  export PYTHONPATH="$SCOUT_CREW_ROOT/src"
  export SCOUT_BLACKBOARD_URL="http://$BBHOST"
  # shellcheck disable=SC1090
  set -a; source "$BB_ENV"; set +a
  "$VENV_PY" - <<'PY' > "$MGR_TOKEN"
import json, os
from scout_crew.blackboard.client import BlackboardClient
r = BlackboardClient(base_url=os.environ["SCOUT_BLACKBOARD_URL"]).issue(
    role="manager", categories=["moderation", "blackboard"],
    device_id="hub-manager", ttl=60 * 60 * 24 * 90)
tok = r["token"]
print(tok)
PY
)
chmod 600 "$MGR_TOKEN"
[[ -s "$MGR_TOKEN" ]] || fail "failed to mint manager token"
ok "Manager token -> $MGR_TOKEN (for the crew manager; 90 days)"

# --- 6. per-device tokens ---------------------------------------------------
if (( ${#DEVICES[@]} > 0 )); then
  info "Issuing $((${#DEVICES[@]})) device token(s) via key authorization..."
  for dev in "${DEVICES[@]}"; do
    (
      export PYTHONPATH="$SCOUT_CREW_ROOT/src"
      export SCOUT_BLACKBOARD_URL="http://$BBHOST"
      export SCOUT_BB_DEVICE="$dev"
      export SCOUT_BB_ROLE="$DEVICE_ROLE"
      # shellcheck disable=SC1090
      set -a; source "$BB_ENV"; set +a
      "$VENV_PY" - <<PY
import json, os
from pathlib import Path
from scout_crew.blackboard.client import BlackboardClient
dev = os.environ["SCOUT_BB_DEVICE"]
r = BlackboardClient(base_url=os.environ["SCOUT_BLACKBOARD_URL"]).authorize(
    device_id=dev, role=os.environ["SCOUT_BB_ROLE"],
    categories=["pipeline"], ttl=60 * 60 * 24 * 30,
    entry_token=os.environ["SCOUT_BLACKBOARD_ENTRY_TOKEN"])
out = Path(os.environ["BB_DIR"])
(out / "devices" / f"{dev}.json").write_text(json.dumps(r, indent=2) + "\n")
(out / "tokens" / f"{dev}.token").write_text(r["token"] + "\n")
print(f"issued {dev}: role={r.get('role')}, observed_ip={r.get('observed_ip')}, expires_in={r.get('expires_in')}")
PY
    )
    ok "Device '$dev' token issued (role=$DEVICE_ROLE) -> $BB_DIR/tokens/$dev.token"
  done
fi

echo ""
ok "Blackboard bootstrap complete."
cat <<EOF
Summary
  URL:          http://$HUB_IP:$BB_PORT
  Data:         $BB_DIR/scout_blackboard.db (file-backed, $BB_ENV for secrets)
  Manager:      $MGR_TOKEN
  Device tokens: $BB_DIR/devices/*.json   ($((${#DEVICES[@]})) issued)
Next steps
  1. Put the manager token into the crew's SCOUT_BLACKBOARD_TOKEN on the hub.
  2. Peer devices keep only their own per-device token (SCOUT_BLACKBOARD_TOKEN);
     the master secret stays on the hub in $BB_ENV.
  3. Watch:      journalctl --user -u scout-blackboard.service -f
  4. Moderate:   /v1/audit with the manager token; revoke via /v1/keys/revoke.
  5. Verify:     ./verify_blackboard.sh
EOF