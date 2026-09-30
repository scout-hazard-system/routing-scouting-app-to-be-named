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

# Debian 13 (trixie) system bootstrap for a Scout map server host.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

WITH_TAILSCALE=0
for arg in "$@"; do
  case "$arg" in
    --with-tailscale) WITH_TAILSCALE=1 ;;
    *)
      fail "unknown argument: $arg (supported: --with-tailscale)"
      ;;
  esac
done

os_release() {
  if [[ -f /etc/os-release ]]; then
    # shellcheck disable=SC1091
    . /etc/os-release
  fi
  if [[ "${ID:-}" == "debian" ]]; then
    if [[ "${VERSION_CODENAME:-}" != "trixie" ]]; then
      warn "Detected Debian '${VERSION_CODENAME:-unknown}' - this suite targets trixie. Proceeding anyway (expect JDK 21 availability)."
    fi
  else
    warn "This system does not look like Debian (ID=${ID:-unknown}). apt steps may not apply."
  fi
}

SUDO=""
if ((EUID != 0)); then
  require_cmd sudo
  SUDO="sudo"
fi

echo ""
info "Scout map server host bootstrap (Debian trixie)"
echo ""

os_release

info "Updating apt package index..."
${SUDO} apt-get update

APT_PKGS=(git curl rsync openssh-server jq unzip ca-certificates python3 python3-venv openjdk-21-jdk-headless)
if [[ -n "$SUDO" ]]; then
  info "Installing: ${APT_PKGS[*]} (sudo may prompt)"
else
  info "Installing: ${APT_PKGS[*]}"
fi
${SUDO} apt-get install -y "${APT_PKGS[@]}"
hash -r

info "Checking Java toolchain..."
if ! detect_java; then
  fail "OpenJDK 21 should be installable from trixie apt (openjdk-21-jdk-headless installed above) but javac is still not usable."
fi
ok "Java toolchain: ${JAVAC_BIN} (javac ${JAVA_VER})"

info "Ensuring SSH server is installed, enabled, and running..."
${SUDO} systemctl enable --now ssh
if ss -tln | grep -q ':22 '; then
  ok "sshd listening on TCP 22"
else
  warn "Nothing listening on TCP 22 yet; check: systemctl --now enable ssh && systemctl status ssh (note: trixie may use ssh.socket)."
fi

if ((WITH_TAILSCALE)); then
  if command -v tailscale >/dev/null 2>&1; then
    ok "tailscale already installed"
  else
    info "Installing Tailscale via official installer (auto-detects Debian trixie apt repo)..."
    curl -fsSL https://tailscale.com/install.sh | ${SUDO} sh
  fi
  info "Attempting tailscale up (may prompt for a browser login) with SSH enabled..."
  ${SUDO} tailscale up --ssh=true || warn "tailscale up did not complete; run it manually."
  local_ip="$(tailscale_ip4)"
  if [[ -n "$local_ip" ]]; then
    ok "Tailscale IPv4: $local_ip"
    info "Trust this host on the tailnet so shard sources can pull back."
  fi
fi

echo ""
ok "Bootstrap complete. Next:"
ok "  1. ./sync_shards.sh <user>@<source-host>   (rsync MVT cache + AZ text roots)"
ok "  2. ./configure_map_server.sh --advertise auto"
ok "  3. cd .. && ./master start && ./verify_map_server.sh"