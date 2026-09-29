# Scout Map Server — Ventoy Deployable Package

This package contains the **pre-built text map shards** and an **automated setup script** for deploying the Scout map server stack on Debian 13.6+.

## Package Contents

```
scout-map-server-deploy/
├── vlm_text_map_shards/           # ~192 MB - Full text shards (z/x/y hierarchy)
├── vlm_text_map_shards_chunked/   # ~192 MB - Chunked shards for LLM context injection
├── scripts/
│   └── setup-map-server.sh        # Automated deployment script (run as root)
└── docs/
    └── DEPLOYMENT.md              # This file
```

**Total package size: ~400 MB** (PMTiles planet file downloaded separately — see below)

## Quick Start (Debian 13.6)

### 1. Extract to target machine
```bash
# If using Ventoy: boot into Debian live, mount the Ventoy partition
# Copy this folder to the target machine (e.g., /home/user/scout-map-server-deploy)
# Or extract from zip:
unzip scout-map-server-deploy.zip -d /home/user/
```

### 2. Run automated setup
```bash
cd /home/user/scout-map-server-deploy/scripts
sudo ./setup-map-server.sh
```

This will:
- Install dependencies (curl, aria2, jq, python3)
- Create `/opt/scout/` directory structure
- Deploy text shards to `/opt/scout/map-shards*` (matches backend expectations)
- **Download PMTiles planet file (~16GB)** from Protomaps to `/opt/scout/pmtiles/20260811.pmtiles`
- Verify download integrity
- Create `/etc/scout/map-server.env` with all required environment variables
- Create Ollama systemd drop-in for Tailscale mesh (`OLLAMA_HOST=0.0.0.0:11434`)

### 3. Complete the stack
```bash
# Install Ollama
curl -fsSL https://ollama.com/install.sh | sh
sudo systemctl enable --now ollama

# Pull base model + build scout models
ollama pull qwen3:8b
bash /path/to/repo/llm/build/build_llm_set.sh

# Compile & run backend (from your repo root)
cd /path/to/repo/navigation/backend
javac *.java
source /etc/scout/map-server.env
java -jar dist/backend-lite.jar
```

### 4. Verify
```bash
curl http://localhost:18080/api/health
curl http://localhost:18080/api/map/status
curl 'http://localhost:18080/api/map/shard?state=AZ'
```

## Offline / Air-Gapped Deploy

If the target machine has no internet, pre-download the PMTiles file on another machine:

```bash
# On a machine with internet:
wget https://build.protomaps.com/20260811.pmtiles
# Copy 20260811.pmtiles to the target machine (e.g., /tmp/20260811.pmtiles)

# On target machine:
sudo ./setup-map-server.sh --pmtiles-path /tmp/20260811.pmtiles
```

Or use a custom mirror:
```bash
sudo ./setup-map-server.sh --pmtiles-url https://your-mirror.example.com/20260811.pmtiles
```

## What the Backend Expects

The Java backend (`ProprietaryMapEngine`, `BackendServer`) reads these environment variables:

| Variable | Value (set by setup script) |
|----------|----------------------------|
| `PLANET_PMTILES_URL` | `file:///opt/scout/pmtiles/20260811.pmtiles` |
| `SCOUT_TEXT_MAP_SHARD_ROOTS` | `/opt/scout/map-shards,/opt/scout/map-shards-chunked` |
| `SCOUT_MAP_CACHE_DIR` | `/var/lib/scanner_stream/map_cache` |
| `JURISDICTION_STATE` | `AZ` |
| `MAP_SHARD_STATE` | `AZ` |

The backend also requires:
- `SCOUT_REPO_ROOT` — set by launcher scripts to repo root
- Ollama running on `localhost:11434` with `qwen3:8b` and scout models

## Tailscale Mesh Configuration

For multi-machine deployments (Linux hub + Windows Hermes peer):

1. Install Tailscale on both machines
2. On Linux hub: `sudo tailscale up`
3. Note the Tailscale IP: `tailscale ip -4` (e.g., `100.78.191.61`)
3. Set in `stack/config/vehicle_stack.env`:
   ```bash
   SCOUT_NETWORK_ADVERTISE_HOST=100.78.191.61
   ```
4. Backend binds `0.0.0.0:18080`, frontend `0.0.0.0:8787` — accessible via Tailscale IP
5. Windows peer Ollama must listen on Tailscale only: `OLLAMA_HOST=0.0.0.0:11434` + firewall to `100.64.0.0/10`

## Directory Layout After Deploy

```
/opt/scout/
├── map-shards/                    # Text shards (z/x/y/*.txt)
│   ├── AZ/
│   ├── CA/
│   ├── _z03/ _z05/ _z07/ _z09/    # Zoom-level shards
│   └── ...
├── map-shards-chunked/            # Chunked for LLM context
│   └── (same structure)
└── pmtiles/
    └── 20260811.pmtiles           # ~16GB planet vector tiles

/var/lib/scanner_stream/map_cache/
└── shards/
    └── AZ/                        # Warmed by backend on demand

/etc/scout/
└── map-server.env                 # Environment for backend launch
```

## Troubleshooting

| Issue | Fix |
|-------|-----|
| PMTiles download fails | Use `aria2c` (installed by script) or `--pmtiles-path` with local file |
| Backend can't find shards | Verify `SCOUT_TEXT_MAP_SHARD_ROOTS` in `/etc/scout/map-server.env` |
| Map tiles not loading | Check `/var/lib/scanner_stream/map_cache/` permissions (user 10001) |
| `api/map/shard?state=AZ` returns 404 | Ensure `MAP_SHARD_STATE=AZ` and backend has write access to cache dir |
| Ollama not reachable from Windows | Verify `OLLAMA_HOST=0.0.0.0:11434` and Tailscale firewall |

## License

- Shard data: Derived from OpenStreetMap (ODbL 1.0) — attribution required
- PMTiles: Protomaps build (CC BY 4.0 / ODbL compatible)
- Setup script: Apache-2.0

---

**Note:** This package does **not** include the PMTiles planet file (~16GB) due to size. The setup script downloads it automatically on first run, or you can provide it via `--pmtiles-path`.