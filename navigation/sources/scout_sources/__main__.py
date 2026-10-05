"""CLI.

  python -m scout_sources poll  [--shards 9tbq,9tbr] [--states AZ] [--interval 120]
  python -m scout_sources serve [--host 127.0.0.1] [--port 8770]
  python -m scout_sources cells --lat 33.45 --lon -112.07     (local helper: cell + neighbours)

Coverage = SCOUT_SOURCES_SHARDS (or the hub bootstrap anchor
SCOUT_SOURCES_LAT/LON -> its cell + neighbours) + cells clients recently
requested from `serve`. Clients never send coordinates to the hub.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time

from . import shards
from .runner import Demand, Seen, ShardStore, coverage, poll_once


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="scout_sources")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("poll", help="ingest hazards for covered shards; emit new/changed events")
    p.add_argument("--shards", default=None, help="override SCOUT_SOURCES_SHARDS (comma list of geohash-4 cells)")
    p.add_argument("--states", default=os.getenv("SCOUT_SOURCES_STATES", ""),
                   help="comma list of USPS codes for state feeds (511, WZDx)")
    p.add_argument("--providers", default=os.getenv("SCOUT_SOURCES_PROVIDERS", "nws,wzdx,511,tomtom"))
    p.add_argument("--log-file", default=os.getenv("SCOUT_SOURCES_LOG_FILE") or os.getenv("PIPELINE_LOG_PATH"))
    p.add_argument("--blackboard", action="store_true", default=os.getenv("SCOUT_SOURCES_BLACKBOARD", "0") == "1")
    p.add_argument("--interval", type=int, default=0, help="seconds between polls (0 = once)")

    s = sub.add_parser("serve", help="coordinate-free hazard shard service")
    s.add_argument("--host", default=os.getenv("SCOUT_SOURCES_SERVE_HOST", "127.0.0.1"))
    s.add_argument("--port", type=int, default=int(os.getenv("SCOUT_SOURCES_SERVE_PORT", "8770")))

    c = sub.add_parser("cells", help="(client-side helper) geohash cell + neighbours for a point")
    c.add_argument("--lat", type=float, required=True)
    c.add_argument("--lon", type=float, required=True)

    args = ap.parse_args(argv)

    if args.cmd == "cells":
        print(",".join(shards.neighbors(shards.encode(args.lat, args.lon))))
        return 0
    if args.cmd == "serve":
        from .serve import serve
        serve(args.host, args.port)
        return 0

    if args.shards is not None:
        os.environ["SCOUT_SOURCES_SHARDS"] = args.shards
    states = [x.strip().upper() for x in args.states.split(",") if x.strip()]
    providers = [x.strip() for x in args.providers.split(",") if x.strip()]
    seen, store = Seen(), ShardStore()
    while True:
        cov = coverage(Demand())
        summary = poll_once(cov, states, providers, log_file=args.log_file, blackboard=args.blackboard,
                            seen=seen, store=store)
        print(json.dumps({"states": states, **summary}), file=sys.stderr, flush=True)
        if args.interval <= 0:
            return 0
        time.sleep(max(30, args.interval))


if __name__ == "__main__":
    raise SystemExit(main())
