# Scout Map Server Setup Suite

Bootstrap, wire, sync, and verify a **Scout map server + sharding system** on a fresh
Pop!_OS / Ubuntu machine. The suite stands up the Java map backend (`:18080`), the
frontend dashboard (`:8787`), and seeds the on-disk shard data by pulling it live from
an existing source host over rsync-over-SSH.

Scope: **map server + shards**, and optionally the **blackboard on the hub**. The
scanner pipeline, Ollama scout models, blackboard (on peer/apps), and CrewAI are
out of scope — see `stack/` and `docs/guides/` for the full-stack runbooks.

## Layout

| Script | Purpose |
|--------|---------|
| `bootstrap_popos.sh` | apt prereqs (git, JDK 21, rsync, jq, sshd, python3), optional Tailscale |
| `ssh_setup.sh` | wire SSH both directions between new host and source host |
| `sync_shards.sh` | rsync MVT shard cache `~/.scanner_stream/map_cache/shards` + text-map roots from source |
| `configure_map_server.sh` | build backend jar, wire `vehicle_stack.env`, export map env, optional systemd |
| `setup_blackboard.sh` | bootstrap the key-authorized blackboard on the hub: secrets, venv, systemd service, manager + per-device tokens |
| `verify_map_server.sh` | health checks: backend, planet, shard prefetch, frontend |
| `verify_blackboard.sh` | health, key-authorization round trip, manager audit, per-device mesh presence |

All scripts are idempotent and reusable; run them from this `map_server_setup/` directory.

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

## Quickstart (on the new Pop!_OS host)

```bash
# 1. Get the repo (shards + AZ text roots come with it)
git clone https://github.com/scout-hazard-system/routing-scouting-app-to-be-named.git
cd routing-scouting-app-to-be-named/map_server_setup

# 2. System prerequisites
./bootstrap_popos.sh                       # add --with-tailscale if joining the tailnet

# 3. SSH between machines
./ssh_setup.sh install                     # sshd (already done by bootstrap)
./ssh_setup.sh key                         # generate ~/.ssh/id_ed25519_popos
./ssh_setup.sh show                        # copy this pubkey onto the source host now
./ssh_setup.sh send-key gibi@100.78.191.61 # or: ssh-copy-id gibi@100.78.191.61
./ssh_setup.sh test gibi@100.78.191.61     # expect "SSH OK"

# 4. Trust the source hub back (optional, reverse SSH)
./ssh_setup.sh authorize ../scout_windows_deploy/ssh/id_ed25519_popos.pub

# 5. Pull the shard data from the source host
./sync_shards.sh gibi@100.78.191.61        # MVT cache + AZ text roots

# 6. Build + configure this machine as a map server
./configure_map_server.sh --advertise auto

# 7. Start and verify
./master start
./verify_map_server.sh --start
```

Resulting mesh URLs (once `SCOUT_NETWORK_ADVERTISE_HOST` is set):

```text
Frontend:      http://<this-host-ip>:8787/
Backend:       http://<this-host-ip>:18080/api/health
Map status:    http://<this-host-ip>:18080/api/map/status
Map shard AZ:  http://<this-host-ip>:18080/api/map/shard?state=AZ
Blackboard:    http://<this-host-ip>:8765/health   (after setup_blackboard.sh)
```

## Blackboard on the hub (optional)

The hub also hosts the Scout blackboard (`:8765`) for the multi-machine crew.
Bootstrap it after `configure_map_server.sh`:

```bash
# SCOUT_CREW_ROOT defaults to ~/Desktop/scout_crew (needs src/scout_crew/blackboard/)
./setup_blackboard.sh --device az-vehicle --device ground-station
./verify_blackboard.sh
```

What `setup_blackboard.sh` does:

1. Creates a venv at `<scout_crew>/.venv-bb` (the blackboard is pure-stdlib).
2. Generates the **master secret** and **entry token** once into `~/.config/scout/blackboard.env`
   (0600). The secret never leaves the hub; peer devices only ever hold their own minted token.
3. Appends a managed exports block to `stack/config/vehicle_stack.env`
   (`SCOUT_BLACKBOARD_URL`, `SCOUT_BLACKBOARD_HOST/PORT`, `SCOUT_BLACKBOARD_SANDBOX_WRITERS`).
4. Installs + starts the `scout-blackboard` user service
   (`stack/deployment/install_blackboard_service.sh` + `systemd/scout-blackboard.service`).
5. Mints the **manager token** (`~/.scout/blackboard/tokens/manager.token`) for crew
   moderation, and one **per-device token** per `--device` via key authorization
   (`~/.scout/blackboard/tokens/<id>.token`, plus `devices/<id>.json` with `observed_ip`).

