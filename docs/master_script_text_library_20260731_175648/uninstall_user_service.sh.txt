#!/usr/bin/env bash
set -euo pipefail

UNIT_DST="$HOME/.config/systemd/user/vehicle-stack.service"

if systemctl --user is-enabled vehicle-stack.service >/dev/null 2>&1; then
  systemctl --user disable vehicle-stack.service
fi

if systemctl --user is-active vehicle-stack.service >/dev/null 2>&1; then
  systemctl --user stop vehicle-stack.service
fi

rm -f "$UNIT_DST"
systemctl --user daemon-reload

echo "Removed user service: vehicle-stack.service"
