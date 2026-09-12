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

# Configure a fresh clone as a Scout map server: build backend, wire env,
# prepare the frontend path. Run from map_server_setup_trixie/ on the new machine.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

if ! ROOT="$(resolve_repo_root)"; then
  fail "Could not resolve the repo root; run from map_server_setup_trixie/ inside the clone."
fi
ENV_FILE="$ROOT/stack/config/vehicle_stack.env"
CONFIGURE_STATE="${MAP_STATE:-}"
ADVERTISE_MODE="auto"
INSTALL_SYSTEMD=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Options:
  --state STATE        jurisdiction to pin (default: existing MAP_SHARD_STATE or AZ)
  --advertise auto     use this host's Tailscale IPv4 if available (default)
  --advertise <ip>     set SCOUT_NETWORK_ADVERTISE_HOST to ip
  --advertise keep     leave SCOUT_NETWORK_ADVERTISE_HOST untouched
  --systemd            also install the vehicle-stack user service
  --help               show this help

Requires JDK 21+ (see ./bootstrap_trixie.sh) and an existing repo clone.
Run AFTER ./sync_shards.sh so the shard data is already on disk.
EOF
}

ARGS=("$@")
i=0
while ((i < ${#ARGS[@]})); do
  arg="${ARGS[$i]}"
  case "$arg" in
    --state=*)
      CONFIGURE_STATE="${arg#*=}"
      i=$((i + 1))
      ;;
    --advertise=*)
      ADVERTISE_MODE="${arg#*=}"
      i=$((i + 1))
      ;;
    --state)
      CONFIGURE_STATE="${ARGS[$((i + 1))]:-}"
      i=$((i + 2))
      ;;
    --advertise)
      ADVERTISE_MODE="${ARGS[$((i + 1))]:-}"
      i=$((i + 2))
      ;;
    --systemd)
      INSTALL_SYSTEMD=1
      i=$((i + 1))
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown argument: $arg (see --help)"
      ;;
  esac
done

[[ -f "$ENV_FILE" ]] || fail "Missing config file: $ENV_FILE (is this the monorepo clone?)"

echo ""
info "Scout map server configuration for repo root: $ROOT"

if [[ -z "$CONFIGURE_STATE" ]]; then
  CONFIGURE_STATE="$(grep -E '^MAP_SHARD_STATE=' "$ENV_FILE" | tail -n 1 | cut -d= -f2- | tr -d '"' || true)"
fi
CONFIGURE_STATE="${CONFIGURE_STATE:-AZ}"

info "Java toolchain..."
detect_java || fail "JDK 21+ required. Run ./bootstrap_trixie.sh (openjdk-21-jdk is in trixie apt)."

info "Building backend (javac ${JAVA_VER})..."
(
  cd "$ROOT/navigation/backend" || fail "backend dir missing: $ROOT/navigation/backend"
  ./build_executable.sh
)
[[ -f "$ROOT/navigation/backend/dist/backend-lite.jar" ]] \
  || fail "backend build produced no dist/backend-lite.jar"
ok "Backend jar: navigation/backend/dist/backend-lite.jar"

info "Configuring $ENV_FILE..."

if [[ -f "$ROOT/.git/config" ]] && ! git -C "$ROOT" diff --quiet -- stack/config/vehicle_stack.env 2>/dev/null; then
  backup="$ENV_FILE.bak.$(hostname)"
  cp "$ENV_FILE" "$backup"
  info "Existing vehicle_stack.env was locally modified; backed up to $backup"
fi

if ! grep -q '^PLANET_PMTILES_URL=' "$ENV_FILE"; then
  printf '\nPLANET_PMTILES_URL=https://build.protomaps.com/20260811.pmtiles\n' >> "$ENV_FILE"
fi

upsert_env() {
  local key="$1" value="$2" file="$3"
  if grep -q "^${key}=" "$file"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$file"
  else
    printf '%s=%s\n' "$key" "$value" >> "$file"
  fi
}

upsert_env MAP_SHARD_STATE "$CONFIGURE_STATE" "$ENV_FILE"
upsert_env JURISDICTION_STATE "$CONFIGURE_STATE" "$ENV_FILE"

