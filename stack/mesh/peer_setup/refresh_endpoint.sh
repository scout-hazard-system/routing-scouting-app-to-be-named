#!/usr/bin/env bash
# Re-select WireGuard Endpoint dynamically (LAN vs public) without reallocating IP.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_DIR="${SCOUT_MESH_STATE_DIR:-$SCRIPT_DIR/state}"
IFACE="${SCOUT_MESH_IFACE:-scoutwg0}"
ENROLL_URL="${SCOUT_MESH_ENROLL_URL:-}"
ENDPOINT_OVERRIDE="${SCOUT_MESH_ENDPOINT_OVERRIDE:-}"
CONF="${SCOUT_MESH_CONF:-/etc/wireguard/${IFACE}.conf}"
DEVICE_ID="${SCOUT_MESH_DEVICE_ID:-}"

if [[ -n "$ENDPOINT_OVERRIDE" ]]; then
  NEW_EP="$ENDPOINT_OVERRIDE"
else
  if [[ -z "$ENROLL_URL" ]]; then
    echo "[x] set SCOUT_MESH_ENROLL_URL or SCOUT_MESH_ENDPOINT_OVERRIDE" >&2
    exit 1
  fi
  ENROLL_URL="${ENROLL_URL%/}"
  # profile is public; endpoint list comes from mesh public json via health? use enroll dry-run not available
  # Prefer LAN host from enroll URL when private
  host="$(echo "$ENROLL_URL" | sed -E 's#https?://([^/:]+).*#\1#')"
  port=51820
  if [[ -f "$STATE_DIR/enroll.json" ]]; then
    port="$(jq -r '.mesh.endpoint|split(":")|.[-1] // "51820"' "$STATE_DIR/enroll.json")"
    cands="$(jq -r '(.mesh.endpoint_candidates // [])[], .mesh.endpoint' "$STATE_DIR/enroll.json" 2>/dev/null || true)"
  else
    cands=""
  fi
  NEW_EP=""
  if [[ -n "$cands" ]]; then
    while read -r c; do
      [[ -z "$c" || "$c" == "null" ]] && continue
      NEW_EP="$c"
      # prefer private
      ch="${c%:*}"
      case "$ch" in 10.*|192.168.*|172.1[6-9].*|172.2[0-9].*|172.3[0-1].*) NEW_EP="$c"; break;; esac
    done <<< "$cands"
  fi
  case "$host" in
    10.*|192.168.*|172.1[6-9].*|172.2[0-9].*|172.3[0-1].*) NEW_EP="${host}:${port}" ;;
  esac
  [[ -n "$NEW_EP" ]] || { echo "[x] no endpoint candidates" >&2; exit 1; }
fi

echo "[*] rebinding $IFACE Endpoint -> $NEW_EP"
tmp="$(mktemp)"
if ((EUID != 0)); then
  sudo sed -E "s|^Endpoint = .*|Endpoint = ${NEW_EP}|" "$CONF" > "$tmp"
  sudo install -m 600 "$tmp" "$CONF"
  rm -f "$tmp"
  sudo systemctl restart "wg-quick@${IFACE}"
else
  sed -E "s|^Endpoint = .*|Endpoint = ${NEW_EP}|" "$CONF" > "$tmp"
  install -m 600 "$tmp" "$CONF"
  rm -f "$tmp"
  systemctl restart "wg-quick@${IFACE}"
fi
jq -n --arg ep "$NEW_EP" '{endpoint:$ep, rebound_at:(now|todate)}' > "$STATE_DIR/binding-endpoint.json" 2>/dev/null || true
echo "[+] endpoint rebound to $NEW_EP"
wg show "$IFACE" 2>/dev/null || sudo wg show "$IFACE"
