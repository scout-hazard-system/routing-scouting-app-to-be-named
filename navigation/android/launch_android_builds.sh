#!/usr/bin/env bash
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

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_NAV="$ROOT_DIR/app/build/outputs/apk/navigation/debug/app-navigation-debug.apk"
APK_CLIENT="$ROOT_DIR/app/build/outputs/apk/dev/debug/app-dev-debug.apk"
PKG_NAV="dev.warp.stream"
PKG_CLIENT="dev.warp.stream.client"
# Activity class lives in the base package even when applicationId has a suffix.
ACTIVITY_CLASS="dev.warp.stream.MainActivity"

ADB_ARGS=()
if [[ "${1:-}" == "-s" ]]; then
  if [[ -z "${2:-}" ]]; then
    echo "Missing device serial after -s" >&2
    exit 1
  fi
  ADB_ARGS=(-s "$2")
  shift 2
fi

CMD="${1:-all}"

usage() {
  cat <<EOF
Usage: $0 [-s DEVICE_SERIAL] {build|install|launch-nav|launch-client|launch-both|all}

Commands:
  build         Build navigation + client debug APKs
  install       Install both built APKs
  launch-nav    Launch Scanner Stream
  launch-client Launch Scanner Stream Client
  launch-both   Launch both apps sequentially
  all           Build + install + launch both apps
EOF
}

adb_cmd() {
  adb "${ADB_ARGS[@]}" "$@"
}

ensure_device() {
  if ! adb_cmd get-state >/dev/null 2>&1; then
    echo "No authorized Android device detected for adb target." >&2
    exit 1
  fi
}

TAILSCALE_BASE_URL="${TAILSCALE_BASE_URL:-http://100.78.191.61:18080}"

build_apks() {
  (cd "$ROOT_DIR" && ./gradlew assembleNavigationDebug assembleDevDebug)
}

# Point installed apps at the Tailscale backend before launch.
configure_tailscale_backend() {
  ensure_device
  local tmp_prefs
  tmp_prefs="$(mktemp)"
  cat >"$tmp_prefs" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="base_url">${TAILSCALE_BASE_URL}</string>
    <boolean name="prefer_tailscale" value="true" />
    <boolean name="active_tracking_enabled" value="true" />
    <boolean name="tracking_consent_resolved" value="true" />
    <boolean name="analytics_enabled" value="true" />
</map>
EOF
  adb_cmd push "$tmp_prefs" /data/local/tmp/scanner_stream_prefs.xml >/dev/null
  rm -f "$tmp_prefs"
  for pkg in "$PKG_NAV" "$PKG_CLIENT"; do
    adb_cmd shell "run-as $pkg sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/scanner_stream_prefs.xml shared_prefs/scanner_stream_prefs.xml'" >/dev/null 2>&1 || true
    adb_cmd shell am force-stop "$pkg" >/dev/null 2>&1 || true
  done
  adb_cmd shell rm -f /data/local/tmp/scanner_stream_prefs.xml >/dev/null 2>&1 || true
  echo "Backend preference: Tailscale ${TAILSCALE_BASE_URL}"
}

install_apks() {
  ensure_device
  if [[ ! -f "$APK_NAV" || ! -f "$APK_CLIENT" ]]; then
    echo "Expected APKs are missing. Run '$0 build' first." >&2
    exit 1
  fi
  adb_cmd install -r "$APK_NAV"
  adb_cmd install -r "$APK_CLIENT"
  configure_tailscale_backend
}

launch_nav() {
  ensure_device
  configure_tailscale_backend
  adb_cmd shell am start -W -n "${PKG_NAV}/${ACTIVITY_CLASS}"
}

launch_client() {
  ensure_device
  configure_tailscale_backend
  adb_cmd shell am start -W -n "${PKG_CLIENT}/${ACTIVITY_CLASS}"
}

case "$CMD" in
  build) build_apks ;;
  install) install_apks ;;
  launch-nav) launch_nav ;;
  launch-client) launch_client ;;
  launch-both)
    launch_nav
    launch_client
    ;;
  all)
    build_apks
    install_apks
    launch_nav
    launch_client
    ;;
  help|-h|--help)
    usage
    ;;
  *)
    usage
    exit 1
    ;;
esac
