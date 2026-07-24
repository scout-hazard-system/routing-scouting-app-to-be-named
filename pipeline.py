import argparse
import subprocess
import shutil
import time
import sounddevice as sd
import scipy.io.wavfile as wav
import requests
import numpy as np
from faster_whisper import WhisperModel


FS = 16000          # Audio frequency standard for Whisper
DURATION = 12       # Grabs audio in 12-second intervals to minimize processing lag
OLLAMA_URL = "http://localhost:11434/api/generate"

# The system prompt context maps out local 10-codes into plain speech warnings
SYSTEM_PROMPT = """
You are an in-car speed trap and radar alert assistant for a driver on a cross country trip.
Analyze the following police scanner transcript. Look exclusively for traffic or highway enforcement events.
Keywords to watch out for: 'radar', 'laser', 'clocked', 'speed trap', 'pacing', '10-38' (traffic stop), 'staged near marker'.
If an active speed trap or traffic stop is mentioned, reply EXACTLY with a 1-sentence warning starting with 'ALERT:'.
Example: 'ALERT: State trooper clocking speed near mile marker 85.'
If it is generic chatter, static, or irrelevant noise, reply strictly with 'IGNORE'.
"""

def query_llm(transcript_text):
    payload = {
        "model": "llama3.1", 
        "prompt": f"{SYSTEM_PROMPT}\n\nTranscript: {transcript_text}",
        "stream": False
    }
    try:
        response = requests.post(OLLAMA_URL, json=payload, timeout=3)
        return response.json().get("response", "IGNORE")
    except Exception:
        return "IGNORE"
def pick_default_input_device():
    devices = sd.query_devices()
    for idx, dev in enumerate(devices):
        if "pipewire" in dev["name"].lower() and dev["max_input_channels"] > 0:
            return idx
    return sd.default.device[0]
def resolve_input_device(device_index=None, source_name=None, strict_source_match=False):
    devices = sd.query_devices()
    if device_index is not None:
        dev = devices[device_index]
        if dev["max_input_channels"] <= 0:
            raise RuntimeError(f"Selected device index {device_index} is not an input device: {dev['name']}")
        return device_index, dev["name"]
    if source_name:
        token = source_name.lower()
        for idx, dev in enumerate(devices):
            if dev["max_input_channels"] > 0 and token in dev["name"].lower():
                return idx, dev["name"]
        if strict_source_match:
            raise RuntimeError(f"No input device matched --source-node '{source_name}'. Use --list-devices to inspect names.")
        print(f"No input device matched --source-node '{source_name}', falling back to default input selection.")
    idx = pick_default_input_device()
    return idx, devices[idx]["name"]
def resolve_capture_samplerate(device_index, requested_rate):
    try:
        sd.check_input_settings(device=device_index, samplerate=requested_rate, channels=1, dtype="float32")
        return requested_rate
    except Exception:
        device_info = sd.query_devices(device_index)
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
    out = run_command(["pactl", "list", "short", "sinks"])
    return any(line.split("\t")[1] == sink_name for line in out.splitlines() if line.strip())
def ensure_null_sink(sink_name):
    if sink_exists(sink_name):
        return
    run_command([
        "pactl", "load-module", "module-null-sink",
        f"sink_name={sink_name}",
        "sink_properties=device.description=ScannerSink"
    ])
def find_scrcpy_sink_input_id():
    out = run_command(["pactl", "list", "sink-inputs"])
    chunks = out.split("Sink Input #")
    for chunk in chunks[1:]:
        first_line = chunk.splitlines()[0].strip()
        try:
            sink_input_id = int(first_line)
        except ValueError:
            continue
        if "scrcpy" in chunk.lower():
            return sink_input_id
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
    cmd = [scrcpy_bin, "--no-video"]
    if serial:
        cmd.extend(["-s", serial])
    print(f"Starting scrcpy: {' '.join(cmd)}")
    return subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

parser = argparse.ArgumentParser(description="Live police scanner -> Whisper -> LLM alert pipeline")
parser.add_argument("--mode", choices=["direct", "scrcpy"], default="scrcpy", help="Audio capture mode: direct input or scrcpy phone stream")
parser.add_argument("--device", type=int, default=None, help="Input device index (use --list-devices to inspect)")
parser.add_argument("--source-node", type=str, default=None, help="Input source name substring (used when --device is not set)")
parser.add_argument("--duration", type=float, default=DURATION, help="Capture duration per chunk in seconds")
parser.add_argument("--list-devices", action="store_true", help="List audio devices and exit")
parser.add_argument("--start-scrcpy", action="store_true", help="Auto-launch scrcpy before capturing (scrcpy mode only)")
parser.add_argument("--serial", type=str, default=None, help="ADB device serial (optional)")
parser.add_argument("--scrcpy-bin", type=str, default="scrcpy", help="scrcpy executable path/name for scrcpy mode")
parser.add_argument("--scrcpy-sink", type=str, default="scanner_sink", help="PulseAudio sink name used to isolate scrcpy audio")
args = parser.parse_args()

if args.list_devices:
    print(sd.query_devices())
    raise SystemExit(0)
scrcpy_proc = None
source_node = args.source_node
input_device = None
input_device_name = None
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
    source_node = f"{args.scrcpy_sink}.monitor"
    capture_fs = FS
    print(f"Routed scrcpy sink-input #{sink_input_id} to isolated sink '{args.scrcpy_sink}'.")
else:
    if source_node is None:
        source_node = "ALC274 Analog"
    strict_source_match = False
    input_device, input_device_name = resolve_input_device(args.device, source_node, strict_source_match=strict_source_match)
    capture_fs = resolve_capture_samplerate(input_device, FS)
chunk_duration = args.duration
print("Loading Whisper model...")
whisper_engine = WhisperModel("medium", device="cpu", compute_type="int8")
print("Whisper forced to CPU (int8).")

print("\n--- Pipeline Fully Loaded and Active ---")
print(f"Listening in mode: {args.mode}")
if args.mode == "scrcpy":
    print(f"Input source node: {source_node}")
else:
    print(f"Input device index: {input_device}")
    print(f"Input source node: {input_device_name}")
print(f"Capture sample rate: {capture_fs}")
try:
    while True:
        if args.mode == "scrcpy":
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
        else:
            # 2. Record raw scanner snippet from direct audio capture
            audio_buffer = sd.rec(
                int(chunk_duration * capture_fs),
                samplerate=capture_fs,
                channels=1,
                dtype='float32',
                device=input_device
            )
            sd.wait()
            wav.write("buffer_chunk.wav", capture_fs, audio_buffer)
            mono = audio_buffer[:, 0]
        rms = float(np.sqrt(np.mean(mono ** 2)))
        clip_ratio = float(np.mean(np.abs(mono) > 0.98))
        if rms < 0.01:
            print("[Skipped]: near-silence chunk")
            continue
        if clip_ratio > 0.15:
            print(f"[Skipped]: heavily clipped chunk (clip_ratio={clip_ratio:.2f})")
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
            print(f"[Captured Chatter]: {raw_text}")
            
            # 4. Offload text to local Ollama Llama 3.1 framework
            ai_response = query_llm(raw_text)
            
            if "ALERT:" in ai_response:
                print(f"🚨 {ai_response}")
                # 5. Linux Native Voice Notification Engine
                clean_message = ai_response.replace("ALERT:", "").strip()
                speak_alert(clean_message)
except KeyboardInterrupt:
    print("\nStopping pipeline.")
finally:
    if scrcpy_proc is not None and scrcpy_proc.poll() is None:
        scrcpy_proc.terminate()
