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

# End-to-end health check for a headless Debian trixie Scout agent box.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

ROOT=""
if ROOT="$(resolve_repo_root)"; then
  ENV_FILE="$ROOT/stack/config/vehicle_stack.env"
  if [[ -f "$ENV_FILE" ]]; then
    # shellcheck disable=SC1090
    source "$ENV_FILE"
  fi
else
  ROOT=""
fi

HUB_IP="${SCOUT_MESH_HUB_ADDRESS:-10.66.2.3}"
BB_URL="${SCOUT_BLACKBOARD_URL:-http://$HUB_IP:8765}"
BACKEND_URL="${SCOUT_MAP_BASE_URL:-http://$HUB_IP:18080}"
OLLAMA_URL="${OLLAMA_BASE_URL:-http://127.0.0.1:11434}"

pass=0
fail=0
check() {
  local name="$1" result="$2"
  if [[ "$result" == "0" ]]; then
    ok "$name"
    pass=$((pass + 1))
  else
    warn "FAIL: $name"
    fail=$((fail + 1))
  fi
}

echo "== OS / headless profile =="
if [[ -f /etc/os-release ]] && grep -qE 'ID=debian' /etc/os-release && grep -qE 'VERSION_CODENAME=trixie' /etc/os-release; then
  check "Debian trixie detected" 0
else
  check "Debian trixie detected" 1
fi
if [[ -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]]; then
  check "No DISPLAY (headless)" 0
else
  warn "DISPLAY=${DISPLAY:-} (set). This is a headless suite; the GUI is out of scope but won't break services."
  check "No DISPLAY (headless)" 0
fi

echo "== Mesh (scoutwg0 split tunnel) =="
if ip -o addr show scoutwg0 >/dev/null 2>&1; then
  check "scoutwg0 interface present" 0
  echo "  $(ip -o -4 addr show scoutwg0 | awk '{print $4}')"
else
  check "scoutwg0 interface present" 1
fi
if ip route get 1.1.1.1 >/dev/null 2>&1; then
  check "IPv4 default route (Internet intact on mesh)" 0
else
  check "IPv4 default route (Internet intact on mesh)" 1
  warn "No IPv4 default route -> git/Kepler/warp will fail with 'network is unreachable'."
  warn "Run the fix the mesh script printed (ip route add default via <lan-gw>)."
fi

echo "== Ollama =="
if curl -fsS "$OLLAMA_URL/api/version" >/dev/null 2>&1; then
  check "Ollama API up ($OLLAMA_URL)" 0
  echo "  $(curl -fsS "$OLLAMA_URL/api/version" 2>/dev/null || true)"
  req=(scout-vet1.0.6 scout-rank scout-alert scout-intel scout-core1.0.5 scout-dev qwen3:8b)
  have="$(curl -fsS "$OLLAMA_URL/api/tags" 2>/dev/null | grep -oE '"name":"[^"]+"' | sed 's/"name":"//;s/"//' || true)"
  for m in "${req[@]}"; do
    if printf '%s\n' "$have" | grep -qF "$m"; then
      check "model: $m" 0
    else
      check "model: $m (NOT installed - run install_ollama_headless.sh)" 1
    fi
  done
  if printf '%s\n' "$have" | grep -qi llama; then
    warn "Llama-family tags present (Qwen3-only policy; review)."
  fi
else
  check "Ollama API up ($OLLAMA_URL)" 1
fi

echo "== Backend / frontend / blackboard =="
if curl -fsS "$BACKEND_URL/api/health" >/dev/null 2>&1; then
  check "backend /api/health ($BACKEND_URL)" 0
else
  check "backend /api/health ($BACKEND_URL)" 1
fi
if curl -fsS -o /dev/null "$BACKEND_URL/api/map/shard?state=AZ" >/dev/null 2>&1; then
  check "map shard AZ prefetch" 0
else
  check "map shard AZ prefetch" 1
fi
if curl -fsS "$BB_URL/health" >/dev/null 2>&1; then
  check "blackboard /health ($BB_URL)" 0
else
  check "blackboard /health ($BB_URL)" 1
fi

echo "== Crew CLI =="
for bin in "$HOME/.scout/venv/bin/scout" "$HOME/scout_crew/.venv/bin/scout"; do
  if [[ -x "$bin" ]]; then
    check "scout CLI present ($bin)" 0
    "$bin" status 2>/dev/null | head -n 25 || true
    break
  fi
done

echo ""
if ((fail == 0)); then
  ok "verify_agent_box: ALL CHECKS PASSED ($pass/$((pass + fail)))"
else
  warn "verify_agent_box: $fail check(s) FAILED, $pass passed."
  exit 1
fi