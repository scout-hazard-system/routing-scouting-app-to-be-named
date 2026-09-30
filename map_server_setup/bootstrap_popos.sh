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

# Pop!_OS / Ubuntu system bootstrap for a Scout map server host.
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
  case "${ID:-}:${ID_LIKE:-}" in
    *ubuntu*|*pop*|*debian*) return 0 ;;
    *)
      warn "This system does not look like Pop!_OS/Ubuntu/Debian (ID=${ID:-unknown}). apt-based steps may not apply."
      return 0
      ;;
  esac
}

SUDO=""
if ((EUID != 0)); then
  require_cmd sudo
  SUDO="sudo"
fi

echo ""
info "Scout map server host bootstrap (Pop!_OS/Ubuntu)"
echo ""

os_release

info "Updating apt package index..."
${SUDO} apt-get update

APT_PKGS=(git curl rsync openssh-server jq unzip ca-certificates python3 python3-venv)
if [[ -n "$SUDO" ]]; then
  info "Installing: ${APT_PKGS[*]} (sudo may prompt)"
else
  info "Installing: ${APT_PKGS[*]}"
fi
${SUDO} apt-get install -y "${APT_PKGS[@]}"

info "Checking Java toolchain..."
if ! detect_java; then
  info "javac not usable yet; trying openjdk-21-jdk-headless from apt..."
  if ${SUDO} apt-get install -y openjdk-21-jdk-headless; then
    hash -r
    if ! detect_java; then
      fail "openjdk-21 installed but javac still not usable."
    fi
  else
    cat <<'EOF'

[x] OpenJDK 21 is not in this release's apt repos (Pop!_OS 22.04 / older).
    Install JDK 21+ yourself, for example via SDKMAN:

      curl -s "https://get.sdkman.io" | bash
      source "$HOME/.sdkman/bin/sdkman-init.sh"
      sdk install java 21.0.5-tem

    Then log out/in (or export JAVA_HOME) and re-run this bootstrap.
EOF
    exit 1
  fi
fi
ok "Java toolchain: ${JAVAC_BIN} (javac ${JAVA_VER})"

info "Ensuring SSH server is installed, enabled, and running..."
${SUDO} systemctl enable --now ssh
if systemctl is-active --quiet ssh; then
  ok "sshd active on TCP 22"
else
  warn "sshd did not report active; check: systemctl status ssh"
fi

if ((WITH_TAILSCALE)); then
  if command -v tailscale >/dev/null 2>&1; then
    ok "tailscale already installed"
  else
    info "Installing Tailscale via official installer..."
    curl -fsSL https://tailscale.com/install.sh | ${SUDO} sh
  fi
  info "Attempting tailscale up (may prompt for a browser login) with SSH enabled..."
  ${SUDO} tailscale up --ssh=true || warn "tailscale up did not complete; run it manually."
  local_ip="$(tailscale_ip4)"
  if [[ -n "$local_ip" ]]; then
    ok "Tailscale IPv4: $local_ip"
    info "Trust this host on the tailnet with: ./ssh_setup.sh authorize <your-hub-public-key>"
  fi
fi

echo ""
ok "Bootstrap complete. Next:"
ok "  1. ./ssh_setup.sh install     (already done above)"
ok "  2. ./ssh_setup.sh key         (generate an ed25519 key for shard pulls)"
ok "  3. ./ssh_setup.sh send-key <user@source-host>"
ok "  4. ./sync_shards.sh <user@source-host>"
ok "  5. ./configure_map_server.sh --advertise auto"
ok "  6. cd .. && ./master start && ./verify_map_server.sh"