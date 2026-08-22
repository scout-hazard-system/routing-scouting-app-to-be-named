# Scout LLM Set
Versioned local Ollama model set used by scanner + navigation assistant flows with deterministic single-client behavior.

## Current baseline models
- `scout-core1.0.7` — nav assistant model (`TASK: NAV`, `TASK: CHAT`)
- `scout-vet1.0.8` — scanner model (`TASK: ALERT`, `TASK: INTEL`, `TASK: VET`)
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
1. ALERT decision (`scout-vet1.0.8`)
2. optional VET gate (`scout-vet1.0.8`)
3. optional INTEL JSON extraction (`scout-vet1.0.8`)
4. optional NAV guidance line (`scout-core1.0.7`)
5. optional route/traffic chat JSON (`scout-core1.0.7`)
6. selector rerank uses `scout-rank` when enabled

## Fallback behavior
If scout models are not installed, client logic falls back to `llama3.1` prompt-mode behavior to avoid runtime hard failures.

## Operational guidance
- Keep model version bumps explicit and intentional.
- Re-run evaluation after any Modelfile prompt edit.
- Preserve strict output contracts (especially ALERT/INTEL/VET) to prevent downstream parser drift.
- Prefer evidence-first prompts and low-variance decoding for reduced hallucination risk in single-client driving sessions.
