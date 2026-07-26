#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="/home/gibi/Desktop"
FRONTEND_DIR="$ROOT_DIR/frontend"
BACKEND_DIR="$FRONTEND_DIR/java_backend"
PIPELINE_SCRIPT="$ROOT_DIR/pipeline.py"

RUNTIME_DIR="/tmp/vehicle_stack"
LOG_DIR="$RUNTIME_DIR/logs"
PID_DIR="$RUNTIME_DIR/pids"
mkdir -p "$LOG_DIR" "$PID_DIR"

PIPELINE_LOG="${PIPELINE_LOG:-/tmp/pipeline_live_doordash.log}"
BACKEND_PORT="${BACKEND_PORT:-18080}"
FRONTEND_PORT="${FRONTEND_PORT:-8787}"

BACKEND_PID_FILE="$PID_DIR/backend.pid"
FRONTEND_PID_FILE="$PID_DIR/frontend.pid"
PIPELINE_PID_FILE="$PID_DIR/pipeline.pid"

BACKEND_LOG_FILE="$LOG_DIR/backend.log"
FRONTEND_LOG_FILE="$LOG_DIR/frontend.log"
port_in_use() {
  local port="$1"
  ss -ltn "sport = :$port" | grep -q LISTEN
}

wait_for_http() {
  local url="$1"
  local attempts="${2:-20}"
  local delay="${3:-0.25}"
  for _ in $(seq 1 "$attempts"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep "$delay"
  done
  return 1
}

is_running() {
  local pid_file="$1"
  [[ -f "$pid_file" ]] || return 1
  local pid
  pid="$(cat "$pid_file" 2>/dev/null || true)"
  [[ -n "${pid}" ]] || return 1
  kill -0 "$pid" 2>/dev/null
}

start_backend() {
  if is_running "$BACKEND_PID_FILE"; then
    return
  fi
  if port_in_use "$BACKEND_PORT"; then
    printf "Backend port %s already in use. Set BACKEND_PORT or stop existing service.\n" "$BACKEND_PORT" >&2
    exit 1
  fi
  if [[ -x "$BACKEND_DIR/build_executable.sh" ]]; then
    "$BACKEND_DIR/build_executable.sh" >/dev/null
  else
    (cd "$BACKEND_DIR" && javac ScannerBackendServer.java && printf 'Main-Class: ScannerBackendServer\n' > /tmp/vehicle_stack_manifest.mf && jar cfm dist/scanner-backend-lite.jar /tmp/vehicle_stack_manifest.mf ScannerBackendServer*.class)
  fi
  nohup env PIPELINE_LOG_PATH="$PIPELINE_LOG" JAVA_BACKEND_HOST="0.0.0.0" JAVA_BACKEND_PORT="$BACKEND_PORT" \
    java -jar "$BACKEND_DIR/dist/scanner-backend-lite.jar" >"$BACKEND_LOG_FILE" 2>&1 &
  echo $! >"$BACKEND_PID_FILE"
  if ! wait_for_http "http://127.0.0.1:${BACKEND_PORT}/api/health" 30 0.25; then
    printf "Backend failed to start cleanly. Check %s\n" "$BACKEND_LOG_FILE" >&2
    exit 1
  fi
}

start_frontend() {
  if is_running "$FRONTEND_PID_FILE"; then
    return
  fi
  if port_in_use "$FRONTEND_PORT"; then
    printf "Frontend port %s already in use. Set FRONTEND_PORT or stop existing service.\n" "$FRONTEND_PORT" >&2
    exit 1
  fi
  nohup env FRONTEND_DEV_PORT="$FRONTEND_PORT" PIPELINE_LOG_PATH="$PIPELINE_LOG" \
    python3 "$FRONTEND_DIR/dev_server.py" >"$FRONTEND_LOG_FILE" 2>&1 &
  echo $! >"$FRONTEND_PID_FILE"
  if ! wait_for_http "http://127.0.0.1:${FRONTEND_PORT}/index.html" 30 0.25; then
    printf "Frontend failed to start cleanly. Check %s\n" "$FRONTEND_LOG_FILE" >&2
    exit 1
  fi
}

start_pipeline() {
  if [[ ! -f "$PIPELINE_SCRIPT" ]]; then
    return
  fi
  if is_running "$PIPELINE_PID_FILE"; then
    return
  fi
  nohup python3 "$PIPELINE_SCRIPT" --integration-json --soft-alert-fallback >>"$PIPELINE_LOG" 2>&1 &
  echo $! >"$PIPELINE_PID_FILE"
}

stop_component() {
  local pid_file="$1"
  if ! is_running "$pid_file"; then
    rm -f "$pid_file"
    return
  fi
  local pid
  pid="$(cat "$pid_file")"
  kill "$pid" 2>/dev/null || true
  sleep 0.5
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$pid_file"
}

status_component() {
  local name="$1"
  local pid_file="$2"
  if is_running "$pid_file"; then
    printf "%s: running (pid=%s)\n" "$name" "$(cat "$pid_file")"
  else
    printf "%s: stopped\n" "$name"
  fi
}

start_all() {
  start_backend
  start_frontend
  start_pipeline
  printf "Vehicle stack started.\n"
  printf "UI: http://127.0.0.1:%s\n" "$FRONTEND_PORT"
  printf "Backend health: http://127.0.0.1:%s/api/health\n" "$BACKEND_PORT"
  printf "Logs: %s and %s (pipeline: %s)\n" "$BACKEND_LOG_FILE" "$FRONTEND_LOG_FILE" "$PIPELINE_LOG"
}

stop_all() {
  stop_component "$PIPELINE_PID_FILE"
  stop_component "$FRONTEND_PID_FILE"
  stop_component "$BACKEND_PID_FILE"
  printf "Vehicle stack stopped.\n"
}

status_all() {
  status_component "backend" "$BACKEND_PID_FILE"
  status_component "frontend" "$FRONTEND_PID_FILE"
  status_component "pipeline" "$PIPELINE_PID_FILE"
}

CMD="${1:-start}"
case "$CMD" in
  start) start_all ;;
  stop) stop_all ;;
  restart) stop_all; start_all ;;
  status) status_all ;;
  *)
    echo "Usage: $0 {start|stop|restart|status}"
    exit 1
    ;;
esac
