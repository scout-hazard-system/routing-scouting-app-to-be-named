# AGENTS.md — routing-scouting-app-to-be-named (scout backend + clients)

## Project rules (preserved — the unique content of the old `docs/guides/AGENTS.md`)

- Always verify the correct source/module directory before running compile commands.
- For scout model integration changes, increment the 3rd version digit each time (e.g. scout-core1.0.1 →
  scout-core1.0.2 …) until explicitly told to use scout-core1.1.1.

## What this is

Local-first navigation + scanner-intel stack: a hardened Java backend, a web dashboard, Android/Android Auto
clients, a Windows admin GUI, and versioned scout models. Apache-2.0 (`LICENSE` / `NOTICE` / `LICENSES/`).

## Home project — branch-project overview

This root is the home project; project roots inside and in sibling repos carry their own AGENTS.md where they are unique.

| Branch project | Path | Overview | Own AGENTS.md |
|---|---|---|---|
| Scout backend (Java) | `navigation/backend` | BackendServer.java + map engine | `navigation/backend/AGENTS.md` |
| Web dashboard | `navigation/frontend` | index.html/app.js/styles.css, dev_server.py :8787 | |
| Android client | `navigation/android` | Gradle app + frontend-ui module | `navigation/android/AGENTS.md` |
| Pipeline | `navigation/pipeline` | pipeline.py, channel_selector, audio routes | |
| Scout LLM set | `llm/` | versioned Modelfiles: core/vet/rank/alert/intel; build + eval | |
| Stack / ops | `stack/`, `master`, `run_vehicle_stack.sh` | launchers, systemd deployment, docker-compose.server.yml | |
| Windows client/admin | `scout_windows_deploy/`, `scout_windows_gui_setup/` | scout GUI + blackboard + modelfiles, install scripts | |
| Extracted distribution branch | secure-mesh-navigation repo | Android-only WireGuard-hardened import from this tree (base `cb86b2bc`) | its `AGENTS.md` |

## Commands

```bash
./master start|stop|status|health|urls          # or ./run_vehicle_stack.sh
javac BackendServer.java MapModel.java PlanetTileStore.java ProprietaryMapEngine.java   # from navigation/backend
./navigation/android/gradlew -p navigation/android :app:compileDevDebugSources          # Android compile
SKIP_SMOKE=1 ./llm/build/build_llm_set.sh        # scout model build
```

## Rules

- Scout models are Qwen3 under Apache-2.0 source terms; the set does **not** ship Meta Llama weights or a
  Llama Community License (`llm/README.md`).
- Keep the API contract stable for the frontend, mobile, and Android Auto clients (`navigation/backend/README.md`).
- Coordinate privacy: clients compute coarse shards on-device; do not add lat/lon ingestion endpoints (`/api/gps/*` unless gated).
- Backend/ops hardening lives in the `stack/` config + deployment docs — verify against those, not this file.
- Documentation index: `docs/guides/` (FINAL_DEPLOYMENT_CONFIG, PEER_MESH_DEPLOYMENT), `stack/README.md`.