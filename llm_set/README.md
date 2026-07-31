# Scout LLM Set
Versioned local Ollama model set used by scanner pipeline alerting, intel extraction, vetting, and channel ranking.

## Current baseline models
- `scout-core1.0.3` — unified `TASK: ALERT`, `TASK: INTEL`, `TASK: NAV`
- `scout-vet1.0.4` — second-stage `TASK: VET` gate (`VET_PASS`/`VET_FAIL`)
- `scout-rank` — Broadcastify candidate reranking

Base model:
- `llama3.1` (fallback when scout artifacts are unavailable)

## Build
```bash
llm_set/build_llm_set.sh
```

Skip smoke tests:
```bash
SKIP_SMOKE=1 llm_set/build_llm_set.sh
```

## Evaluation
```bash
cop_pipeline/bin/python3 llm_set/eval_llm_set.py --json /tmp/scout_eval.json --threshold 0.9
```

Useful modes:
- `--alert-only`
- `--intel-only`
- `--alert-model ...`
- `--intel-model ...`

## Runtime integration
Used by:
- `pipeline.py`
- `llm_set_client.py`

Flow per transcript:
1. ALERT decision (`scout-core1.0.3`)
2. optional VET gate (`scout-vet1.0.4`)
3. optional INTEL JSON extraction (`scout-core1.0.3`)
4. optional NAV guidance line (`scout-core1.0.3`)
5. selector rerank uses `scout-rank` when enabled

## Fallback behavior
If scout models are not installed, client logic falls back to `llama3.1` prompt-mode behavior to avoid runtime hard failures.

## Operational guidance
- Keep model version bumps explicit and intentional.
- Re-run evaluation after any Modelfile prompt edit.
- Preserve strict output contracts (especially ALERT/INTEL/VET) to prevent downstream parser drift.
