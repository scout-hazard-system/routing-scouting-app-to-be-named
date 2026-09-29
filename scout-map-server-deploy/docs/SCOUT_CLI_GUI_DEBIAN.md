# Scout CLI/GUI — Debian/XFCE Deploy Package

This package contains **Debian/XFCE-compatible launchers and installer** for the Scout CrewAI desktop application.

## Package Contents

```
scout-map-server-deploy/
├── scripts/
│   ├── install-debian.sh       # System-wide installer (run as root)
│   ├── scout                   # CLI launcher (→ ~/.local/bin/scout)
│   ├── scout-gui               # GUI launcher (→ ~/.local/bin/scout-gui)
│   └── setup-map-server.sh     # Map server deployment (separate)
└── docs/
    ├── DEPLOYMENT.md           # Map server deployment guide
    └── SCOUT_CLI_GUI_DEBIAN.md # This file
```

## Quick Install (Debian 13.6 + XFCE)

### Option 1: System-wide install (recommended)
```bash
cd scout-map-server-deploy/scripts
sudo ./install-debian.sh
```

This will:
- Install system dependencies (git, python3, PySide6, X11/Wayland libs)
- Clone `scout_crew` to `/opt/scout-crew`
- Create Python venv with CrewAI + PySide6
- Install `scout` and `scout-gui` to `~/.local/bin/`
- Create XFCE/GNOME/KDE desktop entry
- Create default `.env` with local Ollama routing

### Option 2: User-local install (no sudo)
```bash
# Clone manually
git clone https://github.com/wendigoro/scout_crew.git ~/repo/scout_crew
cd ~/repo/scout_crew
python3 -m venv .venv
.venv/bin/pip install -e .

# Copy launchers
cp /path/to/scout-map-server-deploy/scripts/scout ~/repo/scout_crew/bin/scout
cp /path/to/scout-map-server-deploy/scripts/scout-gui ~/repo/scout_crew/bin/scout-gui
chmod +x ~/repo/scout_crew/bin/scout ~/repo/scout_crew/bin/scout-gui

# Add to PATH
mkdir -p ~/.local/bin
ln -sf ~/repo/scout_crew/bin/scout ~/.local/bin/scout
ln -sf ~/repo/scout_crew/bin/scout-gui ~/.local/bin/scout-gui
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

## Post-Install Setup

```bash
# 1. Start Ollama
sudo systemctl enable --now ollama

# 2. Pull base model
ollama pull qwen3:8b

# 3. Build scout models (from your main repo)
bash ~/repo/llm/build/build_llm_set.sh

# 4. Update model tags in .env
# Edit /opt/scout-crew/.env (system) or ~/repo/scout_crew/.env (local)
# Set OLLAMA_MODEL_* to match built models

# 5. Verify
scout status
# Should show: ollama_up=true, external_token_usage=false

# 6. Launch GUI
scout-gui
```

## XFCE-Specific Notes

| Component | Status | Notes |
|-----------|--------|-------|
| **PySide6 GUI** | ✅ Works | Uses `Fusion` style (dark theme built-in) |
| **Terminal pane** | ✅ Works | Spawns `bash -i` with forced local env |
| **xdg-open** | ✅ Works | Opens Thunar for "Open output folder" |
| **Desktop entry** | ✅ Works | Appears in XFCE Applications → Development |
| **Wayland** | ✅ Works | Auto-detects `WAYLAND_DISPLAY` |
| **SSH -X** | ✅ Works | X11 forwarding supported |

### Known XFCE Quirks
- **Window decorations**: Uses Qt's Fusion style — no native XFCE theme integration (by design for consistency)
- **System tray**: Not implemented — GUI runs as regular window
- **HiDPI**: Set `QT_AUTO_SCREEN_SCALE_FACTOR=1` if needed

## CLI Usage

```bash
# Health check
scout status

# Show role → model map
scout roster

# List installed models
scout models

# Single-model chat (manager, core, dev, alert, intel, vet, rank)
scout chat -m manager -p "Reply with exactly: PROMPT_OK" -v

# Dev task modes
scout dev --task-mode DEBUG -p "Explain the vet failure logic"

# Full multi-agent crew
scout crew -v
scout crew --inputs ./my_inputs.json

# Shell env export for other tools
eval "$(scout env)"
```

## GUI Usage

```bash
scout-gui
```

**Tabs:**
1. **Crew** — Full pipeline controls (transcript, dev mode, run/stop)
2. **Chat** — Direct manager/core chat (read-only response panel)
3. **Dev Conversations** — scout-dev with task modes (DEBUG→REFACTOR)
4. **Terminal** — Bash shell with local Ollama env pre-loaded
5. **Blackboard** — Server controls + live snapshot (if configured)
6. **Pipeline** — Category + artifact tails

## Local-Only Guarantee

Both launchers **hard-force** local Ollama routing:
```bash
export OPENAI_API_KEY=ollama
export OPENAI_API_BASE=http://127.0.0.1:11434/v1
export OPENAI_BASE_URL=http://127.0.0.1:11434/v1
export OLLAMA_BASE_URL=http://127.0.0.1:11434
```

Any non-local `OPENAI_BASE_URL` is rejected and reset to localhost.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `scout: command not found` | `source ~/.bashrc` or restart terminal |
| `scout-gui: no DISPLAY` | Run from graphical session, not TTY |
| `ImportError: PySide6` | `cd /opt/scout-crew && .venv/bin/pip install PySide6` |
| `Ollama is unreachable` | `sudo systemctl start ollama` |
| `Missing model scout-core1.0.5` | Run `bash ~/repo/llm/build/build_llm_set.sh` |
| GUI opens in editor | Right-click .desktop → Properties → "Run in terminal" unchecked |
| Terminal pane broken | Ensure `bash` is default shell (`chsh -s /bin/bash`) |

## Uninstall

```bash
# System install
sudo rm -rf /opt/scout-crew
rm ~/.local/bin/scout ~/.local/bin/scout-gui
rm ~/.local/share/applications/scout-crew.desktop
update-desktop-database ~/.local/share/applications

# Local install
rm -rf ~/repo/scout_crew
rm ~/.local/bin/scout ~/.local/bin/scout-gui
```

## Architecture

```
scout / scout-gui (bash launchers)
    │
    ├─ Source ~/.env
    ├─ Force local LLM env vars
    ├─ Add venv/bin + ~/.local/bin to PATH
    │
    └─ Exec .venv/bin/python -m scout_crew.cli|gui
                    │
                    ├─ scout_crew.cli → CLI commands (Typer/Click)
                    └─ scout_crew.gui → PySide6 QApplication
                        ├─ Main window (QMainWindow)
                        ├─ Terminal pane (QProcess + bash)
                        ├─ Process consoles (QPlainTextEdit + QProcess)
                        └─ xdg-open for folder opening
```

## License

Apache-2.0. Scout models use Qwen3 (`qwen3:8b`) — no Meta Llama weights.