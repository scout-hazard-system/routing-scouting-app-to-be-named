Scout fresh setup (GUI + Hermes-hc + blackboard + nvm)
======================================================

1) Accept Taildrop files on gibdowsvista (Tailscale tray).
2) Unzip scout_windows_gui_setup.zip if needed.
3) Admin PowerShell:

   Set-ExecutionPolicy -Scope Process Bypass -Force
   .\INSTALL-WINDOWS.ps1

Installs:
- Ollama + llama3.1 + qwen3:8b
- scout-hermes-hc1.0.0 (100k, thinking, Project Director)
- scout-hermes-hc1.0.0-64k
- nvm-windows + Node LTS + npm
- OpenSSH Server (optional remote)

GUI (Linux/WSL recommended for full PySide6 Scout GUI):
  tabs at startup: Hermes | Crew | Chat | Blackboard | Pipeline | Terminal
  Hermes tab: integrated chat + "Launch classic Hermes GUI" (hermes desktop)
  Blackboard: server controls + live snapshot
  Pipeline: pipeline category + artifact tails

Verify:
  ollama list
  ollama run scout-hermes-hc1.0.0 "Status: AZ alpha? One sentence."
  node -v && npm -v

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
