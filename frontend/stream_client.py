#!/usr/bin/env python3
"""Minimal SSE stream client for the scanner backend.

Connects to the Java backend ``/api/pipeline/stream`` endpoint and
prints incoming events to stdout.  Used by the ``stream`` command in
``start_termius_stack.sh``.
"""

import argparse
import sys
import urllib.request
import json


def stream_events(base_url: str) -> None:
    url = f"{base_url.rstrip('/')}/api/pipeline/stream"
    print(f"Connecting to {url} ...", file=sys.stderr)
    try:
        req = urllib.request.Request(url, headers={"Accept": "text/event-stream"})
        with urllib.request.urlopen(req, timeout=60) as resp:
            event_type = None
            data_lines: list[str] = []
            for raw_line in resp:
                line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
                if line.startswith("event:"):
                    event_type = line.split(":", 1)[1].strip()
                elif line.startswith("data:"):
                    data_lines.append(line.split(":", 1)[1].strip())
                elif line == "":
                    if data_lines:
                        payload = "\n".join(data_lines)
                        try:
                            obj = json.loads(payload)
                            print(json.dumps(obj, ensure_ascii=False))
                        except json.JSONDecodeError:
                            print(payload)
                        data_lines.clear()
                        event_type = None
    except KeyboardInterrupt:
        print("\nStream interrupted.", file=sys.stderr)
    except Exception as exc:
        print(f"Stream error: {exc}", file=sys.stderr)
        sys.exit(1)


def main() -> None:
    parser = argparse.ArgumentParser(description="SSE stream client for scanner backend")
    parser.add_argument("--base-url", default="http://127.0.0.1:18080", help="Backend base URL")
    args = parser.parse_args()
    stream_events(args.base_url)


if __name__ == "__main__":
    main()
