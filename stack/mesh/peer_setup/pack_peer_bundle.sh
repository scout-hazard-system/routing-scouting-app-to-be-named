#!/usr/bin/env bash
# Copyright 2026 Scout Project Contributors
# Licensed under the Apache License, Version 2.0
#
# Build dist/scout-mesh-peer-popos.tar.gz with:
#   peer mesh scripts, wiring examples, scout-dev + scout-dev1.0.1, llm client
# Does NOT include hub secrets (entry_token, admin_token, private keys).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DIST="$ROOT/dist"
STAGE="$DIST/scout-mesh-peer-popos-stage"
OUT="$DIST/scout-mesh-peer-popos.tar.gz"
NAME="scout-mesh-peer-popos"

rm -rf "$STAGE"
mkdir -p "$STAGE/$NAME"/{config,llm/dev,llm/core,llm/client,docs}

install -m 0755 "$SCRIPT_DIR/bootstrap_peer.sh" "$STAGE/$NAME/"
install -m 0755 "$SCRIPT_DIR/join_mesh.sh" "$STAGE/$NAME/"
install -m 0755 "$SCRIPT_DIR/verify_peer.sh" "$STAGE/$NAME/"
install -m 0755 "$SCRIPT_DIR/leave_mesh.sh" "$STAGE/$NAME/"
install -m 0755 "$SCRIPT_DIR/install_all.sh" "$STAGE/$NAME/"
install -m 0644 "$SCRIPT_DIR/README.md" "$STAGE/$NAME/"
install -m 0644 "$SCRIPT_DIR/PEER_SUITE.md" "$STAGE/$NAME/"
install -m 0644 "$SCRIPT_DIR/config/peer_wiring.env.example" "$STAGE/$NAME/config/"

install -m 0644 "$ROOT/llm/dev/Modelfile.scout-dev" "$STAGE/$NAME/llm/dev/"
install -m 0644 "$ROOT/llm/dev/Modelfile.scout-dev1.0.1" "$STAGE/$NAME/llm/dev/"
if [[ -f "$ROOT/llm/core/Modelfile.scout-core1.0.5" ]]; then
  install -m 0644 "$ROOT/llm/core/Modelfile.scout-core1.0.5" "$STAGE/$NAME/llm/core/"
fi
cp -a "$ROOT/llm/client/." "$STAGE/$NAME/llm/client/"
if [[ -f "$ROOT/docs/guides/SCOUT_MESH.md" ]]; then
  install -m 0644 "$ROOT/docs/guides/SCOUT_MESH.md" "$STAGE/$NAME/docs/"
fi
if [[ -f "$ROOT/llm/README.md" ]]; then
  install -m 0644 "$ROOT/llm/README.md" "$STAGE/$NAME/docs/LLM_README.md"
fi

cat > "$STAGE/$NAME/OPERATOR_SECRETS.env.example" <<'EOF'
# Fill on the peer shell before ./install_all.sh or ./join_mesh.sh
export SCOUT_MESH_ENROLL_URL="http://192.168.1.154:18080"
export SCOUT_MESH_ENTRY_TOKEN=""
# export SCOUT_MESH_ENDPOINT_OVERRIDE="192.168.1.154:51820"
# export SCOUT_MESH_DEVICE_ID="popos-peer-$(hostname)"
EOF

cat > "$STAGE/$NAME/QUICKSTART.txt" <<'EOF'
Scout Mesh Pop!_OS peer — QUICKSTART
1) tar xzf scout-mesh-peer-popos.tar.gz && cd scout-mesh-peer-popos
2) Fill OPERATOR_SECRETS.env.example (ENROLL_URL + ENTRY_TOKEN from hub)
3) source OPERATOR_SECRETS.env.example && ./install_all.sh
4) ./verify_peer.sh && curl -sS http://10.66.0.1:18080/api/health
5) Gate model: python3 llm/client/llm_set_client.py gate --task GATE 'Event: ...'
Windows job PC: do NOT use this suite (use scout_windows_admin + admin token).
EOF

mkdir -p "$DIST"
tar -C "$STAGE" -czf "$OUT" "$NAME"
rm -rf "$STAGE"
echo "[+] wrote $OUT"
tar -tzf "$OUT" | head -80
echo "file_count=$(tar -tzf "$OUT" | wc -l)"
ls -la "$OUT"
