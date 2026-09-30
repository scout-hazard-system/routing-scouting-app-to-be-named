#!/usr/bin/env bash
# Copyright 2026 Scout Project Contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Install the scout-blackboard user service. Renders
# systemd/scout-blackboard.service with the resolved paths.
#
# Env overrides:
#   SCOUT_CREW_ROOT   scout_crew checkout containing src/scout_crew/blackboard/
#                     (default: ~/Desktop/scout_crew)
#   BB_ENV            0600 env file with the blackboard config
#                     (default: ~/.config/scout/blackboard.env)
#   BB_DIR            working + data dir for the service
#                     (default: ~/.scout/blackboard)
#   VENV_PY           explicit python interpreter (default: auto-resolved)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="${ROOT_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
SCOUT_CREW_ROOT="${SCOUT_CREW_ROOT:-$HOME/Desktop/scout_crew}"
BB_ENV="${BB_ENV:-$HOME/.config/scout/blackboard.env}"
BB_DIR="${BB_DIR:-$HOME/.scout/blackboard}"
UNIT_SRC="$SCRIPT_DIR/systemd/scout-blackboard.service"
UNIT_DST_DIR="$HOME/.config/systemd/user"
UNIT_DST="$UNIT_DST_DIR/scout-blackboard.service"

[[ -f "$SCOUT_CREW_ROOT/src/scout_crew/blackboard/server.py" ]] || {
  echo "scout_crew source not found under: $SCOUT_CREW_ROOT" >&2
  echo "  Set SCOUT_CREW_ROOT to the checkout that has src/scout_crew/blackboard/server.py" >&2
  exit 1
}

resolve_venv_py() {
  local candidates=(
    "$SCOUT_CREW_ROOT/.venv-llm/bin/python"
    "$SCOUT_CREW_ROOT/.venv-bb/bin/python"
    "$SCOUT_CREW_ROOT/.venv/bin/python"
  )
  local c
  for c in "${candidates[@]}"; do
    if [[ -x "$c" ]]; then
      echo "$c"
      return 0
    fi
  done
  if command -v python3 >/dev/null 2>&1; then
    echo "$(command -v python3)"
    return 0
  fi
  return 1
}

VENV_PY="${VENV_PY:-$(resolve_venv_py || true)}"
if [[ -z "$VENV_PY" ]]; then
  echo "No python interpreter found. Run map_server_setup/setup_blackboard.sh to create a venv." >&2
  exit 1
fi

if ! "$VENV_PY" -c 'pass' >/dev/null 2>&1; then
  echo "Interpreter $VENV_PY is not functional." >&2
  echo "Create the venv first: map_server_setup/setup_blackboard.sh" >&2
  exit 1
fi

[[ -f "$BB_ENV" ]] || {
  echo "Missing blackboard env file: $BB_ENV" >&2
  echo "Run map_server_setup/setup_blackboard.sh to generate $BB_ENV first." >&2
  exit 1
}

mkdir -p "$UNIT_DST_DIR" "$BB_DIR"
sed -e "s|@BB_ENV@|$BB_ENV|g" \
    -e "s|@BB_DIR@|$BB_DIR|g" \
    -e "s|@VENV_PY@|$VENV_PY|g" \
    -e "s|@PYTHONPATH@|$SCOUT_CREW_ROOT/src|g" \
    "$UNIT_SRC" > "$UNIT_DST"

systemctl --user daemon-reload
systemctl --user enable scout-blackboard.service >/dev/null 2>&1 || true

echo "Installed and enabled user service: scout-blackboard.service"
echo "  scout_crew root: $SCOUT_CREW_ROOT (PYTHONPATH=$SCOUT_CREW_ROOT/src)"
echo "  interpreter:    $VENV_PY"
echo "  env file:       $BB_ENV"
echo "  data dir:       $BB_DIR"
echo "  unit:           $UNIT_DST"
echo "Start now with: systemctl --user start scout-blackboard.service"