#!/usr/bin/env bash
# Scout Map Server Deploy Script
# Run this on the target Debian 13.6 machine after extracting the deploy package.
# Usage: sudo ./setup-map-server.sh [--pmtiles-url URL] [--pmtiles-path PATH] [--skip-download]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_ROOT="$(dirname "$SCRIPT_DIR")"
SHARDS_SRC="$DEPLOY_ROOT/vlm_text_map_shards"
SHARDS_CHUNKED_SRC="$DEPLOY_ROOT/vlm_text_map_shards_chunked"

# Default PMTiles (Protomaps 2026-08-11 planet)
DEFAULT_PMTILES_URL="https://build.protomaps.com/20260811.pmtiles"
DEFAULT_PMTILES_FILENAME="20260811.pmtiles"

# Target locations (match backend expectations)
TARGET_SHARDS_ROOT="/opt/scout/map-shards"
TARGET_SHARDS_CHUNKED_ROOT="/opt/scout/map-shards-chunked"
PMTILES_DIR="/opt/scout/pmtiles"
MAP_CACHE_DIR="/var/lib/scanner_stream/map_cache"

PMTILES_URL="$DEFAULT_PMTILES_URL"
PMTILES_PATH=""
SKIP_DOWNLOAD=false

usage() {
    cat <<EOF
Usage: $0 [OPTIONS]

Sets up the Scout map server stack on Debian 13.6+.

Options:
  --pmtiles-url URL     PMTiles download URL (default: $DEFAULT_PMTILES_URL)
  --pmtiles-path PATH   Local path to existing PMTiles file (skip download)
  --skip-download       Skip PMTiles download (use if already present)
  -h, --help            Show this help

Examples:
  # Full auto-download (~16GB, requires good bandwidth)
  sudo $0

  # Use pre-downloaded PMTiles file
  sudo $0 --pmtiles-path /path/to/20260811.pmtiles

  # Custom PMTiles source
  sudo $0 --pmtiles-url https://custom.example.com/planet.pmtiles
EOF
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --pmtiles-url) PMTILES_URL="$2"; shift 2 ;;
        --pmtiles-path) PMTILES_PATH="$2"; shift 2 ;;
        --skip-download) SKIP_DOWNLOAD=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1"; usage; exit 1 ;;
    esac
done

log() { echo "[$(date '+%H:%M:%S')] $*"; }
err() { echo "[$(date '+%H:%M:%S')] ERROR: $*" >&2; }

require_root() {
    if [[ $EUID -ne 0 ]]; then
        err "This script must run as root (use sudo)"
        exit 1
    fi
}

install_deps() {
    log "Installing dependencies..."
    apt-get update -qq
    apt-get install -y -qq curl wget aria2 jq python3 python3-requests 2>/dev/null || true
}

setup_directories() {
    log "Creating directory structure..."
    mkdir -p "$TARGET_SHARDS_ROOT" "$TARGET_SHARDS_CHUNKED_ROOT" "$PMTILES_DIR" "$MAP_CACHE_DIR/shards"
    chown -R 10001:10001 "$TARGET_SHARDS_ROOT" "$TARGET_SHARDS_CHUNKED_ROOT" "$PMTILES_DIR" "$MAP_CACHE_DIR" 2>/dev/null || true
}

deploy_shards() {
    log "Deploying text map shards..."
    if [[ -d "$SHARDS_SRC" ]]; then
        rsync -a --info=progress2 "$SHARDS_SRC/" "$TARGET_SHARDS_ROOT/"
    else
        err "Source shards not found at $SHARDS_SRC"
        exit 1
    fi
    if [[ -d "$SHARDS_CHUNKED_SRC" ]]; then
        rsync -a --info=progress2 "$SHARDS_CHUNKED_SRC/" "$TARGET_SHARDS_CHUNKED_ROOT/"
    else
        err "Source chunked shards not found at $SHARDS_CHUNKED_SRC"
        exit 1
    fi
    log "Shards deployed to $TARGET_SHARDS_ROOT and $TARGET_SHARDS_CHUNKED_ROOT"
}

download_pmtiles() {
    local dest_file="$PMTILES_DIR/$DEFAULT_PMTILES_FILENAME"
    if [[ -f "$dest_file" ]]; then
        log "PMTiles already exists at $dest_file (skipping download)"
        echo "$dest_file"
        return 0
    fi
    if [[ "$SKIP_DOWNLOAD" == "true" ]]; then
        err "PMTiles not found and --skip-download specified"
        exit 1
    fi
    log "Downloading PMTiles from $PMTILES_URL (~16GB)..."
    log "This will take a while. Use --pmtiles-path to use a local file instead."
    mkdir -p "$PMTILES_DIR"
    if command -v aria2c >/dev/null 2>&1; then
        aria2c -x 16 -s 16 -k 1M -d "$PMTILES_DIR" -o "$DEFAULT_PMTILES_FILENAME" "$PMTILES_URL"
    else
        wget -c -O "$dest_file" "$PMTILES_URL"
    fi
    log "Download complete: $dest_file"
    echo "$dest_file"
}

