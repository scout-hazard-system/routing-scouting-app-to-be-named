# Deployment Runbook (Phase 1)
This runbook executes the current production plan for local-first vehicle operation.
## Components
- Pipeline process (`pipeline.py`)
- Java backend (`frontend/java_backend/ScannerBackendServer.java`, executable JAR)
- Frontend dev server (`frontend/dev_server.py`)
- Unified launcher (`run_vehicle_stack.sh`)
## Configuration
Edit `config/vehicle_stack.env` before deployment:
- `PIPELINE_LOG`
- `ENABLE_PIPELINE_AUTOSTART`
- `BACKEND_PORT`
- `FRONTEND_PORT`
- `JAVA_BACKEND_HOST`
- `MAX_LOG_SIZE_MB`
- `MAX_LOG_BACKUPS`
## Manual runtime workflow
From the project root:
```bash
./run_vehicle_stack.sh start
./run_vehicle_stack.sh status
./run_vehicle_stack.sh stop
```
## Systemd supervised workflow (Linux user service)
Install and enable:
```bash
./deployment/install_user_service.sh
```
Start/inspect:
```bash
systemctl --user start vehicle-stack.service
systemctl --user status vehicle-stack.service
journalctl --user -u vehicle-stack.service -f
```
Disable/remove:
```bash
./deployment/uninstall_user_service.sh
```
## Health and verification
- Backend health: `http://127.0.0.1:<BACKEND_PORT>/api/health`
- Frontend UI: `http://127.0.0.1:<FRONTEND_PORT>`
- Mobile bootstrap: `http://127.0.0.1:<BACKEND_PORT>/api/mobile/bootstrap`
- Provider status: `http://127.0.0.1:<BACKEND_PORT>/api/platform/providers/status`
## Log maintenance
Run on-demand:
```bash
./deployment/maintain_logs.sh
```
Suggested cron cadence (every 15 min):
```bash
*/15 * * * * /home/gibi/Desktop/deployment/maintain_logs.sh >> /tmp/vehicle_stack/logs/maintenance.log 2>&1
```
## Latest validation cycle result
Validation run timestamp (UTC from health endpoint): `2026-07-26T07:59:34.614601816Z`
### Executed sequence
1. `./run_vehicle_stack.sh stop || true`
2. `./run_vehicle_stack.sh start`
3. `./run_vehicle_stack.sh status`
4. `GET /api/health`
5. `GET /api/platform/providers/status`
6. `GET /api/mobile/bootstrap`
7. `GET /api/mobile/snapshot`
8. `./deployment/maintain_logs.sh`
9. `./run_vehicle_stack.sh stop`
10. `./run_vehicle_stack.sh status`
### Result summary
- Start succeeded: backend, frontend, and pipeline reported running PIDs.
- Health check passed:
  - `status=ok`
  - `bind_host=0.0.0.0`
  - `bind_port=18080`
- Provider status endpoint passed:
  - weather provider reported `mock` and ready
  - waze provider metadata returned expected base URLs
- Mobile bootstrap endpoint passed:
  - `status=ok`
  - `version=v1`
  - expected endpoint map returned
- Mobile snapshot endpoint passed:
  - valid JSON payload returned with metrics + events array
- Log maintenance script passed:
  - output `Log maintenance complete.`
- Stop/status passed:
  - backend/frontend/pipeline all reported stopped.
