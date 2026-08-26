# Stack commands
Launchers and ops helpers for the vehicle stack.

## Quick commands (from repo root)
```bash
./master start
./master stop
./master restart
./master status
./master health
./master stream
./master check
./master urls
./master prepare-move
./master logs
./master logs pipeline
./master logs backend
./master logs frontend
./master up
./master down
```

Equivalents:
```bash
./run_vehicle_stack.sh start|stop|restart|status
./start_termius_stack.sh start|health|stream|logs
./stack/commands/keep_vehicle_stack_alive.sh
./stack/commands/termius_debug_quick.sh
./stack/deployment/install_user_service.sh
./stack/deployment/maintain_logs.sh
```

## Layout
- `commands/` — master, vehicle stack, termius helpers
- `config/` — `vehicle_stack.env` and channel catalogs
- `deployment/` — systemd unit + installers
- `docker-compose.server.yml` — optional containerized backend/frontend

## Config
Default config: `stack/config/vehicle_stack.env`
Override with `VEHICLE_STACK_CONFIG_FILE=/path/to.env`.

## Mesh / Tailscale broadcast

Config keys in `stack/config/vehicle_stack.env`:

- `JAVA_BACKEND_HOST=0.0.0.0`
- `FRONTEND_DEV_HOST=0.0.0.0`
- `SCOUT_NETWORK_ADVERTISE_HOST=<tailscale-ip>`
- `PLANET_PMTILES_URL=https://build.protomaps.com/20260811.pmtiles`

After `start`, the launcher prints local and mesh URLs. Live discovery:

```bash
curl -s http://127.0.0.1:18080/api/map/status | jq '.network,.planet.ready,.shards[]|select(.state=="AZ")'
curl -s 'http://127.0.0.1:18080/api/map/shard?state=AZ'
curl -s 'http://127.0.0.1:18080/api/map/shard?status=1'
```

Full runbook: `docs/guides/FINAL_DEPLOYMENT_CONFIG.md`

