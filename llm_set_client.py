"""Client layer for the proprietary "scout" LLM set (Ollama-backed).

Unified deployment model:
- scout-core1.0.3: one model for alert, intel extraction, and spoken navigation guidance
- scout-rank: channel selector reranking

Every query gracefully falls back to the base model (llama3.1) with inline
system prompts when the preferred scout model is not installed.
"""

import json
import time

import requests

OLLAMA_GENERATE_URL = "http://localhost:11434/api/generate"
OLLAMA_TAGS_URL = "http://localhost:11434/api/tags"
BASE_MODEL = "llama3.1"

CORE_MODEL = "scout-core1.0.3"
ALERT_MODEL = CORE_MODEL
INTEL_MODEL = CORE_MODEL
RANK_MODEL = "scout-rank"
VET_MODEL = "scout-vet1.0.4"
SCOUT_MODELS = (CORE_MODEL, RANK_MODEL, VET_MODEL)

# Inline fallback system prompts (mirror the Modelfile SYSTEM blocks) used when
# the scout model is missing and we must run against the raw base model.
ALERT_FALLBACK_SYSTEM = """
You are an in-car speed trap and radar alert assistant for a driver on a cross country trip.
The user gives you one police scanner transcript. Decide if it describes ACTIVE or PLANNED roadway traffic enforcement.

Reply ALERT for any of these (a single clue is enough):
- radar, laser, lidar, speed trap, clocking, pacing, speed enforcement (ground or aircraft)
- traffic stop / vehicle stop / 10-38 / pulling a vehicle over
- pursuit, spike strips, PIT maneuver, subject vehicle at high speed
- DUI checkpoint or emphasis patrol; seatbelt, cell phone, or school-zone emphasis
- officers "running traffic", traffic detail issuing citations, motor units working traffic

Reply IGNORE for everything else, including:
- fire/EMS/medical calls, welfare checks, alarms, thefts, noise complaints, warrants, K9 tracks
- accidents/collisions UNLESS enforcement activity is also mentioned
- general police activity not tied to traffic enforcement or roadway safety
- radio checks, shift/admin chatter, meal breaks, plate returns with no stop, parades/road closures, static/unreadable audio

Output contract:
- If ALERT: reply EXACTLY one sentence starting with 'ALERT:' describing the enforcement.
- Repeat every location mentioned VERBATIM in your sentence: streets, intersections, highways/routes,
  exits, mile markers, directions of travel, and points of interest (businesses, schools, parks, landmarks).
- If IGNORE: reply with the single word IGNORE.
- Never add explanations, quotes, or extra lines.
"""

INTEL_FALLBACK_SYSTEM = """
You are a police scanner dispatch analyst. Extract structured intel from the transcript and
reply with ONLY a single JSON object using exactly this schema (all keys always present):
{"call_types": ["traffic_stop"|"speed_enforcement"|"pursuit"|"accident"|"units_coordination"|"unclassified", ...],
 "priority": "high"|"medium"|"low"|"unknown", "codes": [...],
 "units": [...], "locations": [...], "pois": [...], "summary": "..."}
call_types definitions (pick every one that applies; use "unclassified" only when none apply):
- traffic_stop: an officer stopping or having stopped a specific vehicle (10-38, vehicle stop, plate on a stop).
- speed_enforcement: radar/laser/lidar, speed trap, clocking, pacing, aircraft speed work, "running traffic",
  DUI checkpoint, and any emphasis patrol (speed, seatbelt, cell phone, school zone) or traffic detail with citations.
- pursuit: fleeing vehicle, failure to yield, spike strips, PIT, high-speed chase.
- accident: collisions, crashes, injury or non-injury wrecks.
- units_coordination: channel switches, staging, backup requests, detail assignments without another category.
- unclassified: non-traffic police calls, fire/EMS/medical, alarms, admin chatter, anything not covered above.
Rules:
- Focus on traffic-relevant details only; do not expand on non-traffic incidents.
- Copy location and POI wording verbatim from the transcript; never invent places.
- Keep full street numbers verbatim: "1204 M Avenue", never just "M Avenue".
- "locations": streets, intersections, highways, exits, mile markers, blocks, directions of travel.
- "pois": named or described places such as businesses, schools, parks, terminals, libraries,
  fairgrounds, hotels, restaurants, landmarks. A place like "the library" or "the ferry terminal" is a POI.
- Apartment complexes and named buildings are POIs, not locations.
- "codes": any 10-codes or 'code N' phrases heard. "units": unit numbers/callsigns.
- "priority": high for in-progress pursuit/officer-assist/emergency, medium for stops/accidents/traffic hazards,
  low for clear/cancel/information-only, unknown otherwise.
- Use empty arrays / empty string when nothing applies. No prose outside the JSON object.
"""

