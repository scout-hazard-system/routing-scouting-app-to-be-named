#!/usr/bin/env bash
# Copyright 2026 Scout Project Contributors
# Licensed under the Apache License, Version 2.0
#
# Pop!_OS / Ubuntu peer prerequisites for Scout Mesh (WireGuard only).
# Does NOT install Tailscale or any full-tunnel VPN.
set -euo pipefail

echo "[*] Scout Mesh peer bootstrap (Pop!_OS/Ubuntu)"
echo "[*] Scope: wireguard-tools + curl/jq only. No Tailscale."

if ((EUID != 0)); then
  SUDO="sudo"
else
  SUDO=""
fi

if [[ -f /etc/os-release ]]; then
  # shellcheck disable=SC1091
  . /etc/os-release
  case "${ID:-}:${ID_LIKE:-}" in
    *ubuntu*|*pop*|*debian*) ;;
    *)
      echo "[!] This does not look like Pop!_OS/Ubuntu/Debian (ID=${ID:-unknown})." >&2
      ;;
  esac
fi

$SUDO apt-get update
$SUDO apt-get install -y wireguard wireguard-tools curl jq ca-certificates iproute2

if ! command -v wg >/dev/null || ! command -v wg-quick >/dev/null; then
  echo "[x] wireguard-tools missing after install" >&2
  exit 1
fi

# Optional: refuse accidental Tailscale enable on this peer if user wants job-safe box
if command -v tailscale >/dev/null 2>&1; then
  echo "[!] tailscale binary is present on this machine."
  echo "    Scout Mesh peer does not need it. For a remote-job Windows-adjacent"
  echo "    workflow, keep Tailscale stopped: sudo tailscale down && sudo systemctl disable --now tailscaled"
fi

echo "[+] bootstrap complete"
echo "    next: export SCOUT_MESH_ENROLL_URL=... SCOUT_MESH_ENTRY_TOKEN=... && ./join_mesh.sh"
