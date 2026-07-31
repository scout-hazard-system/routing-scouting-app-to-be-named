# Routing/Scouting App Current Build Summary
This repository now tracks the latest deployable state across backend, Android client, and scanner-model integrations.
## Latest completed hardening and feature scope
- Backend request handling is hardened with bounded query/body parsing and secure endpoint gating.
- API access controls include source allowlisting, CIDR checks, pull/global API key enforcement, and per-client pull tokens.
- Security rejection paths now log structured denial details (reason/status/method/path/source) for token, overflow, and access denials.
- Android client includes first-launch usage disclosure, tracking controls, and analytics opt-out compatibility with backend behavior.
- Admin/frontend surfaces were reduced to avoid exposing other-client live location details while preserving tooling workflows.
- Java backend and Android flows remain aligned for routing, stream ingestion, and mobile pull/stream paths.
## Current build baseline
- Keep this branch focused on current production-intended artifacts and active runtime code only.
- Legacy progress snapshots and superseded intermediate history are treated as obsolete once the current baseline is published.
## Scout model baseline (post-1.0)
- `scout-core1.0.3` is the current unified base model target.
- `scout-vet1.0.4` is the current second-stage vet model target.
- `scout-rank` remains the selector rerank model.
## Validation focus
- Backend compile: `javac BackendServer.java MapModel.java PlanetTileStore.java ProprietaryMapEngine.java`
- Android compile/build: Gradle app compile tasks and navigation APK generation.
- Security-sensitive changes must preserve denial behavior while increasing observability.
