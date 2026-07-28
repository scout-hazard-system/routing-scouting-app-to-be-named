# Scout LLM set

The scout set is the scanner stack's proprietary family of local LLMs. Each model is a
purpose-built Ollama model derived from the same `llama3.1` (8B, Q4) base that already
powers the Whisper -> Ollama scanner pipeline, with the system prompt, decoding
parameters, and output contract baked into the model itself. Because all three models
share the base weights, building the set costs no meaningful extra disk space.

## Models

### scout-alert — enforcement alert decision
- **Input**: one raw scanner transcript (sent as the user prompt; no system prompt needed).
- **Output**: exactly one line — either a one-sentence warning starting with `ALERT:`
  (location wording repeated verbatim) or the single word `IGNORE`.
- **Params**: `temperature 0`, `num_predict 64`, `num_ctx 2048` — deterministic and fast.
- **Used by**: `pipeline.py` step 4 (every captured transcript).

### scout-intel — structured dispatch intel extraction
- **Input**: one raw scanner transcript. Always called with Ollama's `"format": "json"`.
- **Output**: a single JSON object, all keys always present:

```json
{
  "call_types": ["traffic_stop", "speed_enforcement", "pursuit", "welfare_check",
                 "suspicious_activity", "accident", "units_coordination", "unclassified"],
  "priority": "high|medium|low|unknown",
  "codes": ["10-38", "code 4"],
  "units": ["23", "Adam-12"],
  "locations": ["mile marker 85", "I-5 northbound"],
  "pois": ["Shell gas station"],
  "summary": "one short sentence, or empty string"
}
```

- **Params**: `temperature 0`, `num_predict 320`, `num_ctx 2048`.
- **Used by**: `pipeline.py` step 4b — only on alert-worthy transcripts (LLM alert, hard
  rule alert, or soft-alert fallback), so it adds no latency to ordinary chatter.
  Extracted `locations`/`pois` are merged (deduped) into the pipeline's regex-based
  location notes and attached to `alert_triggered` events as `llm_intel`.

### scout-rank — channel selector reranking
- **Input**: the JSON payload built by `channel_selector.py:ollama_rerank` (goal,
  location context, candidate channels, instructions).
- **Output**: compact JSON only: `{"ranking":[{"id":"...","score":0.0-1.0,"reason":"..."}]}`.
- **Params**: `temperature 0`, `num_predict 512`, `num_ctx 4096` (candidate lists are larger).
- **Used by**: `channel_selector.py` when reranking is enabled (see below).

## Requirements
- Ollama running locally (default `http://localhost:11434`).
- Base model pulled: `ollama pull llama3.1`.

## Building the set

```bash
./llm_set/build_llm_set.sh
```

The script runs `ollama create <model> -f Modelfile.<model>` for all three models, lists
the installed `scout-*` models, and then runs live smoke tests (an enforcement transcript
that must ALERT, benign chatter that must IGNORE, an intel extraction, and a rank
payload). Environment switches:
- `SKIP_SMOKE=1` — build only, skip the smoke tests.
- `OLLAMA_BIN=/path/to/ollama` — non-default ollama binary.

Rebuild any time a Modelfile changes; `ollama create` is idempotent and only rewrites
the prompt/parameter layers.

## Runtime integration

### pipeline.py flags
- `--llm-set` / `--no-llm-set` (default: on) — route the alert decision through
  `scout-alert` and enable intel extraction. When off, the legacy inline `llama3.1`
  prompt path is used.
- `--llm-alert-model` (default: `scout-alert`) — alert decision model override.
- `--llm-intel` / `--no-llm-intel` (default: on) — structured intel extraction on
  alert-worthy transcripts.
- `--llm-intel-model` (default: `scout-intel`) — intel model override.
- `--llm-intel-timeout` (default: `10.0` seconds) — intel request timeout.
- Existing `--ollama-timeout` / `--ollama-retries` still govern the alert call.

Event stream additions:
- `pipeline_ready` gains an `llm_set` object (`ollama_up`, `base_model_installed`,
  per-model availability, `complete`).
- `alert_decision` and all `alert_triggered` kinds gain `llm_intel` (the parsed intel
  object, or `null` when extraction was skipped or failed).
- With `--alert-debug`, `[INTEL]` log lines show the extraction result or failure reason.

### Channel selector
Point the existing rerank options at the scout model — no code changes needed:

```bash
python3 channel_selector.py --channels-file ... --use-ollama-rerank --ollama-model scout-rank
```

For the Java backend's selector helper, set `BROADCASTIFY_SELECTOR_OLLAMA_MODEL=scout-rank`
(and `BROADCASTIFY_SELECTOR_USE_OLLAMA_RERANK=true`).

### Backend status endpoint
`GET /api/platform/llm/status` (ScannerBackendServer) reports Ollama reachability and
per-model availability:

```json
{
  "status": "ok",
  "ollama_up": true,
  "tags_url": "http://localhost:11434/api/tags",
  "base_model": "llama3.1",
  "base_model_installed": true,
  "models": {"scout-alert": true, "scout-intel": true, "scout-rank": true},
  "complete": true
}
```

Env vars: `OLLAMA_TAGS_URL` (default `http://localhost:11434/api/tags`),
`LLM_BASE_MODEL` (default `llama3.1`).

## Client module: scanner_llm_set.py
Python API used by the pipeline; also usable standalone:
- `llm_set_status(force_refresh=False)` — availability summary (same shape as the
  backend endpoint's core fields).
- `query_alert(transcript, timeout_seconds, retries, model)` — returns
  `{response, status_code, error, raw, attempts, model, used_fallback}`; `response`
  defaults to `IGNORE` on any failure so the pipeline's rule-based promotion still runs.
- `query_intel(transcript, timeout_seconds, retries, model)` — adds
  `{intel, parse_error}`; `intel` is the normalized schema above or `None`.

CLI for quick checks:

```bash
python3 scanner_llm_set.py status
python3 scanner_llm_set.py alert "Unit 23 running radar at mile marker 212"
python3 scanner_llm_set.py intel "10-38 at the Shell gas station on Commercial Avenue"
```

## Fallback behavior
- Model availability is checked against `/api/tags` and cached for 5 minutes.
- If a scout model is missing, calls transparently fall back to the base `llama3.1`
  model with an equivalent inline system prompt (`used_fallback: true` in results).
- If Ollama is unreachable, `query_alert` returns `IGNORE` with error diagnostics and
  the pipeline continues on its rule-based alert path; the backend status endpoint
  still answers 200 with `ollama_up: false`.

## Performance notes
- First call after idle loads the shared base weights (a few seconds). Warm calls:
  scout-alert typically answers in well under a second; scout-intel takes longer in
  proportion to its JSON output. Intel runs only on alert-worthy chunks, so the
  12-second capture cadence is unaffected in the common case.
- All three models being layers over one base means only one model is resident in
  memory at a time and switches are cheap.

## Customizing
- Edit the relevant `Modelfile.scout-*` (system prompt or `PARAMETER` lines) and re-run
  `build_llm_set.sh`.
- To try a different base, change the `FROM` line (and `LLM_BASE_MODEL` /
  `scanner_llm_set.BASE_MODEL` if you want fallback and status reporting to match).
- Keep outputs contract-compatible: `ALERT:`/`IGNORE` single line for scout-alert, the
  fixed JSON schema for scout-intel, and the `ranking` JSON for scout-rank.

## Licensing
The scout models are prompt/parameter layers over Meta's Llama 3.1 weights and remain
subject to the Llama 3.1 Community License. Everything runs locally; no transcript data
leaves the machine.
