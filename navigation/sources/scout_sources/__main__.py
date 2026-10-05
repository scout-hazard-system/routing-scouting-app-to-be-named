"""CLI: python -m scout_sources poll --lat 33.45 --lon -112.07 --radius-km 80 --states AZ

Providers: nws (keyless), wzdx (keyless, per state), 511 (SCOUT_511_KEY_<ST>),
tomtom (SCOUT_TOMTOM_KEY). Region defaults come from SCOUT_SOURCES_LAT/LON/
RADIUS_KM and SCOUT_SOURCES_STATES so a systemd unit needs no arguments.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time

from .net import Region
from .runner import Seen, poll_once


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, "") or default)
    except ValueError:
        return default


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="scout_sources")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("poll", help="fetch hazards around a point and emit new/changed events")
    p.add_argument("--lat", type=float, default=_env_float("SCOUT_SOURCES_LAT", 33.4484))
    p.add_argument("--lon", type=float, default=_env_float("SCOUT_SOURCES_LON", -112.0740))
    p.add_argument("--radius-km", type=float, default=_env_float("SCOUT_SOURCES_RADIUS_KM", 80.0))
    p.add_argument("--states", default=os.getenv("SCOUT_SOURCES_STATES", ""),
                   help="comma list of USPS codes for state feeds (511, WZDx)")
    p.add_argument("--providers", default=os.getenv("SCOUT_SOURCES_PROVIDERS", "nws,wzdx,511,tomtom"))
    p.add_argument("--log-file", default=os.getenv("SCOUT_SOURCES_LOG_FILE") or os.getenv("PIPELINE_LOG_PATH"))
    p.add_argument("--blackboard", action="store_true", default=os.getenv("SCOUT_SOURCES_BLACKBOARD", "0") == "1")
    p.add_argument("--interval", type=int, default=0, help="seconds between polls (0 = once)")
    args = ap.parse_args(argv)

    region = Region(args.lat, args.lon, args.radius_km)
    states = [s.strip().upper() for s in args.states.split(",") if s.strip()]
    providers = [s.strip() for s in args.providers.split(",") if s.strip()]
    seen = Seen()
    while True:
        summary = poll_once(region, states, providers, log_file=args.log_file, blackboard=args.blackboard, seen=seen)
        print(json.dumps({"region": [region.lat, region.lon, region.radius_km], "states": states, **summary}),
              file=sys.stderr, flush=True)
        if args.interval <= 0:
            return 0
        time.sleep(max(30, args.interval))


if __name__ == "__main__":
    raise SystemExit(main())
