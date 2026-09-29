#!/usr/bin/env bash
# Shared log rotation helpers sourced by run_vehicle_stack.sh and deployment/maintain_logs.sh.
# Expects MAX_LOG_SIZE_MB and MAX_LOG_BACKUPS to already be set in the caller's environment,
# plus BACKEND_LOG_FILE, FRONTEND_LOG_FILE and PIPELINE_LOG for rotate_logs().

rotate_one_log() {
  local file_path="$1"
  [[ -f "$file_path" ]] || return 0
  local max_bytes=$((MAX_LOG_SIZE_MB * 1024 * 1024))
  local size
  size="$(wc -c < "$file_path" | tr -d ' ')"
  if (( size < max_bytes )); then
    return 0
  fi
  for i in $(seq "$MAX_LOG_BACKUPS" -1 1); do
    if [[ -f "${file_path}.${i}" ]]; then
      mv "${file_path}.${i}" "${file_path}.$((i + 1))"
    fi
  done
  mv "$file_path" "${file_path}.1"
  : > "$file_path"
}

rotate_logs() {
  rotate_one_log "$BACKEND_LOG_FILE"
  rotate_one_log "$FRONTEND_LOG_FILE"
  rotate_one_log "$PIPELINE_LOG"
}
