#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR="$SCRIPT_DIR/dist"
BUILD_DIR="$DIST_DIR/build"
JAR_PATH="$DIST_DIR/backend-lite.jar"
MANIFEST_PATH="$DIST_DIR/manifest.mf"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
mkdir -p "$DIST_DIR"

javac -d "$BUILD_DIR" "$SCRIPT_DIR"/*.java
printf 'Main-Class: BackendServer\n' > "$MANIFEST_PATH"
jar cfm "$JAR_PATH" "$MANIFEST_PATH" -C "$BUILD_DIR" .

echo "Built executable JAR: $JAR_PATH"
