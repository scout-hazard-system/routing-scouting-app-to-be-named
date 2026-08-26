Scout deploy bundle from pop-os
================================
Contents:
  bin/           scout / scout-gui launchers (Linux; on Windows use WSL or reinstall)
  config/        .env.example, agents.yaml, tasks.yaml, arizona_phase.json, pyproject.toml
  ssh/           pop-os public key to authorize
  SETUP-WINDOWS.ps1  enable OpenSSH + install authorized_keys + copy configs

On Windows (Admin PowerShell):
  1. Unzip/copy this folder somewhere
  2. Right-click SETUP-WINDOWS.ps1 -> Run with PowerShell (Admin)
  3. Install Ollama for Windows + enable Tailscale SSH if desired
  4. git clone https://github.com/wendigoro/scout_crew.git
  5. Copy config files into the clone; cp .env.example .env and edit

After OpenSSH is on, from pop-os:
  ssh -i ~/.ssh/id_ed25519_popos <windows-user>@100.82.130.47

Mesh IP set (locked listen)
---------------------------
Linux hub (pop-os):     100.78.191.61
Windows peer (Hermes):  100.82.130.47

Windows Ollama is reachable on the Tailscale address ONLY.
From Linux: curl http://100.82.130.47:11434/api/version
LAN IP (e.g. 192.168.1.160:11434) times out — do not point SCOUT_PEER_* at LAN.

On Windows, set system env OLLAMA_HOST=0.0.0.0:11434 so Tailscale clients can connect,
and keep firewall limited to Tailscale CGNAT (100.64.0.0/10) if you want the lock.

Blackboard hub stays on Linux: http://100.78.191.61:8765
