#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="/home/gibi/Desktop"
UNIT_SRC="$ROOT_DIR/deployment/systemd/vehicle-stack.service"
UNIT_DST_DIR="$HOME/.config/systemd/user"
UNIT_DST="$UNIT_DST_DIR/vehicle-stack.service"

mkdir -p "$UNIT_DST_DIR"
cp "$UNIT_SRC" "$UNIT_DST"
systemctl --user daemon-reload
systemctl --user enable vehicle-stack.service

echo "Installed and enabled user service: vehicle-stack.service"
echo "Start now with: systemctl --user start vehicle-stack.service"
