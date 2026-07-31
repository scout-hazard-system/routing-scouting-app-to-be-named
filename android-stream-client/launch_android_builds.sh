#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_NAV="$ROOT_DIR/app/build/outputs/apk/navigation/debug/app-navigation-debug.apk"
APK_CLIENT="$ROOT_DIR/app/build/outputs/apk/dev/debug/app-dev-debug.apk"
PKG_NAV="dev.warp.stream"
PKG_CLIENT="dev.warp.stream.client"
ACTIVITY=".MainActivity"

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

build_apks() {
  (cd "$ROOT_DIR" && ./gradlew assembleNavigationDebug assembleDevDebug)
}

install_apks() {
  ensure_device
  if [[ ! -f "$APK_NAV" || ! -f "$APK_CLIENT" ]]; then
    echo "Expected APKs are missing. Run '$0 build' first." >&2
    exit 1
  fi
  adb_cmd install -r "$APK_NAV"
  adb_cmd install -r "$APK_CLIENT"
}

launch_nav() {
  ensure_device
  adb_cmd shell am start -W -n "${PKG_NAV}/${ACTIVITY}"
}

launch_client() {
  ensure_device
  adb_cmd shell am start -W -n "${PKG_CLIENT}/${ACTIVITY}"
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
