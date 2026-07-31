#!/usr/bin/env python3
import argparse
import json
import sys
from datetime import datetime
from urllib.error import URLError, HTTPError
from urllib.request import Request, urlopen


def now() -> str:
    return datetime.now().strftime("%H:%M:%S")


def http_get_json(url: str, timeout: float) -> dict:
    req = Request(url, headers={"Accept": "application/json"})
    with urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8", errors="replace"))


def read_snapshot(base_url: str, timeout: float) -> None:
    url = f"{base_url}/api/pipeline/snapshot"
    data = http_get_json(url, timeout=timeout)
    metrics = data.get("metrics") or {}
    event_counts = data.get("event_type_counts") or {}
    print(f"[{now()}] snapshot")
    print(f"  captured={metrics.get('captured', 0)} skipped_silence={metrics.get('skipped_silence', 0)} skipped_clipped={metrics.get('skipped_clipped', 0)}")
    print(f"  llm_alert={metrics.get('llm_alert', 0)} soft_alert_fallback={metrics.get('soft_alert_fallback', 0)}")
    print(f"  event_types={json.dumps(event_counts, ensure_ascii=False)}")


def format_event_line(ev: dict) -> str:
    event_type = ev.get("event_type", "unknown")
    kind = ev.get("kind")
    transcript = (ev.get("transcript") or "").strip()
    alert = (ev.get("alert") or "").strip()
    pieces = [f"type={event_type}"]
    if kind:
        pieces.append(f"kind={kind}")
    if alert:
        pieces.append(f"alert={alert}")
    elif transcript:
        pieces.append(f"text={transcript}")
    if ev.get("channel_name"):
        pieces.append(f"channel={ev.get('channel_name')}")
    return " | ".join(pieces)


def stream_events(base_url: str, timeout: float, max_events: int | None) -> None:
    url = f"{base_url}/api/pipeline/stream"
    req = Request(url, headers={"Accept": "text/event-stream"})
    count = 0
    with urlopen(req, timeout=timeout) as resp:
        for raw in resp:
            line = raw.decode("utf-8", errors="replace").strip()
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            if not payload:
                continue
            try:
                ev = json.loads(payload)
            except json.JSONDecodeError:
                continue
            print(f"[{now()}] {format_event_line(ev)}")
            sys.stdout.flush()
            count += 1
            if max_events is not None and count >= max_events:
                return


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Standalone scanner stream client (no browser)."
    )
    parser.add_argument(
        "--base-url",
        default="http://127.0.0.1:18080",
        help="Backend base URL.",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=15.0,
        help="Network timeout in seconds.",
    )
    parser.add_argument(
        "--max-events",
        type=int,
        default=None,
        help="Optional event count cap for non-interactive runs.",
    )
    args = parser.parse_args()

    base_url = args.base_url.rstrip("/")
    print(f"[{now()}] scanner stream client connected to {base_url}")

    try:
        read_snapshot(base_url, timeout=args.timeout)
        print(f"[{now()}] streaming events...")
        stream_events(base_url, timeout=args.timeout, max_events=args.max_events)
        return 0
    except HTTPError as exc:
        print(f"[{now()}] HTTP error: {exc.code} {exc.reason}", file=sys.stderr)
        return 2
    except URLError as exc:
        print(f"[{now()}] connection error: {exc.reason}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print(f"\n[{now()}] stopped")
        return 0
    except Exception as exc:
        print(f"[{now()}] unexpected error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
