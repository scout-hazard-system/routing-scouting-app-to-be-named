import argparse
import json
import math
import re
from pathlib import Path
from dataclasses import dataclass
from typing import Any

import requests


DEFAULT_OLLAMA_URL = "http://localhost:11434/api/generate"
TOKEN_SPLIT_RE = re.compile(r"[^a-z0-9]+")
STATE_NAME_TO_ABBR = {
    "alabama": "AL",
    "alaska": "AK",
    "arizona": "AZ",
    "arkansas": "AR",
    "california": "CA",
    "colorado": "CO",
    "connecticut": "CT",
    "delaware": "DE",
    "district of columbia": "DC",
    "florida": "FL",
    "georgia": "GA",
    "hawaii": "HI",
    "idaho": "ID",
    "illinois": "IL",
    "indiana": "IN",
    "iowa": "IA",
    "kansas": "KS",
    "kentucky": "KY",
    "louisiana": "LA",
    "maine": "ME",
    "maryland": "MD",
    "massachusetts": "MA",
    "michigan": "MI",
    "minnesota": "MN",
    "mississippi": "MS",
    "missouri": "MO",
    "montana": "MT",
    "nebraska": "NE",
    "nevada": "NV",
    "new hampshire": "NH",
    "new jersey": "NJ",
    "new mexico": "NM",
    "new york": "NY",
    "north carolina": "NC",
    "north dakota": "ND",
    "ohio": "OH",
    "oklahoma": "OK",
    "oregon": "OR",
    "pennsylvania": "PA",
    "rhode island": "RI",
    "south carolina": "SC",
    "south dakota": "SD",
    "tennessee": "TN",
    "texas": "TX",
    "utah": "UT",
    "vermont": "VT",
    "virginia": "VA",
    "washington": "WA",
    "west virginia": "WV",
    "wisconsin": "WI",
    "wyoming": "WY",
    "puerto rico": "PR",
}


def tokenize(text: str) -> set[str]:
    if not text:
        return set()
    return {tok for tok in TOKEN_SPLIT_RE.split(text.lower()) if tok}


def safe_float(value: Any) -> float | None:
    try:
        if value is None or value == "":
            return None
        return float(value)
    except Exception:
        return None


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius = 6371.0
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return radius * c


@dataclass
class SelectorContext:
    lat: float | None
    lon: float | None
    city: str
    county: str
    state: str
    desired_types: list[str]

    @property
    def jurisdiction_tokens(self) -> set[str]:
        joined = " ".join([self.city or "", self.county or "", self.state or ""]).strip()
        return tokenize(joined)


def _state_code(value: str) -> str:
    raw = (value or "").strip()
    if not raw:
        return ""
    up = raw.upper()
    if len(up) == 2 and up.isalpha():
        return up
    return STATE_NAME_TO_ABBR.get(raw.lower(), up)


def _load_payload(path: str) -> Any:
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def _extract_channels(payload: Any) -> list[dict[str, Any]]:
    if isinstance(payload, dict) and "channels" in payload and isinstance(payload["channels"], list):
        return payload["channels"]
    if isinstance(payload, list):
        return payload
    raise ValueError("Expected channel catalog JSON as a list or {\"channels\": [...]} object.")


