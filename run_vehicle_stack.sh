#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="/home/gibi/Desktop"
FRONTEND_DIR="$ROOT_DIR/frontend"
BACKEND_DIR="$FRONTEND_DIR/java_backend"
PIPELINE_SCRIPT="$ROOT_DIR/pipeline.py"
CONFIG_FILE="${VEHICLE_STACK_CONFIG_FILE:-$ROOT_DIR/config/vehicle_stack.env}"
DEFAULT_PYTHON_BIN="$ROOT_DIR/cop_pipeline/bin/python3"
<<<<<<< HEAD
=======
FIXED_BACKEND_PORT="18080"
FIXED_FRONTEND_PORT="8787"
>>>>>>> feature/integrate-waze-and-service-hardening

RUNTIME_DIR="/tmp/vehicle_stack"
LOG_DIR="$RUNTIME_DIR/logs"
PID_DIR="$RUNTIME_DIR/pids"
mkdir -p "$LOG_DIR" "$PID_DIR"

PIPELINE_LOG="${PIPELINE_LOG:-/tmp/pipeline_live_doordash.log}"
<<<<<<< HEAD
BACKEND_PORT="${BACKEND_PORT:-18080}"
FRONTEND_PORT="${FRONTEND_PORT:-8787}"
MAX_LOG_SIZE_MB="${MAX_LOG_SIZE_MB:-32}"
MAX_LOG_BACKUPS="${MAX_LOG_BACKUPS:-5}"
ENABLE_PIPELINE_AUTOSTART="${ENABLE_PIPELINE_AUTOSTART:-true}"
JAVA_BACKEND_HOST="${JAVA_BACKEND_HOST:-0.0.0.0}"
PYTHON_BIN="${PYTHON_BIN:-$DEFAULT_PYTHON_BIN}"
PIPELINE_MODE="${PIPELINE_MODE:-direct}"
PIPELINE_EXTRA_ARGS="${PIPELINE_EXTRA_ARGS:-}"
FRONTEND_EXECUTABLE_PATH="${FRONTEND_EXECUTABLE_PATH:-$FRONTEND_DIR/dist/scanner-frontend-lite/scanner-frontend-lite}"
FRONTEND_BUILD_ON_START="${FRONTEND_BUILD_ON_START:-true}"
=======
BACKEND_PORT="${BACKEND_PORT:-$FIXED_BACKEND_PORT}"
FRONTEND_PORT="${FRONTEND_PORT:-$FIXED_FRONTEND_PORT}"
MAX_LOG_SIZE_MB="${MAX_LOG_SIZE_MB:-32}"
MAX_LOG_BACKUPS="${MAX_LOG_BACKUPS:-5}"
STOP_TIMEOUT_SECONDS="${STOP_TIMEOUT_SECONDS:-20}"
DOCKER_STOP_TIMEOUT_SECONDS="${DOCKER_STOP_TIMEOUT_SECONDS:-45}"
ENABLE_PIPELINE_AUTOSTART="${ENABLE_PIPELINE_AUTOSTART:-true}"
JAVA_BACKEND_HOST="${JAVA_BACKEND_HOST:-0.0.0.0}"
PYTHON_BIN="${PYTHON_BIN:-$DEFAULT_PYTHON_BIN}"
PIPELINE_MODE="${PIPELINE_MODE:-broadcastify}"
PIPELINE_EXTRA_ARGS="${PIPELINE_EXTRA_ARGS:-}"
FRONTEND_EXECUTABLE_PATH="${FRONTEND_EXECUTABLE_PATH:-$FRONTEND_DIR/dist/scanner-frontend-lite/scanner-frontend-lite}"
FRONTEND_BUILD_ON_START="${FRONTEND_BUILD_ON_START:-true}"
USE_DOCKER_SERVERS="${USE_DOCKER_SERVERS:-false}"
DOCKER_COMPOSE_FILE="${DOCKER_COMPOSE_FILE:-$ROOT_DIR/docker-compose.server.yml}"
DOCKER_SERVICES=(backend frontend)
>>>>>>> feature/integrate-waze-and-service-hardening
if [[ ! -x "$PYTHON_BIN" ]]; then
  PYTHON_BIN="python3"
fi

BACKEND_PID_FILE="$PID_DIR/backend.pid"
FRONTEND_PID_FILE="$PID_DIR/frontend.pid"
PIPELINE_PID_FILE="$PID_DIR/pipeline.pid"

