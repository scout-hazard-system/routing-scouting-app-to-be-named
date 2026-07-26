#!/usr/bin/env bash
set -euo pipefail

CONFIG_FILE="${VEHICLE_STACK_CONFIG_FILE:-/home/gibi/Desktop/config/vehicle_stack.env}"
if [[ -f "$CONFIG_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$CONFIG_FILE"
fi

MAX_LOG_SIZE_MB="${MAX_LOG_SIZE_MB:-32}"
MAX_LOG_BACKUPS="${MAX_LOG_BACKUPS:-5}"
PIPELINE_LOG="${PIPELINE_LOG:-/tmp/pipeline_live_doordash.log}"
STACK_LOG_DIR="/tmp/vehicle_stack/logs"
BACKEND_LOG_FILE="$STACK_LOG_DIR/backend.log"
FRONTEND_LOG_FILE="$STACK_LOG_DIR/frontend.log"

rotate_log() {
  local file_path="$1"
  [[ -f "$file_path" ]] || return 0
  local max_bytes=$((MAX_LOG_SIZE_MB * 1024 * 1024))
  local size
  size="$(wc -c < "$file_path" | tr -d ' ')"
  if (( size < max_bytes )); then
    return 0
  fi
  for i in $(seq "$MAX_LOG_BACKUPS" -1 1); do
    [[ -f "${file_path}.${i}" ]] && mv "${file_path}.${i}" "${file_path}.$((i + 1))"
  done
  mv "$file_path" "${file_path}.1"
  : > "$file_path"
}

mkdir -p "$STACK_LOG_DIR"
rotate_log "$BACKEND_LOG_FILE"
rotate_log "$FRONTEND_LOG_FILE"
rotate_log "$PIPELINE_LOG"

echo "Log maintenance complete."
