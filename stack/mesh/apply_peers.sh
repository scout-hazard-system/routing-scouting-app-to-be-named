#!/usr/bin/env bash
# Copyright 2026 Scout Project Contributors
# Licensed under the Apache License, Version 2.0
#
# Rebuild scoutwg0.conf from hub template + peers/*.conf and live-reload.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_DIR="${SCOUT_MESH_STATE_DIR:-$SCRIPT_DIR/state}"
PEERS_DIR="${SCOUT_MESH_PEERS_DIR:-$SCRIPT_DIR/peers}"
IFACE="${SCOUT_MESH_IFACE:-scoutwg0}"
HUB_ADDRESS="${SCOUT_MESH_HUB_ADDRESS:-10.66.0.1/32}"
LISTEN_PORT="${SCOUT_MESH_LISTEN_PORT:-51820}"
WG_CONF_DIR="${SCOUT_MESH_WG_CONF_DIR:-/etc/wireguard}"
WG_CONF="$WG_CONF_DIR/${IFACE}.conf"

if ((EUID != 0)); then
  echo "[x] run as root: sudo $0" >&2
  exit 1
fi

if [[ ! -f "$STATE_DIR/hub.privatekey" ]]; then
  echo "[x] missing hub key; run install_hub.sh first" >&2
  exit 1
fi

HUB_PRIVATE_KEY="$(cat "$STATE_DIR/hub.privatekey")"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

sed \
  -e "s|{{HUB_ADDRESS}}|$HUB_ADDRESS|g" \
  -e "s|{{LISTEN_PORT}}|$LISTEN_PORT|g" \
  -e "s|{{HUB_PRIVATE_KEY}}|$HUB_PRIVATE_KEY|g" \
  "$SCRIPT_DIR/wg-hub.conf.template" > "$TMP"

if compgen -G "$PEERS_DIR/*.conf" > /dev/null; then
  echo "" >> "$TMP"
  echo "# --- enrolled peers ---" >> "$TMP"
  cat "$PEERS_DIR"/*.conf >> "$TMP"
  PEER_COUNT="$(find "$PEERS_DIR" -maxdepth 1 -name '*.conf' | wc -l)"
else
  PEER_COUNT=0
fi

install -m 600 "$TMP" "$WG_CONF"

if systemctl is-active --quiet "wg-quick@${IFACE}"; then
  wg syncconf "$IFACE" <(wg-quick strip "$IFACE")
  echo "[+] synced $PEER_COUNT peer(s) onto $IFACE"
else
  systemctl start "wg-quick@${IFACE}"
  echo "[+] started $IFACE with $PEER_COUNT peer(s)"
fi
