#!/usr/bin/env bash
# Build the proprietary "scout" LLM set from local Modelfiles.
# Requires: ollama running with the base model (llama3.1) pulled.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OLLAMA_BIN="${OLLAMA_BIN:-ollama}"
MODELS=(scout-core1.0.3 scout-rank scout-vet1.0.4)

if ! command -v "$OLLAMA_BIN" >/dev/null 2>&1; then
  echo "error: ollama binary not found (set OLLAMA_BIN)" >&2
  exit 1
fi

for model in "${MODELS[@]}"; do
  modelfile="$SCRIPT_DIR/Modelfile.$model"
  if [[ ! -f "$modelfile" ]]; then
    echo "error: missing $modelfile" >&2
    exit 1
  fi
  echo "== building $model =="
  "$OLLAMA_BIN" create "$model" -f "$modelfile"
done

echo "== installed scout models =="
"$OLLAMA_BIN" list | grep -E '^scout-' || true

if [[ "${SKIP_SMOKE:-0}" == "1" ]]; then
  echo "smoke tests skipped (SKIP_SMOKE=1)"
  exit 0
fi

echo "== smoke: scout-core1.0.3 alert task (enforcement transcript) =="
"$OLLAMA_BIN" run scout-core1.0.3 $'TASK: ALERT\nTranscript: Unit 23 copy, running radar on I-5 northbound at mile marker 212, vehicle stop in progress.'

echo "== smoke: scout-core1.0.3 alert task (benign transcript) =="
"$OLLAMA_BIN" run scout-core1.0.3 $'TASK: ALERT\nTranscript: Yeah just grabbing coffee, weather is nice today, see you back at the station later.'

echo "== smoke: scout-core1.0.3 intel task =="
"$OLLAMA_BIN" run scout-core1.0.3 $'TASK: INTEL\nTranscript: Unit 23 copy, 10-38 at the Shell gas station on Commercial Avenue, code 4.'

echo "== smoke: scout-core1.0.3 nav task =="
"$OLLAMA_BIN" run scout-core1.0.3 $'TASK: NAV\nTranscript: Unit 12 running radar on Highway 20 eastbound near mile marker 82.'

echo "== smoke: scout-rank =="
"$OLLAMA_BIN" run scout-rank '{"goal":"rank","location_context":{"city":"Anacortes","state":"WA","desired_types":["law","dispatch"]},"candidates":[{"id":"a","name":"Skagit County Law Dispatch","state":"WA"},{"id":"b","name":"Miami Fire Tac 3","state":"FL"}],"instructions":"Return ONLY compact JSON ranking."}'

echo "== smoke: scout-vet1.0.4 =="
"$OLLAMA_BIN" run scout-vet1.0.4 $'TASK: VET\nTranscript: Unit 23 running radar on I-5 northbound near mile marker 212.\nProposed alert: ALERT: active radar enforcement on I-5 northbound near mile marker 212.'

echo "== scout LLM set build complete =="
