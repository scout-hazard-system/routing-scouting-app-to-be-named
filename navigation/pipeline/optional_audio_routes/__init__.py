OPTIONAL_AUDIO_ROUTE_MODES = {"direct", "scrcpy", "audiorelay", "voicemeeter"}


def optional_route_package_name(mode: str) -> str:
    mapping = {
        "scrcpy": "optional_audio_routes.scrcpy",
        "audiorelay": "optional_audio_routes.audiorelay",
        "voicemeeter": "optional_audio_routes.voicemeeter",
        "direct": "optional_audio_routes.direct",
    }
    return mapping.get(mode, "optional_audio_routes")


def ensure_optional_route_enabled(mode: str, enabled: bool) -> None:
    if mode not in OPTIONAL_AUDIO_ROUTE_MODES:
        return
    if enabled:
        return
    package_name = optional_route_package_name(mode)
    raise RuntimeError(
        f"Audio route '{mode}' is optional and disabled in the scanner base build. "
        f"Enable it with --enable-optional-audio-routes after installing/maintaining "
        f"the optional route package path '{package_name}'."
    )
