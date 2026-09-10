#!/usr/bin/env bash
# Copyright 2026 Scout Project Contributors
# Licensed under the Apache License, Version 2.0
#
# Install and enable the Scout Mesh WireGuard hub (scoutwg0).
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

mkdir -p "$STATE_DIR" "$PEERS_DIR" "$WG_CONF_DIR"
chmod 700 "$STATE_DIR" "$PEERS_DIR"

if ! command -v wg >/dev/null 2>&1 || ! command -v wg-quick >/dev/null 2>&1; then
  echo "[*] installing wireguard tools..."
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update
    apt-get install -y wireguard wireguard-tools
  else
    echo "[x] install wireguard / wireguard-tools manually" >&2
    exit 1
  fi
fi

if [[ ! -f "$STATE_DIR/hub.privatekey" ]]; then
  echo "[*] generating hub keypair..."
  umask 077
  wg genkey | tee "$STATE_DIR/hub.privatekey" | wg pubkey > "$STATE_DIR/hub.publickey"
  chmod 600 "$STATE_DIR/hub.privatekey"
  chmod 644 "$STATE_DIR/hub.publickey"
fi

HUB_PRIVATE_KEY="$(cat "$STATE_DIR/hub.privatekey")"
HUB_PUBLIC_KEY="$(cat "$STATE_DIR/hub.publickey")"
cp -f "$STATE_DIR/hub.publickey" "$WG_CONF_DIR/${IFACE}.publickey"

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
sed \
  -e "s|{{HUB_ADDRESS}}|$HUB_ADDRESS|g" \
  -e "s|{{LISTEN_PORT}}|$LISTEN_PORT|g" \
  -e "s|{{HUB_PRIVATE_KEY}}|$HUB_PRIVATE_KEY|g" \
  "$SCRIPT_DIR/wg-hub.conf.template" > "$TMP"

# Append peer fragments
if compgen -G "$PEERS_DIR/*.conf" > /dev/null; then
  echo "" >> "$TMP"
  echo "# --- enrolled peers ---" >> "$TMP"
  cat "$PEERS_DIR"/*.conf >> "$TMP"
fi

install -m 600 "$TMP" "$WG_CONF"

systemctl enable "wg-quick@${IFACE}"
if systemctl is-active --quiet "wg-quick@${IFACE}"; then
  wg syncconf "$IFACE" <(wg-quick strip "$IFACE")
else
  systemctl restart "wg-quick@${IFACE}"
fi

# Persist endpoint hint for the backend
ENDPOINT_HINT="${SCOUT_MESH_ENDPOINT:-}"
if [[ -z "$ENDPOINT_HINT" ]]; then
  # best-effort public IP detection
  PUB_IP="$(curl -4 -fsS --max-time 3 https://ifconfig.me 2>/dev/null || true)"
  if [[ -n "$PUB_IP" ]]; then
    ENDPOINT_HINT="${PUB_IP}:${LISTEN_PORT}"
  else
    ENDPOINT_HINT="0.0.0.0:${LISTEN_PORT}"
  fi
fi
printf '%s\n' "$ENDPOINT_HINT" > "$STATE_DIR/endpoint"
printf '%s\n' "$HUB_PUBLIC_KEY" > "$STATE_DIR/hub.publickey"
cat > "$STATE_DIR/hub.env" <<EOF
SCOUT_MESH_ENABLED=true
SCOUT_MESH_IFACE=${IFACE}
SCOUT_MESH_CIDR=10.66.0.0/16
SCOUT_MESH_HUB_ADDRESS=${HUB_ADDRESS%%/*}
SCOUT_MESH_LISTEN_PORT=${LISTEN_PORT}
SCOUT_MESH_ENDPOINT=${ENDPOINT_HINT}
SCOUT_MESH_HUB_PUBLIC_KEY=${HUB_PUBLIC_KEY}
SCOUT_NETWORK_ADVERTISE_HOST=${HUB_ADDRESS%%/*}
EOF
chmod 600 "$STATE_DIR/hub.env"

echo "[+] Scout Mesh hub ready"
echo "    iface:     $IFACE"
echo "    address:   $HUB_ADDRESS"
echo "    listen:    UDP $LISTEN_PORT"
echo "    publickey: $HUB_PUBLIC_KEY"
echo "    endpoint:  $ENDPOINT_HINT"
echo "    conf:      $WG_CONF"
echo "    env file:  $STATE_DIR/hub.env  (source into vehicle stack)"
echo ""
echo "Next: source $STATE_DIR/hub.env into the backend env, set SCOUT_MESH_ENTRY_TOKEN, restart stack."
