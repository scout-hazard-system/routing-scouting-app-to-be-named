#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="/home/gibi/Desktop"
STACK_SCRIPT="$ROOT_DIR/run_vehicle_stack.sh"
STREAM_CLIENT="$ROOT_DIR/frontend/stream_client.py"
DEFAULT_CONFIG="$ROOT_DIR/config/vehicle_stack.env"
<<<<<<< HEAD
=======
FIXED_BACKEND_PORT="18080"
FIXED_FRONTEND_PORT="8787"
>>>>>>> feature/integrate-waze-and-service-hardening

if [[ -f "${VEHICLE_STACK_CONFIG_FILE:-$DEFAULT_CONFIG}" ]]; then
  # shellcheck disable=SC1090
  source "${VEHICLE_STACK_CONFIG_FILE:-$DEFAULT_CONFIG}"
fi

<<<<<<< HEAD
BACKEND_PORT="${BACKEND_PORT:-18080}"
FRONTEND_PORT="${FRONTEND_PORT:-8787}"
=======
BACKEND_PORT="$FIXED_BACKEND_PORT"
FRONTEND_PORT="$FIXED_FRONTEND_PORT"
>>>>>>> feature/integrate-waze-and-service-hardening
PIPELINE_LOG="${PIPELINE_LOG:-/tmp/pipeline_live_doordash.log}"
BACKEND_LOG_FILE="/tmp/vehicle_stack/logs/backend.log"
FRONTEND_LOG_FILE="/tmp/vehicle_stack/logs/frontend.log"

usage() {
  cat <<EOF
Usage: $0 {start|stop|restart|status|health|stream|logs [pipeline|backend|frontend|all]}

Commands:
  start      Start pipeline + Java backend + frontend dashboard
  stop       Stop all stack processes
  restart    Restart all stack processes
  status     Show process status for stack components
  health     Print key backend/frontend health endpoints
  stream     Open terminal event stream (good for Termius session)
  logs       Tail logs (default: all)

Examples:
  $0 start
  $0 health
  $0 stream
  $0 logs pipeline

Termius notes:
  - Dashboard URL (with SSH port forward): http://127.0.0.1:${FRONTEND_PORT}/index.html
  - Backend health: http://127.0.0.1:${BACKEND_PORT}/api/health
  - Recommended local forwards: ${FRONTEND_PORT}->localhost:${FRONTEND_PORT}, ${BACKEND_PORT}->localhost:${BACKEND_PORT}
EOF
}

require_stack_script() {
  if [[ ! -x "$STACK_SCRIPT" ]]; then
    echo "Missing executable stack launcher: $STACK_SCRIPT" >&2
    exit 1
  fi
}

run_stack() {
  require_stack_script
<<<<<<< HEAD
  "$STACK_SCRIPT" "$1"
=======
  BACKEND_PORT="$FIXED_BACKEND_PORT" FRONTEND_PORT="$FIXED_FRONTEND_PORT" "$STACK_SCRIPT" "$1"
>>>>>>> feature/integrate-waze-and-service-hardening
}

print_health() {
  local backend_url="http://127.0.0.1:${BACKEND_PORT}"
  local frontend_url="http://127.0.0.1:${FRONTEND_PORT}"
  echo "Checking stack health..."
  echo "- backend:  ${backend_url}/api/health"
  curl -fsS "${backend_url}/api/health" || echo "backend health unavailable"
  echo
  echo "- mobile:   ${backend_url}/api/mobile/bootstrap"
  curl -fsS "${backend_url}/api/mobile/bootstrap" || echo "mobile bootstrap unavailable"
  echo
  echo "- llm:      ${backend_url}/api/platform/llm/status"
  curl -fsS "${backend_url}/api/platform/llm/status" || echo "llm status unavailable"
  echo
<<<<<<< HEAD
=======
  echo "- map:      ${backend_url}/api/map/status"
  curl -fsS "${backend_url}/api/map/status" || echo "map status unavailable"
  echo
>>>>>>> feature/integrate-waze-and-service-hardening
  echo "- frontend: ${frontend_url}/index.html"
  curl -fsS -o /dev/null "${frontend_url}/index.html" \
    && echo "frontend reachable" \
    || echo "frontend unavailable"
}

start_and_print() {
  run_stack start
  echo
  echo "Termius quick-use:"
  echo "  Stream events: $0 stream"
  echo "  Follow logs:   $0 logs all"
  echo "  Health check:  $0 health"
}

stream_events() {
  local python_bin="${PYTHON_BIN:-$ROOT_DIR/cop_pipeline/bin/python3}"
  if [[ ! -x "$python_bin" ]]; then
    python_bin="python3"
  fi
  if [[ ! -f "$STREAM_CLIENT" ]]; then
    echo "Missing stream client: $STREAM_CLIENT" >&2
    exit 1
  fi
  "$python_bin" "$STREAM_CLIENT" --base-url "http://127.0.0.1:${BACKEND_PORT}"
}

tail_logs() {
  local target="${1:-all}"
  case "$target" in
    pipeline) tail -n 120 -f "$PIPELINE_LOG" ;;
    backend) tail -n 120 -f "$BACKEND_LOG_FILE" ;;
    frontend) tail -n 120 -f "$FRONTEND_LOG_FILE" ;;
    all) tail -n 80 -f "$PIPELINE_LOG" "$BACKEND_LOG_FILE" "$FRONTEND_LOG_FILE" ;;
    *)
      echo "Unknown log target: $target" >&2
      usage
      exit 1
      ;;
  esac
}

CMD="${1:-start}"
case "$CMD" in
  start) start_and_print ;;
  stop) run_stack stop ;;
  restart) run_stack restart ;;
  status) run_stack status ;;
  health) print_health ;;
  stream) stream_events ;;
  logs) tail_logs "${2:-all}" ;;
  help|-h|--help) usage ;;
  *)
    usage
    exit 1
    ;;
esac
