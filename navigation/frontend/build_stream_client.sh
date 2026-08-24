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
BUILD_DIR="$DIST_DIR/build-stream-client"
APP_NAME="scanner-stream-client"

PYTHON_BIN="${PYTHON_BIN:-/home/gibi/Desktop/cop_pipeline/bin/python3}"
if [[ ! -x "$PYTHON_BIN" ]]; then
  PYTHON_BIN="python3"
fi

"$PYTHON_BIN" -m pip install --quiet pyinstaller

rm -rf "$BUILD_DIR" "$DIST_DIR/$APP_NAME" "$SCRIPT_DIR/${APP_NAME}.spec"
mkdir -p "$DIST_DIR" "$BUILD_DIR"

"$PYTHON_BIN" -m PyInstaller \
  --noconfirm \
  --clean \
  --distpath "$DIST_DIR" \
  --workpath "$BUILD_DIR" \
  --name "$APP_NAME" \
  --onedir \
  "$SCRIPT_DIR/stream_client.py"

echo "Built stream client executable: $DIST_DIR/$APP_NAME/$APP_NAME"
