# Peer Mesh Deployment (Team Runbook)

Step-by-step deployment for the **Linux hub + Windows Hermes peer** Tailscale mesh.

**Verified:** 2026-08-26  
**Jurisdiction:** Arizona alpha  
**Repos:**
- Monorepo (vehicle stack / docs): `/home/gibi/Desktop`
- CrewAI: `/home/gibi/Desktop/scout_crew` (separate git repo)

## Locked mesh IP set

| Machine | Hostname | Tailscale IP | Role |
|---------|----------|--------------|------|
| Linux hub | pop-os | `100.78.191.61` | specialists Ollama, blackboard, backend `:18080`, UI `:8787` |
| Windows peer | gibdowsvista | `100.82.130.47` | Hermes / manager Ollama |

**Critical lock:** Windows Ollama is reachable on **Tailscale `100.82.130.47:11434` only**.  
LAN addresses (e.g. `192.168.1.160:11434`) time out. Never point `SCOUT_PEER_*` at a LAN IP.

```text
Linux  → http://100.78.191.61:{8787,18080,8765,11434}
Windows Hermes → http://100.82.130.47:11434   (Tailscale only)
```

---

## 1. Prerequisites (both machines)

- [ ] Tailscale installed, logged into the same tailnet, peers show **active**
- [ ] Ollama installed
- [ ] Python `>=3.10,<3.14` + [uv](https://docs.astral.sh/uv/) on Linux (and on Windows if running crew there)
- [ ] Git access to `scout_crew` and the monorepo

```bash
# From either side
tailscale status
# expect pop-os and gibdowsvista online
```

---

## 2. Linux hub (pop-os) — vehicle stack

```bash
cd ~/Desktop   # monorepo root
./master start
./master status
./master health
./master urls
```

Config file: `stack/config/vehicle_stack.env`

| Key | Value |
|-----|--------|
| `JAVA_BACKEND_HOST` | `0.0.0.0` |
| `FRONTEND_DEV_HOST` | `0.0.0.0` |
| `BACKEND_PORT` | `18080` |
| `FRONTEND_PORT` | `8787` |
| `SCOUT_NETWORK_ADVERTISE_HOST` | `100.78.191.61` |
| `MAP_SHARD_STATE` / `JURISDICTION_STATE` | `AZ` |

Expose Linux Ollama on all interfaces (for mesh clients):

```bash
# already applied via systemd drop-in on this host:
# /etc/systemd/system/ollama.service.d/tailscale.conf
# Environment=OLLAMA_HOST=0.0.0.0:11434
sudo systemctl daemon-reload && sudo systemctl restart ollama
curl -s http://127.0.0.1:11434/api/version
curl -s http://100.78.191.61:11434/api/version
```

Build specialist models (Qwen3 lineage):

```bash
ollama pull qwen3:8b
bash ~/Desktop/llm/build/build_llm_set.sh
ollama list | egrep 'scout-|qwen3:8b'
```

---

## 3. Linux hub — Scout Crew + blackboard

```bash
cd ~/Desktop/scout_crew
cp -n .env.example .env
# Edit .env for split mesh (see §3.1)
uv sync 2>/dev/null || true

mkdir -p ~/.local/bin
ln -sfn "$PWD/bin/scout" ~/.local/bin/scout
ln -sfn "$PWD/bin/scout-gui" ~/.local/bin/scout-gui
ln -sfn "$PWD/bin/scout-mesh-status" ~/.local/bin/scout-mesh-status
hash -r
```

### 3.1 Required `.env` (split mesh)

```bash
OLLAMA_BASE_URL=http://127.0.0.1:11434
OPENAI_API_KEY=ollama
OPENAI_API_BASE=http://127.0.0.1:11434/v1
OPENAI_BASE_URL=http://127.0.0.1:11434/v1

OLLAMA_MODEL_MANAGER=ollama/scout-hermes-hc1.0.0
OLLAMA_MODEL_HERMES=ollama/scout-hermes-hc1.0.0
OLLAMA_MODEL_CORE=ollama/scout-core1.0.5
OLLAMA_MODEL_DEV=ollama/scout-dev
OLLAMA_MODEL_VET=ollama/scout-vet1.0.6
OLLAMA_MODEL_ALERT=ollama/scout-alert
OLLAMA_MODEL_INTEL=ollama/scout-intel
OLLAMA_MODEL_RANK=ollama/scout-rank
OLLAMA_MODEL_BASE=ollama/qwen3:8b

# Locked Tailscale IP set
SCOUT_TAILSCALE_IP=100.78.191.61
SCOUT_PEER_WINDOWS_IP=100.82.130.47
SCOUT_PEER_OLLAMA_OPENAI=http://100.82.130.47:11434/v1
OLLAMA_HOST_HERMES=http://100.82.130.47:11434
OLLAMA_HOST_MANAGER=http://100.82.130.47:11434

SCOUT_BLACKBOARD_URL=http://100.78.191.61:8765
SCOUT_BLACKBOARD_HOST=0.0.0.0
SCOUT_BLACKBOARD_PORT=8765
SCOUT_BLACKBOARD_PATH=/home/gibi/Desktop/scout_crew/data/blackboard/scout_blackboard.db
```

Start blackboard (if not already running):

```bash
cd ~/Desktop/scout_crew
.venv/bin/python -m scout_crew.blackboard.server --host 0.0.0.0 --port 8765
# or background; health:
curl -s http://100.78.191.61:8765/health
```

---

## 4. Windows peer (gibdowsvista) — Hermes host

### 4.1 Bootstrap packages (from Linux)

On Linux these live under `~/Desktop/`:

| Bundle | Purpose |
|--------|---------|
| `scout_windows_deploy/` (+ `.zip`) | SSH key install, scout config seed |
| `scout_windows_gui_setup/` (+ `.zip`) | Ollama models, Hermes Modelfiles, nvm, GUI notes |

Transfer via Taildrop or copy the zip to the Windows machine.

### 4.2 Admin PowerShell on Windows

```powershell
# Deploy bundle
Set-ExecutionPolicy -Scope Process Bypass -Force
.\SETUP-WINDOWS.ps1          # from scout_windows_deploy
# or full GUI/Hermes setup:
.\INSTALL-WINDOWS.ps1        # from scout_windows_gui_setup
```

### 4.3 Ollama listen + firewall (lock)

```powershell
# System environment (then restart Ollama from tray or services)
[System.Environment]::SetEnvironmentVariable("OLLAMA_HOST", "0.0.0.0:11434", "Machine")
# Restart Ollama app/service

# Prefer allowing TCP 11434 from Tailscale CGNAT only (100.64.0.0/10),
# not the whole LAN, to keep the Tailscale-only lock.
```

Models required on Windows:

```text
qwen3:8b
scout-hermes-hc1.0.0
scout-hermes-hc1.0.0-64k   (optional floor)
```

```powershell
ollama list
ollama run scout-hermes-hc1.0.0 "Reply with exactly: PING_OK"
```

### 4.4 Windows `.env` seed

Copy from bundle `config/.env.example` → repo `.env`. Key mesh lines:

```bash
SCOUT_TAILSCALE_IP=100.82.130.47
SCOUT_PEER_WINDOWS_IP=100.82.130.47
SCOUT_LINUX_IP=100.78.191.61
SCOUT_BLACKBOARD_URL=http://100.78.191.61:8765
SCOUT_SHARED_OLLAMA_OPENAI=http://100.78.191.61:11434/v1
OLLAMA_MODEL_MANAGER=ollama/scout-hermes-hc1.0.0
OLLAMA_MODEL_HERMES=ollama/scout-hermes-hc1.0.0
```

Windows crew machines should **not** use Linux LAN IPs for hub services — only `100.78.191.61`.

---

## 5. Verify mesh (from Linux)

```bash
scout-mesh-status
```

**Expect:**

```text
peer_ollama_lock=tailscale_only
ollama TS win:    {"version":...}          # UP on 100.82.130.47
ollama LAN win:   DOWN (expected)
blackboard TS:    {"ok": true, ...}
role endpoints:
  manager/hermes → http://100.82.130.47:11434
  specialists    → http://127.0.0.1:11434
PASS: Windows Ollama reachable on Tailscale ...
PASS: LAN Ollama closed/unreachable ...
```

Role pings:

```bash
cd ~/Desktop/scout_crew
# specialist (Linux)
scout chat -m alert -p "Reply with exactly: PING_OK" -v --max-tokens 256
# manager (Windows Hermes) — needs enough max_tokens for thinking models
scout chat -m manager -p "Reply with exactly: PING_OK" -v --max-tokens 512
```

Vehicle / map mesh:

```bash
curl -s http://100.78.191.61:18080/api/health | jq .
curl -s http://100.78.191.61:18080/api/map/status | jq '.planet.ready, .network'
curl -s 'http://100.78.191.61:18080/api/map/shard?state=AZ' | jq .
# From Windows browser: http://100.78.191.61:8787/
```

Full crew smoke:

```bash
scout status    # mesh_ip_set + peer_tailscale_ollama
scout roster
scout crew -v   # optional longer run
```

---

## 6. Day-2 operations

| Task | Command / action |
|------|------------------|
| Mesh health | `scout-mesh-status` |
| Stack health | `./master status` / `./master health` |
| Restart stack | `./master restart` |
| Restart blackboard | see §3 start command |
| Rebuild Linux specialists | `bash ~/Desktop/llm/build/build_llm_set.sh` |
| Rebuild Hermes-hc | `bash ~/Desktop/llm/unified/build_hermes_hc.sh` (run on Windows or copy tags) |
| Mesh URLs cheat sheet | `./master urls` or `docs/guides/FINAL_DEPLOYMENT_CONFIG.md` |

### Token budget note

Qwen3 thinking / Hermes-hc can emit internal reasoning before visible content.  
If chat returns empty with `finish_reason=length`, raise `--max-tokens` (256+ specialists, 512+ manager).

### Do / Don't

| Do | Don't |
|----|--------|
| Use Tailscale `100.x` for all cross-machine URLs | Point peers at `192.168.x.x` for Ollama |
| Keep blackboard on Linux hub | Expose cloud LLM API keys |
| Pin manager/hermes to Windows via `OLLAMA_HOST_*` | Assume Windows Ollama is on LAN |
| Run `scout-mesh-status` after IP/DNS changes | Commit `.env` (gitignored secrets/local pins) |

---

## 7. Troubleshooting

| Symptom | Fix |
|---------|-----|
| `ollama TS win` DOWN | Windows: Ollama running? `OLLAMA_HOST=0.0.0.0:11434`? Tailscale up? Firewall? |
| `ollama LAN win` UP unexpectedly | OK functionally; lock is “Tailscale required for clients”. Tighten Windows firewall if desired. |
| Manager empty replies | Increase `max_tokens`; confirm model `scout-hermes-hc*` on Windows |
| `Ollama is unreachable` on role host | `scout status` → `role_endpoints` / `hosts` |
| Map blank on peer browser | Open UI via `http://100.78.191.61:8787/` (not localhost); backend CORS `*`; `SCOUT_NETWORK_ADVERTISE_HOST` set |
| `/api/map/shard` error `missing_state` | Always pass `?state=AZ` |
| Cloud key refused | Unset provider keys; restore dummy `OPENAI_API_KEY=ollama` |

---

## 8. Related docs

- `docs/guides/FINAL_DEPLOYMENT_CONFIG.md` — verified ports, URLs, health table  
- `scout_crew/SETUP.md` — full CrewAI install + split-mesh `.env`  
- `scout_crew/README.md` / `USAGE.md` — CLI/GUI  
- `stack/README.md` — `./master` commands  
- `scout_windows_deploy/README-WINDOWS.txt` — Windows SSH/config bootstrap  
- `scout_windows_gui_setup/README-WINDOWS.txt` — Windows Hermes/GUI bootstrap  

## 9. Acceptance checklist (ship)

- [ ] `tailscale status` shows both peers active  
- [ ] `scout-mesh-status` → TS win PASS, LAN win DOWN/expected, lock `tailscale_only`  
- [ ] `curl http://100.82.130.47:11434/api/version` works from Linux  
- [ ] `scout chat -m manager … PING_OK` routes to Windows and returns content  
- [ ] `scout chat -m alert … PING_OK` stays on Linux  
- [ ] Blackboard `http://100.78.191.61:8765/health` → ok  
- [ ] UI `http://100.78.191.61:8787/` and API health from a peer browser  
- [ ] No cloud LLM base URLs in `scout status`  
