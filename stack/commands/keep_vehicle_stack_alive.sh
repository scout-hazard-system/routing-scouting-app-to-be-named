#!/usr/bin/env bash
set -euo pipefail

SESSION="vehicle-keepalive"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${ROOT:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
STACK="$SCRIPT_DIR/start_termius_stack.sh"
ADB_TARGET="${ADB_SERIAL:-}"

if [[ -z "$ADB_TARGET" ]]; then
  ADB_TARGET="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi

if [[ -z "$ADB_TARGET" ]]; then
  echo "No authorized adb device found. Connect a device or set ADB_SERIAL." >&2
  exit 1
fi

if ! tmux has-session -t "$SESSION" 2>/dev/null; then
  tmux new-session -d -s "$SESSION" -c "$ROOT"
fi

tmux send-keys -t "$SESSION":0 C-c
tmux send-keys -t "$SESSION":0 "
$STACK start
while true; do
  OUT=\$($STACK status)
  echo \"[\$(date -u +%FT%TZ)] \$OUT\"
  if echo \"\$OUT\" | grep -q 'stopped'; then
    echo \"[\$(date -u +%FT%TZ)] restarting stack...\"
    $STACK restart
  fi
  sleep 30
done
" C-m

tmux list-windows -t "$SESSION" | grep -q 'logs' || tmux new-window -t "$SESSION" -n logs -c "$ROOT"
tmux send-keys -t "$SESSION":logs C-c
tmux send-keys -t "$SESSION":logs "adb -s $ADB_TARGET logcat -v time MainActivity:D RouteOptionsActivity:D Map3dView:D AndroidRuntime:E '*:S' | tee /tmp/map3d_tmux_live.log" C-m

echo "tmux session '$SESSION' is running."
echo "Attach: tmux attach -t $SESSION"
