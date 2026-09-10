#!/usr/bin/env bash
# Copyright 2026 Scout Project Contributors
# Licensed under the Apache License, Version 2.0
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
BACKEND="$ROOT/navigation/backend"
TEST_SRC="$BACKEND/tests/ScoutMeshDynamicBindTest.java"
OUT="$(mktemp -d /tmp/scout-mesh-test-XXXXXX)"
STATE1="$(mktemp -d /tmp/mesh-state-XXXXXX)"
STATE2="$(mktemp -d /tmp/mesh-state-XXXXXX)"
STATE3="$(mktemp -d /tmp/mesh-state-XXXXXX)"
trap 'rm -rf "$OUT" "$STATE1" "$STATE2" "$STATE3"' EXIT

echo "[*] ROOT=$ROOT"
echo "[*] compiling mesh control + subscription + tests into $OUT"
javac -encoding UTF-8 -d "$OUT" \
  "$BACKEND/ScoutMeshControl.java" \
  "$BACKEND/ScoutSubscriptionAuth.java" \
  "$BACKEND/ScoutAdminAuth.java" \
  "$TEST_SRC"

run_case() {
  local name="$1"
  shift
  echo "[*] case: $name"
  env "$@" java -cp "$OUT" ScoutMeshDynamicBindTest "$name"
}

run_case endpoints

run_case allocate-default \
  SCOUT_MESH_STATE_DIR="$STATE1" \
  SCOUT_MESH_CIDR=10.66.0.0/16 \
  SCOUT_MESH_HUB_ADDRESS=10.66.0.1 \
  SCOUT_MESH_LISTEN_PORT=51820

run_case allocate-custom \
  SCOUT_MESH_STATE_DIR="$STATE2" \
  SCOUT_MESH_CIDR=10.99.0.0/16 \
  SCOUT_MESH_HUB_ADDRESS=10.99.0.1 \
  SCOUT_MESH_LISTEN_PORT=51820

run_case allocate-exhaust \
  SCOUT_MESH_STATE_DIR="$STATE3" \
  SCOUT_MESH_CIDR=10.77.0.0/30 \
  SCOUT_MESH_HUB_ADDRESS=10.77.0.1 \
  SCOUT_MESH_LISTEN_PORT=51820

echo "[+] all ScoutMeshDynamicBindTest cases passed"