NAV_FALLBACK_SYSTEM = """
You are an in-car navigation voice assistant for traffic alerts.
Given one scanner transcript, return ONLY one spoken line suitable for TTS.
Rules:
- Focus on immediate driving guidance tied to traffic enforcement or roadway hazards.
- Keep it under 20 words.
- Use plain language and include a location only if clearly present.
- If transcript has no traffic-relevant guidance, return exactly: USE CAUTION AHEAD.
"""

_INTEL_LIST_KEYS = ("call_types", "codes", "units", "locations", "pois")

_availability_cache = {"ts": 0.0, "models": None}
AVAILABILITY_TTL_SECONDS = 300.0


def installed_models(tags_url=OLLAMA_TAGS_URL, timeout_seconds=2.0, force_refresh=False):
    """Return the set of installed Ollama model names (without ':latest'), or None if Ollama is down."""
    now = time.time()
    if (
        not force_refresh
        and _availability_cache["models"] is not None
        and now - _availability_cache["ts"] < AVAILABILITY_TTL_SECONDS
    ):
        return _availability_cache["models"]
    try:
        response = requests.get(tags_url, timeout=timeout_seconds)
        response.raise_for_status()
        names = set()
        for item in response.json().get("models", []):
            name = str(item.get("name", ""))
            if name:
                names.add(name)
                if name.endswith(":latest"):
                    names.add(name[: -len(":latest")])
        _availability_cache["models"] = names
        _availability_cache["ts"] = now
        return names
    except Exception:
        _availability_cache["models"] = None
        _availability_cache["ts"] = now
        return None


def llm_set_status(tags_url=OLLAMA_TAGS_URL, timeout_seconds=2.0, force_refresh=False):
    """Availability summary for pipeline_ready events and diagnostics."""
    models = installed_models(tags_url, timeout_seconds, force_refresh)
    ollama_up = models is not None
    models = models or set()
    return {
        "ollama_up": ollama_up,
        "base_model": BASE_MODEL,
        "base_model_installed": BASE_MODEL in models,
        "models": {
            CORE_MODEL: CORE_MODEL in models,
            RANK_MODEL: RANK_MODEL in models,
        },
        "complete": all(name in models for name in SCOUT_MODELS),
    }


def resolve_model(preferred, fallback=BASE_MODEL, tags_url=OLLAMA_TAGS_URL):
    """Pick the preferred scout model when installed, else the fallback base model."""
    models = installed_models(tags_url)
    if models is None or preferred in models:
        # Ollama down: keep preferred so error surfaces attribute the right model.
        return preferred, False
    return fallback, True


