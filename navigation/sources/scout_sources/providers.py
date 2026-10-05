"""Hazard providers: NWS, state 511 (shared v2 platform), USDOT WZDx, TomTom.

Each provider: fetch(region, states) -> list[HazardEvent] (unfinalized).
Keys come only from the environment; nothing here writes a key anywhere.
"""

from __future__ import annotations

import math
import os
import time
from datetime import datetime, timezone
from typing import Any, Dict, Iterable, List, Optional

from .events import HazardEvent
from .net import FetchError, Region, any_point_in, first_point, get_json, redact

STATE_NAMES = {
    "AL": "alabama", "AZ": "arizona", "AR": "arkansas", "CA": "california", "CO": "colorado",
    "CT": "connecticut", "DE": "delaware", "FL": "florida", "GA": "georgia", "ID": "idaho",
    "IL": "illinois", "IN": "indiana", "IA": "iowa", "KS": "kansas", "KY": "kentucky",
    "LA": "louisiana", "ME": "maine", "MD": "maryland", "MA": "massachusetts", "MI": "michigan",
    "MN": "minnesota", "MS": "mississippi", "MO": "missouri", "MT": "montana", "NE": "nebraska",
    "NV": "nevada", "NH": "new hampshire", "NJ": "new jersey", "NM": "new mexico", "NY": "new york",
    "NC": "north carolina", "ND": "north dakota", "OH": "ohio", "OK": "oklahoma", "OR": "oregon",
    "PA": "pennsylvania", "RI": "rhode island", "SC": "south carolina", "SD": "south dakota",
    "TN": "tennessee", "TX": "texas", "UT": "utah", "VT": "vermont", "VA": "virginia",
    "WA": "washington", "WV": "west virginia", "WI": "wisconsin", "WY": "wyoming", "DC": "district of columbia",
}


def _iso(v: Any) -> str:
    """Epoch seconds/ms or ISO string -> ISO-8601 UTC; '' when absent."""
    if v in (None, "", 0):
        return ""
    if isinstance(v, (int, float)):
        ts = v / 1000.0 if v > 1e11 else float(v)
        return datetime.fromtimestamp(ts, tz=timezone.utc).isoformat(timespec="seconds")
    return str(v)


# ---------------------------------------------------------------- NWS (keyless)

NWS_SEVERITY = {"Extreme": "severe", "Severe": "major", "Moderate": "moderate", "Minor": "minor"}


def nws(region: Region, states: Iterable[str] = ()) -> List[HazardEvent]:
    url = "https://api.weather.gov/alerts/active"
    data = get_json(url, params={"point": f"{region.lat:.4f},{region.lon:.4f}"},
                    headers={"Accept": "application/geo+json"})
    out: List[HazardEvent] = []
    for f in data.get("features", []) or []:
        p = f.get("properties") or {}
        lat, lon = first_point(f.get("geometry"))
        out.append(HazardEvent(
            provider="nws",
            native_id=str(p.get("id") or f.get("id") or ""),
            kind="weather",
            title=str(p.get("event") or p.get("headline") or "Weather alert"),
            description=str(p.get("headline") or p.get("description") or ""),
            severity=NWS_SEVERITY.get(str(p.get("severity")), "unknown"),
            road="",
            lat=lat if lat is not None else region.lat,
            lon=lon if lon is not None else region.lon,
            starts_at=str(p.get("onset") or p.get("effective") or ""),
            ends_at=str(p.get("ends") or p.get("expires") or ""),
            updated_at=str(p.get("sent") or ""),
            source_url=url,
            extra={"area": p.get("areaDesc", ""), "urgency": p.get("urgency", ""), "certainty": p.get("certainty", "")},
        ))
    return out


# ------------------------------------------------- state 511 (shared v2 platform)
# Verified to answer on /api/v2/get/event (key required): AZ, UT, GA, WI, CT.
STATE_511 = {
    "AZ": "https://az511.com",
    "UT": "https://udottraffic.utah.gov",
    "GA": "https://511ga.org",
    "WI": "https://511wi.gov",
    "CT": "https://ctroads.org",
}

S511_KIND = {
    "accidentsandincidents": "incident", "incidents": "incident", "accident": "incident",
    "closures": "closure", "closure": "closure",
    "roadwork": "construction", "construction": "construction",
    "specialevents": "other", "weather": "weather",
}


