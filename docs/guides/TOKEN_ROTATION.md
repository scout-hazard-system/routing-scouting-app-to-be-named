# Scout token rotation (admin)

Three secret classes — never collapse them:

| Secret | Env / file | Who holds it | Purpose |
|--------|------------|--------------|---------|
| Mesh **entry** token | `SCOUT_MESH_ENTRY_TOKEN` / `stack/mesh/state/entry_token` | **Admin machines only** | `POST /api/mesh/enroll` — transport identity only |
| **Admin** token | `SCOUT_ADMIN_TOKEN` / `stack/mesh/state/admin_token` | **Three admin machines** | `X-Scout-Admin-Token` — stack/mesh control, bypass paywall |
| **Subscription** token | issued to device, store `stack/mesh/state/subscriptions.tsv` | Registered Android AP | `X-Scout-Subscription` — paid nav APIs |

## Rotate entry token
1. Generate new value: `openssl rand -hex 16` → prefix `dev-entry-` or `entry-`.
2. Write `stack/mesh/state/entry_token` and set `SCOUT_MESH_ENTRY_TOKEN` in backend process env / `runtime.env`.
3. Restart Java backend.
4. Existing WG peers stay up; **new** enrolls need the new entry token.
5. Do not put entry token in Android APKs long-term; issue out-of-band.

## Rotate admin token
1. `openssl rand -base64 32` → prefix `sat_`.
2. Update `stack/mesh/state/admin_token` and `SCOUT_ADMIN_TOKEN`.
3. Restart backend.
4. Update operator secret stores on the three admin hosts only (hub, operator Pop!_OS, Windows Hermes ops box).
5. Never ship admin token to Android clients.

## Issue / revoke subscription (paywall)
```bash
# issue device-bound token (admin)
curl -s -X POST http://10.66.0.1:18080/api/admin/subscription/issue \
  -H "X-Scout-Admin-Token: $SCOUT_ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"device_id":"android-device-1","ttl_seconds":0}'

# client calls paid APIs with:
#   X-Scout-Subscription: sub_...
#   X-Scout-Device-Id: android-device-1

# revoke on peer removal
curl -s -X POST http://10.66.0.1:18080/api/mesh/peer/revoke \
  -H "X-Scout-Admin-Token: $SCOUT_ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"device_id":"android-device-1"}'
```

## Invariants
- Mesh join ≠ paid entitlement.
- Unregistered Android APs hit **402 paywall** on nav/map/mobile routes when `SCOUT_SUBSCRIPTION_REQUIRED=true`.
- Only admin machines hold hub WG private key + admin token + SSH operator key.