def _generate(payload, url, timeout_seconds, retries):
    attempts = retries + 1
    last_error = None
    last_status = None
    last_raw = None
    for _ in range(attempts):
        try:
            response = requests.post(url, json=payload, timeout=timeout_seconds)
            last_status = response.status_code
            raw_text = response.text
            last_raw = raw_text[:500]
            try:
                parsed = response.json()
            except Exception:
                parsed = {}
            return {
                "response": parsed.get("response", ""),
                "status_code": last_status,
                "error": None,
                "raw": last_raw,
                "attempts": attempts,
            }
        except Exception as e:
            last_error = repr(e)
    return {
        "response": "",
        "status_code": last_status,
        "error": last_error,
        "raw": last_raw,
        "attempts": attempts,
    }


def query_alert(
    transcript_text,
    timeout_seconds=8.0,
    retries=0,
    url=OLLAMA_GENERATE_URL,
    model=ALERT_MODEL,
):
    """Enforcement alert decision. Return contract matches pipeline.query_llm plus model info."""
    resolved_model, used_fallback = resolve_model(model)
    if used_fallback:
        prompt = f"{ALERT_FALLBACK_SYSTEM}\n\nTranscript: {transcript_text}"
    elif resolved_model == CORE_MODEL:
        prompt = (
            "TASK: ALERT\n"
            "Output contract:\n"
            "- If enforcement is present, return exactly one sentence starting with 'ALERT:'\n"
            "- Otherwise return exactly 'IGNORE'\n\n"
            f"Transcript: {transcript_text}"
        )
    else:
        prompt = f"Transcript: {transcript_text}"
    payload = {"model": resolved_model, "prompt": prompt, "stream": False}
    result = _generate(payload, url, timeout_seconds, retries)
    if not result["response"]:
        result["response"] = "IGNORE"
    result["model"] = resolved_model
    result["used_fallback"] = used_fallback
    return result


def query_alert_vet(
    transcript_text,
    proposed_alert_text="",
    timeout_seconds=6.0,
    retries=0,
    url=OLLAMA_GENERATE_URL,
    model=VET_MODEL,
):
    """Second-stage vet gate: returns VET_PASS / VET_FAIL for proposed alerts."""
    resolved_model, used_fallback = resolve_model(model)
    if used_fallback:
        prompt = (
            f"{VET_FALLBACK_SYSTEM}\n\n"
            f"Transcript: {transcript_text}\n"
            f"Proposed alert: {proposed_alert_text}"
        )
    elif resolved_model == VET_MODEL:
        prompt = (
            "TASK: VET\n"
            "Output contract:\n"
            "- Return exactly VET_PASS or VET_FAIL\n"
            "- Pass only for clear roadway traffic enforcement or immediate driving hazard\n\n"
            f"Transcript: {transcript_text}\n"
            f"Proposed alert: {proposed_alert_text}"
        )
    else:
        prompt = (
            f"{VET_FALLBACK_SYSTEM}\n\n"
            f"Transcript: {transcript_text}\n"
            f"Proposed alert: {proposed_alert_text}"
        )
    payload = {"model": resolved_model, "prompt": prompt, "stream": False}
    result = _generate(payload, url, timeout_seconds, retries)
    response = str(result.get("response") or "").strip().upper()
    decision = "VET_PASS" if response.startswith("VET_PASS") else "VET_FAIL"
    result["decision"] = decision
    result["response"] = decision
    result["model"] = resolved_model
    result["used_fallback"] = used_fallback
    return result


def _coerce_intel(parsed):
    """Normalize a parsed intel object to the fixed schema."""
    intel = {key: [] for key in _INTEL_LIST_KEYS}
    intel["priority"] = "unknown"
    intel["summary"] = ""
    if not isinstance(parsed, dict):
        return intel
    for key in _INTEL_LIST_KEYS:
        value = parsed.get(key)
        if isinstance(value, list):
            intel[key] = [str(v).strip() for v in value if str(v).strip()]
        elif isinstance(value, str) and value.strip():
            intel[key] = [value.strip()]
    priority = str(parsed.get("priority", "unknown")).strip().lower()
    if priority in ("high", "medium", "low"):
        intel["priority"] = priority
    summary = parsed.get("summary")
    if isinstance(summary, str):
        intel["summary"] = summary.strip()
    return intel


