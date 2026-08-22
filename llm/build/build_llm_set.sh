#!/usr/bin/env bash
# Build the proprietary "scout" LLM set from local Modelfiles.
# Requires: ollama running with the base model (llama3.1) pulled.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LLM_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OLLAMA_BIN="${OLLAMA_BIN:-ollama}"

# Highest complete local iterations present after reorg.
# Client may pin newer tags; missing tags fall back at runtime.
MODELS=(
  "scout-core1.0.5:core"
  "scout-rank:rank"
  "scout-vet1.0.6:vet"
  "scout-dev:dev"
)

find_modelfile() {
  local model="$1"
  local kind="$2"
  local candidate
  candidate="$LLM_ROOT/$kind/Modelfile.$model"
  if [[ -f "$candidate" ]]; then
    echo "$candidate"
    return 0
  fi
  # Fallback: search type folders
  candidate="$(find "$LLM_ROOT" -maxdepth 2 -type f -name "Modelfile.$model" | head -n 1 || true)"
  if [[ -n "$candidate" && -f "$candidate" ]]; then
    echo "$candidate"
    return 0
  fi
  return 1
}

if ! command -v "$OLLAMA_BIN" >/dev/null 2>&1; then
  echo "error: ollama binary not found (set OLLAMA_BIN)" >&2
  exit 1
fi

for entry in "${MODELS[@]}"; do
  model="${entry%%:*}"
  kind="${entry##*:}"
  if ! modelfile="$(find_modelfile "$model" "$kind")"; then
    echo "error: missing Modelfile for $model (expected under llm/$kind/)" >&2
    exit 1
  fi
  echo "== building $model from $modelfile =="
  "$OLLAMA_BIN" create "$model" -f "$modelfile"
done

echo "== installed scout models =="
"$OLLAMA_BIN" list | grep -E '^scout-' || true

if [[ "${SKIP_SMOKE:-0}" == "1" ]]; then
  echo "smoke tests skipped (SKIP_SMOKE=1)"
  exit 0
fi

CORE_MODEL="scout-core1.0.5"
VET_MODEL="scout-vet1.0.6"

echo "== smoke: $VET_MODEL alert task (enforcement transcript) =="
"$OLLAMA_BIN" run "$VET_MODEL" $'TASK: ALERT\nTranscript: Unit 23 copy, running radar on I-5 northbound at mile marker 212, vehicle stop in progress.'

echo "== smoke: $VET_MODEL alert task (benign transcript) =="
"$OLLAMA_BIN" run "$VET_MODEL" $'TASK: ALERT\nTranscript: Yeah just grabbing coffee, weather is nice today, see you back at the station later.'

echo "== smoke: $VET_MODEL intel task =="
"$OLLAMA_BIN" run "$VET_MODEL" $'TASK: INTEL\nTranscript: Unit 23 copy, 10-38 at the Shell gas station on Commercial Avenue, code 4.'

echo "== smoke: $CORE_MODEL nav task =="
"$OLLAMA_BIN" run "$CORE_MODEL" $'TASK: NAV\nRoute context: fastest route ETA 18 min, Waze hazards status ok, hazard cluster near I-5 exit 230.'

echo "== smoke: $CORE_MODEL chat task =="
"$OLLAMA_BIN" run "$CORE_MODEL" $'TASK: CHAT\nRoute context: origin 48.5126,-122.6127; destination 48.4982,-122.6361; alternatives=2; shortest_km=7.1; fastest_min=14; waze_hazards=ok; waze_route_mode=latlon.'

echo "== smoke: scout-rank =="
"$OLLAMA_BIN" run scout-rank '{"goal":"rank","location_context":{"city":"Anacortes","state":"WA","desired_types":["law","dispatch"]},"candidates":[{"id":"a","name":"Skagit County Law Dispatch","state":"WA"},{"id":"b","name":"Miami Fire Tac 3","state":"FL"}],"instructions":"Return ONLY compact JSON ranking."}'

echo "== smoke: $VET_MODEL vet task =="
"$OLLAMA_BIN" run "$VET_MODEL" $'TASK: VET\nTranscript: Unit 23 running radar on I-5 northbound near mile marker 212.\nProposed alert: ALERT: active radar enforcement on I-5 northbound near mile marker 212.'

echo "== scout LLM set build complete =="
