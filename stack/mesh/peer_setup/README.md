# Scout Mesh — Pop!_OS peer join suite

Join a second (or Nth) **Pop!_OS / Ubuntu** machine to the existing Scout Mesh
WireGuard network as a **desktop peer** (`10.66.2.x`). Split-tunnel only:
`AllowedIPs = 10.66.0.0/16`. Normal internet / job traffic is **not** routed
through the mesh.

## What this is / is not

| In scope | Out of scope |
|----------|----------------|
| Pop!_OS map servers, Linux lab boxes | **Windows work PC (keep off all VPN)** |
| Android phones (use in-APK Scout Mesh) | Full-tunnel / forced default route |
| Optional Linux GPU/Ollama peers | Requiring Tailscale |

### Windows remote-job machine — stay off WireGuard

If you do paid remote work on Windows and corporate policy forbids VPN:

1. **Do not** install WireGuard, Tailscale, or this peer suite on that PC.
2. **Do not** run `wg-quick`, `tailscale up`, or any always-on tunnel there.
3. Windows can still help the stack **without** a mesh VPN, e.g.:
   - Hermes/Ollama only on LAN while you are home, **or**
   - Leave GPU models on a Linux mesh peer and point Linux hub at that peer.
4. Scout product phones join mesh via the APK only; they never require the
   Windows box to be on WireGuard.

See also `docs/guides/SCOUT_MESH.md` → **Windows exclusion policy**.

## Prerequisites

On the **hub** (already running mesh):

- `scoutwg0` up (`sudo ./stack/mesh/status.sh`)
- Backend with `SCOUT_MESH_ENABLED=true` and a valid `SCOUT_MESH_ENTRY_TOKEN`
- UDP `51820` reachable from the peer (LAN and/or public endpoint)
- Enrollment URL reachable **without** mesh (usually `http://HUB_LAN_OR_PUBLIC:18080`)

On the **peer** Pop!_OS box:

- sudo / apt
- Network path to hub enrollment HTTP + WireGuard UDP

## Quickstart (on the peer)

```bash
# 1. Copy this folder to the peer (USB, scp, git clone of the monorepo, etc.)
cd stack/mesh/peer_setup   # or wherever you unpacked the suite

# 2. Install wireguard-tools only (no Tailscale)
./bootstrap_peer.sh

# 3. Enroll + bring up split-tunnel interface scoutwg0
#    ENROLL_URL = hub backend BEFORE mesh (LAN/public), NOT http://10.66.0.1
export SCOUT_MESH_ENROLL_URL="http://192.168.1.154:18080"
export SCOUT_MESH_ENTRY_TOKEN="dev-entry-…"          # from hub operator
export SCOUT_MESH_DEVICE_ID="popos-peer-$(hostname)" # stable id
./join_mesh.sh

# 4. Verify
./verify_peer.sh
```

Success looks like:

```text
[+] wireguard handshake ok
[+] ping 10.66.0.1 ok
[+] http://10.66.0.1:18080/api/health ok
```

## Environment knobs

| Variable | Default | Meaning |
|----------|---------|---------|
| `SCOUT_MESH_ENROLL_URL` | _(required)_ | Hub base URL for `/api/mesh/enroll` (pre-mesh) |
| `SCOUT_MESH_ENTRY_TOKEN` | _(required)_ | One-time / lab entry token |
| `SCOUT_MESH_DEVICE_ID` | `popos-<hostname>` | Stable peer id (re-enroll keeps IP) |
| `SCOUT_MESH_IFACE` | `scoutwg0` | Local WG interface name |
| `SCOUT_MESH_PLATFORM` | `linux` | Allocator pool → `10.66.2.x` |
| `SCOUT_MESH_ENDPOINT_OVERRIDE` | empty | Force WG endpoint `host:port` (e.g. LAN IP when public hairpin fails) |
| `SCOUT_MESH_STATE_DIR` | `./state` | Local keys + conf (gitignored if under repo) |

## Day-2

```bash
./join_mesh.sh          # idempotent re-apply
sudo wg show scoutwg0
./leave_mesh.sh         # down iface; does not revoke hub peer
./verify_peer.sh
```

Revoke on hub (operator):

```bash
rm stack/mesh/peers/<device_id>.conf
# remove allocator row if desired
sudo ./stack/mesh/apply_peers.sh
```

## Packaging for handoff

From the monorepo root on the hub:

```bash
./stack/mesh/peer_setup/pack_peer_bundle.sh
# → dist/scout-mesh-peer-popos.tar.gz
```

Copy the tarball to the peer, extract, run `bootstrap_peer.sh` + `join_mesh.sh`.

## Security notes

- Peer conf contains a **private key** under `state/` — mode `600`, do not commit.
- Split tunnel only; do not set `AllowedIPs = 0.0.0.0/0`.
- Entry tokens are secrets; prefer per-device tokens in production.
