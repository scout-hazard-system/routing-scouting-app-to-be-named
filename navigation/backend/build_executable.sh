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
