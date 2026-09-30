# Scout Mesh (product mesh, non-Tailscale)

Private mesh for paid Scout deployments. Phones join through the **Scout navigation APK only** — no Tailscale/WireGuard app install.

**Status:** foundation (hub scripts + enrollment API + Android `VpnService`)  
**Replaces (product path):** Tailscale `100.x` client dependency  
**Lab note:** Tailscale may remain for internal multi-host Ollama until peers are migrated.

## Product model

| Layer | What customer pays for | What it unlocks |
|-------|------------------------|-----------------|
| **Mesh entry (one-time)** | Mesh license / entry token | WireGuard peer, mesh IP, route to hub services CIDR |
| **Stack subscription (recurring)** | Plan token on API calls | Routing, map shards, scanner stream, assistant, etc. |

Mesh membership alone must **not** grant full stack. Enrollment and `/api/health` stay reachable; everything else checks subscription (or lab open mode).

## Addressing

| Role | Address | Notes |
|------|---------|--------|
| Mesh CIDR | `10.66.0.0/16` | Not Tailscale CGNAT (`100.64.0.0/10`) |
| Hub (Linux map/backend) | `10.66.0.1/32` | WireGuard interface `scoutwg0` |
| Android peers | `10.66.1.0/24` … | Issued by enrollment allocator |
| Desktop peers (optional) | `10.66.2.0/24` | Same control plane |
| WireGuard listen | UDP `51820` (default) | Public endpoint on hub or edge |

Default in-app backend URL after tunnel up:

```text
http://10.66.0.1:18080
```

## Why WireGuard inside the APK