ADVERTISE_IP=""
case "$ADVERTISE_MODE" in
  keep)
    info "Keeping existing SCOUT_NETWORK_ADVERTISE_HOST"
    ;;
  auto)
    ADVERTISE_IP="$(tailscale_ip4)"
    if [[ -n "$ADVERTISE_IP" ]]; then
      upsert_env SCOUT_NETWORK_ADVERTISE_HOST "$ADVERTISE_IP" "$ENV_FILE"
      ok "SCOUT_NETWORK_ADVERTISE_HOST=$ADVERTISE_IP"
    else
      warn "No Tailscale IPv4 detected; leaving SCOUT_NETWORK_ADVERTISE_HOST as-is."
      warn "Set it manually later or re-run with --advertise auto after 'tailscale up'."
    fi
    ;;
  *)
    ADVERTISE_IP="$ADVERTISE_MODE"
    upsert_env SCOUT_NETWORK_ADVERTISE_HOST "$ADVERTISE_IP" "$ENV_FILE"
    ok "SCOUT_NETWORK_ADVERTISE_HOST=$ADVERTISE_IP"
    ;;
esac

EXPORT_ADVERTISE="$ADVERTISE_IP"
if [[ -z "$EXPORT_ADVERTISE" && "$ADVERTISE_MODE" == "keep" ]]; then
  EXPORT_ADVERTISE="$(grep -E '^SCOUT_NETWORK_ADVERTISE_HOST=' "$ENV_FILE" | tail -n 1 | cut -d= -f2- | tr -d '"' || true)"
fi

EXPORT_MARKER="# --- scout map server exports (managed by map_server_setup_trixie/configure_map_server.sh) ---"
if grep -qF -- "$EXPORT_MARKER" "$ENV_FILE"; then
  head_line="$(grep -nF -- "$EXPORT_MARKER" "$ENV_FILE" | head -n 1 | cut -d: -f1)"
  head -n "$((head_line - 1))" "$ENV_FILE" > "$ENV_FILE.tmp"
  mv "$ENV_FILE.tmp" "$ENV_FILE"
fi

{
  echo ""
  echo "$EXPORT_MARKER"
  echo "export SCOUT_TEXT_MAP_SHARD_ROOTS=\"$ROOT/vlm_text_map_shards,$ROOT/vlm_text_map_shards_chunked\""
  echo "export MAP_SHARD_STATE=$CONFIGURE_STATE"
  echo "export JURISDICTION_STATE=$CONFIGURE_STATE"
  if [[ -n "$EXPORT_ADVERTISE" ]]; then
    echo "export SCOUT_NETWORK_ADVERTISE_HOST=$EXPORT_ADVERTISE"
  else
    echo "export SCOUT_NETWORK_ADVERTISE_HOST=${SCOUT_NETWORK_ADVERTISE_HOST:-}"
  fi
} >> "$ENV_FILE"
ok "Map env exported for the stack launcher (SCOUT_TEXT_MAP_SHARD_ROOTS, MAP_SHARD_STATE, ...)"

info "Frontend..."
require_cmd python3
if python3 -c 'import py_compile; py_compile.compile("navigation/frontend/dev_server.py", doraise=True)' 2>/dev/null; then
  ok "python3 present; frontend will use the PyInstaller build or dev_server.py fallback."
else
  warn "python3 missing or dev_server.py failed a syntax check; install python3."
fi

if ((INSTALL_SYSTEMD)); then
  info "Installing vehicle-stack user service..."
  (
    cd "$ROOT/stack/deployment"
    VEHICLE_STACK_CONFIG_FILE="$ENV_FILE" ROOT_DIR="$ROOT" ./install_user_service.sh
  )
  ok "User service installed; start with: systemctl --user start vehicle-stack.service"
fi

echo ""
ok "Configuration complete."
cat <<EOF
Next steps:
  1. Start the stack:            cd "$ROOT" && ./master start
  2. Verify serving:             ./verify_map_server.sh --start
  3. Kick prefetch manually:     curl -s 'http://127.0.0.1:18080/api/map/shard?state=$CONFIGURE_STATE'
  4. Mesh URLs after advertise:  http://<advertise-ip>:18080/api/map/status
EOF