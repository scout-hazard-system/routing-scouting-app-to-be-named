# Deployment Runbook
Operational guide for the current local-first baseline.

## Scope
This runbook covers:
- pipeline runtime startup,
- Java backend startup,
- frontend serving,
- systemd user-service supervision,
- health verification and log maintenance.

## Runtime components
- Scanner pipeline: `navigation/pipeline/pipeline.py`
- Java backend: `navigation/backend/BackendServer.java` (or `dist/backend-lite.jar`)
- Frontend: executable build or `navigation/frontend/dev_server.py`
- Orchestrator: `stack/commands/run_vehicle_stack.sh`

## Primary config file
- `stack/config/vehicle_stack.env`

Key variables to confirm before start:
- `PIPELINE_LOG`
- `BACKEND_PORT`
- `JAVA_BACKEND_HOST`
- `FRONTEND_PORT`
- `FRONTEND_EXECUTABLE_PATH`
- `ENABLE_PIPELINE_AUTOSTART`
- `FRONTEND_BUILD_ON_START`

## Standard runtime workflow
From repo root:
```bash
./stack/commands/run_vehicle_stack.sh start
./stack/commands/run_vehicle_stack.sh status
./stack/commands/run_vehicle_stack.sh stop
```

## Systemd user-service workflow
Install:
```bash
./stack/deployment/install_user_service.sh
```

Operate:
```bash
systemctl --user start vehicle-stack.service
systemctl --user status vehicle-stack.service
journalctl --user -u vehicle-stack.service -f
```

Remove:
```bash
./stack/deployment/uninstall_user_service.sh
```

## Health verification checklist
Backend:
- `GET /api/health`
- `GET /api/platform/providers/status`
- `GET /api/platform/llm/status`
- `GET /api/map/status` (expect `planet.ready=true` and `network.urls`)
- `GET /api/map/shard?state=AZ` (state required)
- `GET /api/map/shard?status=1`

Mesh (replace host with `SCOUT_NETWORK_ADVERTISE_HOST`):
- Frontend `http://<host>:8787/`
- Blackboard `http://<host>:8765/health`
- Ollama `http://<host>:11434/api/tags`

See `docs/guides/FINAL_DEPLOYMENT_CONFIG.md` for the verified baseline.

Mobile-facing:
- `GET /api/mobile/bootstrap`
- `GET /api/mobile/snapshot`
- `GET /api/mobile/stream`

Pipeline-facing:
- `GET /api/pipeline/snapshot`
- `GET /api/pipeline/stream`

## Logging and maintenance
Manual maintenance:
```bash
./stack/deployment/maintain_logs.sh
```

Suggested recurring execution:
```bash
*/15 * * * * /home/gibi/Desktop/stack/deployment/maintain_logs.sh >> /tmp/vehicle_stack/logs/maintenance.log 2>&1
```

## Security operations notes
- Keep backend bound only as needed for deployment topology.
- Do not set wildcard CORS in runtime configuration.
- Use source/CIDR controls and API keys for non-local exposure.
- Monitor stderr/stdout for structured `request_rejected` events.

## Current baseline validation intent
After any security or model integration update, run:
1) backend compile check,
2) Android compile checks for both flavors,
3) stack start/status/health cycle,
4) mobile bootstrap/snapshot quick checks.

## Tailscale setup and verification (future reference)
This project is configured to prefer a Tailscale backend path by default in the Android client (`AppPrefs.TAILSCALE_BASE_URL`), with fallback probing to alternate LAN addresses.

### 1) Host machine setup (backend side)
1. Install and start Tailscale:
   - `curl -fsSL https://tailscale.com/install.sh | sh`
   - `sudo systemctl enable --now tailscaled`
2. Join the tailnet:
   - `sudo tailscale up`
3. Confirm host tailnet address:
   - `tailscale ip -4`
4. Ensure backend stack is running on port `18080`:
   - `./start_termius_stack.sh start`
5. Confirm backend health over localhost:
   - `curl -fsS http://127.0.0.1:18080/api/health`

### 2) Network policy checks
1. In Tailscale admin, ensure ACLs allow the Android device/user to reach this host on TCP `18080` and `8787` (if dashboard access is needed).
2. If host firewall is enabled, allow tailnet interface traffic to backend/frontend ports.
3. Keep backend API hardening enabled:
   - `BACKEND_RESTRICT_ALL_APIS=true`
   - configure `BACKEND_GLOBAL_API_KEY` and `BACKEND_PULL_API_KEY` for non-local exposure.

### 3) Android app setup
1. In Scout app, open server settings and enable **Tailscale mode**.
2. Set backend base URL to:
   - `http://<host-tailnet-ip>:18080`
3. Save; app will probe `/api/health` and cache the first reachable candidate.
4. Re-open app and verify status panel shows live backend responses.

### 4) Wireless debug + deploy workflow
1. Enable Developer options + Wireless debugging on device.
2. Pair once:
   - `adb pair <device-ip>:<pair-port>`
3. Connect for deployment:
   - `adb connect <device-ip>:<debug-port>`
4. Verify device visibility:
   - `adb devices -l`
5. Install Scout debug APK:
   - `adb -s <device-ip>:<debug-port> install -r /home/gibi/Desktop/android-stream-client/app/build/outputs/apk/debug/scout-debug.apk`
6. Launch and check logs:
   - `adb -s <device-ip>:<debug-port> shell am start -n dev.warp.stream/.MainActivity`
   - `adb -s <device-ip>:<debug-port> logcat -d -v brief MainActivity:I AndroidRuntime:E ActivityTaskManager:I *:S`

### 5) Runtime verification checklist
- `/api/health` returns `status=ok`.
- `/api/mobile/bootstrap` returns endpoint map.
- `/api/platform/llm/status` returns model availability payload.
- App launches without `AndroidRuntime` fatal logs.
- `MainActivity` logs show successful map/backend fetches.
