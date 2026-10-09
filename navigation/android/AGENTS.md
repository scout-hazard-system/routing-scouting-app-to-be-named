# AGENTS.md — Android / Android Auto client

## What this is

The Android + Android Auto navigation client: a Gradle app plus a shared `frontend-ui` library
(Map3dView, AudioVisualizerView, menu panel). Parent repo: `../AGENTS.md`. A hardened, Android-only
WireGuard distribution variant was extracted to the sibling **secure-mesh-navigation** repo (see its
AGENTS.md) — treat that repo as the canary for privacy/security work, this one as the working tree of record.

## Layout

```
navigation/android/app            app module
navigation/android/frontend-ui    shared UI library (dev.warp.stream)
navigation/android/gradle/wrapper checked-in Gradle wrapper
```

## Commands

```bash
./navigation/android/gradlew -p navigation/android :app:compileDevDebugSources :app:compileNavigationDebugSources
# test modules (see secure-mesh-navigation README for the full suite):
./navigation/android/gradlew -p navigation/android :app:testFossPreviewUnitTest :app:testPlayPreviewUnitTest
```

## Rules

- JDK 21; checked-in Gradle wrapper. FOSS and Play flavors; Android Auto sources isolated under `app/src/play`.
- FOSS builds exclude GMS/Firebase/Play Billing; tracking/analytics off by default; HTTPS-only preview/release.
- Map default is Leaflet/OSM; Google APIs stay key-guarded and optional.
- Privacy: location reads are consent-scoped; never broadcast the raw fix outside the request scope.
- Do not re-add the obsolete `launch_android_builds.sh` behavior (it forced tracking/analytics on).