Device tokens are role-scoped, category-scoped, and time-limited; the `/v1/audit`
endpoint (manager-only) shows per-token volume for flood moderation. See
`docs/guides/SCOUT_BLACKBOARD_ON_MAPSERVER.md` for the full model and the
per-device scoping seams (device-id assignment, IP-presence check, analytics).

## Script reference

### `bootstrap_popos.sh` `[--with-tailscale]`

Installs `git curl rsync openssh-server jq unzip ca-certificates python3 python3-venv`,
ensures a JDK 21+ (`javac`), enables `sshd`, and optionally installs Tailscale.
On Pop!_OS 22.04 / Ubuntu 22.04, OpenJDK 21 is not in apt; the script prints
SDKMAN install steps and exits.

### `ssh_setup.sh`

```bash
./ssh_setup.sh install                     # enable + start sshd
./ssh_setup.sh key [name]                  # ed25519 keypair (default id_ed25519_popos)
./ssh_setup.sh show [name]                 # print local pubkey for the other host
./ssh_setup.sh authorize <pubkey|path>     # trust the other host's key here
./ssh_setup.sh send-key <user@host[:port]> [name]   # push our key via ssh-copy-id
./ssh_setup.sh test <user@host[:port]> [name]       # passwordless SSH check
```

The source host only needs our public key to accept the shard pulls
(`sync_shards.sh` uses `BatchMode=yes` and fails fast if the key is not installed).

### `sync_shards.sh` `<user@host[:port]> [remote-repo]`

Pulls, over rsync-over-SSH:

1. `~/.scanner_stream/map_cache/shards/` → local `~/.scanner_stream/map_cache/shards/`
   (the MVT + overpass tiles the backend serves from disk).
2. `<repo>/vlm_text_map_shards/<STATE>/` and `<repo>/vlm_text_map_shards_chunked/<STATE>/`
   → matching local repo paths (default remote repo: the source's `~/Desktop`).

Environment: `MAP_STATE` (default `AZ`), `MAP_CACHE_DIR`, `REMOTE_REPO_ROOT`,
`SYNC_TEXT_SHARDS=1|0`, `MIRROR=1` (mirror cache with `--delete`), `VERBOSE=1`.

### `configure_map_server.sh`

- Builds `navigation/backend/dist/backend-lite.jar` (requires JDK 21+).
- Pins `MAP_SHARD_STATE` / `JURISDICTION_STATE` (`--state`, default from repo env or `AZ`).
- Sets `SCOUT_NETWORK_ADVERTISE_HOST` (`--advertise auto|keep|<ip>`; `auto` uses Tailscale IPv4).
- Appends an idempotent "managed exports" block to `stack/config/vehicle_stack.env` so the
  stack launcher actually passes the map env to the backend:
  `SCOUT_TEXT_MAP_SHARD_ROOTS`, `MAP_SHARD_STATE`, `JURISDICTION_STATE`,
  `SCOUT_NETWORK_ADVERTISE_HOST`, `PLANET_PMTILES_URL`.
- `--systemd` also runs `stack/deployment/install_user_service.sh`.

`SCOUT_TEXT_MAP_SHARD_ROOTS` points at this repo's `vlm_text_map_shards` and
`vlm_text_map_shards_chunked` so `/api/map/status` reports the text roots as present.

### `verify_map_server.sh` `[--start] [--state AZ] [--host 127.0.0.1]`

Checks `/api/health`, `/api/map/status` (planet readiness, shard inventory, text roots),
kicks the `/api/map/shard?state=<STATE>` prefetch, and probes the frontend. Exits non-zero
if any check fails. `--start` auto-starts the stack via `./master start`.

## Optional: systemd supervision

```bash
./map_server_setup/configure_map_server.sh --systemd
systemctl --user start vehicle-stack.service
systemctl --user status vehicle-stack.service
journalctl --user -u vehicle-stack.service -f
```

## Notes / gotchas

- The backend reads map env from the **process environment**, so the launcher must export
  `vehicle_stack.env` values. `configure_map_server.sh` handles this with its exports block;
  do not rely on plain `KEY=value` lines reaching the backend otherwise.
- First stack start builds the frontend executable via PyInstaller (needs network for pip);
  `run_vehicle_stack.sh` falls back to `python3 dev_server.py` if the build is unavailable.
- New host IPs/URLs should be pinned in the team runbook
  (`docs/guides/PEER_MESH_DEPLOYMENT.md`) once provisioned.
- See `docs/guides/FINAL_DEPLOYMENT_CONFIG.md` for the verified branch baseline and
  `stack/deployment/README.md` for the general runtime model.