def load_channels(path: str, ctx: SelectorContext | None = None) -> list[dict[str, Any]]:
    payload = _load_payload(path)
    if isinstance(payload, dict) and isinstance(payload.get("regions"), dict):
        base_dir = Path(path).parent
        state = _state_code(ctx.state) if ctx else ""
        cross_shard_mode = bool(
            (ctx is not None)
            and not state
            and (ctx.lat is not None)
            and (ctx.lon is not None)
        )
        selected_files: list[str] = []
        regions = payload.get("regions", {})
        state_to_region = payload.get("state_to_region", {})
        if cross_shard_mode:
            for _, robj in regions.items():
                if not isinstance(robj, dict):
                    continue
                r_state_files = robj.get("state_files", {})
                if isinstance(r_state_files, dict):
                    selected_files.extend(str(item) for item in r_state_files.values() if item)
                r_shared = robj.get("shared_files", [])
                if isinstance(r_shared, list):
                    selected_files.extend(str(item) for item in r_shared if item)
        else:
            region_name = state_to_region.get(state) if state else None
            if not region_name and state:
                for rname, robj in regions.items():
                    if not isinstance(robj, dict):
                        continue
                    r_state_files = robj.get("state_files", {})
                    if isinstance(r_state_files, dict) and state in r_state_files:
                        region_name = rname
                        break
            if region_name and region_name in regions and isinstance(regions[region_name], dict):
                region_obj = regions[region_name]
                r_state_files = region_obj.get("state_files", {})
                if isinstance(r_state_files, dict) and state and state in r_state_files:
                    selected_files.append(str(r_state_files[state]))
                r_shared = region_obj.get("shared_files", [])
                if isinstance(r_shared, list):
                    selected_files.extend(str(item) for item in r_shared if item)
        shared_files = payload.get("shared_files", [])
        if isinstance(shared_files, list):
            selected_files.extend(str(item) for item in shared_files if item)
        fallback_files = payload.get("fallback_files", [])
        if isinstance(fallback_files, list):
            selected_files.extend(str(item) for item in fallback_files if item)
        if not selected_files:
            default_files = payload.get("default_files", [])
            if isinstance(default_files, list):
                selected_files.extend(str(item) for item in default_files if item)
        out: list[dict[str, Any]] = []
        seen_ids: set[str] = set()
        for rel in selected_files:
            shard_path = Path(rel)
            if not shard_path.is_absolute():
                shard_path = base_dir / shard_path
            shard_payload = _load_payload(str(shard_path))
            for ch in _extract_channels(shard_payload):
                ch_id = str(ch.get("id", ""))
                if ch_id and ch_id in seen_ids:
                    continue
                if ch_id:
                    seen_ids.add(ch_id)
                out.append(ch)
        return out
    if isinstance(payload, dict) and isinstance(payload.get("state_files"), dict):
        base_dir = Path(path).parent
        selected_files: list[str] = []
        state_files = payload.get("state_files", {})
        state = _state_code(ctx.state) if ctx else ""
        cross_shard_mode = bool(
            (ctx is not None)
            and not state
            and (ctx.lat is not None)
            and (ctx.lon is not None)
        )
        if state and state in state_files:
            selected_files.append(str(state_files[state]))
        elif cross_shard_mode:
            selected_files.extend(str(item) for item in state_files.values() if item)
        for extra_key in ("shared_files", "fallback_files"):
            extra = payload.get(extra_key, [])
            if isinstance(extra, list):
                selected_files.extend(str(item) for item in extra if item)
        if not selected_files:
            default_files = payload.get("default_files", [])
            if isinstance(default_files, list):
                selected_files.extend(str(item) for item in default_files if item)
        out: list[dict[str, Any]] = []
        seen_ids: set[str] = set()
        for rel in selected_files:
            shard_path = Path(rel)
            if not shard_path.is_absolute():
                shard_path = base_dir / shard_path
            shard_payload = _load_payload(str(shard_path))
            for ch in _extract_channels(shard_payload):
                ch_id = str(ch.get("id", ""))
                if ch_id and ch_id in seen_ids:
                    continue
                if ch_id:
                    seen_ids.add(ch_id)
                out.append(ch)
        return out
    return _extract_channels(payload)


def channel_text_tokens(channel: dict[str, Any]) -> set[str]:
    parts = [
        str(channel.get("name", "")),
        str(channel.get("description", "")),
        str(channel.get("city", "")),
        str(channel.get("county", "")),
        str(channel.get("state", "")),
    ]
    for key in ("tags", "service_types", "jurisdictions"):
        value = channel.get(key)
        if isinstance(value, list):
            parts.extend(str(item) for item in value)
        elif isinstance(value, str):
            parts.append(value)
    return tokenize(" ".join(parts))


