#!/usr/bin/env python3
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

import argparse
import json
from pathlib import Path


def load_json(path: Path):
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def normalize_region(value: str) -> str:
    return (value or "").strip().lower()


def collect_region_channels(repo_root: Path, manifest: dict, region: str):
    regions = manifest.get("regions") or {}
    region_entry = regions.get(region) or {}
    state_files = region_entry.get("state_files") or {}
    shared_files = region_entry.get("shared_files") or []
    files = list(state_files.values()) + list(shared_files)

    channels = []
    for rel in files:
        p = (repo_root / rel).resolve()
        if not p.exists():
            continue
        shard = load_json(p)
        for ch in shard.get("channels") or []:
            channels.append(
                {
                    "id": ch.get("id"),
                    "name": ch.get("name"),
                    "city": ch.get("city"),
                    "county": ch.get("county"),
                    "state": ch.get("state"),
                    "service_types": ch.get("service_types") or [],
                    "stream_url": ch.get("stream_url"),
                }
            )
    channels.sort(key=lambda x: ((x.get("name") or ""), (x.get("id") or "")))
    return channels


def main():
    parser = argparse.ArgumentParser(description="Broadcastify regional catalog API helper")
    parser.add_argument("--manifest", required=True, help="Path to national manifest JSON")
    parser.add_argument("--region", default="", help="Optional region key")
    parser.add_argument("--output-format", choices=["json"], default="json")
    args = parser.parse_args()

    manifest_path = Path(args.manifest).resolve()
    repo_root = manifest_path.parent
    manifest = load_json(manifest_path)

    regions = sorted((manifest.get("regions") or {}).keys())
    region = normalize_region(args.region)
    if region and region not in set(regions):
        print(
            json.dumps(
                {
                    "error": "invalid_region",
                    "region": region,
                    "regions": regions,
                },
                ensure_ascii=False,
            )
        )
        return

    payload = {
        "regions": regions,
        "selected_region": region or None,
        "channels": [],
    }
    if region:
        payload["channels"] = collect_region_channels(repo_root, manifest, region)
    else:
        payload["region_counts"] = {
            r: len(collect_region_channels(repo_root, manifest, r)) for r in regions
        }

    print(json.dumps(payload, ensure_ascii=False))


if __name__ == "__main__":
    main()
