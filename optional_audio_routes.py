"""Optional audio route enablement for the scanner pipeline.

This module gates access to non-default audio capture routes (e.g. direct
device input, scrcpy PulseAudio sink).  When the user has not explicitly
opted in via ``--enable-optional-audio-routes`` the function below simply
returns, leaving the pipeline in its safe default mode.
"""

import sys


def ensure_optional_route_enabled(mode: str, enabled: bool) -> None:
    """Validate that the requested audio mode is allowed.

    Parameters
    ----------
    mode:
        Pipeline mode string (``broadcastify``, ``direct``, ``scrcpy``).
    enabled:
        Value of the ``--enable-optional-audio-routes`` flag.
    """
    if mode in ("direct", "scrcpy") and not enabled:
        print(
            f"[optional_audio_routes] Mode '{mode}' requires "
            f"--enable-optional-audio-routes to be set explicitly.",
            file=sys.stderr,
        )
        raise SystemExit(1)
