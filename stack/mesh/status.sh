#!/usr/bin/env bash
# Copyright 2026 Scout Project Contributors
# Licensed under the Apache License, Version 2.0
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_DIR="${SCOUT_MESH_STATE_DIR:-$SCRIPT_DIR/state}"
PEERS_DIR="${SCOUT_MESH_PEERS_DIR:-$SCRIPT_DIR/peers}"
IFACE="${SCOUT_MESH_IFACE:-scoutwg0}"

echo "== Scout Mesh status =="
if [[ -f "$STATE_DIR/hub.env" ]]; then
  # shellcheck disable=SC1090
  set -a; source "$STATE_DIR/hub.env"; set +a
  echo "endpoint:   ${SCOUT_MESH_ENDPOINT:-unknown}"
  echo "hub pubkey: ${SCOUT_MESH_HUB_PUBLIC_KEY:-unknown}"
  echo "hub addr:   ${SCOUT_MESH_HUB_ADDRESS:-unknown}"
fi
echo "peer files: $(find "$PEERS_DIR" -maxdepth 1 -name '*.conf' 2>/dev/null | wc -l)"
echo ""
if command -v wg >/dev/null 2>&1; then
  if ((EUID == 0)); then
    wg show "$IFACE" || echo "(interface $IFACE not up)"
  else
    sudo wg show "$IFACE" || echo "(interface $IFACE not up — try sudo)"
  fi
else
  echo "wg not installed"
fi
echo ""
echo "allocator: $STATE_DIR/ip_allocator.tsv"
if [[ -f "$STATE_DIR/ip_allocator.tsv" ]]; then
  tail -n 20 "$STATE_DIR/ip_allocator.tsv"
fi
