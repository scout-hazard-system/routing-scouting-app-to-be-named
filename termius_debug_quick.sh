#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STACK_SCRIPT="$ROOT_DIR/start_termius_stack.sh"

BACKEND_LOG="/tmp/vehicle_stack/logs/backend.log"
FRONTEND_LOG="/tmp/vehicle_stack/logs/frontend.log"
PIPELINE_LOG="/tmp/pipeline_live_doordash.log"

DEVICE_TARGET="${1:-${ADB_SERIAL:-}}"

if [[ ! -x "$STACK_SCRIPT" ]]; then
  echo "Missing stack script: $STACK_SCRIPT" >&2
  exit 1
fi

echo "== Scout quick debug pass =="
echo
echo "[1/6] Stack status"
"$STACK_SCRIPT" status || true
echo
echo "[2/6] Stack health"
"$STACK_SCRIPT" health || true
echo
echo "[3/6] Backend signal scan"
if [[ -f "$BACKEND_LOG" ]]; then
  grep -nE "error|exception|request_rejected|timeout|invalid_|forbidden|denied" "$BACKEND_LOG" || true
else
  echo "backend log missing: $BACKEND_LOG"
fi
echo
echo "[4/6] Pipeline signal scan"
if [[ -f "$PIPELINE_LOG" ]]; then
  grep -nE "ALERT|VET_|INTEL|EVENT_JSON|llm_|soft_alert|alert_triggered" "$PIPELINE_LOG" || true
else
  echo "pipeline log missing: $PIPELINE_LOG"
fi
echo
echo "[5/6] Recent log tails"
if [[ -f "$BACKEND_LOG" ]]; then
  echo "-- backend tail --"
  tail -n 80 "$BACKEND_LOG" || true
fi
if [[ -f "$FRONTEND_LOG" ]]; then
  echo "-- frontend tail --"
  tail -n 40 "$FRONTEND_LOG" || true
fi
if [[ -f "$PIPELINE_LOG" ]]; then
  echo "-- pipeline tail --"
  tail -n 80 "$PIPELINE_LOG" || true
fi
echo
echo "[6/6] Optional device runtime check"
if [[ -z "$DEVICE_TARGET" ]]; then
  echo "No device target provided. To include Android logcat checks:"
  echo "  $0 <device-ip:port>"
  echo "or set ADB_SERIAL then rerun."
else
  if adb -s "$DEVICE_TARGET" get-state >/dev/null 2>&1; then
    adb -s "$DEVICE_TARGET" logcat -c || true
    adb -s "$DEVICE_TARGET" shell am start -n dev.warp.stream/.MainActivity >/dev/null 2>&1 || true
    adb -s "$DEVICE_TARGET" logcat -d -v brief MainActivity:I AndroidRuntime:E ActivityTaskManager:I "*:S" || true
  else
    echo "ADB target not reachable: $DEVICE_TARGET"
  fi
fi

echo
echo "== Quick debug pass complete =="
