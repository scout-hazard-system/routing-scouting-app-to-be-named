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

# Install and start the Scout user services on a headless Debian trixie box:
#   vehicle-stack.service         (pipeline + Java backend :18080 + frontend :8787)
#   scout-blackboard.service      (file-backed key-authorized store :8765, hub role)
# Services run as user units under loginctl enable-linger, so they survive logout
# with no desktop session.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

SUDO=""
if ((EUID != 0)); then
  require_cmd sudo
  SUDO="sudo"
fi

if ! ROOT="$(resolve_repo_root)"; then
  fail "Could not resolve the repo root; run from agent_box_setup_trixie/ inside the clone."
fi

ENV_FILE="$ROOT/stack/config/vehicle_stack.env"
SCOUT_CREW_ROOT="${SCOUT_CREW_ROOT:-$HOME/scout_crew}"
BB_ENV="${BB_ENV:-$HOME/.config/scout/blackboard.env}"
BB_DIR="${BB_DIR:-$HOME/.scout/blackboard}"
INSTALL_BLACKBOARD=0
SHARD_SRC=""
ARGS=("$@")
i=0
while ((i < ${#ARGS[@]})); do
  case "${ARGS[$i]}" in
    --with-blackboard) INSTALL_BLACKBOARD=1; i=$((i + 1)) ;;
    --with-shards)     SHARD_SRC="${ARGS[$((i + 1))]:-}"; i=$((i + 2)) ;;
    *) fail "unknown argument: ${ARGS[$i]} (supported: --with-blackboard, --with-shards <user@host[:port]>)" ;;
  esac
done

if command -v loginctl >/dev/null 2>&1; then
  info "Enabling linger for headless user services (survive logout, no desktop session)..."
  loginctl enable-linger "$(id -un)" 2>/dev/null || warn "could not enable linger (loginctl); user services may stop at logout."
fi

info "Rendering vehicle-stack.service (ROOT_DIR=$ROOT)..."
(
  cd "$ROOT/stack/deployment"
  VEHICLE_STACK_CONFIG_FILE="$ENV_FILE" ROOT_DIR="$ROOT" ./install_user_service.sh
)

if ((INSTALL_BLACKBOARD)); then
  [[ -d "$SCOUT_CREW_ROOT/src/scout_crew/blackboard" ]] \
    || fail "blackboard source not found at $SCOUT_CREW_ROOT/src/scout_crew/blackboard (run ./install_crew_headless.sh first)"
  info "Rendering scout-blackboard.service..."
  (
    cd "$ROOT/stack/deployment"
    SCOUT_CREW_ROOT="$SCOUT_CREW_ROOT" BB_ENV="$BB_ENV" BB_DIR="$BB_DIR" \
      ./install_blackboard_service.sh
  )
fi

if [[ -n "$SHARD_SRC" ]]; then
  # The agent box serves its own map sharding backend on :18080 (same data the
  # crew's map_* tools read). Pull the MVT shard cache + text-map roots from the
  # hub/map-server host pre-install so the backend is authoritative on boot.
  SYNCS=(
    "$ROOT/map_server_setup_trixie/sync_shards.sh"
    "$ROOT/map_server_setup/sync_shards.sh"
  )
  SYNC=""
  for cand in "${SYNCS[@]}"; do
    if [[ -x "$cand" ]]; then
      SYNC="$cand"
      break
    fi
  done
  if [[ -z "$SYNC" ]]; then
    fail "--with-shards needs map_server_setup/sync_shards.sh in this clone (map-server suite)."
  fi
  info "Syncing map shards from $SHARD_SRC (backend serves MAP_SHARD_STATE from cache)..."
  "$SYNC" "$SHARD_SRC"
fi

info "Starting user services..."
systemctl --user daemon-reload
systemctl --user start vehicle-stack.service
ok "vehicle-stack.service started"
if ((INSTALL_BLACKBOARD)); then
  systemctl --user start scout-blackboard.service
  ok "scout-blackboard.service started"
fi

echo ""
ok "Services installed and started."
cat <<EOF
Useful journal/tail commands:
  journalctl --user -u vehicle-stack.service -f
  journalctl --user -u scout-blackboard.service -f
  ./verify_agent_box.sh

Shards:
  The backend serves map shards from ~/.scanner_stream/map_cache/shards/.
  To pull them from the map-server host:
    ./install_services.sh --with-shards <user@host[:port]>   (passwordless SSH)
EOF