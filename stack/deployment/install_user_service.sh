#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# stack/deployment -> repo root is ../..
ROOT_DIR="${ROOT_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
UNIT_SRC="$SCRIPT_DIR/systemd/vehicle-stack.service"
UNIT_DST_DIR="$HOME/.config/systemd/user"
UNIT_DST="$UNIT_DST_DIR/vehicle-stack.service"

mkdir -p "$UNIT_DST_DIR"
sed -e "s|@ROOT_DIR@|$ROOT_DIR|g" "$UNIT_SRC" > "$UNIT_DST"
systemctl --user daemon-reload
systemctl --user enable vehicle-stack.service

echo "Installed and enabled user service: vehicle-stack.service"
echo "  Repo root: $ROOT_DIR"
echo "  Unit file: $UNIT_DST"
echo "Start now with: systemctl --user start vehicle-stack.service"
