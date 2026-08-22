#!/usr/bin/env bash
set -euo pipefail

REMOTE_BASE_URL="${1:-${REMOTE_BASE_URL:-}}"
LOCAL_BASE_URL="${2:-${LOCAL_BASE_URL:-http://127.0.0.1:18080}}"

if [[ -z "$REMOTE_BASE_URL" ]]; then
  echo "Usage: $0 <remote_base_url> [local_base_url]" >&2
  echo "Example: $0 https://cloud-backend.example.com http://127.0.0.1:18080" >&2
  exit 1
fi

python3 - "$REMOTE_BASE_URL" "$LOCAL_BASE_URL" <<'PY'
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

remote_base = sys.argv[1].rstrip("/")
local_base = sys.argv[2].rstrip("/")

export_url = f"{remote_base}/api/platform/address-catalog/export"
try:
    with urllib.request.urlopen(export_url, timeout=20) as resp:
        payload = json.loads(resp.read().decode("utf-8", "ignore"))
except Exception as exc:
    print(f"sync failed: unable to fetch remote export: {exc}", file=sys.stderr)
    sys.exit(2)

entries = payload.get("entries")
if not isinstance(entries, list):
    print("sync failed: remote payload missing entries[]", file=sys.stderr)
    sys.exit(2)

ok = 0
failed = 0
upsert_url = f"{local_base}/api/platform/address-catalog/upsert"
for entry in entries:
    query = str(entry.get("query", "")).strip()
    if not query:
        continue
    params = {
        "query": query,
        "display_name": str(entry.get("display_name", query)),
        "source": str(entry.get("source", "cloud_sync")),
        "lat": str(entry.get("lat", "")),
        "lon": str(entry.get("lon", "")),
    }
    url = upsert_url + "?" + urllib.parse.urlencode(params, safe="")
    try:
        with urllib.request.urlopen(url, timeout=15) as resp:
            body = resp.read().decode("utf-8", "ignore")
        parsed = json.loads(body)
        if parsed.get("status") == "ok":
            ok += 1
        else:
            failed += 1
    except (urllib.error.URLError, urllib.error.HTTPError, json.JSONDecodeError):
        failed += 1

print(f"cloud->local catalog sync complete: imported={ok} failed={failed} source_entries={len(entries)}")
if failed > 0:
    sys.exit(3)
PY
