#!/usr/bin/env bash
# Copyright 2026 Scout Project Contributors
# Licensed under the Apache License, Version 2.0
#
# Enroll this host as a Scout Mesh peer with dynamic IP + endpoint binding.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_DIR="${SCOUT_MESH_STATE_DIR:-$SCRIPT_DIR/state}"
IFACE="${SCOUT_MESH_IFACE:-scoutwg0}"
PLATFORM="${SCOUT_MESH_PLATFORM:-linux}"
DEVICE_ID="${SCOUT_MESH_DEVICE_ID:-popos-$(hostname | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-')}"
ENROLL_URL="${SCOUT_MESH_ENROLL_URL:-}"
ENTRY_TOKEN="${SCOUT_MESH_ENTRY_TOKEN:-}"
ENDPOINT_OVERRIDE="${SCOUT_MESH_ENDPOINT_OVERRIDE:-}"
PREFERRED_ENDPOINT="${SCOUT_MESH_PREFERRED_ENDPOINT:-$ENDPOINT_OVERRIDE}"

mkdir -p "$STATE_DIR"
chmod 700 "$STATE_DIR"

if [[ -z "$ENROLL_URL" || -z "$ENTRY_TOKEN" ]]; then
  echo "[x] set SCOUT_MESH_ENROLL_URL and SCOUT_MESH_ENTRY_TOKEN" >&2
  echo "    ENROLL_URL is the hub backend reachable WITHOUT mesh (LAN/public)." >&2
  exit 1
fi

ENROLL_URL="${ENROLL_URL%/}"
require_bin() { command -v "$1" >/dev/null 2>&1 || { echo "[x] missing $1 (run ./bootstrap_peer.sh)" >&2; exit 1; }; }
require_bin curl
require_bin jq
require_bin wg
require_bin wg-quick

echo "[*] enrolling device_id=$DEVICE_ID platform=$PLATFORM"
BODY="$(jq -n \
  --arg t "$ENTRY_TOKEN" \
  --arg d "$DEVICE_ID" \
  --arg p "$PLATFORM" \
  --arg pe "$PREFERRED_ENDPOINT" \
  '{entry_token:$t, device_id:$d, platform:$p} + (if $pe != "" then {preferred_endpoint:$pe} else {} end)')"

RESP="$(curl -fsS -X POST "$ENROLL_URL/api/mesh/enroll" \
  -H 'Content-Type: application/json' \
  -d "$BODY")"

echo "$RESP" | jq -e '.status == "ok"' >/dev/null
echo "$RESP" > "$STATE_DIR/enroll.json"
chmod 600 "$STATE_DIR/enroll.json"

PRIV="$(echo "$RESP" | jq -r '.mesh.client_private_key')"
PUB_SERVER="$(echo "$RESP" | jq -r '.mesh.server_public_key')"
ADDR="$(echo "$RESP" | jq -r '.mesh.client_address')"
ENDPOINT="$(echo "$RESP" | jq -r '.mesh.endpoint')"
KEEPALIVE="$(echo "$RESP" | jq -r '.mesh.persistent_keepalive // 25')"
ALLOWED="$(echo "$RESP" | jq -r '.mesh.allowed_ips | join(", ")')"
BACKEND="$(echo "$RESP" | jq -r '.mesh.backend_base_url')"
CIDR="$(echo "$RESP" | jq -r '.mesh.cidr // empty')"

# Dynamic endpoint binding: override > first reachable candidate > server pick
pick_endpoint() {
  local cand host port
  if [[ -n "$ENDPOINT_OVERRIDE" ]]; then
    echo "$ENDPOINT_OVERRIDE"
    return
  fi
  # Try candidates from hub (LAN + public), then selected endpoint
  while read -r cand; do
    [[ -z "$cand" || "$cand" == "null" ]] && continue
    host="${cand%:*}"; port="${cand##*:}"
    [[ -z "$port" || "$port" == "$cand" ]] && port=51820
    # UDP "open" is hard to probe; try TCP health on same host if enroll host matches
    if timeout 1 bash -c "echo >/dev/udp/${host}/${port}" 2>/dev/null; then
      # Prefer private hosts when available
      echo "$cand"
      return
    fi
  done < <(echo "$RESP" | jq -r '(.mesh.endpoint_candidates // [])[], .mesh.endpoint' 2>/dev/null)
  echo "$ENDPOINT"
}

ENDPOINT="$(pick_endpoint)"

# If still public but enroll URL was LAN, prefer LAN host:port from enroll URL
if [[ -z "$ENDPOINT_OVERRIDE" ]]; then
  enroll_host="$(echo "$ENROLL_URL" | sed -E 's#https?://([^/:]+).*#\1#')"
  if [[ "$enroll_host" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
    case "$enroll_host" in
      10.*|192.168.*|172.1[6-9].*|172.2[0-9].*|172.3[0-1].*)
        ep_host="${ENDPOINT%:*}"
        case "$ep_host" in
          10.*|192.168.*|172.1[6-9].*|172.2[0-9].*|172.3[0-1].*) ;;
          *)
            ENDPOINT="${enroll_host}:${ENDPOINT##*:}"
            [[ "$ENDPOINT" == *:* ]] || ENDPOINT="${enroll_host}:51820"
            # if ENDPOINT had no port
            if [[ "$ENDPOINT" != *:* ]]; then ENDPOINT="${enroll_host}:51820"; fi
            # normalize: if we set enroll_host:full endpoint wrongly fix
            if [[ "$ENDPOINT" == *:*:* ]]; then ENDPOINT="${enroll_host}:51820"; fi
            ENDPOINT="${enroll_host}:${ENDPOINT##*:}"
            ;;
        esac
        ;;
    esac
  fi
fi

CONF_FILE="$STATE_DIR/${IFACE}.conf"
umask 077
cat > "$CONF_FILE" <<EOF
# Scout Mesh peer — dynamic address binding from hub allocator
# client_address=$ADDR cidr=${CIDR:-unknown} endpoint=$ENDPOINT
[Interface]
PrivateKey = $PRIV
Address = $ADDR

[Peer]
PublicKey = $PUB_SERVER
Endpoint = $ENDPOINT
AllowedIPs = $ALLOWED
PersistentKeepalive = $KEEPALIVE
