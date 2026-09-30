#!/usr/bin/env bash
# Copyright 2026 Scout Project Contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# rsync the Scout map shards from a source host into this machine.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

usage() {
  cat <<EOF
Usage: $(basename "$0") <user@host[:port]> [remote-repo]

Pulls the Scout map shard data from a source host over rsync-over-SSH:
  - MVT shard cache     ~/.scanner_stream/map_cache/shards/  (served by the backend)
  - Text map roots      <repo>/vlm_text_map_shards/ and vlm_text_map_shards_chunked/

Environment:
  MAP_STATE             state(s) directory to sync (default: AZ, or MAP_SHARD_STATE)
  MAP_CACHE_DIR         local cache root (default: ~/.scanner_stream/map_cache)
  REMOTE_REPO_ROOT      remote monorepo root (default: source's ~/Desktop)
  SYNC_TEXT_SHARDS      "1" to also sync text-map roots (default) or "0" to skip
  MIRROR                "1" to mirror the cache with --delete (default: 0)
  VERBOSE               set to show rsync progress

Requires passwordless SSH to the source. Install your public key on the source
host's ~/.ssh/authorized_keys first (ssh-copy-id).
EOF
}

SRC_TARGET="${1:-}"
if [[ -z "$SRC_TARGET" ]]; then
  usage
  exit 1
fi

require_cmd rsync ssh

if ! ROOT="$(resolve_repo_root)"; then
  fail "Could not resolve the repo root; run from map_server_setup_trixie/ inside the clone."
fi
if [[ -z "${MAP_STATE:-}" && -f "$ROOT/stack/config/vehicle_stack.env" ]]; then
  MAP_STATE="$(grep -E '^MAP_SHARD_STATE=' "$ROOT/stack/config/vehicle_stack.env" | tail -n 1 | cut -d= -f2- | tr -d '"' || true)"
fi
MAP_STATE="${MAP_STATE:-AZ}"

LOCAL_CACHE_ROOT="${MAP_CACHE_DIR:-$HOME/.scanner_stream/map_cache}"
LOCAL_SHARDS="$LOCAL_CACHE_ROOT/shards"
mkdir -p "$LOCAL_SHARDS"

split_ssh_target "$SRC_TARGET"
SSH_OBJ="${SSH_USER}@${SSH_HOST}"
SSH_ARGS=(-o BatchMode=yes -o ConnectTimeout=10)
if [[ "$SSH_PORT" != "22" ]]; then
  SSH_ARGS+=(-p "$SSH_PORT")
fi

info "Testing SSH to ${SSH_OBJ}..."
ssh "${SSH_ARGS[@]}" "$SSH_OBJ" 'echo ssh_ok' >/dev/null
ok "SSH to ${SSH_OBJ} works"

RSYNC_SSH="ssh"
if [[ "$SSH_PORT" != "22" ]]; then
  RSYNC_SSH="$RSYNC_SSH -p $SSH_PORT"
fi

resolve_remote() {
  ssh "${SSH_ARGS[@]}" "$SSH_OBJ" "printf '%s' \"$1\""
}

REMOTE_CACHE_BASE="$(resolve_remote '$HOME/.scanner_stream/map_cache/shards')"
if [[ -z "${REMOTE_REPO_ROOT:-}" ]]; then
  REMOTE_REPO="$(resolve_remote '$HOME/Desktop')"
else
  REMOTE_REPO="$REMOTE_REPO_ROOT"
fi

RSYNC_FLAGS=(-a --partial)
if [[ "${MIRROR:-0}" == "1" ]]; then
  RSYNC_FLAGS+=(--delete)
fi
if [[ -n "${VERBOSE:-}" ]]; then
  RSYNC_FLAGS+=(--info=progress2)
else
  RSYNC_FLAGS+=(--quiet)
fi

echo ""
info "Syncing MVT shard cache: ${SSH_OBJ}:${REMOTE_CACHE_BASE}/ -> $LOCAL_SHARDS/"
rsync "${RSYNC_FLAGS[@]}" -e "$RSYNC_SSH" "${SSH_OBJ}:${REMOTE_CACHE_BASE}/" "$LOCAL_SHARDS/"
ok "MVT shard cache synced ($(du -sh "$LOCAL_SHARDS" | cut -f1))"

if [[ "${SYNC_TEXT_SHARDS:-1}" == "1" ]]; then
  echo ""
  info "Syncing text-map roots for state '$MAP_STATE' from repo at ${SSH_OBJ}:${REMOTE_REPO}"
  for sub in vlm_text_map_shards vlm_text_map_shards_chunked; do
    remote_sub="$REMOTE_REPO/$sub/$MAP_STATE"
    if ! ssh "${SSH_ARGS[@]}" "$SSH_OBJ" "test -d \"$remote_sub\"" 2>/dev/null; then
      warn "skip $sub/$MAP_STATE (not present on source)"
      continue
    fi
    local_dest="$ROOT/$sub/$MAP_STATE"
    mkdir -p "$local_dest"
    info "Syncing $sub/$MAP_STATE -> $local_dest"
    rsync -a --partial --quiet -e "$RSYNC_SSH" "${SSH_OBJ}:${remote_sub}/" "$local_dest"
  done
fi

echo ""
ok "Shard sync complete."
echo "Next: ./configure_map_server.sh --advertise auto   (then ./master start)"