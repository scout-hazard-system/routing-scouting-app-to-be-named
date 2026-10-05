"""Small stdlib HTTP + geo helpers shared by providers."""

from __future__ import annotations

import json
import math
import os
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Dict, Iterable, Optional, Tuple

USER_AGENT = os.getenv(
    "SCOUT_SOURCES_USER_AGENT",
    "ScoutHazardSystem/0.1 (+https://github.com/scout-hazard-system)",
)
MAX_BYTES = int(os.getenv("SCOUT_SOURCES_MAX_BYTES", str(64 * 1024 * 1024)))


class FetchError(RuntimeError):
    pass


def get_json(url: str, *, params: Optional[Dict[str, Any]] = None, headers: Optional[Dict[str, str]] = None,
             timeout: float = 20.0) -> Any:
    if params:
        url = f"{url}{'&' if '?' in url else '?'}{urllib.parse.urlencode(params)}"
    h = {"User-Agent": USER_AGENT, "Accept": "application/json, application/geo+json"}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read(MAX_BYTES + 1)
    except urllib.error.HTTPError as e:
        raise FetchError(f"HTTP {e.code} from {redact(url)}") from None
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise FetchError(f"{type(e).__name__} fetching {redact(url)}") from None
    if len(raw) > MAX_BYTES:
        raise FetchError(f"response over {MAX_BYTES} bytes from {redact(url)}")
    try:
        return json.loads(raw.decode("utf-8", errors="replace"))
    except json.JSONDecodeError:
        raise FetchError(f"non-JSON response from {redact(url)}") from None


def redact(url: str) -> str:
    """Drop query strings (API keys / access tokens) from anything logged or stored."""
    p = urllib.parse.urlsplit(url)
    return urllib.parse.urlunsplit((p.scheme, p.netloc, p.path, "", ""))


@dataclass(frozen=True)
class Region:
    """A circle around a point (vehicle position / area of interest)."""
    lat: float
    lon: float
    radius_km: float

    def bbox(self) -> Tuple[float, float, float, float]:
        """(min_lon, min_lat, max_lon, max_lat)."""
        dlat = self.radius_km / 111.0
        dlon = self.radius_km / (111.0 * max(math.cos(math.radians(self.lat)), 0.01))
        return (self.lon - dlon, self.lat - dlat, self.lon + dlon, self.lat + dlat)

    def contains(self, lat: Optional[float], lon: Optional[float]) -> bool:
        if lat is None or lon is None:
            return False
        return haversine_km(self.lat, self.lon, lat, lon) <= self.radius_km


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def first_point(geometry: Optional[Dict[str, Any]]) -> Tuple[Optional[float], Optional[float]]:
    """(lat, lon) of a representative point for Point/LineString/Polygon/Multi* GeoJSON."""
    if not geometry or not isinstance(geometry, dict):
        return None, None
    coords = geometry.get("coordinates")
    for _ in range(4):  # descend nested arrays until we hit [lon, lat]
        if isinstance(coords, (list, tuple)) and coords and isinstance(coords[0], (int, float)):
            break
        if isinstance(coords, (list, tuple)) and coords:
            coords = coords[0]
        else:
            return None, None
    try:
        return float(coords[1]), float(coords[0])
    except (TypeError, ValueError, IndexError):
        return None, None


def any_point_in(region: Region, geometry: Optional[Dict[str, Any]]) -> bool:
    """True if any vertex of the geometry falls in the region (cheap line filter)."""
    def walk(c: Any) -> Iterable[Tuple[float, float]]:
        if isinstance(c, (list, tuple)) and c and isinstance(c[0], (int, float)):
            yield float(c[1]), float(c[0])
        elif isinstance(c, (list, tuple)):
            for x in c:
                yield from walk(x)
    if not geometry:
        return False
    return any(region.contains(lat, lon) for lat, lon in walk(geometry.get("coordinates")))
