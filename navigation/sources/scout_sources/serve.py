"""Coordinate-free hazard shard service.

  GET /v1/hazards?shards=9tbq,9tbr,...   -> current events for those cells
  GET /v1/health                          -> coverage/event counts

Privacy contract: the ONLY location input is a list of coarse geohash cell
ids that the client computed itself. Any coordinate-shaped parameter (lat,
lon, latitude, longitude, ll, coords, gps, location, point, bbox...) is
refused with 400 rather than ignored, so a client bug can never quietly start
sending positions. Requests record demand per cell (no client identity, no
IP) so hub ingestion covers where clients are without knowing who they are.
Access logging is off: the service never writes requested cells per client.
"""

from __future__ import annotations

import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from . import shards
from .runner import Demand, ShardStore

MAX_SHARDS = int(os.getenv("SCOUT_SOURCES_MAX_SHARDS_PER_REQUEST", "27"))
FORBIDDEN_PARAMS = {"lat", "lon", "lng", "long", "latitude", "longitude", "ll", "latlon", "latlng",
                    "coords", "coordinates", "gps", "location", "loc", "pos", "position", "point", "bbox",
                    "geo", "geometry", "x", "y"}


class _State:
    def __init__(self):
        self.store = ShardStore()
        self.store_mtime = 0.0
        self.demand = Demand()
        self.demand_saved = 0.0

    def refresh(self):
        try:
            m = self.store.path.stat().st_mtime
        except OSError:
            return
        if m != self.store_mtime:
            self.store = ShardStore(self.store.path)
            self.store_mtime = m


def make_handler(state: _State):
    class H(BaseHTTPRequestHandler):
        server_version = "scout-hazards"
        sys_version = ""

        def log_message(self, fmt, *args):  # no per-client access log by design
            return

        def _send(self, code: int, body: dict):
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):  # noqa: N802
            u = urlsplit(self.path)
            q = parse_qs(u.query, keep_blank_values=True)
            bad = sorted(k for k in q if k.strip().lower() in FORBIDDEN_PARAMS)
            if bad:
                return self._send(400, {"error": "coordinates_not_accepted",
                                        "detail": "send geohash cell ids (shards=...) computed on the client; "
                                                  f"refused parameters: {', '.join(bad)}"})
            if u.path == "/v1/health":
                state.refresh()
                cells = {d.get("shard") for d in state.store.events.values()}
                return self._send(200, {"ok": True, "events": len(state.store.events), "cells_with_events": len(cells)})
            if u.path != "/v1/hazards":
                return self._send(404, {"error": "not_found"})
            extra = sorted(set(q) - {"shards"})
            if extra:
                return self._send(400, {"error": "unknown_parameters", "detail": ", ".join(extra)})
            ids = shards.parse_ids((q.get("shards") or [""])[0], MAX_SHARDS)
            if ids is None:
                return self._send(400, {"error": "bad_shards",
                                        "detail": f"1-{MAX_SHARDS} geohash cells of precision {shards.PRECISION}"})
            state.refresh()
            state.demand.touch(ids)
            if time.time() - state.demand_saved > 30:
                state.demand.save()
                state.demand_saved = time.time()
            by = state.store.for_shards(ids)
            return self._send(200, {"precision": shards.PRECISION, "shards": by,
                                    "count": sum(len(v) for v in by.values())})

        def do_POST(self):  # noqa: N802
            return self._send(405, {"error": "read_only"})

    return H


def serve(host: str, port: int) -> None:
    httpd = ThreadingHTTPServer((host, port), make_handler(_State()))
    print(f"[scout_sources] hazard shard service on {host}:{port} (coordinate-free)", flush=True)
    httpd.serve_forever()
