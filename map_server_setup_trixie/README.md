# Scout Map Server Setup Suite - Debian 13 (trixie)

Bootstrap, configure, sync, and verify a **Scout map server + sharding system** on a
**Debian 13 (trixie)** machine. This mirrors the Pop!_OS/Ubuntu suite in
`map_server_setup/` but is tuned for trixie, where the toolchain differences are
small but real:

| Concern | Pop!_OS 22.04 / Ubuntu 22.04 | Debian 13 (trixie) |
|---------|------------------------------|--------------------|
| OpenJDK 21 | NOT in apt (SDKMAN workaround) | in apt: `openjdk-21-jdk` |
| `rsync` | in apt | in apt (not preinstalled) |
| sshd | `openssh-server`, service `ssh` | same; socket-activated unit present |
| Tailscale | official installer | same installer, Debian repo auto-detected |

Scope: **map server + shards only**. The scanner pipeline, Ollama scout models,
blackboard, and CrewAI are out of scope - see `stack/` and `docs/guides/` for the
full-stack runbooks.

## Layout

| Script | Purpose |
|--------|---------|
| `bootstrap_trixie.sh` | apt prereqs (git, JDK 21, rsync, jq, sshd, python3), optional Tailscale |
| `sync_shards.sh` | rsync MVT shard cache + text-map roots from a source host |
| `configure_map_server.sh` | build backend jar, export map env, optional systemd |
| `verify_map_server.sh` | health checks: backend, planet, shard prefetch, frontend |

All scripts are idempotent and reusable; run them from this `map_server_setup_trixie/`
directory inside a fresh clone of the repo.

## Data model (what "sharding" means here)

The backend serves maps from a disk shard cache and mirrors it through several
representations:

| Data | Path | Notes |
|------|------|-------|
| MVT shard cache | `~/.scanner_stream/map_cache/shards/<STATE>/<z>_<x>_<y>.mvt.gz` | served by backend; grows via `/api/map/shard?state=...` prefetch |
| Low-zoom shards | `~/.scanner_stream/map_cache/shards/_zNN/...` | zoom < 10, per-zoom dirs |
| Overpass cells | `~/.scanner_stream/map_cache/shards/<STATE>/cell_15_<x>_<y>.json` | z15 detail |
| Text-map roots | `<repo>/vlm_text_map_shards/` + `vlm_text_map_shards_chunked/` | committed for AZ; re-synced here |
| Planet source | `PLANET_PMTILES_URL` (Protomaps PMTiles, HTTP range) | fallback when tile is not cached |

## Quickstart (on the new Debian trixie host)

```bash
# 1. Get the repo (shards + AZ text roots come with it)
git clone https://github.com/scout-hazard-system/routing-scouting-app-to-be-named.git
cd routing-scouting-app-to-be-named/map_server_setup_trixie

# 2. System prerequisites (JDK 21 ships in trixie apt)
./bootstrap_trixie.sh                       # add --with-tailscale if joining the tailnet

# 3. Trust the source hub and pull shard data
./sync_shards.sh <user>@<source-host>       # MVT cache + AZ text roots

# 4. Build + configure this machine as a map server
./configure_map_server.sh --advertise auto

# 5. Start and verify
cd .. && ./master start
./map_server_setup_trixie/verify_map_server.sh --start
```

Resulting mesh URLs (once `SCOUT_NETWORK_ADVERTISE_HOST` is set):

```text
Frontend:      http://<this-host-ip>:8787/
Backend:       http://<this-host-ip>:18080/api/health
Map status:    http://<this-host-ip>:18080/api/map/status
Map shard AZ:  http://<this-host-ip>:18080/api/map/shard?state=AZ
```

## Deployment package alternative

The map backend can also be deployed from the self-contained `map-server-deploy`
package (`BackendServer.java`, `MapModel.java`, etc. plus `dist/backend-lite.jar`)
instead of a full repo clone. That package expects the text-map shards at
`./vlm_text_map_shards/` **relative to the server's working directory**
(`README_DEPLOY.md`), and the backend reports them under
`SCOUT_TEXT_MAP_SHARD_ROOTS`. Everything else (env names, endpoints, prefetch)
is identical to the repo-clone flow above.