BACKEND_LOG_FILE="$LOG_DIR/backend.log"
FRONTEND_LOG_FILE="$LOG_DIR/frontend.log"
if [[ -f "$CONFIG_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$CONFIG_FILE"
fi
<<<<<<< HEAD
=======
# Always pin stack ports for stable Termius/mobile workflows.
BACKEND_PORT="$FIXED_BACKEND_PORT"
FRONTEND_PORT="$FIXED_FRONTEND_PORT"
docker_compose_available() {
  command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1
}

ensure_no_local_server_pids() {
  if is_running "$BACKEND_PID_FILE" || is_running "$FRONTEND_PID_FILE"; then
    printf "Local backend/frontend processes are running. Stop the stack first before enabling Docker server mode.\n" >&2
    exit 1
  fi
}
prepare_docker_pipeline_log_env() {
  local pipeline_log_abs="$PIPELINE_LOG"
  if [[ "$pipeline_log_abs" != /* ]]; then
    pipeline_log_abs="$ROOT_DIR/$pipeline_log_abs"
  fi
  DOCKER_PIPELINE_LOG_DIR="$(dirname "$pipeline_log_abs")"
  DOCKER_PIPELINE_LOG_FILE="$(basename "$pipeline_log_abs")"
  mkdir -p "$DOCKER_PIPELINE_LOG_DIR"
}

start_docker_servers() {
  validate_docker_server_mode
  DOCKER_PIPELINE_LOG_DIR="$DOCKER_PIPELINE_LOG_DIR" DOCKER_PIPELINE_LOG_FILE="$DOCKER_PIPELINE_LOG_FILE" \
    docker compose -f "$DOCKER_COMPOSE_FILE" up -d --build "${DOCKER_SERVICES[@]}"
  if ! wait_for_http "http://127.0.0.1:${BACKEND_PORT}/api/health" 60 0.5; then
    printf "Docker backend failed health check. Check docker compose logs.\n" >&2
    exit 1
  fi
  if ! wait_for_http "http://127.0.0.1:${FRONTEND_PORT}/index.html" 60 0.5; then
    printf "Docker frontend failed readiness check. Check docker compose logs.\n" >&2
    exit 1
  fi
}

validate_docker_server_mode() {
  if ! docker_compose_available; then
    printf "Docker Compose is required for USE_DOCKER_SERVERS=true but is not available.\n" >&2
    exit 1
  fi
  if ! docker info >/dev/null 2>&1; then
    printf "Docker daemon is not accessible by current user. Ensure your user is in the docker group and start a new login session.\n" >&2
    exit 1
  fi
  if [[ ! -f "$DOCKER_COMPOSE_FILE" ]]; then
    printf "Docker compose file not found: %s\n" "$DOCKER_COMPOSE_FILE" >&2
    exit 1
  fi
  prepare_docker_pipeline_log_env
  if ! DOCKER_PIPELINE_LOG_DIR="$DOCKER_PIPELINE_LOG_DIR" DOCKER_PIPELINE_LOG_FILE="$DOCKER_PIPELINE_LOG_FILE" \
    docker compose -f "$DOCKER_COMPOSE_FILE" config -q >/dev/null; then
    printf "Docker compose configuration failed validation for %s.\n" "$DOCKER_COMPOSE_FILE" >&2
    exit 1
  fi
  ensure_no_local_server_pids
}

stop_docker_servers() {
  if ! docker_compose_available; then
    return
  fi
  if [[ ! -f "$DOCKER_COMPOSE_FILE" ]]; then
    return
  fi
  prepare_docker_pipeline_log_env
  DOCKER_PIPELINE_LOG_DIR="$DOCKER_PIPELINE_LOG_DIR" DOCKER_PIPELINE_LOG_FILE="$DOCKER_PIPELINE_LOG_FILE" \
    docker compose -f "$DOCKER_COMPOSE_FILE" stop -t "$DOCKER_STOP_TIMEOUT_SECONDS" "${DOCKER_SERVICES[@]}" >/dev/null 2>&1 || true
}

status_docker_servers() {
  if ! docker_compose_available; then
    printf "docker-services: unavailable (docker compose missing)\n"
    return
  fi
  if [[ ! -f "$DOCKER_COMPOSE_FILE" ]]; then
    printf "docker-services: unavailable (compose file missing: %s)\n" "$DOCKER_COMPOSE_FILE"
    return
  fi
  prepare_docker_pipeline_log_env
  DOCKER_PIPELINE_LOG_DIR="$DOCKER_PIPELINE_LOG_DIR" DOCKER_PIPELINE_LOG_FILE="$DOCKER_PIPELINE_LOG_FILE" \
    docker compose -f "$DOCKER_COMPOSE_FILE" ps "${DOCKER_SERVICES[@]}" \
    || printf "docker-services: unavailable (current user cannot access docker daemon)\n"
}
>>>>>>> feature/integrate-waze-and-service-hardening

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

<<<<<<< HEAD
=======
parse_shell_words() {
  local raw="$1"
  local parser_bin="$PYTHON_BIN"
  if [[ -z "$parser_bin" || ! -x "$parser_bin" ]]; then
    parser_bin="python3"
  fi
  "$parser_bin" - "$raw" <<'PY'
import shlex
import sys

raw = sys.argv[1]
for token in shlex.split(raw):
    print(token)
PY
}

>>>>>>> feature/integrate-waze-and-service-hardening
rotate_logs() {
  rotate_one_log "$BACKEND_LOG_FILE"
  rotate_one_log "$FRONTEND_LOG_FILE"
  rotate_one_log "$PIPELINE_LOG"
}
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
  rotate_logs
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
<<<<<<< HEAD
    (cd "$BACKEND_DIR" && javac ScannerBackendServer.java && printf 'Main-Class: ScannerBackendServer\n' > /tmp/vehicle_stack_manifest.mf && jar cfm dist/scanner-backend-lite.jar /tmp/vehicle_stack_manifest.mf ScannerBackendServer*.class)
=======
    (cd "$BACKEND_DIR" && javac BackendServer.java && printf 'Main-Class: BackendServer\n' > /tmp/vehicle_stack_manifest.mf && jar cfm dist/backend-lite.jar /tmp/vehicle_stack_manifest.mf BackendServer*.class)
>>>>>>> feature/integrate-waze-and-service-hardening
  fi
  nohup env \
    PIPELINE_LOG_PATH="$PIPELINE_LOG" \
    JAVA_BACKEND_HOST="$JAVA_BACKEND_HOST" \
    JAVA_BACKEND_PORT="$BACKEND_PORT" \
    SELECTOR_PYTHON_BIN="${SELECTOR_PYTHON_BIN:-$PYTHON_BIN}" \
    SELECTOR_SCRIPT_PATH="${SELECTOR_SCRIPT_PATH:-$ROOT_DIR/channel_selector.py}" \
<<<<<<< HEAD
=======
    BROADCASTIFY_CATALOG_SCRIPT_PATH="${BROADCASTIFY_CATALOG_SCRIPT_PATH:-$ROOT_DIR/broadcastify_catalog_service.py}" \
>>>>>>> feature/integrate-waze-and-service-hardening
    BROADCASTIFY_CHANNELS_FILE="${BROADCASTIFY_CHANNELS_FILE:-$ROOT_DIR/config/broadcastify_channels.sample.json}" \
    BROADCASTIFY_SELECTOR_CITY="${BROADCASTIFY_SELECTOR_CITY:-Sample City}" \
    BROADCASTIFY_SELECTOR_COUNTY="${BROADCASTIFY_SELECTOR_COUNTY:-Sample County}" \
    BROADCASTIFY_SELECTOR_STATE="${BROADCASTIFY_SELECTOR_STATE:-Sample State}" \
    BROADCASTIFY_SELECTOR_DESIRED_TYPES="${BROADCASTIFY_SELECTOR_DESIRED_TYPES:-law,dispatch}" \
    BROADCASTIFY_SELECTOR_TOP_K="${BROADCASTIFY_SELECTOR_TOP_K:-8}" \
    BROADCASTIFY_SELECTOR_PRINT_TOP="${BROADCASTIFY_SELECTOR_PRINT_TOP:-3}" \
<<<<<<< HEAD
    BROADCASTIFY_SELECTOR_USE_OLLAMA_RERANK="${BROADCASTIFY_SELECTOR_USE_OLLAMA_RERANK:-false}" \
    BROADCASTIFY_SELECTOR_OLLAMA_MODEL="${BROADCASTIFY_SELECTOR_OLLAMA_MODEL:-llama3.1}" \
    BROADCASTIFY_SELECTOR_OLLAMA_URL="${BROADCASTIFY_SELECTOR_OLLAMA_URL:-http://localhost:11434/api/generate}" \
    BROADCASTIFY_SELECTOR_OLLAMA_TIMEOUT="${BROADCASTIFY_SELECTOR_OLLAMA_TIMEOUT:-8.0}" \
    BROADCASTIFY_SELECTOR_OLLAMA_WEIGHT="${BROADCASTIFY_SELECTOR_OLLAMA_WEIGHT:-0.2}" \
    java -jar "$BACKEND_DIR/dist/scanner-backend-lite.jar" >"$BACKEND_LOG_FILE" 2>&1 &
=======
    BROADCASTIFY_SELECTOR_USE_OLLAMA_RERANK="${BROADCASTIFY_SELECTOR_USE_OLLAMA_RERANK:-true}" \
    BROADCASTIFY_SELECTOR_OLLAMA_MODEL="${BROADCASTIFY_SELECTOR_OLLAMA_MODEL:-scout-rank}" \
    BROADCASTIFY_SELECTOR_OLLAMA_URL="${BROADCASTIFY_SELECTOR_OLLAMA_URL:-http://localhost:11434/api/generate}" \
    BROADCASTIFY_SELECTOR_OLLAMA_TIMEOUT="${BROADCASTIFY_SELECTOR_OLLAMA_TIMEOUT:-8.0}" \
    BROADCASTIFY_SELECTOR_OLLAMA_WEIGHT="${BROADCASTIFY_SELECTOR_OLLAMA_WEIGHT:-0.2}" \
    BACKEND_HELPER_TIMEOUT_SECONDS="${BACKEND_HELPER_TIMEOUT_SECONDS:-90}" \
    java -jar "$BACKEND_DIR/dist/backend-lite.jar" >"$BACKEND_LOG_FILE" 2>&1 &
>>>>>>> feature/integrate-waze-and-service-hardening
  echo $! >"$BACKEND_PID_FILE"
  if ! wait_for_http "http://127.0.0.1:${BACKEND_PORT}/api/health" 30 0.25; then
    printf "Backend failed to start cleanly. Check %s\n" "$BACKEND_LOG_FILE" >&2
    exit 1
  fi
}

start_frontend() {
  rotate_logs
  if is_running "$FRONTEND_PID_FILE"; then
    return
  fi
  if port_in_use "$FRONTEND_PORT"; then
    printf "Frontend port %s already in use. Set FRONTEND_PORT or stop existing service.\n" "$FRONTEND_PORT" >&2
    exit 1
  fi
  local frontend_cmd
  if [[ ! -x "$FRONTEND_EXECUTABLE_PATH" && "$FRONTEND_BUILD_ON_START" == "true" && -x "$FRONTEND_DIR/build_executable.sh" ]]; then
    "$FRONTEND_DIR/build_executable.sh" >/dev/null
  fi
  if [[ -x "$FRONTEND_EXECUTABLE_PATH" ]]; then
    frontend_cmd="$FRONTEND_EXECUTABLE_PATH"
  else
    frontend_cmd="$PYTHON_BIN $FRONTEND_DIR/dev_server.py"
  fi
  nohup env FRONTEND_DEV_PORT="$FRONTEND_PORT" PIPELINE_LOG_PATH="$PIPELINE_LOG" \
    bash -lc "$frontend_cmd" >"$FRONTEND_LOG_FILE" 2>&1 &
  echo $! >"$FRONTEND_PID_FILE"
  if ! wait_for_http "http://127.0.0.1:${FRONTEND_PORT}/index.html" 30 0.25; then
    printf "Frontend failed to start cleanly. Check %s\n" "$FRONTEND_LOG_FILE" >&2
    exit 1
  fi
}

start_pipeline() {
  rotate_logs
  if [[ "${ENABLE_PIPELINE_AUTOSTART}" != "true" ]]; then
    return
  fi
  if [[ ! -f "$PIPELINE_SCRIPT" ]]; then
    return
  fi
  if is_running "$PIPELINE_PID_FILE"; then
    return
  fi
  local -a pipeline_cmd=(
    "$PYTHON_BIN" "$PIPELINE_SCRIPT"
    --mode "$PIPELINE_MODE"
    --integration-json
    --soft-alert-fallback
    --log-file "$PIPELINE_LOG"
  )
  if [[ -n "$PIPELINE_EXTRA_ARGS" ]]; then
    local -a extra_args=()
<<<<<<< HEAD
    # shellcheck disable=SC2294
    eval "extra_args=($PIPELINE_EXTRA_ARGS)"
    pipeline_cmd+=("${extra_args[@]}")
  fi
  nohup "${pipeline_cmd[@]}" >>"$PIPELINE_LOG" 2>&1 &
=======
    if ! mapfile -t extra_args < <(parse_shell_words "$PIPELINE_EXTRA_ARGS"); then
      printf "Invalid PIPELINE_EXTRA_ARGS; unable to parse shell words safely.\n" >&2
      exit 1
    fi
    pipeline_cmd+=("${extra_args[@]}")
  fi
  nohup "${pipeline_cmd[@]}" >/dev/null 2>&1 &
>>>>>>> feature/integrate-waze-and-service-hardening
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
<<<<<<< HEAD
  sleep 0.5
  kill -9 "$pid" 2>/dev/null || true
=======
  local waited=0
  while kill -0 "$pid" 2>/dev/null; do
    if (( waited >= STOP_TIMEOUT_SECONDS )); then
      break
    fi
    sleep 1
    ((waited++))
  done
  if kill -0 "$pid" 2>/dev/null; then
    kill -9 "$pid" 2>/dev/null || true
  fi
>>>>>>> feature/integrate-waze-and-service-hardening
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
  rotate_logs
<<<<<<< HEAD
  start_backend
  start_frontend
  start_pipeline
  printf "Vehicle stack started.\n"
  printf "UI: http://127.0.0.1:%s\n" "$FRONTEND_PORT"
  printf "Backend health: http://127.0.0.1:%s/api/health\n" "$BACKEND_PORT"
=======
  if [[ "$USE_DOCKER_SERVERS" == "true" ]]; then
    validate_docker_server_mode
    # Ordering for stream stability: pipeline first (producer), then backend/frontend containers (consumers).
    start_pipeline
    start_docker_servers
  else
    start_backend
    start_frontend
    start_pipeline
  fi
  printf "Vehicle stack started.\n"
  printf "UI: http://127.0.0.1:%s\n" "$FRONTEND_PORT"
  printf "Backend health: http://127.0.0.1:%s/api/health\n" "$BACKEND_PORT"
  printf "Map status: http://127.0.0.1:%s/api/map/status\n" "$BACKEND_PORT"
>>>>>>> feature/integrate-waze-and-service-hardening
  printf "Mobile bootstrap: http://127.0.0.1:%s/api/mobile/bootstrap\n" "$BACKEND_PORT"
  printf "Logs: %s and %s (pipeline: %s)\n" "$BACKEND_LOG_FILE" "$FRONTEND_LOG_FILE" "$PIPELINE_LOG"
}

stop_all() {
<<<<<<< HEAD
  stop_component "$PIPELINE_PID_FILE"
  stop_component "$FRONTEND_PID_FILE"
  stop_component "$BACKEND_PID_FILE"
=======
  if [[ "$USE_DOCKER_SERVERS" == "true" ]]; then
    stop_docker_servers
    stop_component "$PIPELINE_PID_FILE"
    stop_component "$FRONTEND_PID_FILE"
    stop_component "$BACKEND_PID_FILE"
  else
    stop_component "$PIPELINE_PID_FILE"
    stop_component "$FRONTEND_PID_FILE"
    stop_component "$BACKEND_PID_FILE"
  fi
>>>>>>> feature/integrate-waze-and-service-hardening
  printf "Vehicle stack stopped.\n"
}

status_all() {
<<<<<<< HEAD
  status_component "backend" "$BACKEND_PID_FILE"
  status_component "frontend" "$FRONTEND_PID_FILE"
=======
  if [[ "$USE_DOCKER_SERVERS" == "true" ]]; then
    status_docker_servers
  else
    status_component "backend" "$BACKEND_PID_FILE"
    status_component "frontend" "$FRONTEND_PID_FILE"
  fi
>>>>>>> feature/integrate-waze-and-service-hardening
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
