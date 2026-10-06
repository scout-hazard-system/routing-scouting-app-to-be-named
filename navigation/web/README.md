# Scout web (public user frontend)

Cloudflare Pages project **`scout`** → https://scout-386.pages.dev

Landing page (visual language peeled from the Imagoro portfolio: ASCII brand, slat backdrop) plus `/app`:
map, place search, route search and hazard **cluster summaries**. No admin, harness, blackboard or agent data.

```
npm install
npm test          # gateway allowlist / scrub / summary tests
npm run build
npm run deploy    # wrangler pages deploy dist --project-name scout --branch main
```

## Gateway (`functions/api/[[path]].ts`, rules in `gateway/lib.ts`)

Everything under `/api/*` is an allowlist; anything else is a 404 at the edge.

| Public route | Upstream (map server) | Response |
|---|---|---|
| `/api/health` | `/api/health` | `{online}` |
| `/api/tiles/{z}/{x}/{y}.png` | `/api/map/render` (tile centre, tilt 0, 256 px) | PNG, edge-cached |
| `/api/geocode?q=[&lat&lon]` | `/api/platform/geocode` | scrubbed results |
| `/api/route?origin_lat…dest_lon` | `/api/platform/route/options` | routes/alternatives only, scrubbed |
| `/api/alerts?cells=` (≤9 geohash-4 cells) | `/api/platform/hazards?shards=` | per-cell summary: count, worst severity, kinds, roads, source names |

Never proxied: `/api/platform/alerts/clusters` and `/api/map/scene` (they embed raw radio transcripts),
admin/mesh/mobile/assistant/pipeline/broadcastify. `scrub()` also drops `alert_clusters`, `transcript`,
`waze_*`, `signature`, `content_hash`, `ingest_host`, `source_url` at any depth.

Privacy: the browser computes its geohash-4 cell on-device (`src/shard-client.js`, a parity-tested copy of
`navigation/sources/clients/shard-client.js`); only route search sends the two user-chosen points.

## Connecting the map service (not done yet)

Pages env for the `scout` project:

- `ORIGIN`: the Dell map server through a Cloudflare Tunnel, e.g. `https://scout-api.<your-domain>`.
  Unset = offline mode (OSM basemap, everything else reports `service_offline`).
- `ORIGIN_SUBSCRIPTION` (secret): an `X-Scout-Subscription` token issued for the public site
  (`/api/admin/subscription/issue`), revocable on its own.

Needs: a domain on the Cloudflare account (for the tunnel hostname), the Dell running a backend build that
has `/api/platform/hazards` (PR #23), and a WAF rate-limit rule once the zone exists (the in-function
limiter is per isolate only).