def state511(region: Region, states: Iterable[str] = ()) -> List[HazardEvent]:
    out: List[HazardEvent] = []
    for st in [s.upper() for s in states]:
        base = STATE_511.get(st)
        key = os.getenv(f"SCOUT_511_KEY_{st}", "").strip()
        if not base or not key:
            continue
        url = f"{base}/api/v2/get/event"
        rows = get_json(url, params={"key": key, "format": "json"})
        for r in rows if isinstance(rows, list) else []:
            lat, lon = r.get("Latitude"), r.get("Longitude")
            try:
                lat, lon = float(lat), float(lon)
            except (TypeError, ValueError):
                lat, lon = None, None
            if lat is not None and not region.contains(lat, lon):
                continue
            etype = str(r.get("EventType") or "").replace(" ", "").lower()
            full = bool(r.get("IsFullClosure"))
            sev = str(r.get("Severity") or "").lower()
            out.append(HazardEvent(
                provider=f"state511:{st}",
                native_id=str(r.get("ID") or r.get("SourceId") or ""),
                kind="closure" if full else S511_KIND.get(etype, "other"),
                title=f"{r.get('EventSubType') or r.get('EventType') or 'Event'} - {r.get('RoadwayName') or ''}".strip(" -"),
                description=str(r.get("Description") or ""),
                severity={"major": "major", "minor": "minor", "moderate": "moderate"}.get(sev, "unknown"),
                road=str(r.get("RoadwayName") or ""),
                direction=str(r.get("DirectionOfTravel") or ""),
                lat=lat, lon=lon,
                starts_at=_iso(r.get("StartDate") or r.get("Reported")),
                ends_at=_iso(r.get("PlannedEndDate")),
                updated_at=_iso(r.get("LastUpdated")),
                source_url=redact(url),
                extra={"lanes_affected": r.get("LanesAffected", ""), "full_closure": full},
            ))
    return out


# ------------------------------------------------------------- USDOT WZDx (keyless)
WZDX_REGISTRY = "https://data.transportation.gov/resource/69qe-yiui.json"


def wzdx_feeds(states: Iterable[str]) -> List[Dict[str, str]]:
    want = {STATE_NAMES.get(s.upper(), s).lower() for s in states}
    feeds = []
    for r in get_json(WZDX_REGISTRY, params={"$limit": 500}) or []:
        if str(r.get("active", "")).lower() != "true" or str(r.get("needapikey", "")).lower() != "false":
            continue
        if str(r.get("state", "")).strip().lower() not in want:
            continue
        u = r.get("url")
        u = u.get("url") if isinstance(u, dict) else u
        if u and str(r.get("format", "")).lower() == "geojson":
            feeds.append({"state": str(r.get("state")).lower(), "url": u, "version": str(r.get("version", ""))})
    return feeds


def wzdx(region: Region, states: Iterable[str] = ()) -> List[HazardEvent]:
    out: List[HazardEvent] = []
    for feed in wzdx_feeds(states):
        try:
            data = get_json(feed["url"], timeout=40.0)
        except FetchError:
            continue  # one dead state feed must not sink the others
        for f in data.get("features", []) or []:
            geom = f.get("geometry")
            if not any_point_in(region, geom):
                continue
            p = f.get("properties") or {}
            core = p.get("core_details") or p  # v4 nests, v3 is flat
            roads = core.get("road_names") or ([core["road_name"]] if core.get("road_name") else [])
            lat, lon = first_point(geom)
            etype = str(core.get("event_type") or "work-zone")
            out.append(HazardEvent(
                provider=f"wzdx:{feed['state']}",
                native_id=str(f.get("id") or p.get("road_event_id") or ""),
                kind="work_zone" if etype == "work-zone" else ("closure" if "closure" in etype else "construction"),
                title=f"Work zone - {', '.join(roads)}".strip(" -"),
                description=str(core.get("description") or ""),
                severity="major" if p.get("vehicle_impact") in ("all-lanes-closed",) else "minor",
                road=", ".join(roads),
                direction=str(core.get("direction") or ""),
                lat=lat, lon=lon,
                starts_at=str(p.get("start_date") or ""),
                ends_at=str(p.get("end_date") or ""),
                updated_at=str(core.get("update_date") or ""),
                source_url=redact(feed["url"]),
                extra={"vehicle_impact": p.get("vehicle_impact", ""), "status": p.get("event_status", "")},
            ))
    return out


