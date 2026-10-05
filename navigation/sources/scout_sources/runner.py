"""Poll providers, keep revisions, emit accountable hazard events.

Sinks:
  * pipeline log: `[EVENT_JSON] {"event_type": "hazard_event", ...}` lines,
    the same channel the backend already streams (/api/pipeline/stream)
  * scout blackboard (optional): pipeline/raw entries written as role `alert`
    with the event as JSON body and meta {event_id, content_hash, signature},
    so agents can cite event ids and auditors can verify them
Only NEW or CHANGED events (by content_hash) are emitted.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.request
from pathlib import Path
from typing import Callable, Dict, Iterable, List, Optional, Tuple

from .events import HazardEvent, dedupe
from .net import FetchError, Region
from .providers import PROVIDERS


def state_dir() -> Path:
    explicit = os.getenv("SCOUT_STATE_DIR", "").strip()
    if explicit:
        return Path(explicit).expanduser()
    xdg = os.getenv("XDG_STATE_HOME", "").strip()
    return (Path(xdg).expanduser() if xdg else Path.home() / ".local" / "state") / "scout"


class Seen:
    """event_id -> content_hash, persisted so restarts don't re-emit everything."""

    def __init__(self, path: Optional[Path] = None):
        self.path = path or state_dir() / "sources-seen.json"
        try:
            self.map: Dict[str, str] = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.map = {}

    def changed(self, ev: HazardEvent) -> bool:
        return self.map.get(ev.event_id) != ev.content_hash

    def mark(self, ev: HazardEvent) -> None:
        self.map[ev.event_id] = ev.content_hash

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.map), encoding="utf-8")
        tmp.replace(self.path)


def collect(region: Region, states: Iterable[str], providers: Iterable[str],
            registry: Dict[str, Callable] = PROVIDERS) -> Tuple[List[HazardEvent], Dict[str, str]]:
    """Run providers; one failing provider never sinks the others."""
    events: List[HazardEvent] = []
    status: Dict[str, str] = {}
    states = list(states)
    for name in providers:
        fn = registry.get(name)
        if not fn:
            status[name] = "unknown provider"
            continue
        try:
            got = fn(region, states)
            events.extend(e.finalize() for e in got)
            status[name] = f"ok ({len(got)})"
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


def poll_once(region: Region, states: Iterable[str], providers: Iterable[str], *, log_file: Optional[str] = None,
              blackboard: bool = False, seen: Optional[Seen] = None) -> Dict[str, object]:
    seen = seen or Seen()
    events, status = collect(region, states, providers)
    fresh = [e for e in events if seen.changed(e)]
    emit_log(fresh, log_file)
    written = emit_blackboard(fresh) if blackboard else 0
    for e in fresh:
        seen.mark(e)
    seen.save()
    return {"providers": status, "total": len(events), "new_or_changed": len(fresh), "blackboard_written": written}
