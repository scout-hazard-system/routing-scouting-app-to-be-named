"""Poll providers over covered shards, keep revisions, emit accountable events.

Coverage = configured shards (SCOUT_SOURCES_SHARDS, or the bootstrap point's
cell + neighbours) + shards clients recently ASKED FOR (demand, coarse ids
only — clients never send coordinates). Per-point providers (NWS, TomTom) run
per cell with per-(provider, cell) throttles; state providers (WZDx, 511) run
once and are filtered to the covered cells.

Sinks for NEW or CHANGED events (by content_hash):
  * pipeline log: `[EVENT_JSON] {"event_type": "hazard_event", ...}`
  * scout blackboard (optional): pipeline/raw, role alert, meta {event_id,
    content_hash, signature, ingest_host}
The shard store (current events per cell) backs the coordinate-free
`scout_sources serve` endpoint.
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.request
from pathlib import Path
from typing import Callable, Dict, Iterable, List, Optional, Tuple

from . import shards
from .events import HazardEvent, dedupe, now_iso
from .net import Coverage, FetchError, Region
from .providers import PROVIDERS

PER_CELL = {"nws": "SCOUT_NWS_MIN_INTERVAL_S", "tomtom": "SCOUT_TOMTOM_MIN_INTERVAL_S"}
PER_CELL_DEFAULT_S = {"nws": 600.0, "tomtom": 600.0}
_last_call: Dict[Tuple[str, str], float] = {}


def state_dir() -> Path:
    explicit = os.getenv("SCOUT_STATE_DIR", "").strip()
    if explicit:
        return Path(explicit).expanduser()
    xdg = os.getenv("XDG_STATE_HOME", "").strip()
    return (Path(xdg).expanduser() if xdg else Path.home() / ".local" / "state") / "scout"


def _read_json(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def _write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    tmp.replace(path)


class Seen:
    """event_id -> content_hash, persisted so restarts don't re-emit everything."""

    def __init__(self, path: Optional[Path] = None):
        self.path = path or state_dir() / "sources-seen.json"
        self.map: Dict[str, str] = _read_json(self.path, {})

    def changed(self, ev: HazardEvent) -> bool:
        return self.map.get(ev.event_id) != ev.content_hash

    def mark(self, ev: HazardEvent) -> None:
        self.map[ev.event_id] = ev.content_hash

    def save(self) -> None:
        _write_json(self.path, self.map)


class ShardStore:
    """Current events per cell; an event expires once upstream stops reporting it."""

    def __init__(self, path: Optional[Path] = None, ttl_s: Optional[float] = None):
        self.path = path or state_dir() / "hazard-shards.json"
        self.ttl_s = ttl_s if ttl_s is not None else float(os.getenv("SCOUT_SOURCES_EVENT_TTL_S", "1800"))
        self.events: Dict[str, dict] = _read_json(self.path, {})

    def update(self, events: Iterable[HazardEvent]) -> None:
        now = time.time()
        for e in events:
            if e.lat is None or e.lon is None:
                continue
            d = e.to_dict()
            d["shard"] = shards.encode(e.lat, e.lon)
            d["last_seen_epoch"] = now
            self.events[e.event_id] = d
        self.events = {k: v for k, v in self.events.items() if now - v.get("last_seen_epoch", 0) <= self.ttl_s}

    def for_shards(self, ids: Iterable[str]) -> Dict[str, List[dict]]:
        want = set(ids)
        out: Dict[str, List[dict]] = {s: [] for s in want}
        for d in self.events.values():
            if d.get("shard") in want:
                ev = {k: v for k, v in d.items() if k != "last_seen_epoch"}
                out[d["shard"]].append(ev)
        return out

    def save(self) -> None:
        _write_json(self.path, self.events)


class Demand:
    """Coarse shard ids clients asked for recently (no client identity kept)."""

    def __init__(self, path: Optional[Path] = None):
        self.path = path or state_dir() / "hazard-demand.json"
        self.map: Dict[str, float] = _read_json(self.path, {})

    def touch(self, ids: Iterable[str]) -> None:
        now = time.time()
        for s in ids:
            self.map[s] = now

    def recent(self, ttl_s: float, limit: int) -> List[str]:
        now = time.time()
        live = [(t, s) for s, t in self.map.items() if now - t <= ttl_s]
        return [s for _, s in sorted(live, reverse=True)[:limit]]

    def save(self) -> None:
        cutoff = time.time() - 7 * 86400
        self.map = {s: t for s, t in self.map.items() if t >= cutoff}
        _write_json(self.path, self.map)