# ------------------------------------------------------------------ TomTom (key)
TOMTOM_KIND = {1: "incident", 2: "weather", 3: "incident", 4: "weather", 5: "weather", 6: "congestion",
               7: "closure", 8: "closure", 9: "construction", 10: "weather", 11: "weather", 14: "incident"}
TOMTOM_SEV = {0: "unknown", 1: "minor", 2: "moderate", 3: "major", 4: "severe"}
TOMTOM_FIELDS = ("{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay,"
                 "events{description,code},startTime,endTime,from,to,length,delay,roadNumbers,lastReportTime}}}")


TOMTOM_MAX_TILE_KM2 = 9000.0          # API hard cap is 10,000 km^2 per bbox
_tomtom_last_call = 0.0


def tomtom_tiles(region: Region, max_km2: float = TOMTOM_MAX_TILE_KM2):
    """Split the region's bbox into an n x n grid whose tiles each stay under the cap."""
    min_lon, min_lat, max_lon, max_lat = region.bbox()
    km_per_lon = 111.0 * max(math.cos(math.radians(region.lat)), 0.01)
    area = (max_lon - min_lon) * km_per_lon * (max_lat - min_lat) * 111.0
    n = max(1, math.ceil(math.sqrt(area / max_km2)))
    dlon, dlat = (max_lon - min_lon) / n, (max_lat - min_lat) / n
    return [(min_lon + i * dlon, min_lat + j * dlat, min_lon + (i + 1) * dlon, min_lat + (j + 1) * dlat)
            for i in range(n) for j in range(n)]


def tomtom(region: Region, states: Iterable[str] = ()) -> List[HazardEvent]:
    """TomTom incidents over the whole region (tiled under the bbox cap), rate
    limited by SCOUT_TOMTOM_MIN_INTERVAL_S (default 600 s) for the free tier."""
    global _tomtom_last_call
    key = os.getenv("SCOUT_TOMTOM_KEY", "").strip()
    if not key:
        return []
    min_interval = float(os.getenv("SCOUT_TOMTOM_MIN_INTERVAL_S", "600") or 600)
    if _tomtom_last_call and time.monotonic() - _tomtom_last_call < min_interval:
        return []
    _tomtom_last_call = time.monotonic()
    url = "https://api.tomtom.com/traffic/services/5/incidentDetails"
    incidents: Dict[str, Any] = {}
    for (a, b, c, d) in tomtom_tiles(region):
        data = get_json(url, params={
            "key": key, "bbox": f"{a:.5f},{b:.5f},{c:.5f},{d:.5f}",
            "fields": TOMTOM_FIELDS, "language": "en-US", "timeValidityFilter": "present",
        })
        for f in data.get("incidents", []) or []:
            pid = str((f.get("properties") or {}).get("id") or len(incidents))
            incidents[pid] = f  # tiles overlap at edges: merge by incident id
    data = {"incidents": [f for f in incidents.values() if any_point_in(region, f.get("geometry"))]}
    out: List[HazardEvent] = []
    for f in data.get("incidents", []) or []:
        p = f.get("properties") or {}
        lat, lon = first_point(f.get("geometry"))
        evs = p.get("events") or []
        desc = "; ".join(str(e.get("description", "")) for e in evs if e.get("description"))
        roads = p.get("roadNumbers") or []
        cat = int(p.get("iconCategory") or 0)
        out.append(HazardEvent(
            provider="tomtom",
            native_id=str(p.get("id") or ""),
            kind=TOMTOM_KIND.get(cat, "other"),
            title=f"{desc or 'Traffic incident'}".split(";")[0][:120],
            description=f"{desc} ({p.get('from', '')} -> {p.get('to', '')})".strip(),
            severity=TOMTOM_SEV.get(int(p.get("magnitudeOfDelay") or 0), "unknown"),
            road=", ".join(map(str, roads)),
            lat=lat, lon=lon,
            starts_at=str(p.get("startTime") or ""),
            ends_at=str(p.get("endTime") or ""),
            updated_at=str(p.get("lastReportTime") or ""),
            source_url=redact(url),
            extra={"delay_s": p.get("delay"), "length_m": p.get("length")},
        ))
    return out


PROVIDERS = {"nws": nws, "511": state511, "wzdx": wzdx, "tomtom": tomtom}
