#!/usr/bin/env bash
# Copyright 2026 Scout Project Contributors
# Licensed under the Apache License, Version 2.0
set -euo pipefail

IFACE="${SCOUT_MESH_IFACE:-scoutwg0}"
HUB="${SCOUT_MESH_HUB_ADDRESS:-10.66.0.1}"
BACKEND_PORT="${BACKEND_PORT:-18080}"

echo "== Scout Mesh peer verify ($IFACE) =="
if ! ip link show "$IFACE" >/dev/null 2>&1; then
  echo "[x] interface $IFACE missing — run ./join_mesh.sh" >&2
  exit 1
fi

if ((EUID == 0)); then
  wg show "$IFACE"
else
  sudo wg show "$IFACE"
fi

if sudo wg show "$IFACE" 2>/dev/null | grep -q "latest handshake"; then
  echo "[+] wireguard handshake ok"
else
  echo "[!] no handshake yet (check UDP 51820 / endpoint / hub apply_peers)"
fi

if ping -c 2 -W 2 "$HUB" >/dev/null 2>&1; then
  echo "[+] ping $HUB ok"
else
  echo "[x] ping $HUB failed" >&2
  exit 1
fi

if curl -fsS --connect-timeout 5 "http://${HUB}:${BACKEND_PORT}/api/health" | grep -q '"status":"ok"'; then
  echo "[+] http://${HUB}:${BACKEND_PORT}/api/health ok"
else
  echo "[x] health check failed" >&2
  exit 1
fi

echo "[+] peer verify PASS"
