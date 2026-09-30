#!/usr/bin/env bash
# Copyright 2026 Scout Project Contributors
# Licensed under the Apache License, Version 2.0
#
# Create (or show path to) the VPN-free Scout admin token for Windows/job PCs.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_DIR="${SCOUT_MESH_STATE_DIR:-$SCRIPT_DIR/state}"
TOKEN_FILE="${SCOUT_ADMIN_TOKEN_FILE:-$STATE_DIR/admin_token}"

mkdir -p "$STATE_DIR"
chmod 700 "$STATE_DIR"

if [[ -f "$TOKEN_FILE" && -s "$TOKEN_FILE" ]]; then
  echo "[*] existing admin token file: $TOKEN_FILE"
else
  umask 077
  # sat_ + 32 random bytes, url-safe
  TOKEN="sat_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"
  printf '%s\n' "$TOKEN" > "$TOKEN_FILE"
  chmod 600 "$TOKEN_FILE"
  echo "[+] wrote new admin token → $TOKEN_FILE"
fi

echo ""
echo "Export on the hub before starting the backend:"
echo "  export SCOUT_ADMIN_TOKEN=\"\$(cat $TOKEN_FILE)\""
echo "  export SCOUT_ADMIN_TOKEN_HEADER=X-Scout-Admin-Token"
echo "  # optional: export SCOUT_ADMIN_ALLOW_CIDRS=\"YOUR.EGRESS.IP/32\""
echo ""
echo "Windows (no VPN):"
echo "  \$env:SCOUT_ADMIN_BASE_URL = \"http://HUB:18080\""
echo "  \$env:SCOUT_ADMIN_TOKEN    = \"\$(Get-Content ... from secure channel)\""
echo "  .\\scout_windows_admin\\Scout-Admin.ps1 status"
echo ""
echo "Token (handle as a secret):"
cat "$TOKEN_FILE"
