#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR="$SCRIPT_DIR/dist"
BUILD_DIR="$DIST_DIR/build"
APP_NAME="scanner-frontend-lite"

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
  --add-data "$SCRIPT_DIR/index.html:." \
  --add-data "$SCRIPT_DIR/app.js:." \
  --add-data "$SCRIPT_DIR/styles.css:." \
  "$SCRIPT_DIR/dev_server.py"

echo "Built frontend executable: $DIST_DIR/$APP_NAME/$APP_NAME"