def deterministic_score(channel: dict[str, Any], ctx: SelectorContext) -> tuple[float, dict[str, float]]:
    score = 0.0
    parts: dict[str, float] = {}
    tokens = channel_text_tokens(channel)

    online = bool(channel.get("online", True))
    if online:
        parts["online_bonus"] = 20.0
        score += 20.0
    else:
        parts["offline_penalty"] = -25.0
        score -= 25.0

    if ctx.jurisdiction_tokens:
        matches = len(ctx.jurisdiction_tokens.intersection(tokens))
        ratio = matches / max(1, len(ctx.jurisdiction_tokens))
        jurisdiction_points = ratio * 35.0
        parts["jurisdiction_match"] = round(jurisdiction_points, 3)
        score += jurisdiction_points

    desired_points = 0.0
    for desired in ctx.desired_types:
        d = desired.lower().strip()
        if not d:
            continue
        if d in tokens:
            desired_points += 10.0
        if d == "law" and any(word in tokens for word in ("police", "sheriff", "state", "trooper", "highway")):
            desired_points += 8.0
        if d == "dispatch" and "dispatch" in tokens:
            desired_points += 10.0
    if desired_points:
        capped = min(desired_points, 30.0)
        parts["desired_type_match"] = round(capped, 3)
        score += capped

    listeners = safe_float(channel.get("listeners")) or 0.0
    if listeners > 0:
        listener_points = min(math.log10(listeners + 1) * 5.0, 8.0)
        parts["listener_activity"] = round(listener_points, 3)
        score += listener_points

    chan_lat = safe_float(channel.get("lat"))
    chan_lon = safe_float(channel.get("lon"))
    if ctx.lat is not None and ctx.lon is not None and chan_lat is not None and chan_lon is not None:
        dist_km = haversine_km(ctx.lat, ctx.lon, chan_lat, chan_lon)
        distance_points = max(0.0, 30.0 - 0.35 * dist_km)
        parts["distance_km"] = round(dist_km, 3)
        parts["distance_bonus"] = round(distance_points, 3)
        score += distance_points

    return score, parts


def ollama_rerank(
    candidates: list[dict[str, Any]],
    ctx: SelectorContext,
    model: str,
    url: str,
    timeout_seconds: float,
) -> dict[str, float]:
    prompt = {
        "goal": (
            "Rank radio channels for driver safety monitoring by jurisdiction fit and likely traffic-enforcement relevance. "
            "Prefer law enforcement dispatch/main channels over tactical/non-traffic channels."
        ),
        "location_context": {
            "city": ctx.city,
            "county": ctx.county,
            "state": ctx.state,
            "lat": ctx.lat,
            "lon": ctx.lon,
            "desired_types": ctx.desired_types,
        },
        "candidates": [
            {
                "id": c.get("id"),
                "name": c.get("name"),
                "description": c.get("description"),
                "city": c.get("city"),
                "county": c.get("county"),
                "state": c.get("state"),
                "service_types": c.get("service_types"),
                "tags": c.get("tags"),
            }
            for c in candidates
        ],
        "instructions": (
            "Return ONLY compact JSON in this format: "
            "{\"ranking\":[{\"id\":\"<id>\",\"score\":<0_to_1_float>,\"reason\":\"<short>\"}, ...]} "
            "Include only candidate IDs provided."
        ),
    }
    payload = {
        "model": model,
        "prompt": json.dumps(prompt, ensure_ascii=False),
        "stream": False,
    }
    response = requests.post(url, json=payload, timeout=timeout_seconds)
    response.raise_for_status()
    body = response.json().get("response", "").strip()

    parsed = json.loads(body)
    ranking = parsed.get("ranking", [])
    out: dict[str, float] = {}
    for item in ranking:
        ch_id = str(item.get("id", "")).strip()
        score = safe_float(item.get("score"))
        if ch_id and score is not None:
            out[ch_id] = max(0.0, min(1.0, score))
    return out


def normalize_channel(channel: dict[str, Any]) -> dict[str, Any]:
    out = dict(channel)
    if "id" not in out:
        out["id"] = out.get("name", "unknown")
    return out


