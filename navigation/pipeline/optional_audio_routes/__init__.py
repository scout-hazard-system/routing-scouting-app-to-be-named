# Copyright 2026 Scout Project Contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

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
