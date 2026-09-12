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

# Shared helpers for the Debian trixie Scout map server setup suite.
# Source, do not execute.

if [[ -n "${MAP_SERVER_TRIXIE_LIB_SOURCED:-}" ]]; then
  return 0 2>/dev/null || exit 0
fi
export MAP_SERVER_TRIXIE_LIB_SOURCED=1

if [[ -t 1 ]]; then
  C_RESET="\033[0m"
  C_INFO="\033[36m"
  C_OK="\033[32m"
  C_WARN="\033[33m"
  C_ERR="\033[31m"
  C_BOLD="\033[1m"
else
  C_RESET=""
  C_INFO=""
  C_OK=""
  C_WARN=""
  C_ERR=""
  C_BOLD=""
fi

info() { printf "${C_INFO}[*]${C_RESET} %s\n" "$*"; }
ok()   { printf "${C_OK}[+]${C_RESET} %s\n" "$*"; }
warn() { printf "${C_WARN}[!]${C_RESET} %s\n" "$*" >&2; }
fail() { printf "${C_ERR}[x]${C_RESET} %s\n" "$*" >&2; exit 1; }

SUITE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

require_cmd() {
  for bin in "$@"; do
    if ! command -v "$bin" >/dev/null 2>&1; then
      fail "required command not found: $bin (run ./bootstrap_trixie.sh first?)"
    fi
  done
}

resolve_repo_root() {
  local dir="$SUITE_DIR"
  while [[ "$dir" != "/" ]]; do
    if [[ -f "$dir/stack/commands/run_vehicle_stack.sh" && -f "$dir/navigation/backend/BackendServer.java" ]]; then
      echo "$dir"
      return 0
    fi
    dir="$(dirname "$dir")"
  done
  return 1
}

detect_java() {
  local javac_bin=""
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
    javac_bin="$JAVA_HOME/bin/javac"
  elif command -v javac >/dev/null 2>&1; then
    javac_bin="$(command -v javac)"
  fi
  if [[ -z "$javac_bin" ]]; then
    warn "javac not found (and JAVA_HOME/bin/javac does not exist)."
    return 1
  fi
  local ver major
  ver="$("$javac_bin" -version 2>&1 | awk '{print $2}')"
  case "$ver" in
    1.*)
      major="${ver#1.}"
      major="${major%%.*}"
      ;;
    *)
      major="${ver%%.*}"
      ;;
  esac
  if [[ ! "$major" =~ ^[0-9]+$ ]] || ((major < 21)); then
    warn "javac $ver found, but the Java backend requires JDK 21+ (major >= 21)."
    return 1
  fi
  JAVAC_BIN="$javac_bin"
  JAVA_VER="$ver"
  return 0
}

tailscale_ip4() {
  if command -v tailscale >/dev/null 2>&1; then
    tailscale ip -4 2>/dev/null | head -n 1 || true
  else
    echo ""
  fi
}

# Parses "user@host" or "user@host:port" into SSH_USER, SSH_HOST, SSH_PORT.
split_ssh_target() {
  local target="$1"
  SSH_USER=""
  SSH_HOST=""
  SSH_PORT="22"
  if [[ "$target" =~ ^(.+)@([^@:]+):([0-9]+)$ ]]; then
    SSH_USER="${BASH_REMATCH[1]}"
    SSH_HOST="${BASH_REMATCH[2]}"
    SSH_PORT="${BASH_REMATCH[3]}"
  elif [[ "$target" =~ ^(.+)@([^@:]+)$ ]]; then
    SSH_USER="${BASH_REMATCH[1]}"
    SSH_HOST="${BASH_REMATCH[2]}"
  else
    fail "invalid SSH target (expected user@host[:port]): $target"
  fi
}