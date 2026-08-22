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
