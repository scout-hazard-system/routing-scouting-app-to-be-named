#!/usr/bin/env bash
# Copyright 2026 Scout Project Contributors
# Licensed under the Apache License, Version 2.0
#
# Bring down local mesh iface. Does not revoke hub peer registration.
set -euo pipefail
IFACE="${SCOUT_MESH_IFACE:-scoutwg0}"
if ((EUID != 0)); then
  sudo systemctl disable --now "wg-quick@${IFACE}" || true
  sudo ip link delete "$IFACE" 2>/dev/null || true
else
  systemctl disable --now "wg-quick@${IFACE}" || true
  ip link delete "$IFACE" 2>/dev/null || true
fi
echo "[+] $IFACE down (hub peer still enrolled until operator revokes)"
