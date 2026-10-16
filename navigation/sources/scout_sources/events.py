"""Normalized hazard events + accountability primitives.

Every provider (NWS, state 511, USDOT WZDx, TomTom, later SDR/Broadcastify
API) is mapped into one HazardEvent. Accountability fields:

  event_id      stable id: sha256(provider | native_id)[:24]
  content_hash  sha256 of the canonical event body (changes when the
                upstream record changes -> a new revision, never silent)
  signature     HMAC-SHA256(SCOUT_SOURCES_SIGNING_KEY, content_hash) by the
                host that ingested it, so a claim an agent cites can be traced
                to (source, fetch time, ingesting host, exact content)
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import socket
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

KINDS = ("incident", "closure", "construction", "work_zone", "weather", "congestion", "other")
SEVERITIES = ("unknown", "minor", "moderate", "major", "severe")


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


@dataclass
class HazardEvent:
    provider: str                     # nws | state511:AZ | wzdx:utah | tomtom
    native_id: str                    # id in the upstream system
    kind: str                         # one of KINDS
    title: str
    description: str = ""
    severity: str = "unknown"
    road: str = ""
    direction: str = ""
    lat: Optional[float] = None
    lon: Optional[float] = None
    starts_at: str = ""
    ends_at: str = ""
    updated_at: str = ""              # upstream last-update time
    source_url: str = ""              # host/endpoint the record came from (no keys)
    extra: Dict[str, Any] = field(default_factory=dict)
    # filled by finalize()
    event_id: str = ""
    observed_at: str = ""             # when this host fetched it
    content_hash: str = ""
    ingest_host: str = ""
    signature: str = ""

    def body(self) -> Dict[str, Any]:
        """Fields covered by content_hash (excludes fetch/ingest metadata)."""
        d = asdict(self)
        for k in ("event_id", "observed_at", "content_hash", "ingest_host", "signature"):
            d.pop(k, None)
        return d

    def finalize(self, signing_key: Optional[bytes] = None) -> "HazardEvent":
        if self.kind not in KINDS:
            self.kind = "other"
        if self.severity not in SEVERITIES:
            self.severity = "unknown"
        self.title = (self.title or "").strip()[:200]
        self.description = (self.description or "").strip()[:2000]
        self.event_id = hashlib.sha256(f"{self.provider}|{self.native_id}".encode()).hexdigest()[:24]
        canonical = json.dumps(self.body(), sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        self.content_hash = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        self.observed_at = now_iso()
        self.ingest_host = os.getenv("SCOUT_SOURCES_HOST_ID") or socket.gethostname()
        key = signing_key if signing_key is not None else signing_key_from_env()
        self.signature = sign(key, self.content_hash, self.ingest_host) if key else ""
        return self

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


def signing_key_from_env() -> Optional[bytes]:
    raw = os.getenv("SCOUT_SOURCES_SIGNING_KEY", "").strip()
    return raw.encode() if raw else None


def sign(key: bytes, content_hash: str, ingest_host: str) -> str:
    return hmac.new(key, f"{ingest_host}|{content_hash}".encode(), hashlib.sha256).hexdigest()


def verify(event: Dict[str, Any], key: bytes) -> bool:
    """Recompute content_hash from the body and check the host signature."""
    ev = HazardEvent(**{k: v for k, v in event.items() if k in HazardEvent.__dataclass_fields__})
    canonical = json.dumps(ev.body(), sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    if hashlib.sha256(canonical.encode("utf-8")).hexdigest() != event.get("content_hash"):
        return False
    expected = sign(key, event["content_hash"], event.get("ingest_host", ""))
    return hmac.compare_digest(expected, event.get("signature", ""))


def dedupe(events: List[HazardEvent]) -> List[HazardEvent]:
    seen: Dict[str, HazardEvent] = {}
    for e in events:
        seen[e.event_id] = e
    return list(seen.values())
