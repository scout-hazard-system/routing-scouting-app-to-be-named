# Routing/Scouting App
Local-first navigation + scanner-intel stack with a hardened Java backend, Android clients, and versioned scout models.

## Repository map
```text
llm/                  Scout models by type + client/build tooling
  core/ vet/ rank/ alert/ intel/
  client/             llm_set_client.py
  build/              build_llm_set.sh, eval_llm_set.py
navigation/
  frontend/           Web dashboard
  backend/            Java API (BackendServer)
  android/            Android + Android Auto client
  pipeline/           pipeline.py, channel_selector, audio routes
stack/
  commands/           master + run_vehicle_stack + termius helpers
  config/             vehicle_stack.env, catalogs
  deployment/         systemd / ops
  docker-compose.server.yml
docs/                 guides, progress logs, script text library
cop_pipeline/         local Python venv (gitignored)
```

## Quickstart
```bash
./master start
./master status
# or
./run_vehicle_stack.sh start
```

See `stack/README.md` for the full command list.

## Compile checks
```bash
# Backend
cd navigation/backend
javac BackendServer.java MapModel.java PlanetTileStore.java ProprietaryMapEngine.java

# Android
./navigation/android/gradlew -p navigation/android :app:compileDevDebugSources :app:compileNavigationDebugSources
```

## Scout LLM set
```bash
SKIP_SMOKE=1 ./llm/build/build_llm_set.sh
cop_pipeline/bin/python3 llm/build/eval_llm_set.py --threshold 0.9
```
Model iterations live under `llm/{core,vet,rank,alert,intel}/`.

## Documentation
- Peer mesh team runbook: `docs/guides/PEER_MESH_DEPLOYMENT.md`
- Final deployment / mesh config: `docs/guides/FINAL_DEPLOYMENT_CONFIG.md`
- Stack commands: `stack/README.md`
- Deployment: `stack/deployment/README.md`
- Frontend: `navigation/frontend/README.md`
- Backend: `navigation/backend/README.md`
- Scout models: `llm/README.md`
- Progress: `docs/progress/README.md`
- Agent rules: `docs/guides/AGENTS.md`

## License

Copyright 2026 Scout Project Contributors.

Licensed under the Apache License, Version 2.0 (the "License"). You may not use
this project except in compliance with the License. You may obtain a copy of the
License at:

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.

See also `NOTICE` for attribution and third-party notices.

### OpenStreetMap attribution

Map displays that incorporate OpenStreetMap data include an on-screen attribution
watermark and retain textual attribution in the user interface. OpenStreetMap data
remains under the ODbL 1.0 and is not re-licensed by Apache-2.0. See `NOTICE`.