Environment for a bare `java -jar` launch:

```bash
cd <deploy-dir>
SCOUT_TEXT_MAP_SHARD_ROOTS="$PWD/vlm_text_map_shards,$PWD/vlm_text_map_shards_chunked" \
  java -jar dist/backend-lite.jar
```

Without `SCOUT_TEXT_MAP_SHARD_ROOTS` the jar defaults to
`~/Desktop/vlm_text_map_shards{,chunked}`, so /`api/map/status` reports the
`text_map_shards` roots as missing unless the data lives there.

## Script reference

### `bootstrap_trixie.sh` `[--with-tailscale]`

Installs `git curl rsync openssh-server jq unzip ca-certificates python3 python3-venv`
and `openjdk-21-jdk-headless` via apt, enables `sshd`, and optionally installs
Tailscale. On trixie OpenJDK 21 is packaged, so no SDKMAN fallback is needed -
unlike the Pop!_OS/Ubuntu suite.

### `sync_shards.sh` `<user@host[:port]> [remote-repo]`

Pulls over rsync-over-SSH:
1. `~/.scanner_stream/map_cache/shards/` -> local `~/.scanner_stream/map_cache/shards/`
   (the MVT + overpass tiles the backend serves from disk).
2. `<repo>/vlm_text_map_shards/<STATE>/` and
   `<repo>/vlm_text_map_shards_chunked/<STATE>/` -> matching local repo paths
   (default remote repo: the source's `~/Desktop`).

Environment: `MAP_STATE` (default `AZ`), `MAP_CACHE_DIR`, `REMOTE_REPO_ROOT`,
`SYNC_TEXT_SHARDS=1|0`, `MIRROR=1` (mirror cache with `--delete`), `VERBOSE=1`.

### `configure_map_server.sh`

- Builds `navigation/backend/dist/backend-lite.jar` (JDK 21+ from trixie apt).
- Pins `MAP_SHARD_STATE` / `JURISDICTION_STATE` (`--state`, default from repo env or `AZ`).
- Sets `SCOUT_NETWORK_ADVERTISE_HOST` (`--advertise auto|keep|<ip>`; `auto` uses Tailscale IPv4).
- Appends an idempotent "managed exports" block to `stack/config/vehicle_stack.env` so the
  stack launcher actually passes the map env to the backend:
  `SCOUT_TEXT_MAP_SHARD_ROOTS`, `MAP_SHARD_STATE`, `JURISDICTION_STATE`,
  `SCOUT_NETWORK_ADVERTISE_HOST`, `PLANET_PMTILES_URL`.
- `--systemd` also runs `stack/deployment/install_user_service.sh`.

### `verify_map_server.sh` `[--start] [--state AZ] [--host 127.0.0.1]`

Checks `/api/health`, `/api/map/status` (planet readiness, shard inventory, text roots),
kicks the `/api/map/shard?state=<STATE>` prefetch, and probes the frontend. Exits non-zero
if any check fails. `--start` auto-starts the stack via `./master start`.

## Optional: systemd supervision

```bash
./map_server_setup_trixie/configure_map_server.sh --systemd
systemctl --user start vehicle-stack.service
systemctl --user status vehicle-stack.service
journalctl --user -u vehicle-stack.service -f
```

## Notes / gotchas

- The backend reads map env from the **process environment**, so the launcher must export
  `vehicle_stack.env` values. `configure_map_server.sh` handles this with its exports block;
  do not rely on plain `KEY=value` lines reaching the backend otherwise.
- On trixie, sshd can be socket-activated (`ssh.socket`). `bootstrap_trixie.sh` enables
  `ssh.service`; if `systemctl is-active ssh` looks odd, confirm with `ss -tlnp | grep :22`.
- First stack start builds the frontend executable via PyInstaller (needs network for pip);
  `run_vehicle_stack.sh` falls back to `python3 dev_server.py` if the build is unavailable.
- New host IPs/URLs should be pinned in the team runbook
  (`docs/guides/PEER_MESH_DEPLOYMENT.md`) once provisioned.
- See `docs/guides/FINAL_DEPLOYMENT_CONFIG.md` for the verified branch baseline and
  `stack/deployment/README.md` for the general runtime model.