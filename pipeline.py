import argparse
import subprocess
import shutil
import time
import re
import sys
import signal
import json
from datetime import datetime, UTC
import scipy.io.wavfile as wav
import requests
import numpy as np
from faster_whisper import WhisperModel
from channel_selector import SelectorContext, load_channels, select_channels
from optional_audio_routes import ensure_optional_route_enabled


FS = 16000          # Audio frequency standard for Whisper
DURATION = 12       # Grabs audio in 12-second intervals to minimize processing lag
OLLAMA_URL = "http://localhost:11434/api/generate"
SHOULD_EXIT = False
sd = None


def require_sounddevice():
    global sd
    if sd is None:
        import sounddevice as _sd
        sd = _sd
    return sd

def request_shutdown(signum, _frame):
    global SHOULD_EXIT
    SHOULD_EXIT = True
    print(f"\nReceived signal {signum}. Shutting down gracefully...")

def emit_event_json(event_type, enabled=True, **payload):
    if not enabled:
        return
    event = {
        "ts": datetime.now(UTC).isoformat(),
        "event_type": event_type,
        **payload,
    }
    print(f"[EVENT_JSON] {json.dumps(event, ensure_ascii=False)}")

# The system prompt context maps out local 10-codes into plain speech warnings
SYSTEM_PROMPT = """
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

CALL_TYPE_KEYWORDS = {
    "traffic_stop": ["traffic stop", "stopped", "vehicle stop", "10-38", "10 38"],
    "speed_enforcement": ["radar", "laser", "clocked", "speed trap", "pacing"],
    "pursuit": ["pursuit", "chase", "failing to yield"],
    "welfare_check": ["welfare check", "check well-being"],
    "suspicious_activity": ["suspicious", "loitering", "prowler"],
    "accident": ["accident", "mvc", "crash", "collision"],
    "units_coordination": ["copy", "switch over", "channel", "unit", "dispatch"],
}
PRIORITY_KEYWORDS = {
    "high": ["in progress", "shots fired", "officer needs assistance", "pursuit", "urgent"],
    "medium": ["traffic stop", "suspicious", "welfare check", "accident"],
    "low": ["clear", "cancel", "advised", "non-injury", "information only"],
}
DISPATCH_CUE_GROUPS = {
    "primary_enforcement": ["radar", "laser", "clocked", "speed trap", "pacing", "10-38", "10 38", "vehicle stop", "running traffic"],
    "unit_ack": ["unit", "copy", "go ahead", "10-", "code "],
    "location_markers": ["mile marker", "exit", "northbound", "southbound", "on-ramp", "shoulder", "highway"],
    "coordination": ["switch to channel", "channel", "in progress", "dispatch"],
}
DISPATCH_CUE_WEIGHTS = {
    "primary_enforcement": 3,
    "location_markers": 2,
    "unit_ack": 1,
    "coordination": 1,
}
PRIMARY_ENFORCEMENT_STRONG = {"radar", "laser", "clocked", "speed trap", "pacing", "vehicle stop", "running traffic"}
DIRECT_SOURCE_FALLBACK_TOKENS = [
    "pipewire",
    "alsa_input",
    "analog",
    "usb",
    "microphone",
    "mic",
    "input",
]

def log_safe(text):
    return text.replace('"', "'").replace("\n", " ").strip()

def extract_dispatch_cues(text):
    lower = text.lower()
    matched = {}
    for group, cues in DISPATCH_CUE_GROUPS.items():
        found = []
        for cue in cues:
            if cue in lower:
                found.append(cue)
        if found:
            matched[group] = sorted(set(found))
    cue_count = sum(len(v) for v in matched.values())
    return matched, cue_count

def score_dispatch_cues(cue_map):
    score = 0
    for group, values in cue_map.items():
        weight = DISPATCH_CUE_WEIGHTS.get(group, 1)
        score += weight * len(values)
    return score

def has_strong_enforcement_signal(cue_map):
    for cue in cue_map.get("primary_enforcement", []):
        if cue in PRIMARY_ENFORCEMENT_STRONG:
            return True
    return False

def extract_codes(text):
    ten_codes = [f"10-{m.group(1)}" for m in re.finditer(r"\b10[-\s]?(\d{1,2})\b", text, flags=re.IGNORECASE)]
    generic_codes = [f"code {m.group(1)}" for m in re.finditer(r"\bcode[-\s]?(\d{1,3})\b", text, flags=re.IGNORECASE)]
    return sorted(set(ten_codes + generic_codes))

POI_KEYWORDS = [
    "gas station", "truck stop", "rest area", "rest stop", "weigh station",
    "high school", "middle school", "elementary school", "school", "college", "university",
    "hospital", "clinic", "fire station", "police station", "courthouse", "post office",
    "library", "city hall", "park", "fairgrounds", "campground", "marina", "ferry terminal",
    "airport", "train station", "bus station", "transit center",
    "mall", "shopping center", "plaza", "supermarket", "grocery store", "convenience store",
    "liquor store", "pharmacy", "bank", "casino", "church",
    "hotel", "motel", "inn", "apartment complex", "trailer park", "mobile home park",
    "restaurant", "diner", "bar", "tavern", "car wash", "parking lot", "parking garage",
    "bridge", "overpass", "underpass", "tunnel", "roundabout", "railroad crossing",
]

def _dedupe_mentions(mentions):
    deduped = []
    seen = set()
    for mention in mentions:
        key = mention.lower()
        if key not in seen:
            seen.add(key)
            deduped.append(mention)
    return deduped

def extract_location_mentions(text):
    patterns = [
        r"\b\d{1,5}\s+[A-Za-z0-9.'-]+(?:\s+[A-Za-z0-9.'-]+){0,4}\s+(?:st|street|ave|avenue|rd|road|blvd|boulevard|dr|drive|ln|lane|ct|court|hwy|highway|pkwy|parkway|way|pl|place)\b",
        r"\b\d{2,5}\s+block\s+of\s+[A-Za-z0-9.'-]+(?:\s+[A-Za-z0-9.'-]+){0,4}\b",
        r"\b(?:at|near|on)\s+([A-Za-z0-9.'-]+(?:\s+[A-Za-z0-9.'-]+){0,4}\s+(?:and|&)\s+[A-Za-z0-9.'-]+(?:\s+[A-Za-z0-9.'-]+){0,4})\b",
        r"\b(?:interstate|i)[-\s]?\d{1,3}\b",
        r"\b(?:us|state route|sr|route|highway|hwy)[-\s]?\d{1,3}\b",
        r"\b(?:mile marker|mm)\s*\d{1,3}(?:\.\d+)?\b",
        r"\bexit\s+\d+[A-Za-z]?\b",
        r"\b(?:northbound|southbound|eastbound|westbound)\b",
        r"\b(?:on-ramp|off-ramp|shoulder|interchange)\b",
    ]
    mentions = []
    for pattern in patterns:
        for match in re.finditer(pattern, text, flags=re.IGNORECASE):
            if match.groups():
                candidate = match.group(1)
            else:
                candidate = match.group(0)
            clean = re.sub(r"\s+", " ", candidate).strip(" ,.;:")
            # Trim trailing connector clauses (e.g. '... Avenue near the Shell').
            clean = re.split(r"\s+(?:near|by|at)\s+", clean, maxsplit=1, flags=re.IGNORECASE)[0]
            if clean:
                mentions.append(clean)
    return _dedupe_mentions(mentions)

def extract_poi_mentions(text):
    mentions = []
    for keyword in POI_KEYWORDS:
        for match in re.finditer(r"\b" + re.escape(keyword) + r"\b", text, flags=re.IGNORECASE):
            # Pull up to three preceding capitalized name words so named POIs are
            # captured whole (e.g. 'Anacortes High School', 'Shell gas station').
            prefix = text[: match.start()]
            lead = re.search(r"((?:[A-Z][A-Za-z0-9'&.-]*\s+){1,3})$", prefix)
            mention = (lead.group(1) if lead else "") + match.group(0)
            clean = re.sub(r"\s+", " ", mention).strip(" ,.;:")
            if clean:
                mentions.append(clean)
    # Drop shorter mentions fully contained in a longer one (keyword-only vs named POI).
    filtered = []
    lowered = [m.lower() for m in mentions]
    for i, mention in enumerate(mentions):
        contained = any(
            j != i and lowered[i] in lowered[j] and len(lowered[j]) > len(lowered[i])
            for j in range(len(mentions))
        )
        if not contained:
            filtered.append(mention)
    return _dedupe_mentions(filtered)

def classify_transcript(text):
    lower = text.lower()
    matched_types = []
    for call_type, keywords in CALL_TYPE_KEYWORDS.items():
        if any(k in lower for k in keywords):
            matched_types.append(call_type)
    if not matched_types:
        matched_types = ["unclassified"]

    priority = "unknown"
    for level, keywords in PRIORITY_KEYWORDS.items():
        if any(k in lower for k in keywords):
            priority = level
            break

    confidence = 0.45
    if matched_types != ["unclassified"]:
        confidence += 0.25
    if priority != "unknown":
        confidence += 0.15
    codes = extract_codes(text)
    if codes:
        confidence += 0.15
    confidence = min(confidence, 0.95)

    return {
        "call_types": matched_types,
        "priority": priority,
        "codes": codes,
        "confidence": round(confidence, 2),
    }
def query_llm(transcript_text, timeout_seconds=3.0, retries=0):
    payload = {
        "model": "llama3.1", 
        "prompt": f"{SYSTEM_PROMPT}\n\nTranscript: {transcript_text}",
        "stream": False
    }
    attempts = retries + 1
    last_error = None
    last_status = None
    last_raw = None
    for _ in range(attempts):
        try:
            response = requests.post(OLLAMA_URL, json=payload, timeout=timeout_seconds)
            last_status = response.status_code
            raw_text = response.text
            last_raw = raw_text[:500]
            try:
                parsed = response.json()
            except Exception:
                parsed = {}
            model_response = parsed.get("response", "IGNORE")
            return {
                "response": model_response,
                "status_code": last_status,
                "error": None,
                "raw": last_raw,
                "attempts": attempts,
            }
        except Exception as e:
            last_error = repr(e)
    return {
        "response": "IGNORE",
        "status_code": last_status,
        "error": last_error,
        "raw": last_raw,
        "attempts": attempts,
    }
def list_input_devices():
    devices = require_sounddevice().query_devices()
    return [(idx, dev) for idx, dev in enumerate(devices) if dev["max_input_channels"] > 0]
def pick_default_input_device():
    sdev = require_sounddevice()
    input_devices = list_input_devices()
    if not input_devices:
        raise RuntimeError("No audio input devices detected by sounddevice.")
    for idx, dev in input_devices:
        if "pipewire" in dev["name"].lower():
            return idx, dev["name"], "preferred_pipewire_match"
    default_input_idx = None
    default_device = sdev.default.device
    if isinstance(default_device, (list, tuple)) and len(default_device) >= 1:
        default_input_idx = default_device[0]
    elif isinstance(default_device, int):
        default_input_idx = default_device
    if isinstance(default_input_idx, int) and default_input_idx >= 0:
        default_info = sdev.query_devices(default_input_idx)
        if default_info["max_input_channels"] > 0:
            return default_input_idx, default_info["name"], "system_default_input"
    first_idx, first_dev = input_devices[0]
    return first_idx, first_dev["name"], "first_available_input"
def resolve_input_device(device_index=None, source_name=None, strict_source_match=False):
    sdev = require_sounddevice()
    input_devices = list_input_devices()
    if device_index is not None:
        devices = sdev.query_devices()
        dev = devices[device_index]
        if dev["max_input_channels"] <= 0:
            raise RuntimeError(f"Selected device index {device_index} is not an input device: {dev['name']}")
        return device_index, dev["name"], "explicit_device_index"
    if source_name:
        token = source_name.lower()
        for idx, dev in input_devices:
            if token in dev["name"].lower():
                return idx, dev["name"], "explicit_source_token_match"
        if strict_source_match:
            raise RuntimeError(f"No input device matched --source-node '{source_name}'. Use --list-devices to inspect names.")
        print(f"No input device matched --source-node '{source_name}', continuing with auto-detection fallback.")
    used_fallback_tokens = []
    for token in DIRECT_SOURCE_FALLBACK_TOKENS:
        used_fallback_tokens.append(token)
        for idx, dev in input_devices:
            if token in dev["name"].lower():
                print(f"Auto-detected input via fallback token '{token}': {dev['name']} (index {idx})")
                return idx, dev["name"], f"fallback_token:{token}"
    idx, dev_name, selection_reason = pick_default_input_device()
    print(
        "No fallback token match found; using default strategy "
        f"({selection_reason}) -> {dev_name} (index {idx}). "
        f"Tried tokens: {used_fallback_tokens}"
    )
    return idx, dev_name, selection_reason
def resolve_capture_samplerate(device_index, requested_rate):
    sdev = require_sounddevice()
    try:
        sdev.check_input_settings(device=device_index, samplerate=requested_rate, channels=1, dtype="float32")
        return requested_rate
    except Exception:
        device_info = sdev.query_devices(device_index)
        fallback_rate = int(device_info["default_samplerate"])
        print(f"Requested sample rate {requested_rate} unsupported on this source; using {fallback_rate} Hz.")
        return fallback_rate

def speak_alert(message):
    try:
        subprocess.run(["spd-say", message], check=False)
    except Exception:
        pass
def run_command(command):
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT)
def list_sinks():
    out = run_command(["pactl", "list", "short", "sinks"])
    sinks = []
    for line in out.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) >= 2:
            try:
                sink_index = int(parts[0])
            except ValueError:
                continue
            sinks.append({"index": sink_index, "name": parts[1]})
    return sinks
def sink_name_index_maps():
    sinks = list_sinks()
    index_to_name = {s["index"]: s["name"] for s in sinks}
    name_to_index = {s["name"]: s["index"] for s in sinks}
    return index_to_name, name_to_index
def default_sink_name():
    out = run_command(["pactl", "info"])
    for line in out.splitlines():
        if line.lower().startswith("default sink:"):
            return line.split(":", 1)[1].strip()
    raise RuntimeError("Could not determine default sink from pactl info.")
def parse_sink_inputs():
    out = run_command(["pactl", "list", "sink-inputs"])
    sink_inputs = []
    chunks = out.split("Sink Input #")
    for chunk in chunks[1:]:
        lines = chunk.splitlines()
        if not lines:
            continue
        try:
            sink_input_id = int(lines[0].strip())
        except ValueError:
            continue
        sink_index = None
        for line in lines:
            stripped = line.strip()
            if stripped.startswith("Sink:"):
                try:
                    sink_index = int(stripped.split(":", 1)[1].strip())
                except ValueError:
                    sink_index = None
                break
        lower = chunk.lower()
        is_scrcpy = (
            'application.process.binary = "scrcpy"' in lower
            or 'application.name = "scrcpy"' in lower
            or "scrcpy" in lower
        )
        sink_inputs.append({
            "id": sink_input_id,
            "sink_index": sink_index,
            "is_scrcpy": is_scrcpy,
        })
    return sink_inputs
def ensure_binary(name_or_path):
    if "/" in name_or_path:
        if not shutil.which(name_or_path):
            raise RuntimeError(f"Required binary '{name_or_path}' not found or not executable.")
    elif shutil.which(name_or_path) is None:
        raise RuntimeError(f"Required binary '{name_or_path}' not found. Install it first.")
def ensure_scrcpy_audio_support(scrcpy_bin):
    out = subprocess.check_output([scrcpy_bin, "--version"], text=True)
    first = out.splitlines()[0].strip().lower()
    # scrcpy audio forwarding is available in modern releases (2.x+)
    version_token = first.split()[1]
    major = int(version_token.split(".")[0])
    if major < 2:
        raise RuntimeError(
            f"Installed scrcpy version {version_token} does not support audio forwarding. "
            "Install scrcpy 2.x+ or use direct mode."
        )
def adb_connected_devices():
    output = subprocess.check_output(["adb", "devices"], text=True)
    lines = [ln.strip() for ln in output.splitlines()[1:] if ln.strip()]
    devices = []
    for ln in lines:
        if "\tdevice" in ln:
            devices.append(ln.split("\t")[0])
    return devices
def sink_exists(sink_name):
    _, name_to_index = sink_name_index_maps()
    return sink_name in name_to_index
def ensure_null_sink(sink_name):
    if sink_exists(sink_name):
        return
    run_command([
        "pactl", "load-module", "module-null-sink",
        f"sink_name={sink_name}",
        "sink_properties=device.description=ScannerSink"
    ])
def find_scrcpy_sink_input_id():
    scrcpy_inputs = [si["id"] for si in parse_sink_inputs() if si["is_scrcpy"]]
    if scrcpy_inputs:
        return max(scrcpy_inputs)
    return None
def wait_for_scrcpy_sink_input(timeout_seconds=20):
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        sink_input_id = find_scrcpy_sink_input_id()
        if sink_input_id is not None:
            return sink_input_id
        time.sleep(0.5)
    return None
def move_sink_input_to_sink(sink_input_id, sink_name):
    run_command(["pactl", "move-sink-input", str(sink_input_id), sink_name])
def enforce_scrcpy_sink_purity(target_sink_name, scrcpy_sink_input_id):
    index_to_name, name_to_index = sink_name_index_maps()
    target_sink_index = name_to_index.get(target_sink_name)
    if target_sink_index is None:
        raise RuntimeError(f"Target sink '{target_sink_name}' is missing.")
    current_default_sink = default_sink_name()
    fallback_sink = current_default_sink
    if fallback_sink == target_sink_name:
        for name in name_to_index.keys():
            if name != target_sink_name:
                fallback_sink = name
                break
    evicted = 0
    for sink_input in parse_sink_inputs():
        if sink_input["sink_index"] != target_sink_index:
            continue
        if sink_input["id"] == scrcpy_sink_input_id:
            continue
        if sink_input["is_scrcpy"]:
            continue
        if fallback_sink != target_sink_name:
            move_sink_input_to_sink(sink_input["id"], fallback_sink)
            evicted += 1
    if evicted > 0:
        print(f"Evicted {evicted} non-scrcpy stream(s) from '{target_sink_name}'.")
    return evicted
def capture_scrcpy_chunk_to_wav(source_name, duration_seconds, output_path, sample_rate):
    subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "pulse",
        "-i", source_name,
        "-ac", "1",
        "-ar", str(sample_rate),
        "-t", str(duration_seconds),
        output_path
    ], check=True)
def capture_stream_chunk_to_wav(stream_url, duration_seconds, output_path, sample_rate):
    subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-rw_timeout", "15000000",
        "-i", stream_url,
        "-vn",
        "-ac", "1",
        "-ar", str(sample_rate),
        "-t", str(duration_seconds),
        output_path
    ], check=True)
def resolve_broadcast_streams(args):
    if args.stream_url:
        return [{"id": "manual_stream", "name": "manual_stream", "stream_url": args.stream_url}], None
    if not args.channels_file:
        raise RuntimeError("Broadcastify mode requires --stream-url or --channels-file for selector-based auto-selection.")
    desired_types = [token.strip() for token in args.selector_desired_types.split(",") if token.strip()]
    ctx = SelectorContext(
        lat=args.selector_lat,
        lon=args.selector_lon,
        city=args.selector_city,
        county=args.selector_county,
        state=args.selector_state,
        desired_types=desired_types,
    )
    channels = load_channels(args.channels_file, ctx=ctx)
    ranked, rerank_error = select_channels(
        channels=channels,
        ctx=ctx,
        top_k=args.selector_top_k,
        use_ollama_rerank=args.selector_use_ollama_rerank,
        ollama_model=args.selector_ollama_model,
        ollama_url=args.selector_ollama_url,
        ollama_timeout=args.selector_ollama_timeout,
        ollama_weight=args.selector_ollama_weight,
    )
    if not ranked:
        raise RuntimeError("Channel selector did not return any ranked candidates.")
    candidates = []
    for item in ranked:
        channel = dict(item["channel"])
        stream_url = channel.get("stream_url")
        if not stream_url:
            continue
        channel["_rank_score"] = item.get("score")
        candidates.append(channel)
    if not candidates:
        raise RuntimeError("Channel selector returned no streamable channels (missing stream_url).")
    return candidates, rerank_error
class TeeStream:
    def __init__(self, *streams):
        self.streams = streams
    def write(self, data):
        for s in self.streams:
            s.write(data)
            s.flush()
        return len(data)
    def flush(self):
        for s in self.streams:
            s.flush()
    def isatty(self):
        return any(getattr(s, "isatty", lambda: False)() for s in self.streams)
    def fileno(self):
        for s in self.streams:
            if hasattr(s, "fileno"):
                try:
                    return s.fileno()
                except Exception:
                    continue
        raise OSError("No fileno available")
def start_scrcpy(scrcpy_bin, serial=None):
    ensure_binary(scrcpy_bin)
    ensure_binary("adb")
    ensure_scrcpy_audio_support(scrcpy_bin)
    devices = adb_connected_devices()
    if serial:
        if serial not in devices:
            raise RuntimeError(f"ADB device '{serial}' not connected/authorized. Connected: {devices}")
    elif not devices:
        raise RuntimeError("No authorized ADB devices connected.")
    cmd = [scrcpy_bin, "--no-video", "--require-audio", "--audio-source=output", "--no-control"]
    if serial:
        cmd.extend(["-s", serial])
    print(f"Starting scrcpy: {' '.join(cmd)}")
    return subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

parser = argparse.ArgumentParser(description="Live police scanner -> Whisper -> LLM alert pipeline")
parser.add_argument(
    "--mode",
    choices=["broadcastify", "direct", "scrcpy", "audiorelay", "voicemeeter"],
    default="broadcastify",
    help="Audio capture mode. Scanner base defaults to broadcastify; other routes are optional.",
)
parser.add_argument(
    "--enable-optional-audio-routes",
    action=argparse.BooleanOptionalAction,
    default=False,
    help="Enable optional non-broadcast routes (direct, scrcpy, audiorelay, voicemeeter).",
)
parser.add_argument("--device", type=int, default=None, help="Input device index (use --list-devices to inspect)")
parser.add_argument("--source-node", type=str, default=None, help="Input source name substring (used when --device is not set)")
parser.add_argument("--duration", type=float, default=DURATION, help="Capture duration per chunk in seconds")
parser.add_argument("--list-devices", action="store_true", help="List audio devices and exit")
parser.add_argument("--start-scrcpy", action="store_true", help="Auto-launch scrcpy before capturing (scrcpy mode only)")
parser.add_argument("--serial", type=str, default=None, help="ADB device serial (optional)")
parser.add_argument("--scrcpy-bin", type=str, default="scrcpy", help="scrcpy executable path/name for scrcpy mode")
parser.add_argument("--scrcpy-sink", type=str, default="scanner_sink", help="PulseAudio sink name used to isolate scrcpy audio")
parser.add_argument("--stream-url", type=str, default=None, help="Direct broadcast stream URL (broadcastify mode)")
parser.add_argument("--channels-file", type=str, default=None, help="Channel catalog JSON path for broadcastify auto-selection")
parser.add_argument("--selector-city", type=str, default="", help="Jurisdiction city for channel selector scoring")
parser.add_argument("--selector-county", type=str, default="", help="Jurisdiction county for channel selector scoring")
parser.add_argument("--selector-state", type=str, default="", help="Jurisdiction state for channel selector scoring")
parser.add_argument("--selector-lat", type=float, default=None, help="Latitude for distance-aware selector scoring")
parser.add_argument("--selector-lon", type=float, default=None, help="Longitude for distance-aware selector scoring")
parser.add_argument("--selector-desired-types", type=str, default="law,dispatch", help="Comma-separated desired channel type tokens")
parser.add_argument("--selector-top-k", type=int, default=8, help="Top deterministic candidates to consider before reranking")
parser.add_argument("--selector-use-ollama-rerank", action=argparse.BooleanOptionalAction, default=True, help="Enable optional Ollama rerank for selector")
parser.add_argument("--selector-ollama-model", type=str, default="llama3.1", help="Ollama model for selector reranking")
parser.add_argument("--selector-ollama-url", type=str, default="http://localhost:11434/api/generate", help="Ollama endpoint for selector reranking")
parser.add_argument("--selector-ollama-timeout", type=float, default=8.0, help="Ollama timeout in seconds for selector reranking")
parser.add_argument("--selector-ollama-weight", type=float, default=0.2, help="Blend weight [0..1] for Ollama rerank influence")
parser.add_argument("--log-file", type=str, default="/tmp/pipeline_runtime.log", help="Path to append pipeline logs")
parser.add_argument("--alert-debug", action=argparse.BooleanOptionalAction, default=True, help="Emit detailed [ALERT_DEBUG] lines for cue matching and Ollama decisions")
parser.add_argument("--ollama-timeout", type=float, default=8.0, help="Ollama request timeout in seconds")
parser.add_argument("--ollama-retries", type=int, default=1, help="Retry attempts after first Ollama failure")
parser.add_argument("--rms-threshold", type=float, default=0.01, help="Skip chunk when RMS is below this value")
parser.add_argument("--clip-threshold", type=float, default=0.15, help="Skip chunk when clipped sample ratio exceeds this value")
parser.add_argument("--soft-alert-fallback", action=argparse.BooleanOptionalAction, default=True, help="Emit SOFT_ALERT when dispatch cues meet threshold but LLM does not emit ALERT")
parser.add_argument("--integration-json", action=argparse.BooleanOptionalAction, default=True, help="Emit structured [EVENT_JSON] lines for Java/HTML integrations")
parser.add_argument("--rule-score-threshold", type=int, default=3, help="Weighted dispatch score threshold that marks a transcript as rule-expected alert")
parser.add_argument("--hard-rule-score-threshold", type=int, default=4, help="Weighted dispatch score threshold for hard rule-based alert promotion")
parser.add_argument("--loop-heartbeat", action=argparse.BooleanOptionalAction, default=False, help="Emit per-loop heartbeat diagnostics during capture/transcription runtime")
parser.add_argument("--loop-heartbeat-every", type=int, default=1, help="Emit loop heartbeat every N loop iterations when --loop-heartbeat is enabled")
args = parser.parse_args()
signal.signal(signal.SIGTERM, request_shutdown)
signal.signal(signal.SIGINT, request_shutdown)
for _sig in (signal.SIGTSTP, signal.SIGTTIN, signal.SIGTTOU):
    try:
        signal.signal(_sig, signal.SIG_IGN)
    except Exception:
        pass
log_handle = open(args.log_file, "a", buffering=1)
sys.stdout = TeeStream(sys.stdout, log_handle)
sys.stderr = TeeStream(sys.stderr, log_handle)
print(f"Logging pipeline output to: {args.log_file}")
ensure_optional_route_enabled(args.mode, args.enable_optional_audio_routes)

if args.list_devices:
    print(require_sounddevice().query_devices())
    raise SystemExit(0)
scrcpy_proc = None
source_node = args.source_node
input_device = None
input_device_name = None
input_selection_reason = None
stream_url = None
selected_channel = None
broadcast_candidates = []
broadcast_candidate_idx = 0
if args.mode == "scrcpy":
    ensure_binary("ffmpeg")
    ensure_binary("pactl")
    if args.start_scrcpy:
        scrcpy_proc = start_scrcpy(args.scrcpy_bin, args.serial)
        time.sleep(3)
    ensure_null_sink(args.scrcpy_sink)
    sink_input_id = wait_for_scrcpy_sink_input(timeout_seconds=20)
    if sink_input_id is None:
        raise RuntimeError("Could not detect scrcpy audio stream in PulseAudio sink-inputs.")
    move_sink_input_to_sink(sink_input_id, args.scrcpy_sink)
    enforce_scrcpy_sink_purity(args.scrcpy_sink, sink_input_id)
    source_node = f"{args.scrcpy_sink}.monitor"
    capture_fs = FS
    print(f"Routed scrcpy sink-input #{sink_input_id} to isolated sink '{args.scrcpy_sink}'.")
elif args.mode == "broadcastify":
    ensure_binary("ffmpeg")
    broadcast_candidates, selector_rerank_error = resolve_broadcast_streams(args)
    broadcast_candidate_idx = 0
    selected_channel = broadcast_candidates[broadcast_candidate_idx]
    stream_url = selected_channel.get("stream_url")
    capture_fs = FS
    print(
        f"Selected broadcast stream: id={selected_channel.get('id')} "
        f"name={selected_channel.get('name')} url={stream_url}"
    )
    if len(broadcast_candidates) > 1:
        print(f"Broadcast fallback enabled across {len(broadcast_candidates)} ranked channels.")
    if selector_rerank_error:
        print(f"Selector rerank fallback to deterministic score due to: {selector_rerank_error}")
elif args.mode == "direct":
    strict_source_match = False
    input_device, input_device_name, input_selection_reason = resolve_input_device(
        args.device,
        source_node,
        strict_source_match=strict_source_match
    )
    capture_fs = resolve_capture_samplerate(input_device, FS)
else:
    raise RuntimeError(
        f"Mode '{args.mode}' is optional and not wired in the scanner base runtime build."
    )
chunk_duration = args.duration
print("Loading Whisper model...")
whisper_engine = WhisperModel("medium", device="cpu", compute_type="int8")
print("Whisper forced to CPU (int8).")

print("\n--- Pipeline Fully Loaded and Active ---")
print(f"Listening in mode: {args.mode}")
if args.mode == "scrcpy":
    print(f"Input source node: {source_node}")
elif args.mode == "broadcastify":
    print(f"Broadcast stream URL: {stream_url}")
    if selected_channel:
        print(
            "Broadcast channel context: "
            f"id={selected_channel.get('id')} "
            f"name={selected_channel.get('name')} "
            f"jurisdiction={selected_channel.get('city','')}/{selected_channel.get('county','')}/{selected_channel.get('state','')}"
        )
else:
    print(f"Input device index: {input_device}")
    print(f"Input source node: {input_device_name}")
    print(f"Input selection strategy: {input_selection_reason}")
print(f"Capture sample rate: {capture_fs}")
emit_event_json(
    "pipeline_ready",
    enabled=args.integration_json,
    mode=args.mode,
    source_node=source_node if args.mode == "scrcpy" else (stream_url if args.mode == "broadcastify" else input_device_name),
    input_selection_reason=(
        "broadcast_stream_selector"
        if args.mode == "broadcastify"
        else ("scrcpy_sink_monitor" if args.mode == "scrcpy" else input_selection_reason)
    ),
    sample_rate=capture_fs,
    soft_alert_fallback=args.soft_alert_fallback,
    channel_id=selected_channel.get("id") if selected_channel else None,
    channel_name=selected_channel.get("name") if selected_channel else None,
)
run_stats = {
    "captured": 0,
    "skipped_silence": 0,
    "skipped_clipped": 0,
    "llm_alert": 0,
    "soft_alert_fallback": 0,
}
run_started_at = time.time()
loop_counter = 0
try:
    while not SHOULD_EXIT:
        try:
            loop_counter += 1
            if args.loop_heartbeat and loop_counter % max(1, args.loop_heartbeat_every) == 0:
                print(
                    "[LOOP_HEARTBEAT] "
                    f"loop={loop_counter} "
                    f"mode={args.mode} "
                    f"captured={run_stats['captured']} "
                    f"skipped_silence={run_stats['skipped_silence']} "
                    f"skipped_clipped={run_stats['skipped_clipped']} "
                    f"llm_alert={run_stats['llm_alert']} "
                    f"soft_alert_fallback={run_stats['soft_alert_fallback']}"
                )
                emit_event_json(
                    "loop_heartbeat",
                    enabled=args.integration_json,
                    loop=loop_counter,
                    mode=args.mode,
                    uptime_seconds=round(time.time() - run_started_at, 3),
                    captured=run_stats["captured"],
                    skipped_silence=run_stats["skipped_silence"],
                    skipped_clipped=run_stats["skipped_clipped"],
                    llm_alert=run_stats["llm_alert"],
                    soft_alert_fallback=run_stats["soft_alert_fallback"],
                )
            if args.mode == "scrcpy":
                enforce_scrcpy_sink_purity(args.scrcpy_sink, sink_input_id)
                capture_scrcpy_chunk_to_wav(source_node, chunk_duration, "buffer_chunk.wav", capture_fs)
                chunk_fs, chunk_data = wav.read("buffer_chunk.wav")
                if chunk_data.ndim == 2:
                    chunk_data = chunk_data[:, 0]
                mono = chunk_data.astype(np.float32)
                if chunk_data.dtype == np.int16:
                    mono = mono / 32768.0
                elif chunk_data.dtype == np.int32:
                    mono = mono / 2147483648.0
                capture_fs = chunk_fs
            elif args.mode == "broadcastify":
                try:
                    capture_stream_chunk_to_wav(stream_url, chunk_duration, "buffer_chunk.wav", capture_fs)
                except subprocess.CalledProcessError as stream_err:
                    if len(broadcast_candidates) > 1:
                        from_channel = selected_channel
                        broadcast_candidate_idx = (broadcast_candidate_idx + 1) % len(broadcast_candidates)
                        selected_channel = broadcast_candidates[broadcast_candidate_idx]
                        stream_url = selected_channel.get("stream_url")
                        print(
                            "[BroadcastifyFallback] "
                            f"capture_failed_exit={stream_err.returncode} "
                            f"from={from_channel.get('id')} -> to={selected_channel.get('id')} "
                            f"url={stream_url}"
                        )
                        emit_event_json(
                            "broadcast_channel_switch",
                            enabled=args.integration_json,
                            reason="capture_failed",
                            from_channel_id=from_channel.get("id"),
                            from_channel_name=from_channel.get("name"),
                            to_channel_id=selected_channel.get("id"),
                            to_channel_name=selected_channel.get("name"),
                            ffmpeg_exit_code=stream_err.returncode,
                        )
                        continue
                    raise
                chunk_fs, chunk_data = wav.read("buffer_chunk.wav")
                if chunk_data.ndim == 2:
                    chunk_data = chunk_data[:, 0]
                mono = chunk_data.astype(np.float32)
                if chunk_data.dtype == np.int16:
                    mono = mono / 32768.0
                elif chunk_data.dtype == np.int32:
                    mono = mono / 2147483648.0
                capture_fs = chunk_fs
            else:
                # 2. Record raw scanner snippet from direct audio capture
                sdev = require_sounddevice()
                audio_buffer = sdev.rec(
                    int(chunk_duration * capture_fs),
                    samplerate=capture_fs,
                    channels=1,
                    dtype='float32',
                    device=input_device
                )
                sdev.wait()
                wav.write("buffer_chunk.wav", capture_fs, audio_buffer)
                mono = audio_buffer[:, 0]
            rms = float(np.sqrt(np.mean(mono ** 2)))
            clip_ratio = float(np.mean(np.abs(mono) > 0.98))
            if rms < args.rms_threshold:
                run_stats["skipped_silence"] += 1
                if args.alert_debug:
                    print(f"[Skipped]: near-silence chunk (rms={rms:.6f}, threshold={args.rms_threshold})")
                else:
                    print("[Skipped]: near-silence chunk")
                emit_event_json(
                    "chunk_skipped_silence",
                    enabled=args.integration_json,
                    rms=rms,
                    threshold=args.rms_threshold,
                )
                continue
            if clip_ratio > args.clip_threshold:
                run_stats["skipped_clipped"] += 1
                print(f"[Skipped]: heavily clipped chunk (clip_ratio={clip_ratio:.4f}, threshold={args.clip_threshold})")
                emit_event_json(
                    "chunk_skipped_clipped",
                    enabled=args.integration_json,
                    clip_ratio=clip_ratio,
                    threshold=args.clip_threshold,
                )
                continue
            
            # 3. Process transcription on CPU
            segments, _ = whisper_engine.transcribe(
                "buffer_chunk.wav",
                beam_size=5,
                vad_filter=True,
                condition_on_previous_text=False,
                no_speech_threshold=0.6,
                log_prob_threshold=-1.0
            )
            raw_text = " ".join([seg.text for seg in segments]).strip()
            
            if raw_text:
                run_stats["captured"] += 1
                print(f"[Captured Chatter]: {raw_text}")
                classification = classify_transcript(raw_text)
                location_mentions = extract_location_mentions(raw_text)
                poi_mentions = extract_poi_mentions(raw_text)
                if location_mentions or poi_mentions:
                    print(f"[Location Notes]: locations={location_mentions} pois={poi_mentions}")
                print(
                    "[Classification]: "
                    f"types={classification['call_types']} "
                    f"priority={classification['priority']} "
                    f"codes={classification['codes']} "
                    f"confidence={classification['confidence']}"
                )
                emit_event_json(
                    "chunk_captured",
                    enabled=args.integration_json,
                    transcript=raw_text,
                    classification=classification,
                    location_mentions=location_mentions,
                    poi_mentions=poi_mentions,
                    rms=rms,
                    clip_ratio=clip_ratio,
                )
                cue_map, cue_count = extract_dispatch_cues(raw_text)
                dispatch_score = score_dispatch_cues(cue_map)
                strong_enforcement = has_strong_enforcement_signal(cue_map)
                has_location = bool(cue_map.get("location_markers"))
                
                # 4. Offload text to local Ollama Llama 3.1 framework
                llm_result = query_llm(raw_text, timeout_seconds=args.ollama_timeout, retries=args.ollama_retries)
                ai_response = llm_result["response"]
                llm_alert = "ALERT:" in ai_response
                rule_expected_alert = dispatch_score >= args.rule_score_threshold
                hard_rule_alert = (
                    dispatch_score >= args.hard_rule_score_threshold
                    and strong_enforcement
                    and (has_location or classification["call_types"] != ["unclassified"] or classification["codes"])
                )
                if llm_alert:
                    decision_reason = "llm_alert"
                elif hard_rule_alert:
                    decision_reason = "hard_rule_alert_promotion"
                elif llm_result["error"]:
                    decision_reason = "llm_error_timeout_or_transport"
                elif rule_expected_alert and not llm_alert:
                    decision_reason = "llm_ignore_despite_rule_expected_alert"
                else:
                    decision_reason = "insufficient_dispatch_cues_or_llm_ignore"
                fallback_soft_alert = (
                    args.soft_alert_fallback
                    and rule_expected_alert
                    and not hard_rule_alert
                    and (not llm_alert)
                    and (strong_enforcement or has_location)
                )
                emit_event_json(
                    "alert_decision",
                    enabled=args.integration_json,
                    transcript=raw_text,
                    cue_count=cue_count,
                    dispatch_score=dispatch_score,
                    cue_groups=cue_map,
                    llm_alert=llm_alert,
                    rule_expected_alert=rule_expected_alert,
                    hard_rule_alert=hard_rule_alert,
                    decision_reason=decision_reason,
                    fallback_soft_alert=fallback_soft_alert,
                    llm_status=llm_result["status_code"],
                    llm_attempts=llm_result["attempts"],
                    llm_error=llm_result["error"],
                    llm_response=ai_response,
                    classification=classification,
                    location_mentions=location_mentions,
                    poi_mentions=poi_mentions,
                    rms=rms,
                    clip_ratio=clip_ratio,
                )
                if args.alert_debug:
                    llm_raw_excerpt = log_safe(llm_result["raw"]) if llm_result["raw"] else "none"
                    print(
                        "[ALERT_DEBUG] "
                        f"ts={datetime.now(UTC).isoformat()} "
                        f"cue_count={cue_count} "
                        f"dispatch_score={dispatch_score} "
                        f"cue_groups={cue_map} "
                        f"rule_expected_alert={rule_expected_alert} "
                        f"hard_rule_alert={hard_rule_alert} "
                        f"llm_alert={llm_alert} "
                        f"decision_reason={decision_reason} "
                        f"classification_types={classification['call_types']} "
                        f"classification_priority={classification['priority']} "
                        f"llm_status={llm_result['status_code']} "
                        f"llm_attempts={llm_result['attempts']} "
                        f"llm_error={log_safe(str(llm_result['error'])) if llm_result['error'] else 'none'} "
                        f"fallback_soft_alert={fallback_soft_alert} "
                        f"llm_raw_excerpt=\"{llm_raw_excerpt}\" "
                        f"llm_response=\"{log_safe(ai_response)}\" "
                        f"transcript=\"{log_safe(raw_text)}\""
                    )
                
                if llm_alert:
                    run_stats["llm_alert"] += 1
                    print(f"🚨 {ai_response}")
                    print(
                        "[ALERT_LOG] "
                        f"ts={datetime.now(UTC).isoformat()} "
                        f"kind=llm_alert "
                        f"alert=\"{ai_response}\" "
                        f"types={classification['call_types']} "
                        f"priority={classification['priority']} "
                        f"codes={classification['codes']} "
                        f"locations={location_mentions} "
                        f"pois={poi_mentions} "
                        f"transcript=\"{raw_text}\""
                    )
                    # 5. Linux Native Voice Notification Engine
                    clean_message = ai_response.replace("ALERT:", "").strip()
                    speak_alert(clean_message)
                    emit_event_json(
                        "alert_triggered",
                        enabled=args.integration_json,
                        kind="llm_alert",
                        alert=ai_response,
                        transcript=raw_text,
                        classification=classification,
                        location_mentions=location_mentions,
                        poi_mentions=poi_mentions,
                        rms=rms,
                        clip_ratio=clip_ratio,
                    )
                elif hard_rule_alert:
                    run_stats["llm_alert"] += 1
                    hard_alert_message = (
                        f"ALERT: probable enforcement activity (rule score={dispatch_score}, "
                        f"strong_enforcement={strong_enforcement}, location={has_location})."
                    )
                    print(f"🚨 {hard_alert_message}")
                    print(
                        "[ALERT_LOG] "
                        f"ts={datetime.now(UTC).isoformat()} "
                        f"kind=rule_alert_high_confidence "
                        f"alert=\"{hard_alert_message}\" "
                        f"types={classification['call_types']} "
                        f"priority={classification['priority']} "
                        f"codes={classification['codes']} "
                        f"locations={location_mentions} "
                        f"pois={poi_mentions} "
                        f"transcript=\"{raw_text}\""
                    )
                    speak_alert("Potential traffic enforcement ahead. Slow down and use caution.")
                    emit_event_json(
                        "alert_triggered",
                        enabled=args.integration_json,
                        kind="rule_alert_high_confidence",
                        alert=hard_alert_message,
                        transcript=raw_text,
                        classification=classification,
                        location_mentions=location_mentions,
                        poi_mentions=poi_mentions,
                        cue_count=cue_count,
                        dispatch_score=dispatch_score,
                        rms=rms,
                        clip_ratio=clip_ratio,
                    )
                elif fallback_soft_alert:
                    soft_alert_message = (
                        f"SOFT_ALERT: dispatch-style cues met threshold (cue_count={cue_count}, score={dispatch_score}) "
                        f"but LLM returned IGNORE."
                    )
                    run_stats["soft_alert_fallback"] += 1
                    print(f"⚠️ {soft_alert_message}")
                    print(
                        "[FALLBACK_LOG] "
                        f"ts={datetime.now(UTC).isoformat()} "
                        f"reason=llm_ignore_despite_rule_expected_alert "
                        f"cue_count={cue_count} "
                        f"dispatch_score={dispatch_score} "
                        f"types={classification['call_types']} "
                        f"priority={classification['priority']} "
                        f"codes={classification['codes']} "
                        f"locations={location_mentions} "
                        f"transcript=\"{raw_text}\""
                    )
                    print(
                        "[ALERT_LOG] "
                        f"ts={datetime.now(UTC).isoformat()} "
                        f"kind=soft_alert_fallback "
                        f"alert=\"{soft_alert_message}\" "
                        f"types={classification['call_types']} "
                        f"priority={classification['priority']} "
                        f"codes={classification['codes']} "
                        f"locations={location_mentions} "
                        f"pois={poi_mentions} "
                        f"transcript=\"{raw_text}\""
                    )
                    speak_alert("Possible traffic enforcement activity detected. Use caution.")
                    emit_event_json(
                        "alert_triggered",
                        enabled=args.integration_json,
                        kind="soft_alert_fallback",
                        alert=soft_alert_message,
                        transcript=raw_text,
                        classification=classification,
                        location_mentions=location_mentions,
                        poi_mentions=poi_mentions,
                        cue_count=cue_count,
                        rms=rms,
                        clip_ratio=clip_ratio,
                    )
        except Exception as e:
            if SHOULD_EXIT:
                break
            print(f"[LoopError] recoverable error: {repr(e)}")
            emit_event_json(
                "loop_error",
                enabled=args.integration_json,
                error=repr(e),
            )
            time.sleep(1)
finally:
    print(
        "[RUN_SUMMARY] "
        f"captured={run_stats['captured']} "
        f"skipped_silence={run_stats['skipped_silence']} "
        f"skipped_clipped={run_stats['skipped_clipped']} "
        f"llm_alert={run_stats['llm_alert']} "
        f"soft_alert_fallback={run_stats['soft_alert_fallback']}"
    )
    emit_event_json(
        "run_summary",
        enabled=args.integration_json,
        **run_stats,
    )
    if scrcpy_proc is not None and scrcpy_proc.poll() is None:
        scrcpy_proc.terminate()
