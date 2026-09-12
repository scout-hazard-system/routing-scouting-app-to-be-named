# Scout Agent Box — headless Debian 13 (trixie) setup suite

Turn a clean Debian 13.6 trixie machine into a **custom harnessed Scout agent
box**: an always-on, headless workstation that runs the local scout stack
pipeline, the Qwen3-lineage Ollama model set, and the `scout_crew` CLI — joined
to the WireGuard **Scout Mesh** as a split tunnel.

This suite (like `map_server_setup_trixie/` for the map-server host) is the
Debian port of the Pop!_OS / Windows harness. The target box is **exactly the
same OS this file ships on in practice (Debian 13.6 trixie)**, so every script
can be smoke-tested on the reference box before powering a new machine.

## What an agent box is

| Layer | What runs | Role |
|-------|-----------|------|
| Mesh | WireGuard `scoutwg0` (`10.66.0.0/16`) | reach hub + peers; Internet stays on LAN default route |
| Ollama | `127.0.0.1:11434` (specialists) | `scout-alert/intel/vet/rank/core/dev` + optional hermes brain | 
| Crew | `scout_crew` venv, run via the `scout` CLI | headless agent execution, blackboard reads/writes |
| Stack | Java backend `:18080`, frontend `:8787`, (optional) blackboard `:8765` | map/pipeline/UI services as systemd user units |
| Control plane | `master`/`scout` CLI, `ssh` | everything; **no GUI, no `Scout-Crew.desktop`, no PySide6** |

The agent box is pure-CLI. The PySide6 GUI (`scout_windows_gui_setup/gui/gui.py`)
only exists on the Windows peer; this box manages the crew with:

```bash
scout status      # model roster + mesh/hub probes
scout roster      # role -> model
scout models      # per-role endpoint table
scout crew ...    # run the crew headless (also: scout_crew, run_crew, scout chat ...)
```

## Prerequisites

- Debian 13 (trixie), 64-bit, **JDK 21 available** (`openjdk-21-jdk-headless`)
- RAM: >=8 GB minimum; >=16 GB if you build the hermes brain (`--with-hermes`)
- A user with `sudo`; SSH enabled for headless administration
- Network: LAN with an IPv4 DHCP gateway (see **The IPv4 default-route gotcha**)

## Install (in this order)

```bash
# 0. This clone must live somewhere stable, e.g. /opt/scout/routing-scouting-app
#    (the stack resolves its root from its own location, not /home/gibi/Desktop).

# 1. Base packages: git, curl, jq, rsync, ssh, python3-venv, wireguard-tools,
#    openjdk-21-jdk-headless. Installs nothing graphical.
./bootstrap_agent_box.sh

# 2. Join the Scout Mesh (WireGuard, split tunnel). You need the hub's WG pubkey
#    and endpoint. Verify your OWN mesh IP (default 10.66.2.5) is free.
SCOUT_MESH_HUB_PUBKEY="<hub pubkey>" \
SCOUT_MESH_HUB_ENDPOINT="<hub-host:51820>" \
SCOUT_MESH_OWN_IP="10.66.2.5" \
./configure_scout_mesh.sh up

# 3. Ollama + models (specialists always; add --with-hermes for the brain,
#    --mesh-serve if this box will serve manager/hermes to peers).
./install_ollama_headless.sh --with-hermes

# 4. scout_crew venv + blackboard enrollment.
SCOUT_BLACKBOARD_ENTRY_TOKEN="<from hub operator>" \
./install_crew_headless.sh --device <this-box-name> --device-role alert

# 5. systemd user services (+ blackboard when this box is also the hub).
./install_services.sh --with-blackboard   # or without --with-blackboard

# 6. Verify the whole box.
./verify_agent_box.sh
```

## Runtime control plane (CLI only)

```bash
./master start|stop|restart|status|health|logs|urls    # repo-root wrapper -> stack/commands/master
./master check                                          # status + health in one shot
$HOME/.scout/venv/bin/scout status                      # crew-side status (Ollama/mesh/blackboard)
journalctl --user -u vehicle-stack.service -f
```

## Ports