def configured_cells() -> List[str]:
    raw = os.getenv("SCOUT_SOURCES_SHARDS", "")
    ids = shards.parse_ids(raw, 10_000) if raw.strip() else None
    if ids:
        return ids
    lat, lon = os.getenv("SCOUT_SOURCES_LAT"), os.getenv("SCOUT_SOURCES_LON")
    if lat and lon:  # bootstrap: a hub-side anchor, not any client's position
        return shards.neighbors(shards.encode(float(lat), float(lon)))
    return []


def coverage(demand: Optional[Demand] = None) -> Coverage:
    demand = demand or Demand()
    recent = demand.recent(float(os.getenv("SCOUT_SOURCES_DEMAND_TTL_S", str(6 * 3600))),
                           int(os.getenv("SCOUT_SOURCES_MAX_DEMAND_CELLS", "81")))
    return Coverage(recent + configured_cells())


def _due(provider: str, cell: str) -> bool:
    interval = float(os.getenv(PER_CELL[provider], "") or PER_CELL_DEFAULT_S[provider])
    last = _last_call.get((provider, cell))
    return last is None or time.monotonic() - last >= interval


def collect(cov: Coverage, states: Iterable[str], providers: Iterable[str],
            registry: Dict[str, Callable] = PROVIDERS) -> Tuple[List[HazardEvent], Dict[str, str]]:
    """Run providers over the coverage; one failing provider never sinks the others."""
    events: List[HazardEvent] = []
    status: Dict[str, str] = {}
    states = list(states)
    tomtom_cells = int(os.getenv("SCOUT_TOMTOM_MAX_CELLS", "12"))
    for name in providers:
        fn = registry.get(name)
        if not fn:
            status[name] = "unknown provider"
            continue
        try:
            if name in PER_CELL:
                regions = cov.regions[:tomtom_cells] if name == "tomtom" else cov.regions
                got, calls = [], 0
                for r in regions:
                    if not _due(name, r.cell):
                        continue
                    _last_call[(name, r.cell)] = time.monotonic()
                    got.extend(e for e in fn(r, states) if r.contains(e.lat, e.lon) or e.lat is None)
                    calls += 1
                status[name] = f"ok ({len(got)}, {calls} cells polled)"
            else:
                got = [e for e in fn(cov, states)]
                status[name] = f"ok ({len(got)})"
            events.extend(e.finalize() for e in got)
        except FetchError as e:
            status[name] = f"error: {e}"
    return dedupe(events), status


def emit_log(events: List[HazardEvent], log_file: Optional[str]) -> None:
    lines = [f"[EVENT_JSON] {json.dumps({'event_type': 'hazard_event', **e.to_dict()}, ensure_ascii=False)}"
             for e in events]
    if not lines:
        return
    if log_file:
        with open(log_file, "a", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
    else:
        print("\n".join(lines))


def emit_blackboard(events: List[HazardEvent]) -> int:
    url = os.getenv("SCOUT_BLACKBOARD_URL", "").rstrip("/")
    token = os.getenv("SCOUT_BLACKBOARD_TOKEN_ALERT") or os.getenv("SCOUT_BLACKBOARD_TOKEN", "")
    if not url or not token:
        return 0
    n = 0
    for e in events:
        payload = {
            "category": "pipeline", "role": "alert", "kind": "raw",
            "title": f"[{e.provider}] {e.title}"[:200],
            "body": json.dumps(e.to_dict(), ensure_ascii=False),
            "tags": ["hazard", e.kind, e.provider.split(":")[0]],
            "meta": {"event_id": e.event_id, "content_hash": e.content_hash,
                     "signature": e.signature, "ingest_host": e.ingest_host},
        }
        req = urllib.request.Request(f"{url}/v1/write", data=json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"})
        try:
            with urllib.request.urlopen(req, timeout=10):
                n += 1
        except OSError as err:
            print(f"[scout_sources] blackboard write failed for {e.event_id}: {type(err).__name__}", file=sys.stderr)
    return n


def poll_once(cov, states: Iterable[str], providers: Iterable[str], *, log_file: Optional[str] = None,
              blackboard: bool = False, seen: Optional[Seen] = None, store: Optional[ShardStore] = None) -> Dict[str, object]:
    if isinstance(cov, Region) and not isinstance(cov, Coverage):
        cov = Coverage(shards.neighbors(shards.encode(cov.lat, cov.lon)))
    seen = seen or Seen()
    store = store or ShardStore()
    events, status = collect(cov, states, providers)
    fresh = [e for e in events if seen.changed(e)]
    emit_log(fresh, log_file)
    written = emit_blackboard(fresh) if blackboard else 0
    for e in fresh:
        seen.mark(e)
    seen.save()
    store.update(events)
    store.save()
    return {"cells": len(cov.cells), "providers": status, "total": len(events),
            "new_or_changed": len(fresh), "blackboard_written": written, "at": now_iso()}
