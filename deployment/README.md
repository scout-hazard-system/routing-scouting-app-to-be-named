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
- Scanner pipeline: `pipeline.py`
- Java backend: `frontend/java_backend/BackendServer.java` (or `dist/backend-lite.jar`)
- Frontend: executable build or `frontend/dev_server.py`
- Orchestrator: `run_vehicle_stack.sh`

## Primary config file
- `config/vehicle_stack.env`

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
./run_vehicle_stack.sh start
./run_vehicle_stack.sh status
./run_vehicle_stack.sh stop
```

## Systemd user-service workflow
Install:
```bash
./deployment/install_user_service.sh
```

Operate:
```bash
systemctl --user start vehicle-stack.service
systemctl --user status vehicle-stack.service
journalctl --user -u vehicle-stack.service -f
```

Remove:
```bash
./deployment/uninstall_user_service.sh
```

## Health verification checklist
Backend:
- `GET /api/health`
- `GET /api/platform/providers/status`
- `GET /api/platform/llm/status`

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
./deployment/maintain_logs.sh
```

Suggested recurring execution:
```bash
*/15 * * * * /home/gibi/Desktop/deployment/maintain_logs.sh >> /tmp/vehicle_stack/logs/maintenance.log 2>&1
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
