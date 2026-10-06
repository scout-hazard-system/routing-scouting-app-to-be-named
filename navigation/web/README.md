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

## Live setup (2026-10-06)

- Site: https://scoutnavigation.stream (+ www), Pages project `scout` (also https://scout-386.pages.dev).
- `ORIGIN` = https://api.scoutnavigation.stream: Cloudflare Tunnel `scout-dell-api` (remotely managed,
  `scout-tunnel.service` on the Dell, token in `/etc/cloudflared/scout-tunnel.env`, root 600).
- Tunnel target: nginx gate `127.0.0.1:18090` on the Dell (`/etc/nginx/conf.d/scout-public-gate.conf`,
  root 600). It 404s anything without `X-Scout-Edge-Key`; only health, map render, geocode,
  route/options and hazards pass. No access log. It strips caller auth headers and injects the
  `public-web` subscription token, which never leaves the Dell.
- Pages secrets: `ORIGIN`, `EDGE_KEY`. Rotate the edge key on both sides together.
- Alerts are served from the hazard service (`127.0.0.1:8770/v1/hazards`) by the gate.
- Tiles: `/api/map/render?...&tile=1` (no device marker, no per-tile attribution). Bump `?v=` in
  `src/MapApp.tsx` after renderer changes so edge-cached tiles refresh.
- Still to do: a WAF rate-limit rule on the zone (the in-function limiter is per isolate; the gate also
  rate-limits per CF-Connecting-IP at 30 r/s).
