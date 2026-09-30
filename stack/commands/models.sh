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

# Print/verify the required Scout Ollama model roster on a host.
# Headless-friendly: no GUI, pure stdout, exits nonzero when a required tag
# is missing. The tags mirror scout_crew local_llms.ROLE_MODEL_PREFS on the
# Linux specialist side (Qwen3 lineage; Llama tags are NOT in the roster).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="${ROOT_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"

OLLAMA_URL="${OLLAMA_BASE_URL:-http://127.0.0.1:11434}"
VERIFY=0
MESH_SERVE_URL=""
for arg in "$@"; do
  case "$arg" in
    --verify) VERIFY=1 ;;
    --host=*)
      MESH_SERVE_URL="${arg#*=}"
      OLLAMA_URL="$MESH_SERVE_URL"
      ;;
    -h|--help)
      cat <<EOF
Usage: $(basename "$0") [--verify] [--host <ollama-url>]

Prints the required Scout model roster. With --verify, checks each required tag
against $OLLAMA_URL/api/tags and exits nonzero if any is missing.
EOF
      exit 0
      ;;
    *) echo "unknown argument: $arg" >&2; exit 1 ;;
  esac
done

REQUIRED=(
  scout-alert
  scout-intel
  scout-vet1.0.6
  scout-rank
  scout-core1.0.5
  scout-dev
  qwen3:8b
)
# The thinking brain is commonly hosted on a peer; optional here but listed.
OPTIONAL=(
  scout-hermes-hc1.1.0
  scout-hermes-hc1.0.0
)

echo "Scout model roster (Qwen3 lineage)"
echo "  Ollama: $OLLAMA_URL"
echo "  Required specialists: ${REQUIRED[*]}"
echo "  Optional (manager/hermes brain): ${OPTIONAL[*]}"
echo ""

if ((VERIFY)); then
  echo "Querying $OLLAMA_URL/api/tags ..."
  if ! curl -fsS "$OLLAMA_URL/api/tags" >/dev/null 2>&1; then
    echo "ERROR: Ollama not reachable at $OLLAMA_URL" >&2
    exit 1
  fi
  installed="$(curl -fsS "$OLLAMA_URL/api/tags" | grep -oE '"name":"[^"]+"' | sed 's/"name":"//;s/"//' || true)"
  missing=0
  for m in "${REQUIRED[@]}"; do
    if printf '%s\n' "$installed" | grep -qF "$m"; then
      echo "  [ok]   $m"
    else
      echo "  [MISS] $m"
      missing=1
    fi
  done
  for m in "${OPTIONAL[@]}"; do
    if printf '%s\n' "$installed" | grep -qF "$m"; then
      echo "  [ok]   $m (optional)"
    else
      echo "  [skip] $m (optional, not installed)"
    fi
  done
  if printf '%s\n' "$installed" | grep -qi llama; then
    echo "  [warn] Llama-family tag present (Qwen3-only policy; review)"
  fi
  echo ""
  if ((missing)); then
    echo "Some required models are missing. Build them from the llm/ Modelfiles:" >&2
    echo "  llm/build/build_llm_set.sh   (specialists)" >&2
    echo "  llm/unified/build_hermes_hc.sh (optional brain)" >&2
    exit 1
  fi
  echo "All required models present."
fi