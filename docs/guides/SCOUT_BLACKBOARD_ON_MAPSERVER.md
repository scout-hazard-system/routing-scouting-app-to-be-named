# Scout Blackboard on the Map-Server Hub

The hub host runs both the **map server** (`:18080`) and the **Scout blackboard**
(`:8765`). Devices on the Scout mesh pull map shards from the map server and write
structured alerts to the blackboard; the AZ manager crew reads blackboard entries,
moderates device keys, and forwards resolved items to the map overlay pipeline.

> Blackboard code lives in the `scout_crew` repo (`src/scout_crew/blackboard/`).
> This guide covers the routing-repo provisioning and the per-device scoping seams.

## Hub layout (after `map_server_setup/setup_blackboard.sh`)

| Path | Contents |
|------|----------|
| `~/.config/scout/blackboard.env` (0600) | master secret, entry token, host/port, data path, sandbox writers |
| `~/.scout/blackboard/scout_blackboard.db` | file-backed store (SQLite), recreated per-device on the hub |
| `~/.scout/blackboard/tokens/manager.token` (0600) | crew manager moderation token (90-day) |
| `~/.scout/blackboard/tokens/<id>.token` (0600) | per-device bearer token (30-day, authorized) |
| `~/.scout/blackboard/devices/<id>.json` | authorize response incl. `observed_ip` (presence record) |
| `~/.config/systemd/user/scout-blackboard.service` | user service (pure-stdlib venv `.venv-bb`) |
| `stack/config/vehicle_stack.env` | managed exports: `SCOUT_BLACKBOARD_URL/HOST/PORT/SANDBOX_WRITERS` |

**Secret policy:** the master secret lives only on the hub (`blackboard.env`, 0600).
Peers/apps hold only their own minted token (`SCOUT_BLACKBOARD_TOKEN`). Rotating the
master secret orphans every outstanding token by design (tokens are HMAC-signed).

## Key authorization model

- `POST /v1/keys/authorize` — device onboarding. Proof = configured entry token
  (pre-shared to authorized devices) or a captcha/proof marker when the entry token
  is unset. Claims: `role` (a specialist pipeline role, default `alert`), `categories`
  (`pipeline`), `device_id` (operator or server-assigned; **never an IMEI**),
  `ttl`, `iat/exp/jti`.
- `POST /v1/keys/issue` — manager/CLI minting with the **master secret**
  (`X-Scout-Admin`). This is the moderation path, not the deploy path.
- `POST /v1/keys/revoke` — revoke by `jti` (master secret).
- `GET /v1/audit` — manager-only; event log + per-token write volume (flood watch).
- Data endpoints (`/v1/write`, `/v1/read`, `/v1/snapshot`) require a valid bearer;
  role/category mismatch → 403, missing/invalid/revoked → 401.

`SCOUT_BLACKBOARD_SANDBOX_WRITERS` (default `alert`) names the roles the raw
scan-pipeline is allowed to write as; everything else is manager-write/rewrite so a
compromised device cannot drown the blackboard undisturbed.

## Per-device scoping (minimal, this branch)

Strictly device-oriented seams, with no device-side secrets beyond the token:

1. **Device-id assignment** — device ids are assigned at `/v1/keys/authorize` time
   (operator-supplied `--device <id>` in `setup_blackboard.sh`, or auto `uuid4().hex`).
   Ids are namespaced to the token's `device_id` claim; no IMEI/MAC collection.
2. **IP-presence check** — the server records the caller's `observed_ip` on each
   authorize. `verify_blackboard.sh` asserts it is within the Scout mesh
   (`10.66.0.0/16`); mismatch means the "device" is off-mesh and should be treated
   as unauthenticated in the mesh sense.
3. **Role/category scoping** — device tokens carry a specialist pipeline role
   (`alert` by default, same as `SCOUT_BLACKBOARD_SANDBOX_WRITERS`; `--device-role`
   switches to intel/vet/rank/core/dev) restricted to the `pipeline` category. The
   device's identity is the `device_id` claim (e.g. `smoke-goose`), which the store
   records as the entry `author` alongside the role. A device cannot write as a
   different role or into `moderation`/`dev_debug` categories it is not scoped to.
4. **Analytics injection guards** — per-device map/blackboard activity is keyed by
   `device_id` (the token claim), never by free-form header. The map-usage per-device
   analytics allocator lands with PR #8 ("Dynamic mesh IP binding, paywall gates, and
   unit tests"); this branch only establishes the `devices/<id>.json` presence records
   it is keyed on.
5. **Transport hardening (production)** — run the blackboard behind a mesh- or
   localhost-only HTTPS terminator on the hub; the default service binds `0.0.0.0:8765`
   because scoutwg0 traffic is already constrained to the mesh interface.

## Moderation runbook

```bash
# As the crew manager (has manager.token):
export SCOUT_BLACKBOARD_URL=http://<hub>:8765
export SCOUT_BLACKBOARD_TOKEN="$(cat ~/.scout/blackboard/tokens/manager.token)"

# Watch the audit log + per-token volume
curl -s -H "Authorization: Bearer $SCOUT_BLACKBOARD_TOKEN" \
  "$SCOUT_BLACKBOARD_URL/v1/audit?limit=100"

# Review moderation-category entries, then rewrite approved ones or revoke the device
curl -s -H "Authorization: Bearer $SCOUT_BLACKBOARD_TOKEN" \
  "$SCOUT_BLACKBOARD_URL/v1/read?category=moderation"
```

## Interlock with PR #8

`routing-scouting-app-to-be-named#8` adds dynamic mesh IP/endpoint binding, the
per-device allocator, and paywall gates on top of `main-2`. The blackboard branch
provides the token/secret seams it composes with: per-device issued tokens instead of
shared secrets, `devices/<id>.json` presence records keyed by the same `device_id`,
and a manager moderation surface. Neither branch rewrites the other's files; merge
order is `scout/blackboard-on-mapserver` first (no conflicts with #8's surface).

## Gotchas

- Tokens are HMAC-signed with expiry; a hub clock skew > token `exp` invalidates
  devices. Keep the hub on NTP.
- `--no-systemd` still mints tokens (for non-systemd hosts); the runtime just isn't
  supervised.
- The entry token is a shared onboarding secret — distribute it the same way as
  `SCOUT_PEER_MESH_IP` (mesh-only config), not in the public scaffolding.