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

# Debian 13 (trixie) system bootstrap for a headless Scout agent box.
# Installs the base toolchain only (no GUI, no Ollama, no mesh). Run as the
# user that will own the stack (sudo prompts are used for apt/systemctl).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

HEADLESS=1
for arg in "$@"; do
  case "$arg" in
    --with-desktop-gui)
      HEADLESS=0
      warn "--with-desktop-gui: this suite targets headless boxes; the PySide6 GUI is NOT supported."
      ;;
    *)
      fail "unknown argument: $arg (supported: --with-desktop-gui)"
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
info "Scout agent box bootstrap (Debian trixie, headless)"
echo ""

os_release

info "Updating apt package index..."
${SUDO} apt-get update

APT_PKGS=(
  ca-certificates curl git jq openssh-server python3 python3-venv rsync unzip wireguard-tools
  openjdk-21-jdk-headless
)
if ((HEADLESS)); then
  info "Installing: ${APT_PKGS[*]} (headless profile; no desktop/window-manager packages)"
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
  warn "Nothing listening on TCP 22 yet; check: systemctl enable --now ssh && systemctl status ssh (note: trixie may use ssh.socket)."
fi

if ((HEADLESS)); then
  info "Headless profile notes"
  ok "  - No PySide6 / scout-gui / Scout-Crew.desktop are installed (crew runs via the 'scout' CLI only)."
  ok "  - Scrcpy/whisper/audio scanner routes are peers-only; this box runs the pipeline services."
fi

echo ""
ok "Bootstrap complete. Next:"
ok "  1. ./configure_scout_mesh.sh            (join the WireGuard Scout Mesh, split tunnel)"
ok "  2. ./install_ollama_headless.sh         (Ollama + specialist/hermes Modelfiles)"
ok "  3. ./install_crew_headless.sh           (scout_crew venv + blackboard enrollment)"
ok "  4. ./install_services.sh && ./verify_agent_box.sh"