def select_channels(
    channels: list[dict[str, Any]],
    ctx: SelectorContext,
    top_k: int,
    use_ollama_rerank: bool,
    ollama_model: str,
    ollama_url: str,
    ollama_timeout: float,
    ollama_weight: float,
) -> tuple[list[dict[str, Any]], str | None]:
    scored: list[dict[str, Any]] = []
    for raw in channels:
        channel = normalize_channel(raw)
        base_score, parts = deterministic_score(channel, ctx)
        scored.append(
            {
                "channel": channel,
                "deterministic_score": round(base_score, 4),
                "score_parts": parts,
                "ollama_score": None,
                "final_score": round(base_score, 4),
            }
        )
    scored.sort(key=lambda item: item["deterministic_score"], reverse=True)
    preselected = scored[: max(1, top_k)]

    rerank_error = None
    if use_ollama_rerank and preselected:
        try:
            ollama_map = ollama_rerank(
                [item["channel"] for item in preselected],
                ctx=ctx,
                model=ollama_model,
                url=ollama_url,
                timeout_seconds=ollama_timeout,
            )
            weight = max(0.0, min(1.0, ollama_weight))
            for item in preselected:
                channel_id = str(item["channel"].get("id"))
                model_score = ollama_map.get(channel_id)
                if model_score is None:
                    continue
                item["ollama_score"] = round(model_score, 4)
                bonus = model_score * 100.0 * weight
                item["final_score"] = round(item["deterministic_score"] + bonus, 4)
            preselected.sort(key=lambda item: item["final_score"], reverse=True)
        except Exception as exc:
            rerank_error = repr(exc)

    return preselected, rerank_error


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Deterministic Broadcastify-style channel selector with optional Ollama reranking."
    )
    parser.add_argument("--channels-file", required=True, help="Path to channel catalog JSON file.")
    parser.add_argument("--lat", type=float, default=None, help="Current latitude (optional).")
    parser.add_argument("--lon", type=float, default=None, help="Current longitude (optional).")
    parser.add_argument("--city", type=str, default="", help="Current city/jurisdiction context.")
    parser.add_argument("--county", type=str, default="", help="Current county context.")
    parser.add_argument("--state", type=str, default="", help="Current state context.")
    parser.add_argument(
        "--desired-types",
        type=str,
        default="law,dispatch",
        help="Comma-separated desired channel type tokens (e.g. law,dispatch,highway).",
    )
    parser.add_argument("--top-k", type=int, default=8, help="How many top deterministic candidates to consider.")
    parser.add_argument(
        "--use-ollama-rerank",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Enable optional Ollama reranking on top deterministic candidates.",
    )
    parser.add_argument("--ollama-model", type=str, default="llama3.1", help="Ollama model for reranking.")
    parser.add_argument("--ollama-url", type=str, default=DEFAULT_OLLAMA_URL, help="Ollama /api/generate endpoint.")
    parser.add_argument("--ollama-timeout", type=float, default=8.0, help="Ollama timeout in seconds.")
    parser.add_argument(
        "--ollama-weight",
        type=float,
        default=0.20,
        help="Blend weight [0..1] for Ollama score bonus into final ranking.",
    )
    parser.add_argument(
        "--output-format",
        choices=["json", "text"],
        default="text",
        help="Output format for selection results.",
    )
    parser.add_argument("--print-top", type=int, default=5, help="How many ranked candidates to print.")
    return parser


def main() -> None:
    args = build_parser().parse_args()
    desired_types = [token.strip() for token in args.desired_types.split(",") if token.strip()]
    ctx = SelectorContext(
        lat=args.lat,
        lon=args.lon,
        city=args.city,
        county=args.county,
        state=args.state,
        desired_types=desired_types,
    )
    channels = load_channels(args.channels_file, ctx=ctx)
    ranked, rerank_error = select_channels(
        channels=channels,
        ctx=ctx,
        top_k=args.top_k,
        use_ollama_rerank=args.use_ollama_rerank,
        ollama_model=args.ollama_model,
        ollama_url=args.ollama_url,
        ollama_timeout=args.ollama_timeout,
        ollama_weight=args.ollama_weight,
    )
    top = ranked[: max(1, args.print_top)]

    if args.output_format == "json":
        output = {
            "selected": top[0]["channel"] if top else None,
            "ranked": [
                {
                    "id": item["channel"].get("id"),
                    "name": item["channel"].get("name"),
                    "stream_url": item["channel"].get("stream_url"),
                    "deterministic_score": item["deterministic_score"],
                    "ollama_score": item["ollama_score"],
                    "final_score": item["final_score"],
                    "score_parts": item["score_parts"],
                }
                for item in top
            ],
            "rerank_error": rerank_error,
        }
        print(json.dumps(output, ensure_ascii=False))
        return

    print("Channel selector result")
    if rerank_error:
        print(f"Ollama rerank disabled due to error: {rerank_error}")
    for idx, item in enumerate(top, start=1):
        channel = item["channel"]
        print(
            f"{idx}. id={channel.get('id')} "
            f"name={channel.get('name')} "
            f"final={item['final_score']:.3f} "
            f"deterministic={item['deterministic_score']:.3f} "
            f"ollama={item['ollama_score']}"
        )
        print(
            f"   jurisdiction={channel.get('city','')}/{channel.get('county','')}/{channel.get('state','')} "
            f"service_types={channel.get('service_types')}"
        )
        print(f"   stream_url={channel.get('stream_url')}")
        print(f"   score_parts={item['score_parts']}")


if __name__ == "__main__":
    main()
