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

# Set up SSH between the Scout map server and a source host.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

KEY_DIR="$HOME/.ssh"
AUTH_KEYS="$KEY_DIR/authorized_keys"

usage() {
  cat <<EOF
Usage: $(basename "$0") <command> [args]

Commands:
  install                     Install/enable/start the SSH server (sshd on :22)
  key [name]                  Generate an ed25519 keypair (default: id_ed25519_popos)
  show [name]                 Print the local public key (paste this on the other host)
  authorize <keyfile|key>     Trust a public key from the other machine
  send-key <user@host[:port]> [name]   Copy our public key to the other host
  test <user@host[:port]> [name]       Verify passwordless SSH to the other host

Examples:
  ./ssh_setup.sh install
  ./ssh_setup.sh key
  ./ssh_setup.sh show
  ./ssh_setup.sh send-key gibi@100.78.191.61
  ./ssh_setup.sh authorize ~/hub_popos.pub
  ./ssh_setup.sh test gibi@100.78.191.61
EOF
}

SUDO=""
if ((EUID != 0)); then
  require_cmd sudo
  SUDO="sudo"
fi

cmd_install() {
  info "Installing/enabling openssh-server..."
  if ! dpkg -s openssh-server >/dev/null 2>&1; then
    ${SUDO} apt-get update
    ${SUDO} apt-get install -y openssh-server
  fi
  ${SUDO} systemctl enable --now ssh
  if systemctl is-active --quiet ssh; then
    ok "sshd active on TCP 22"
  else
    fail "sshd is not running; check: systemctl status ssh"
  fi
  info "Firewall note: allow TCP 22 from your shard-source host so it can rsync back (ufw allow 22/tcp from <source-ip>)."
}

find_key() {
  local name="${1:-id_ed25519_popos}"
  KEY_FILE="$KEY_DIR/$name"
  PUB_FILE="$KEY_FILE.pub"
}

cmd_key() {
  find_key "${2:-}"
  mkdir -p "$KEY_DIR"
  chmod 700 "$KEY_DIR"
  if [[ ! -f "$KEY_FILE" ]]; then
    info "Generating ed25519 keypair at $KEY_FILE..."
    ssh-keygen -t ed25519 -C "$(whoami)@$(hostname)" -N "" -f "$KEY_FILE"
    ok "Generated keypair."
  else
    ok "Keypair already exists: $KEY_FILE"
  fi
  cmd_show "${2:-}"
}

cmd_show() {
  find_key "${2:-}"
  if [[ ! -f "$PUB_FILE" ]]; then
    fail "No public key at $PUB_FILE (run: ./ssh_setup.sh key ${2:-})"
  fi
  echo ""
  echo "Public key (add to the other machine's ~/.ssh/authorized_keys):"
  printf '%s\n' '---BEGIN---'
  cat "$PUB_FILE"
  printf '%s\n' '---END---'
}

cmd_authorize() {
  local arg="${2:-}"
  if [[ -z "$arg" ]]; then
    fail "usage: ./ssh_setup.sh authorize <keyfile|public-key-string>"
  fi
  local key=""
  if [[ -f "$arg" ]]; then
    key="$(cat "$arg")"
  elif [[ "$arg" =~ ^(ssh-ed25519|ssh-rsa|ecdsa-sha2-|sk-ssh-) ]]; then
    key="$arg"
  else
    warn "Argument is neither an existing file nor a recognizable SSH public key: $arg"
    fail "Provide a path to a .pub file, or paste the key string directly."
  fi
  mkdir -p "$KEY_DIR"
  chmod 700 "$KEY_DIR"
  touch "$AUTH_KEYS"
  chmod 600 "$AUTH_KEYS"
  if grep -qF -- "$key" "$AUTH_KEYS"; then
    ok "Key already trusted in $AUTH_KEYS"
  else
    printf '%s\n' "$key" >> "$AUTH_KEYS"
    ok "Trusted public key in $AUTH_KEYS"
  fi
}

cmd_send_key() {
  local target="${2:-}"
  local name="${3:-id_ed25519_popos}"
  if [[ -z "$target" ]]; then
    fail "usage: ./ssh_setup.sh send-key <user@host[:port]> [name]"
  fi
  require_cmd ssh-copy-id
  split_ssh_target "$target"
  if [[ ! -f "$KEY_DIR/$name.pub" ]]; then
    info "Keypair missing; generating $KEY_DIR/$name ..."
    mkdir -p "$KEY_DIR"
    chmod 700 "$KEY_DIR"
    ssh-keygen -t ed25519 -C "$(whoami)@$(hostname)" -N "" -f "$KEY_DIR/$name"
    ok "Generated keypair."
  fi
  local port_args=()
  if [[ "$SSH_PORT" != "22" ]]; then
    port_args=(-p "$SSH_PORT")
  fi
  info "Copying $KEY_DIR/$name.pub to ${SSH_USER}@${SSH_HOST} (password prompt expected)..."
  ssh-copy-id "${port_args[@]}" -i "$KEY_DIR/$name.pub" "${SSH_USER}@${SSH_HOST}"
  ok "Public key installed on ${SSH_USER}@${SSH_HOST}"
}

cmd_test() {
  local target="${2:-}"
  if [[ -z "$target" ]]; then
    fail "usage: ./ssh_setup.sh test <user@host[:port]> [name]"
  fi
  split_ssh_target "$target"
  local args=(-o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new)
  if [[ "$SSH_PORT" != "22" ]]; then
    args+=(-p "$SSH_PORT")
  fi
  local id_args=()
  if [[ -n "${3:-}" ]]; then
    id_args=(-i "$KEY_DIR/$3")
  fi
  info "Testing passwordless SSH to ${SSH_USER}@${SSH_HOST}..."
  local remote
  if remote="$(ssh "${args[@]}" "${id_args[@]}" "${SSH_USER}@${SSH_HOST}" 'hostname' 2>/dev/null)"; then
    ok "SSH OK -> ${SSH_USER}@${SSH_HOST} ($remote)"
  else
    fail "SSH failed. Run ./ssh_setup.sh send-key ${SSH_USER}@${SSH_HOST} first, or check the host key/firewall."
  fi
}

CMD="${1:-help}"
case "$CMD" in
  install) cmd_install ;;
  key) cmd_key "$@" ;;
  show) cmd_show "$@" ;;
  authorize) cmd_authorize "$@" ;;
  send-key) cmd_send_key "$@" ;;
  test) cmd_test "$@" ;;
  help|-h|--help|"")
    usage
    ;;
  *)
    usage
    exit 1
    ;;
esac