Android only allows a userspace tunnel via [`VpnService`](https://developer.android.com/reference/android/net/VpnService). The Scout APK:

1. Enrolls over the **public internet** (HTTPS to enrollment host).
2. Stores the peer profile (private key never leaves the device).
3. Starts `ScoutMeshVpnService` → OS VPN consent once.
4. Routes `10.66.0.0/16` (and optional DNS) into the TUN; other traffic stays on the normal default route (**split tunnel**).

No second client download is required.

## Trust boundaries

```text
Internet
  │
  ├─ HTTPS :443/18080  /api/mesh/enroll   (public, license-gated)
  ├─ HTTPS             /api/health        (public)
  └─ UDP  :51820       WireGuard          (public endpoint)

Scout Mesh 10.66.0.0/16
  │
  ├─ 10.66.0.1:18080  Java backend (subscription-gated APIs)
  ├─ 10.66.0.1:8787   Frontend
  └─ 10.66.0.1:8765   Blackboard (if enabled)
```

- **Entry license** → peer keys + IP.
- **Subscription token** → `X-Scout-Subscription` (or env-configured header) on stack APIs.
- Backend pull allowlist should include `10.66.0.0/16` (see `BACKEND_PULL_ALLOW_CIDRS`).

## Hub install (Linux)

```bash
cd /path/to/repo
sudo ./stack/mesh/install_hub.sh
sudo ./stack/mesh/status.sh
```

Generates hub keys under `stack/mesh/state/` (gitignored), writes `/etc/wireguard/scoutwg0.conf`, enables `wg-quick@scoutwg0`.

Public endpoint must be a stable DNS or IP reachable by phones:

```bash
export SCOUT_MESH_ENDPOINT="mesh.example.com:51820"
# or IP:port of this host / edge
```

## Enrollment (dev / lab)

Dev mode accepts a shared entry token:

```bash
export SCOUT_MESH_ENTRY_TOKEN="dev-entry-change-me"
export SCOUT_MESH_ENABLED=true
export SCOUT_MESH_ENDPOINT="YOUR_PUBLIC_IP:51820"
export SCOUT_MESH_HUB_PUBLIC_KEY="$(sudo cat /etc/wireguard/scoutwg0.publickey 2>/dev/null || cat stack/mesh/state/hub.publickey)"
export SCOUT_NETWORK_ADVERTISE_HOST=10.66.0.1
export BACKEND_PULL_ALLOW_CIDRS="10.66.0.0/16,127.0.0.1/32,::1/128,172.16.0.0/12,192.168.0.0/16,10.0.0.0/8"
```

Phone / curl:

```bash
curl -sS -X POST "http://PUBLIC_HOST:18080/api/mesh/enroll" \
  -H 'Content-Type: application/json' \
  -d '{"entry_token":"dev-entry-change-me","device_id":"android-test-1","platform":"android"}'
```

Response (shape):

```json
{
  "status": "ok",
  "mesh": {
    "cidr": "10.66.0.0/16",
    "client_address": "10.66.1.2/32",
    "dns": [],
    "endpoint": "PUBLIC:51820",
    "server_public_key": "...",
    "client_private_key": "...",
    "allowed_ips": ["10.66.0.0/16"],
    "persistent_keepalive": 25,
    "backend_base_url": "http://10.66.0.1:18080"
  },
  "subscription": {
    "required": true,
    "header": "X-Scout-Subscription",
    "hint": "stack APIs require an active subscription token after mesh join"
  }
}
```

Hub peer apply (after enroll writes `stack/mesh/peers/*.conf` fragments):

```bash
sudo ./stack/mesh/apply_peers.sh
```

## Android UX

1. Menu → **Scout Mesh** → Enroll (paste entry token) or import profile JSON.
2. System VPN permission prompt.
3. Tunnel up → app sets backend to `http://10.66.0.1:18080` and prefers mesh.
4. Lab fallback: still allow manual URL / legacy Tailscale `100.x` when mesh is off.

## Subscription gate (planned enforcement)

| Path class | Mesh required | Subscription required |
|------------|---------------|------------------------|
| `/api/health`, `/api/mesh/*` | no | no |
| `/api/mobile/bootstrap` | no (returns mesh metadata) | no |
| map / route / stream / assistant | yes (or lab LAN) | yes when `SCOUT_SUBSCRIPTION_REQUIRED=true` |

Payment provider integration is out of band: a fulfillment webhook sets `entry_token` + `subscription_token` rows. This repo ships a **file/env verifier** only.

## Cutover from Tailscale lab

1. Stand up `scoutwg0` on the hub; keep Tailscale for Windows Hermes if needed.
2. Point `SCOUT_NETWORK_ADVERTISE_HOST=10.66.0.1`.
3. Ship APK with mesh service; enroll test devices.
4. Verify `/api/health` via mesh IP from phone.
5. Migrate peer Ollama hosts to mesh IPs or keep Tailscale only for GPU peers.
6. Drop Tailscale defaults from `AppPrefs` once stable.

## Files

| Path | Role |
|------|------|
| `stack/mesh/install_hub.sh` | Install WireGuard hub |
| `stack/mesh/apply_peers.sh` | Reload peer fragments |
| `stack/mesh/status.sh` | `wg show` + health |
| `stack/mesh/wg-hub.conf.template` | Hub conf template |
| `navigation/android/.../mesh/*` | In-APK VPN client |
| `docs/guides/PEER_MESH_DEPLOYMENT.md` | Legacy Tailscale lab runbook |

## Security checklist

- [ ] Hub private key only on hub disk; never in APK
- [ ] Entry tokens single-use or device-bound in production
- [ ] Split tunnel only (`AllowedIPs = 10.66.0.0/16`) unless product requires full tunnel
- [ ] Rate-limit `/api/mesh/enroll`
- [ ] Rotate entry tokens; revoke peers via `apply_peers.sh` + remove fragment
- [ ] Do not commit `stack/mesh/state/` or live peer private keys

## Windows exclusion policy (remote-job PC)

The Windows machine used for a corporate remote job **must not** run WireGuard,
Tailscale, or any always-on VPN client if that risks employment policy
violations.

| Role | Transport |
|------|-----------|
| Android / Pop peers | Scout Mesh WireGuard (split tunnel `10.66.0.0/16` only) |
| Windows job PC | **No VPN** — `X-Scout-Admin-Token` over HTTPS |

See `scout_windows_admin/README.md` and `./stack/mesh/issue_admin_token.sh`.

Admin token grants only stack manage + a few read-only status endpoints. It does
**not** enroll a mesh peer and does not open the full API surface.
