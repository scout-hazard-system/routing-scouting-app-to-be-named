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

# Health + readiness check for a Scout map server.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

if ! ROOT="$(resolve_repo_root)"; then
  fail "Could not resolve the repo root; run from map_server_setup_trixie/ inside the clone."
fi
STATE="${MAP_STATE:-}"
HOST="${HOST:-127.0.0.1}"
PORT="${BACKEND_PORT:-18080}"
AUTO_START=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Options:
  --start        start the stack first if health checks fail (./master start)
  --state STATE  jurisdiction to prefetch (default: MAP_SHARD_STATE in repo env, or AZ)
  --host HOST    backend host to probe (default: 127.0.0.1)
  --help         show this help

Checks: backend health, /api/map/status (planet + shards), AZ prefetch, frontend.
EOF
}

ARGS=("$@")
i=0
while ((i < ${#ARGS[@]})); do
  arg="${ARGS[$i]}"
  case "$arg" in
    --start)
      AUTO_START=1
      i=$((i + 1))
      ;;
    --state=*)
      STATE="${arg#*=}"
      i=$((i + 1))
      ;;
    --state)
      STATE="${ARGS[$((i + 1))]:-}"
      i=$((i + 2))
      ;;
    --host=*)
      HOST="${arg#*=}"
      i=$((i + 1))
      ;;
    --host)
      HOST="${ARGS[$((i + 1))]:-}"
      i=$((i + 2))
      ;;
    --port=*)
      PORT="${arg#*=}"
      i=$((i + 1))
      ;;
    --port)
      PORT="${ARGS[$((i + 1))]:-}"
      i=$((i + 2))
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

require_cmd curl jq

if [[ -z "$STATE" && -f "$ROOT/stack/config/vehicle_stack.env" ]]; then
  STATE="$(grep -E '^MAP_SHARD_STATE=' "$ROOT/stack/config/vehicle_stack.env" | tail -n 1 | cut -d= -f2- | tr -d '"' || true)"
fi
STATE="${STATE:-AZ}"

BACKEND_URL="http://${HOST}:${PORT}"
FRONTEND_URL="http://${HOST}:8787"

PASS=0
FAILCT=0
pass() { PASS=$((PASS + 1)); ok "$1"; }
fail_check() { FAILCT=$((FAILCT + 1)); warn "$1"; }

http_ok() {
  curl -fsS "$1" >/dev/null 2>&1
}

echo ""
info "Scout map server verification (backend=${BACKEND_URL}, state=${STATE})"
echo ""

if ! http_ok "$BACKEND_URL/api/health"; then
  if ((AUTO_START)); then
    info "Backend not responding; starting the stack..."
    "$ROOT/master" start || fail "stack failed to start (see: $ROOT/master start)"
  else
    fail "Backend not reachable at $BACKEND_URL/api/health. Start it with: $ROOT/master start"
  fi
fi

info "Backend health..."
if http_ok "$BACKEND_URL/api/health"; then
  pass "GET /api/health -> 200"
else
  fail_check "GET /api/health -> failed"
fi

info "Map status..."
MAP_STATUS_JSON="$(curl -fsS "$BACKEND_URL/api/map/status" 2>/dev/null || true)"
if [[ -z "$MAP_STATUS_JSON" ]]; then
  fail_check "GET /api/map/status -> failed"
else
  planet_ready="$(jq -r '.planet.ready // "false"' <<< "$MAP_STATUS_JSON")"
  cache_dir="$(jq -r '.cache_dir // ""' <<< "$MAP_STATUS_JSON")"
  shard_count="$(jq '[.shards[]? | select(.planet_tiles > 0)] | length' <<< "$MAP_STATUS_JSON")"
  text_ok="$(jq '[.text_map_shards[]? | select(.az_exists == true)] | length' <<< "$MAP_STATUS_JSON")"
  planet_url="$(jq -r '.planet.url // ""' <<< "$MAP_STATUS_JSON")"
  planet_mode="$(jq -r '.planet.mode // ""' <<< "$MAP_STATUS_JSON")"
  if [[ "$planet_ready" == "true" ]]; then
    pass "planet ready ($planet_mode, $planet_url)"
  else
    fail_check "planet not ready: $planet_url"
  fi
  info "  cache_dir:    ${cache_dir:-n/a}"
  info "  shard states with tiles: $shard_count"
  info "  text AZ roots present:   $text_ok/2"
fi

info "Shard prefetch ($STATE)..."
PREFETCH_JSON="$(curl -fsS --get "$BACKEND_URL/api/map/shard" --data-urlencode "state=$STATE" 2>/dev/null || true)"
if [[ -z "$PREFETCH_JSON" ]]; then
  fail_check "GET /api/map/shard?state=$STATE -> failed"
else
  pref_status="$(jq -r '.status // "error"' <<< "$PREFETCH_JSON")"
  pref_phase="$(jq -r '.prefetch.phase // ""' <<< "$PREFETCH_JSON")"
  if [[ "$pref_status" == "ok" || "$pref_status" == "started" || "$pref_status" == "already_running" ]]; then
    pass "shard prefetch: $pref_status (phase=$pref_phase)"
  else
    fail_check "shard prefetch: $pref_status (phase=$pref_phase)"
  fi
fi

info "Frontend..."
if http_ok "$FRONTEND_URL/index.html"; then
  pass "GET $FRONTEND_URL/index.html -> 200"
else
  fail_check "GET $FRONTEND_URL/index.html -> failed"
fi

ADVERTISE="$(jq -r '.network.advertise_host // ""' <<< "$MAP_STATUS_JSON")"
echo ""
if ((FAILCT == 0)); then
  ok "ALL CHECKS PASSED ($PASS/$PASS)"
else
  warn "FAILED CHECKS: $FAILCT (passed $PASS)"
fi
cat <<EOF

Mesh URLs (advertise_host=${ADVERTISE:-<not set in network status>}):
  Frontend:      ${FRONTEND_URL}/
  Backend:       ${BACKEND_URL}/api/health
  Map status:    ${BACKEND_URL}/api/map/status
  Map shard:     ${BACKEND_URL}/api/map/shard?state=${STATE}
EOF

[[ "$FAILCT" == "0" ]]