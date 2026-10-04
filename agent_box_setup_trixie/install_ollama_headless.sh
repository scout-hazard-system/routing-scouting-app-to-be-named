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

# Install Ollama on a headless Debian trixie box and build the Scout model set
# (Qwen3-lineage specialists + the unified Hermes reasoning brain). Ollama binds
# 127.0.0.1:11434 by default (specialists run on this box). If this box must
# serve the manager/hermes over the Scout Mesh, pass --mesh-serve later.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

SUDO=""
if ((EUID != 0)); then
  require_cmd sudo
  SUDO="sudo"
fi

OLLAMA_PORT="11434"
BUILD_HERMES=0
LLM_ROOT=""
if ! ROOT="$(resolve_repo_root)"; then
  warn "Could not resolve repo root; model Modelfiles will only be used if found under SCOUT_LLM_ROOT."
  ROOT=""
fi

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Options:
  --with-hermes   also build scout-hermes-hc* Modelfiles (thinking brain;
                  big context, needs >=16GB RAM). Specialists are always built.
  --mesh-serve    bind Ollama on 0.0.0.0:$OLLAMA_PORT so mesh peers can use it
                  (specialists stay local unless role OLLAMA_HOST_* routes them)
  --help          show this help

Env:
  SCOUT_LLM_ROOT  where llm/ lives for Modelfiles + build scripts
                  (default: <repo-root>/llm if present)
EOF
}

OPT_MESH_SERVE=0
ARGS=("$@")
i=0
while ((i < ${#ARGS[@]})); do
  case "${ARGS[$i]}" in
    --with-hermes) BUILD_HERMES=1; i=$((i + 1)) ;;
    --mesh-serve)  OPT_MESH_SERVE=1; i=$((i + 1)) ;;
    -h|--help)     usage; exit 0 ;;
    *) fail "unknown argument: ${ARGS[$i]} (see --help)" ;;
  esac
done

LLM_ROOT="${SCOUT_LLM_ROOT:-}"
if [[ -z "$LLM_ROOT" && -n "$ROOT" && -d "$ROOT/llm" ]]; then
  LLM_ROOT="$ROOT/llm"
fi

require_cmd curl
if command -v ollama >/dev/null 2>&1; then
  ok "ollama already installed: $(ollama --version 2>/dev/null | head -n1 || echo "(version unknown)")"
else
  info "Installing Ollama via official installer (headless)..."
  if [[ -n "$SUDO" ]]; then
    curl -fsSL https://ollama.com/install.sh | ${SUDO} sh
  else
    curl -fsSL https://ollama.com/install.sh | sh
  fi
fi

if ((OPT_MESH_SERVE)); then
  info "Setting OLLAMA_HOST=0.0.0.0:$OLLAMA_PORT (mesh serving)..."
  if command -v systemctl >/dev/null 2>&1 && systemctl list-unit-files ollama.service >/dev/null 2>&1; then
    ${SUDO} mkdir -p /etc/systemd/system/ollama.service.d
    ${SUDO} tee /etc/systemd/system/ollama.service.d/override.conf >/dev/null <<EOF
[Service]
Environment="OLLAMA_HOST=0.0.0.0:$OLLAMA_PORT"
EOF
    ${SUDO} systemctl daemon-reload
  else
    warn "No ollama systemd unit found; set OLLAMA_HOST=0.0.0.0:$OLLAMA_PORT yourself."
  fi
fi

if command -v systemctl >/dev/null 2>&1; then
  ${SUDO} systemctl enable --now ollama >/dev/null 2>&1 || warn "could not enable ollama.service (start it manually: ollama serve)"
fi

info "Waiting for Ollama API at 127.0.0.1:$OLLAMA_PORT..."
up=0
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:$OLLAMA_PORT/api/version" >/dev/null 2>&1; then
    up=1
    break
  fi
  sleep 1
done
if ((!up)); then
  fail "Ollama did not come up on 127.0.0.1:$OLLAMA_PORT; check: journalctl -u ollama -n 50"
fi
ok "Ollama API up on 127.0.0.1:$OLLAMA_PORT"

info "Pulling base weights qwen3:8b (Qwen3 lineage; no Llama)..."
ollama pull qwen3:8b

if [[ -n "$LLM_ROOT" ]]; then
  if [[ -x "$LLM_ROOT/build/build_llm_set.sh" ]]; then
    info "Building specialist Modelfiles with build_llm_set.sh..."
    (
      cd "$LLM_ROOT/build"
      ./build_llm_set.sh
    ) || warn "build_llm_set.sh reported errors (see above). Continuing."
  else
    warn "No build_llm_set.sh under $LLM_ROOT; specialist tags must be built manually (see llm/build/README.go if present)."
  fi

  if ((BUILD_HERMES)); then
    if [[ -x "$LLM_ROOT/unified/build_hermes_hc.sh" ]]; then
      info "Building unified Hermes brain (scout-hermes-hc*; thinking enabled; big context)..."
      (
        cd "$LLM_ROOT/unified"
        ./build_hermes_hc.sh
      ) || warn "build_hermes_hc.sh reported errors (see above)."
    else
      warn "No build_hermes_hc.sh under $LLM_ROOT; skipping Hermes brain."
    fi
  else
    info "Skipping Hermes brain (pass --with-hermes to build scout-hermes-hc*)."
  fi
else
  warn "No llm/ tree found (SCOUT_LLM_ROOT unset); built only qwen3:8b. Build Modelfiles from the scout llm tree manually."
fi

echo ""
info "Installed model roster:"
ollama list

echo ""
ok "Ollama headless install complete."
cat <<EOF
Required specialist tags (pipeline + crew):
  scout-alert scout-intel scout-vet1.0.6 scout-rank scout-core1.0.5 scout-dev
Manager/hermes brain (optional here; often peers on another host):
  scout-hermes-hc1.1.0 / scout-hermes-hc1.0.0
EOF