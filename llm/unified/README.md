# scout-hermes-hc — unified high-context Scout (reasoning enabled)

**License:** Apache License, Version 2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

Single Ollama model merging ALERT/INTEL/VET/RANK/CORE/DEV/MANAGER with **thinking**.

**Persona:** Project Director — succinct, decisive, confident. Short final answers; no filler.

## Tags

| Tag | Base | Thinking | num_ctx |
|-----|------|----------|---------|
| `scout-hermes-hc1.0.0` | qwen3:8b | **yes** | 100000 |
| `scout-hermes-hc1.0.0-64k` | qwen3:8b | **yes** | 65536 |
| `scout-hermes-hc1.1.0` | qwen3:8b | **yes** | 100000 |
| `scout-hermes-hc1.1.0-64k` | qwen3:8b | **yes** | 65536 |

Hermes floor: **≥ 65536**. Prefer 100k when RAM allows.

## Build

```bash
ollama pull qwen3:8b
bash ~/Desktop/llm/unified/build_hermes_hc.sh
```

## CrewAI routing

`manager`, `dev`, and `core` resolve to `scout-hermes-hc1.0.0` so all reasoning roles share this brain.
Specialists (alert/intel/vet/rank) stay on their small non-thinking models unless overridden.

## Hermes

```yaml
model.default: scout-hermes-hc1.0.0
agent.reasoning_effort: medium   # supported — model has thinking capability
```

```text
base_url: http://127.0.0.1:11434/v1
api_key: ollama
```
