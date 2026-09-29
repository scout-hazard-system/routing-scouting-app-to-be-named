#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="${ROOT_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
CONFIG_FILE="${VEHICLE_STACK_CONFIG_FILE:-$ROOT_DIR/stack/config/vehicle_stack.env}"
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

LOG_ROTATION_SH="$ROOT_DIR/stack/config/log_rotation.sh"
# shellcheck source=../config/log_rotation.sh
source "$LOG_ROTATION_SH"

mkdir -p "$STACK_LOG_DIR"
rotate_logs

echo "Log maintenance complete."