verify_pmtiles() {
    local pmtiles_file="$1"
    log "Verifying PMTiles integrity..."
    if [[ ! -f "$pmtiles_file" ]]; then
        err "PMTiles file not found: $pmtiles_file"
        return 1
    fi
    local size_bytes
    size_bytes=$(stat -c%s "$pmtiles_file" 2>/dev/null || stat -f%z "$pmtiles_file" 2>/dev/null)
    local size_gb=$((size_bytes / 1024 / 1024 / 1024))
    log "PMTiles size: ${size_gb}GB"
    if [[ $size_gb -lt 10 ]]; then
        err "PMTiles file seems too small (${size_gb}GB). Expected ~16GB for planet."
        return 1
    fi
    # Quick header check
    if head -c 4 "$pmtiles_file" | grep -q "PMTiles"; then
        log "PMTiles header check passed"
    else
        log "Warning: PMTiles header not recognized (may still work)"
    fi
    return 0
}

warm_az_cache() {
    log "Warming Arizona tile cache (optional, run backend to complete)..."
    # Backend handles actual tile prefetch via /api/map/shard?state=AZ
    # This just ensures the cache directory exists with correct permissions
    mkdir -p "$MAP_CACHE_DIR/shards/AZ"
    chown -R 10001:10001 "$MAP_CACHE_DIR" 2>/dev/null || true
}

create_env_file() {
    local pmtiles_file="$1"
    local env_file="/etc/scout/map-server.env"
    log "Creating environment file at $env_file..."
    mkdir -p "$(dirname "$env_file")"
    cat > "$env_file" <<EOF
# Scout Map Server Environment
# Generated by setup-map-server.sh on $(date)

# PMTiles planet file (required by backend)
PLANET_PMTILES_URL=file://$pmtiles_file

# Text map shard roots (required by backend for marker extraction)
SCOUT_TEXT_MAP_SHARD_ROOTS=$TARGET_SHARDS_ROOT,$TARGET_SHARDS_CHUNKED_ROOT

# Map cache directory (used by ProprietaryMapEngine)
SCOUT_MAP_CACHE_DIR=$MAP_CACHE_DIR

# Jurisdiction scope (AZ alpha)
JURISDICTION_STATE=AZ
MAP_SHARD_STATE=AZ
EOF
    chmod 644 "$env_file"
    log "Environment file created. Source it in your backend launch:"
    log "  source $env_file"
    log "  java -jar backend-lite.jar"
}

create_systemd_dropin() {
    local dropin_dir="/etc/systemd/system/ollama.service.d"
    log "Creating Ollama systemd drop-in for Tailscale mesh at $dropin_dir..."
    mkdir -p "$dropin_dir"
    cat > "$dropin_dir/tailscale.conf" <<'EOF'
[Service]
Environment=OLLAMA_HOST=0.0.0.0:11434
EOF
    systemctl daemon-reload
    log "Ollama drop-in created. Restart with: systemctl restart ollama"
}

print_summary() {
    local pmtiles_file="$1"
    cat <<EOF

============================================================
Scout Map Server Deployment Complete
============================================================

Deployed components:
  ✓ Text map shards:        $TARGET_SHARDS_ROOT
  ✓ Chunked text shards:    $TARGET_SHARDS_CHUNKED_ROOT
  ✓ PMTiles planet file:    $pmtiles_file
  ✓ Map cache directory:    $MAP_CACHE_DIR
  ✓ Environment file:       /etc/scout/map-server.env

Backend expects these environment variables (auto-loaded from env file):
  PLANET_PMTILES_URL=file://$pmtiles_file
  SCOUT_TEXT_MAP_SHARD_ROOTS=$TARGET_SHARDS_ROOT,$TARGET_SHARDS_CHUNKED_ROOT
  SCOUT_MAP_CACHE_DIR=$MAP_CACHE_DIR
  JURISDICTION_STATE=AZ
  MAP_SHARD_STATE=AZ

Next steps:
  1. Install Ollama: curl -fsSL https://ollama.com/install.sh | sh
  2. Start Ollama:   systemctl enable --now ollama
  3. Pull base model: ollama pull qwen3:8b
  4. Build scout models: bash /path/to/repo/llm/build/build_llm_set.sh
  5. Compile backend:  cd /path/to/repo/navigation/backend && javac *.java
  6. Run backend with env: source /etc/scout/map-server.env && java -jar dist/backend-lite.jar

Health checks:
  curl http://localhost:18080/api/health
  curl http://localhost:18080/api/map/status
  curl 'http://localhost:18080/api/map/shard?state=AZ'

Tailscale mesh (if using):
  - Set SCOUT_NETWORK_ADVERTISE_HOST=<your-tailscale-ip> in vehicle_stack.env
  - Backend binds 0.0.0.0:18080, frontend 0.0.0.0:8787

EOF
}

main() {
    require_root
    log "Starting Scout Map Server deployment..."
    install_deps
    setup_directories
    deploy_shards
    
    local pmtiles_file
    if [[ -n "$PMTILES_PATH" ]]; then
        pmtiles_file="$PMTILES_PATH"
        [[ -f "$pmtiles_file" ]] || { err "PMTiles not found at $pmtiles_file"; exit 1; }
        log "Using provided PMTiles: $pmtiles_file"
    else
        pmtiles_file=$(download_pmtiles)
    fi
    
    verify_pmtiles "$pmtiles_file" || { err "PMTiles verification failed"; exit 1; }
    warm_az_cache
    create_env_file "$pmtiles_file"
    create_systemd_dropin
    print_summary "$pmtiles_file"
}

main "$@"