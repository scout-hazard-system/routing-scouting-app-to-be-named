#!/usr/bin/env bash
# Scout CLI/GUI — Debian/XFCE System Installer
# Installs scout and scout-gui to /opt/scout-crew with ~/.local/bin symlinks
# Run as root: sudo ./install-debian.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_ROOT="$(dirname "$SCRIPT_DIR")"

INSTALL_ROOT="/opt/scout-crew"
LOCAL_BIN="$HOME/.local/bin"

# Allow override via env
REPO_URL="${SCOUT_CREW_REPO:-https://github.com/wendigoro/scout_crew.git}"
BRANCH="${SCOUT_CREW_BRANCH:-main}"

log() { echo "[$(date '+%H:%M:%S')] $*"; }
err() { echo "[$(date '+%H:%M:%S')] ERROR: $*" >&2; }

require_root() {
    if [[ $EUID -ne 0 ]]; then
        err "This script must run as root (use sudo)"
        exit 1
    fi
}

install_deps() {
    log "Installing system dependencies..."
    apt-get update -qq
    apt-get install -y -qq \
        git python3 python3-venv python3-pip \
        python3-dev build-essential \
        libgl1-mesa-glx libegl1-mesa libxkbcommon-x11-0 \
        libwayland-client0 libwayland-cursor0 libwayland-egl1 \
        xdg-utils \
        2>/dev/null || true
}

clone_or_update_repo() {
    log "Setting up scout_crew at $INSTALL_ROOT..."
    if [[ -d "$INSTALL_ROOT/.git" ]]; then
        log "Repository exists, updating..."
        cd "$INSTALL_ROOT"
        git fetch origin
        git checkout "$BRANCH"
        git pull origin "$BRANCH"
    else
        log "Cloning from $REPO_URL..."
        git clone --branch "$BRANCH" "$REPO_URL" "$INSTALL_ROOT"
    fi
}

setup_venv() {
    log "Creating Python virtual environment..."
    cd "$INSTALL_ROOT"
    python3 -m venv .venv
    .venv/bin/pip install --upgrade pip -q
    .venv/bin/pip install -e . -q 2>/dev/null || .venv/bin/pip install crewai pysided6 python-dotenv requests -q
}

create_env_file() {
    log "Creating default .env..."
    cd "$INSTALL_ROOT"
    if [[ ! -f .env ]]; then
        cat > .env <<'EOF'
# Scout Crew — Local-only environment
# All LLM traffic forced to local Ollama

OLLAMA_BASE_URL=http://127.0.0.1:11434
OPENAI_API_KEY=ollama
OPENAI_API_BASE=http://127.0.0.1:11434/v1
OPENAI_BASE_URL=http://127.0.0.1:11434/v1

# Model assignments (update after building scout models)
OLLAMA_MODEL_MANAGER=ollama/llama3.1
OLLAMA_MODEL_CORE=ollama/scout-core1.0.5
OLLAMA_MODEL_VET=ollama/scout-vet1.0.6
OLLAMA_MODEL_ALERT=ollama/scout-alert
OLLAMA_MODEL_INTEL=ollama/scout-intel
OLLAMA_MODEL_RANK=ollama/scout-rank
OLLAMA_MODEL_DEV=ollama/scout-dev

CREWAI_TRACING_ENABLED=true
CREWAI_DISABLE_TELEMETRY=true
EOF
        chown -R "$SUDO_USER:$SUDO_USER" .env
    else
        log ".env already exists, preserving"
    fi
}

install_launchers() {
    log "Installing launchers to $LOCAL_BIN..."
    mkdir -p "$LOCAL_BIN"
    
    # Copy launchers from deploy package
    cp "$DEPLOY_ROOT/scripts/scout" "$INSTALL_ROOT/bin/scout"
    cp "$DEPLOY_ROOT/scripts/scout-gui" "$INSTALL_ROOT/bin/scout-gui"
    chmod +x "$INSTALL_ROOT/bin/scout" "$INSTALL_ROOT/bin/scout-gui"
    
    # Create symlinks
    ln -sf "$INSTALL_ROOT/bin/scout" "$LOCAL_BIN/scout"
    ln -sf "$INSTALL_ROOT/bin/scout-gui" "$LOCAL_BIN/scout-gui"
    
    # Ensure ~/.local/bin is in PATH
    if ! grep -q '$HOME/.local/bin' "$HOME/.bashrc" 2>/dev/null; then
        echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$HOME/.bashrc"
        log "Added ~/.local/bin to PATH in ~/.bashrc"
    fi
    
    chown -R "$SUDO_USER:$SUDO_USER" "$LOCAL_BIN"
}

create_desktop_entry() {
    log "Creating desktop entry for XFCE/GNOME/KDE..."
    mkdir -p "$HOME/.local/share/applications"
    cat > "$HOME/.local/share/applications/scout-crew.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Scout Crew
Comment=Hermes + CrewAI + Blackboard (local Ollama)
Exec=$INSTALL_ROOT/bin/scout-gui
Icon=$INSTALL_ROOT/assets/scout.png
Terminal=false
Categories=Development;
StartupNotify=true
StartupWMClass=Scout Crew
EOF
    chown "$SUDO_USER:$SUDO_USER" "$HOME/.local/share/applications/scout-crew.desktop"
    update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
}

verify_install() {
    log "Verifying installation..."
    sudo -u "$SUDO_USER" bash -c "
        export PATH=\"\$HOME/.local/bin:\$PATH\"
        $INSTALL_ROOT/bin/scout status
    " 2>/dev/null || log "Note: 'scout status' needs Ollama running to pass fully"
}

print_summary() {
    cat <<EOF

============================================================
Scout CLI/GUI Installed for Debian/XFCE
============================================================

Install location: $INSTALL_ROOT
Launchers:        $LOCAL_BIN/scout, $LOCAL_BIN/scout-gui
Desktop entry:    ~/.local/share/applications/scout-crew.desktop

Next steps:
  1. Restart your shell or run: source ~/.bashrc
  2. Start Ollama:    sudo systemctl enable --now ollama
  3. Pull base model: ollama pull qwen3:8b
  4. Build scout models (from your repo):
       bash ~/repo/llm/build/build_llm_set.sh
  5. Update $INSTALL_ROOT/.env with model tags
  6. Run: scout status
  7. Run GUI: scout-gui

XFCE notes:
  - Uses Fusion style (dark theme built-in)
  - xdg-open works for "Open output folder"
  - Terminal pane uses bash (standard on Debian)
  - No DISPLAY? Run from graphical session or SSH -X

Troubleshooting:
  - GUI won't start: check DISPLAY/WAYLAND_DISPLAY
  - Missing models: run 'ollama list' and update .env
  - Import errors: cd $INSTALL_ROOT && .venv/bin/pip install -e .

EOF
}

main() {
    require_root
    log "Installing Scout CLI/GUI for Debian/XFCE..."
    install_deps
    clone_or_update_repo
    setup_venv
    create_env_file
    install_launchers
    create_desktop_entry
    verify_install
    print_summary
}

main "$@"