| Service | Port | Bind |
|---------|------|------|
| Java backend | 18080 | `0.0.0.0` (mesh) |
| Frontend | 8787 | `0.0.0.0` (mesh) |
| Blackboard (hub only) | 8765 | `0.0.0.0` (mesh) |
| Ollama (specialists) | 11434 | `127.0.0.1` (or `--mesh-serve`) |

## Model roster

Built from the `llm/` Modelfiles (Qwen3 lineage, “never Llama”):

| Role | Tag | Notes |
|------|-----|-------|
| alert / intel / vet / rank / core | `scout-alert`, `scout-intel`, `scout-vet1.0.6`, `scout-rank`, `scout-core1.0.5` | `/no_think`, narrow contracts |
| dev | `scout-dev` | chained from core; longer context |
| manager / hermes | `scout-hermes-hc1.1.0` (→1.0.0→-64k→`qwen3:8b`) | thinking enabled; 100k context |
| base fallback | `qwen3:8b` | last resort only |

Roles/`OLLAMA_MODEL_*`/`OLLAMA_HOST_*` overrides come from `stack/config/vehicle_stack.env`
and the `scout` CLI env; see `scout status`.

## Mesh notes (hard-won)

- **Split tunnel**: `AllowedIPs = 10.66.0.0/16`. The tunnel carries only the
  Scout Mesh; your Internet default route stays on your LAN. Keep
  `PersistentKeepalive = 25` so the hub can reach this box through NAT.
- **The IPv4 default-route gotcha** (reproduced in the field): on a LAN that
  hands out IPv6 Router Advertisements but **no IPv4 DHCP gateway**, joining the
   mesh “works” (mesh pings fine, DNS resolves) while git/Hub/warp die with
  `rtnetlink answers: network is unreachable` — because there is simply no IPv4
  default route. Browsers/email still work because they go over IPv6. The mesh
  script checks this and prints the fix:

  ```bash
  nmcli -g IP4.GATEWAY device show <eth>                 # if empty -> this bug
  sudo ip route add default via <router-ip> dev <eth>    # immediate fix
  sudo nmcli connection modify <conn> ipv4.gateway <router-ip> ipv4.method auto  # persist
  sudo nmcli device reapply <eth>
  ```

  Do not “fix” this by routing `0.0.0.0/0` into the tunnel.
- To reach a peer's Ollama over the mesh, add a second `[Peer]` block with
  `AllowedIPs = <peer-mesh-ip>/32` (or set the role's `OLLAMA_HOST_*`).

## Relationship to the other suites / PRs

- `map_server_setup_trixie/` (PR `debian-trixie-map-server`): map-server suite
  (backend, shard sync, verify). An agent box + map server can share a host.
- `map_server_setup/setup_blackboard.sh` + `setup_blackboard.sh --device …`
  (PR `scout/blackboard-on-mapserver`): the hub side mints per-device tokens;
  `install_crew_headless.sh` consumes the entry token via key authorization.
- `stack/mesh/` (PR `pr-7-map-server-setup`): hub installation (`install_hub.sh`,
  `apply_peers.sh`, `wg-hub.conf.template`) — the hub is the WireGuard server;
  this suite is the peer-side join.
- scout_crew `manager-agentic-tools` (new): managers call specialists as tools;
  num_predict.context budget and tool wiring match the roster above.

## Headless optimizations applied here

- No desktop packages, no PySide6, no `Scout-Crew.desktop`, no `xdg-open`.
- `loginctl enable-linger` keeps user services alive without a session.
- Launchers resolve a repo-relative venv (`/opt/scout/.venv` or `$HOME/.scout/venv`)
  instead of the legacy `cop_pipeline/bin/python3` fallback.
- Everything observable via `curl`/`journalctl`/`scout status` — nothing needs a
  browser except the (optional) web dashboard on `:8787`.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `rtnetlink: network is unreachable` on git/Hub | missing IPv4 default route; see gotcha above |
| Ollama not building models | memory limits; build specialists first, add `--with-hermes` later |
| Blackboard health down | hub not enrolled / token absent: `journalctl --user -u scout-blackboard.service -n 50` |
| Crew can't reach manager model | manager routes to peer via `SCOUT_PEER_OLLAMA_OPENAI`/`OLLAMA_HOST_MANAGER`; ensure that host is on-mesh |
| `scout status` refuses cloud keys | correct: `assert_local_only()` blocks cloud LLM env; that is by design |