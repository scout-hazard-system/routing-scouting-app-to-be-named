# scout_sources — accountable hazard events

Official and commercial traffic/weather feeds normalized into one signed,
citable event format (`HazardEvent`): stdlib only, keys from env only.

| provider | coverage | key |
|---|---|---|
| `nws` | US weather alerts (api.weather.gov) | none |
| `wzdx` | USDOT Work Zone Data Exchange: ~29 active keyless state feeds (registry on data.transportation.gov) | none |
| `511` | state 511 systems on the shared v2 platform (`/api/v2/get/event`): AZ, UT, GA, WI, CT verified | `SCOUT_511_KEY_<ST>` (free, per state) |
| `tomtom` | national incidents (Traffic Incident Details v5) | `SCOUT_TOMTOM_KEY` |

Each event carries `event_id` (stable per upstream record), `content_hash`
(new revision when upstream changes), `observed_at`, `ingest_host` and an
HMAC `signature` (`SCOUT_SOURCES_SIGNING_KEY`). Agents cite `event_id`;
`scout_sources.verify(event, key)` proves the content wasn't altered.
Only new/changed events are emitted (state in `$SCOUT_STATE_DIR`).

```
python -m scout_sources poll --lat 40.76 --lon -111.89 --radius-km 60 --states UT
python -m unittest discover -s tests
```

Broadcastify: the pipeline's web-player capture is disabled by default
(`SCOUT_ALLOW_BROADCASTIFY_WEB_CAPTURE`) because it circumvents Broadcastify's
access controls. Audio will come from the official Broadcastify API (on
approval) and/or the project's own SDR receivers.