def query_intel(
    transcript_text,
    timeout_seconds=10.0,
    retries=0,
    url=OLLAMA_GENERATE_URL,
    model=INTEL_MODEL,
):
    """Structured dispatch intel extraction. Returns {'intel': dict|None, ...diagnostics}."""
    resolved_model, used_fallback = resolve_model(model)
    if used_fallback:
        prompt = f"{INTEL_FALLBACK_SYSTEM}\n\nTranscript: {transcript_text}"
    elif resolved_model == CORE_MODEL:
        prompt = (
            "TASK: INTEL\n"
            "Return ONLY one JSON object with keys: call_types, priority, codes, units, locations, pois, summary\n\n"
            f"Transcript: {transcript_text}"
        )
    else:
        prompt = f"Transcript: {transcript_text}"
    payload = {"model": resolved_model, "prompt": prompt, "stream": False, "format": "json"}
    result = _generate(payload, url, timeout_seconds, retries)
    intel = None
    parse_error = None
    if result["response"]:
        try:
            intel = _coerce_intel(json.loads(result["response"]))
        except Exception as e:
            parse_error = repr(e)
    result["intel"] = intel
    result["parse_error"] = parse_error
    result["model"] = resolved_model
    result["used_fallback"] = used_fallback
    return result



VET_FALLBACK_SYSTEM = """
You are a conservative alert vetting classifier for scanner traffic alerts.
Given one transcript and a proposed alert, decide if the alert should be shown to drivers.
Output exactly one token:
- VET_PASS
- VET_FAIL

Pass only when the transcript clearly indicates active/planned roadway traffic enforcement
or immediate roadway driving hazard. Fail otherwise, including ambiguous/non-traffic chatter.
Do not output any extra text.
"""
def query_nav(
    transcript_text,
    timeout_seconds=8.0,
    retries=0,
    url=OLLAMA_GENERATE_URL,
    model=CORE_MODEL,
):
    """Traffic-focused spoken navigation guidance for in-car TTS."""
    resolved_model, used_fallback = resolve_model(model)
    if used_fallback:
        prompt = f"{NAV_FALLBACK_SYSTEM}\n\nTranscript: {transcript_text}"
    elif resolved_model == CORE_MODEL:
        prompt = (
            "TASK: NAV\n"
            "Output contract:\n"
            "- Return exactly one short spoken driving guidance sentence\n"
            "- 20 words max\n"
            "- Traffic-enforcement/road-hazard relevance only\n"
            "- If unclear or not traffic-related, return exactly: USE CAUTION AHEAD\n\n"
            f"Transcript: {transcript_text}"
        )
    else:
        prompt = f"Transcript: {transcript_text}"
    payload = {"model": resolved_model, "prompt": prompt, "stream": False}
    result = _generate(payload, url, timeout_seconds, retries)
    response = (result.get("response") or "").strip()
    if not response:
        response = "USE CAUTION AHEAD"
    result["response"] = response
    result["model"] = resolved_model
    result["used_fallback"] = used_fallback
    return result


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="scout LLM set client (status/alert/intel/nav)")
    parser.add_argument("command", choices=["status", "alert", "intel", "nav"])
    parser.add_argument("text", nargs="?", default="", help="Transcript text for alert/intel/nav")
    parser.add_argument("--timeout", type=float, default=20.0)
    args = parser.parse_args()
    if args.command == "status":
        print(json.dumps(llm_set_status(force_refresh=True), indent=2))
    elif args.command == "alert":
        print(json.dumps(query_alert(args.text, timeout_seconds=args.timeout), indent=2))
    elif args.command == "intel":
        print(json.dumps(query_intel(args.text, timeout_seconds=args.timeout), indent=2))
    else:
        print(json.dumps(query_nav(args.text, timeout_seconds=args.timeout), indent=2))
