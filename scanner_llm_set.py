"""Client layer for the proprietary "scout" LLM set (Ollama-backed).

The scout set is built from llm_set/Modelfile.* (see llm_set/build_llm_set.sh):
- scout-alert: enforcement alert decision (ALERT:/IGNORE, 1 sentence)
- scout-intel: structured dispatch intel extraction (strict JSON)
- scout-rank:  channel selector reranking (strict JSON; used via channel_selector.py)

Every query gracefully falls back to the base model (llama3.1) with an inline
system prompt when the scout model is not installed, so the pipeline keeps
working before/without `llm_set/build_llm_set.sh` having been run.
"""

import json
import time

import requests

OLLAMA_GENERATE_URL = "http://localhost:11434/api/generate"
OLLAMA_TAGS_URL = "http://localhost:11434/api/tags"
BASE_MODEL = "llama3.1"

ALERT_MODEL = "scout-alert"
INTEL_MODEL = "scout-intel"
RANK_MODEL = "scout-rank"
SCOUT_MODELS = (ALERT_MODEL, INTEL_MODEL, RANK_MODEL)

# Inline fallback system prompts (mirror the Modelfile SYSTEM blocks) used when
# the scout model is missing and we must run against the raw base model.
ALERT_FALLBACK_SYSTEM = """
You are an in-car speed trap and radar alert assistant for a driver on a cross country trip.
Analyze the following police scanner transcript. Look exclusively for traffic or highway enforcement events.
Primary keywords: 'radar', 'laser', 'clocked', 'speed trap', 'pacing', '10-38' (traffic stop), 'staged near marker'.
Also treat dispatch-style cues as alert-worthy when they plausibly indicate traffic enforcement activity:
- unit callouts and acknowledgements ('unit', 'copy', 'go ahead', '10-xx', 'code xx')
- lane/highway/location markers ('mile marker', 'exit', 'northbound', 'southbound', 'on-ramp', 'shoulder')
- coordination language ('switch to channel', 'in progress', 'vehicle stop', 'running traffic')
If traffic enforcement is explicit OR dispatch-style clues strongly suggest active roadway enforcement, reply EXACTLY with a 1-sentence warning starting with 'ALERT:'.
Example: 'ALERT: State trooper clocking speed near mile marker 85.'
If transcript is ambiguous, prefer ALERT only when at least 2 dispatch/enforcement clues are present; otherwise reply 'IGNORE'.
If it is generic chatter, static, or irrelevant noise, reply strictly with 'IGNORE'.
Always note location context in the alert sentence when it is present in the transcript:
street addresses, intersections, highways/routes, exits, mile markers, and points of interest
(businesses, schools, parks, gas stations, hotels, landmarks). Repeat the location wording verbatim.
"""

INTEL_FALLBACK_SYSTEM = """
You are a police scanner dispatch analyst. Extract structured intel from the transcript and
reply with ONLY a single JSON object using exactly this schema (all keys always present):
{"call_types": [...], "priority": "high"|"medium"|"low"|"unknown", "codes": [...],
 "units": [...], "locations": [...], "pois": [...], "summary": "..."}
Copy location and POI wording verbatim from the transcript; never invent places.
Use empty arrays / empty string when nothing applies. No prose outside the JSON object.
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
        "models": {name: name in models for name in SCOUT_MODELS},
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
    else:
        prompt = f"Transcript: {transcript_text}"
    payload = {"model": resolved_model, "prompt": prompt, "stream": False}
    result = _generate(payload, url, timeout_seconds, retries)
    if not result["response"]:
        result["response"] = "IGNORE"
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


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="scout LLM set client (status/alert/intel)")
    parser.add_argument("command", choices=["status", "alert", "intel"])
    parser.add_argument("text", nargs="?", default="", help="Transcript text for alert/intel")
    parser.add_argument("--timeout", type=float, default=20.0)
    args = parser.parse_args()
    if args.command == "status":
        print(json.dumps(llm_set_status(force_refresh=True), indent=2))
    elif args.command == "alert":
        print(json.dumps(query_alert(args.text, timeout_seconds=args.timeout), indent=2))
    else:
        print(json.dumps(query_intel(args.text, timeout_seconds=args.timeout), indent=2))
