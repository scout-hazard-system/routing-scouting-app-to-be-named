# Dynamic mesh IP + endpoint binding

## What changed
Peer tunnel addresses and UDP endpoints are **no longer a fixed hardcoded set** in allocator logic.

### Client address (AllowedIPs / Interface Address)
- Allocated inside `SCOUT_MESH_CIDR` (default `10.66.0.0/16`, fully overridable).
- Pools (optional):
  - `SCOUT_MESH_ANDROID_SUBNET` (default: `x.y.1.0/24` inside a /16)
  - `SCOUT_MESH_PEER_SUBNET` for linux/other (default: `x.y.2.0/24`)
- Hub address reserved via `SCOUT_MESH_HUB_ADDRESS`.
- Re-enroll **reuses** the same device_id → same IP; new device_id → next free /32.

### Endpoint (WireGuard peer Endpoint=)
Selection order in `ScoutMeshControl.resolveEndpoint`:
1. `preferred_endpoint` in enroll JSON (or `SCOUT_MESH_PREFERRED_ENDPOINT` / `SCOUT_MESH_ENDPOINT_OVERRIDE` on client)
2. `SCOUT_MESH_ENDPOINT_LAN` when client remote IP is private/loopback/CGNAT
3. `SCOUT_MESH_ENDPOINT` (public)
4. `stack/mesh/state/endpoint` from `install_hub.sh`

Enroll response includes:
- `mesh.endpoint` — chosen value
- `mesh.endpoint_candidates` — LAN + public options
- `mesh.endpoint_selection: "dynamic"`

### Client scripts
- `stack/mesh/peer_setup/join_mesh.sh` — dynamic IP from hub + endpoint pick/rebind helpers
- `stack/mesh/peer_setup/refresh_endpoint.sh` — change Endpoint without new IP

### Example env (hub `runtime.env`)
```bash
SCOUT_MESH_CIDR=10.66.0.0/16
SCOUT_MESH_HUB_ADDRESS=10.66.0.1
SCOUT_MESH_ENDPOINT=97.188.103.160:51820
SCOUT_MESH_ENDPOINT_LAN=192.168.1.154:51820
# SCOUT_MESH_ANDROID_SUBNET=10.66.1.0/24
# SCOUT_MESH_PEER_SUBNET=10.66.2.0/24
```
