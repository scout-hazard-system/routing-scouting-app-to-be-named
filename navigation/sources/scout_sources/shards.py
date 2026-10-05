"""Location shards: coarse geohash cells, so precise positions never leave a client.

A client computes its own cell(s) locally (see clients/shard-client.js) and
asks the hazard service for cell IDS only; the service never receives
coordinates. Precision 4 cells are ~39 km x 20 km; clients normally request
their cell plus its 8 neighbours (~117 km x 58 km), which also blurs which of
the nine cells the client is actually in.
"""

from __future__ import annotations

import re
from typing import Iterable, List, Optional, Set, Tuple

BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
PRECISION = 4
SHARD_RE = re.compile(r"^[0-9b-hjkmnp-z]{4}$")  # geohash alphabet, fixed precision


def encode(lat: float, lon: float, precision: int = PRECISION) -> str:
    lat_rng, lon_rng = [-90.0, 90.0], [-180.0, 180.0]
    bits, bit, ch, even, out = 0, 0, 0, True, []
    while len(out) < precision:
        rng, val = (lon_rng, lon) if even else (lat_rng, lat)
        mid = (rng[0] + rng[1]) / 2
        if val >= mid:
            ch = (ch << 1) | 1
            rng[0] = mid
        else:
            ch = ch << 1
            rng[1] = mid
        even = not even
        bit += 1
        if bit == 5:
            out.append(BASE32[ch])
            bit, ch = 0, 0
    return "".join(out)


def bounds(cell: str) -> Tuple[float, float, float, float]:
    """(min_lat, min_lon, max_lat, max_lon) of a geohash cell."""
    lat_rng, lon_rng = [-90.0, 90.0], [-180.0, 180.0]
    even = True
    for c in cell:
        v = BASE32.index(c)
        for shift in range(4, -1, -1):
            rng = lon_rng if even else lat_rng
            mid = (rng[0] + rng[1]) / 2
            if (v >> shift) & 1:
                rng[0] = mid
            else:
                rng[1] = mid
            even = not even
    return lat_rng[0], lon_rng[0], lat_rng[1], lon_rng[1]


def center(cell: str) -> Tuple[float, float]:
    a, b, c, d = bounds(cell)
    return (a + c) / 2, (b + d) / 2


def neighbors(cell: str) -> List[str]:
    """The cell plus its 8 neighbours (computed from the cell size)."""
    a, b, c, d = bounds(cell)
    dlat, dlon = c - a, d - b
    clat, clon = (a + c) / 2, (b + d) / 2
    out = []
    for i in (-1, 0, 1):
        for j in (-1, 0, 1):
            lat = max(-89.999, min(89.999, clat + i * dlat))
            lon = ((clon + j * dlon + 180.0) % 360.0) - 180.0
            g = encode(lat, lon, len(cell))
            if g not in out:
                out.append(g)
    return out


def valid(cell: str) -> bool:
    return bool(SHARD_RE.match(cell or ""))


def parse_ids(raw: str, limit: int) -> Optional[List[str]]:
    """Comma list of shard ids -> list, or None if anything is malformed/over limit."""
    ids = [s.strip().lower() for s in (raw or "").split(",") if s.strip()]
    if not ids or len(ids) > limit or not all(valid(s) for s in ids):
        return None
    return list(dict.fromkeys(ids))


def cells_for_points(points: Iterable[Tuple[Optional[float], Optional[float]]]) -> Set[str]:
    return {encode(lat, lon) for lat, lon in points if lat is not None and lon is not None}
