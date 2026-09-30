# Pop!_OS peer suite (mesh + scout-dev1.0.1 gate + wiring)

Self-contained handoff for a second Pop!_OS machine on the **Scout Mesh**
WireGuard network, including the **scout-dev1.0.1** unauth-gate model and
local wiring env.

## Bundle contents

| Path | Purpose |
|------|---------|
| `bootstrap_peer.sh` | apt: wireguard-tools, curl, jq |
| `join_mesh.sh` / `verify_peer.sh` / `leave_mesh.sh` | enroll + split-tunnel `scoutwg0` |
| `install_all.sh` | one-shot: tools + Ollama models + mesh join |
| `config/peer_wiring.env.example` | mesh + gate model env knobs |
| `llm/dev/Modelfile.scout-dev` | base dev specialist |
| `llm/dev/Modelfile.scout-dev1.0.1` | **unauth IP/device gate** specialist |
| `llm/core/Modelfile.scout-core1.0.5` | core base for the FROM chain |
| `llm/client/llm_set_client.py` | local `gate` CLI helper |

**Not included (operator provides out-of-band):**

- `SCOUT_MESH_ENTRY_TOKEN` (mesh enroll)
- hub private keys / admin token (Windows admin stays off this peer path)

## On the hub (pack)

```bash
cd /path/to/routing-scouting-app-to-be-named
./stack/mesh/peer_setup/pack_peer_bundle.sh
# → dist/scout-mesh-peer-popos.tar.gz
```

Copy tarball to the peer (scp/USB).

## On the Pop!_OS peer

```bash
tar xzf scout-mesh-peer-popos.tar.gz
cd scout-mesh-peer-popos   # or peer_setup/ depending on pack layout

# one shot
export SCOUT_MESH_ENROLL_URL="http://192.168.1.154:18080"   # hub LAN/public HTTP
export SCOUT_MESH_ENTRY_TOKEN="…"                            # from hub operator
# if handshake fails via public IP hairpin:
# export SCOUT_MESH_ENDPOINT_OVERRIDE="192.168.1.154:51820"
./install_all.sh
```

Or stepwise:

```bash
./bootstrap_peer.sh
# optional models only:
SCOUT_BUILD_GATE_MODEL=1 SKIP_MESH_JOIN=1 ./install_all.sh
export SCOUT_MESH_ENROLL_URL=... SCOUT_MESH_ENTRY_TOKEN=...
./join_mesh.sh && ./verify_peer.sh
```

## After install

```bash
source state/peer_wiring.env
ping -c 2 10.66.0.1
curl -sS http://10.66.0.1:18080/api/health

# gate model (unauth analysis)
python3 state/llm-client/llm_set_client.py gate --task GATE \
  $'Event: POST /api/mesh/enroll from 203.0.113.9\nResult: HTTP 403 invalid_entry_token'
```

## Windows job PC

Do **not** run this suite on the corporate Windows machine. That host uses
`scout_windows_admin/` + `X-Scout-Admin-Token` over HTTPS only (no WireGuard).

## Hub checklist before peer joins

- [ ] `sudo ./stack/mesh/status.sh` — `scoutwg0` up, UDP 51820 open
- [ ] Backend up with `SCOUT_MESH_ENABLED=true` + entry token
- [ ] `sudo ./stack/mesh/apply_peers.sh` after each enroll (or auto-apply)
- [ ] Enroll URL reachable from peer **without** mesh
