# Scout Agent / Model / Harness Finalization Plan

**Date:** 2026-09-12
**Status:** Plan (companion PR for the scout_crew orchestration work; points at files in `routing-scouting-app-to-be-named` and `scout_crew`)

This PR finalizes the remaining changes to the **agents**, **models**, and **harnesses** that were shaped by the headless agent-box suite (PR #11) and the orchestrated-manager work (scout_crew PR #2). It explains the strengths of the different model options and the alternatives considered, based on the 2026 local-model research summarized in [§7](#7-sources).

Scope decisions locked with the operator:
- Split roles stay: manager (reasoning) + narrow specialists (non-reasoning).
- Every agent box runs its **own sharding backend** (map shards served locally on `:18080`), not just the hub.
- Agentic tools must stay **non-reasoning, low temperature, non-persona**.
- PR merge order = order they come out; no topological fuss.

---

## 1. Current state

Landing stack (all local/mesh, Qwen3 lineage only):

| Area | State | Where |
|---|---|---|
| Blackboard on map-server hub | PR #10 `scout/blackboard-on-mapserver` (open) | `map_server_setup/`, blackboard on hub `:8765` |
| Headless Debian trixie agent box | PR #11 `debian-agent-box` (open) | `agent_box_setup_trixie/` + `stack/` harness tuning |
| Orchestrated manager | scout_crew PR #2 `manager-agentic-tools` (open) | `crew.py` `SCOUT_ORCHESTRATED`, `tools/specialist_tools.py` |
| Mesh lock to Tailscale IP set | merged (scout_crew PR #1) | `config/`, status surface |

Today two modes exist in scout_crew:
- **Sequential (legacy/dev):** seven agents, one task each.
- **Orchestrated:** only `local_manager` + `dev_specialist` run; alert/intel/vet/rank/core are reached as **manager tools**.

The orchestrated mode is the production shape. The sequential DAG stays for evaluation and contract regression.

---

## 2. Final agent changes (`scout_crew`)

### 2.1 Invariants already enforced (do not loosen)
- **Non-reasoning:** agentic tools bind the `/no_think` specialist tags; the manager is the only reasoning stage. `specialist_tools.py` builds prompts with `role="custom"` + `source="orchestrated-manager"` and `make_llm(role_key, temperature=0.0, ...)`.
- **Low temperature:** every tool pins `temperature=0.0`; a manager cannot pass `temperature` through tool args.
- **Non-persona:** each tool substitutes a contract-only `SPECIALIST_ROLES[role]["system"]` (no role/backstory framing).
- **Never write from tools:** specialists return raw answers; the manager synthesizes and writes per blackboard ACL.

### 2.2 Remaining agent changes to land
1. **Make orchestrated the default.** Gate is `SCOUT_ORCHESTRATED` (default `0`). Flip the default to `1` in `vehicle_stack.env` + CLI after the eval path stabilizes; keep `SCOUT_ORCHESTRATED=0` for the seven-agent regression harness.
2. **Pin the synthesis contract.** `manager_synthesis_task` currently writes `output/local_brief.json` with a free-text description. Add `output_pydantic=ManagerBrief` + a guardrail that requires the contract keys present (matches `core_specialist` keys: `nav_line, chat, alert, vet, intel, channels, manager_notes, open_task_items`). This is what makes the manager's JSON machine-readable by the frontend.
3. **Align temperatures with the roster table (3.3):** manager 0.1, dev 0.2, all specialist tools 0.0 (already true in `specialist_tools.py`); `core` agent drops 0.1 → 0.0 to match its tool form. Use `Ollama` `top_p`/`top_k` shaping (current `top_p 0.85` in vet Modelfile) for variance control without raising temperature.
4. **Alias/roster cleanup** (`local_llms.py` `ROLE_MODEL_PREFS`): the `vet` prefs list `scout-vet1.0.8/1.0.7` and the build README names `scout-core1.0.7` while `ROLE_MODEL_PREFS` pins `scout-core1.0.5` and `scout-vet1.0.6`. Pick **one canonical tag per role**, alias the rest so `scout <...> status` reflects deployed reality. Same for `scout-hermes-hc1.0.0` vs `1.1.0` vs `-64k` (keep `-64k` variants only if the 64k context is exercised — see 3.4).
5. **Structured output for specialist tasks** that are still agent-shaped in eval mode: `Task(output_pydantic=...)` for intel/rank so parser drift at the contract boundary becomes a pydantic validation error instead of a silent mis-parse. (Tool form already returns raw text; the manager parse stays on the manager side.)
6. **Dev specialist stays an agent, not a tool.** `dev_specialist` (admin, `scout-dev`) is the only non-tool non-manager agent. It already has `output_file="output/dev_brief.md"` and its own task — keep it out of the agentic-tool set so the "tools are contract executors" rule stays clean.

---

## 3. Model plan

### 3.1 Model landscape researched (2026 local models)

From the searches in §7 — strengths at a glance:

| Model | Size / arch | RAM (approx) | Strength | Notes |
|---|---|---|---|---|
| **Qwen3 8B** (`qwen3:8b`) | dense | ~6–8 GB | current default; small-role workhorse; Apache-2.0 | the seed of every specialist/agent today |
| **Qwen3.8 27B** | dense | ~24 GB | best overall local; long context (262k); strong agentic/tool use | first-choice manager-brain upgrade when the box has the VRAM |
| **Qwen3.6 27B / 35B-A3B** | dense / MoE | ~24 GB / ~18 GB | agentic coding + thinking-preservation upgrades | 35B-A3B = ~3B active, much faster than dense 27B |
| **Gemma 4 26B-A4B** | MoE | ~15 GB | best reasoning per RAM (89% AIME-2026 territory); Apache-2.0 | strongest *non-Qwen* reasoning candidate — evaluated, not adopted (lineage rule) |
| **Qwen2.5-Coder 7B** | dense | ~5 GB | best coding per size | candidate only if `scout-dev` gets heavy code tasks |
| **Phi-4-mini** | dense | ~2.5 GB | CPU-only option | not in the Qwen lineage; listed for the light tier only |
| **Gemma 4 E2B / E4B** | dense | ~2–4 GB | best small always-on | same license caveat as above |
| **Nemotron 3.5 Lightning** (30B-A3B) | MoE | ~fits an RTX 5090-class card | built for always-on agents, 1M ctx | **excluded**: OpenMDW-1.1 license + non-Qwen lineage (no adoption) |

Qwen3 `/thought` semantics: soft switch is turn-level (`/no_think` / `/think`), hard switch is `enable_thinking`. The current Modelfiles already force `/no_think` on the last user turn of every specialist — that is the same deterministic effect as a hard-off, and the plan keeps it.

Upstream sampling guidance for Qwen3 says thinking ≈ temp 0.6 / top-p 0.95, non-thinking ≈ temp 0.7 / top-p 0.8. **We deliberately override to 0.0 for the specialist contracts** (deterministic JSON/decision output wins over natural-language variance). Where more variance is wanted, shape with `top_k`/`top_p`, never by raising temperature on contract roles.

Ollama structured output (`format` JSON-schema operand) is now supported for JSON-schema constrained generation — this is the tool we use for rank/intel/core determinism without parser drift.

### 3.2 Why commit to Qwen3-only

- All current Modelfiles (`llm/*/Modelfile.*`) build `FROM qwen3:8b`; fallbacks in `local_llms.py` are Qwen3-only by rule (never Llama).
- Cross-peer consistency: specialists run on multiple mesh peers; a single lineage keeps contract behavior uniform and lets a peer serve another user's crews with identical tags.
- Apache-2.0 across the family keeps the deployment path clean.
- The strongest *non-Qwen* local options were reviewed (Gemma 4, Nemotron 3.5 Lightning) and are rejected on license (Nemotron OpenMDW-1.1) and/or drift risk — documented in §7 so the decision is repeatable.

### 3.3 Target roster (role → model) and why

| Role | Current | Target | Reasoning |
|---|---|---|---|
| `manager` (hermes brain) | `scout-hermes-hc1.1.0` (FROM `qwen3:8b`, thinking on) | **`scout-hermes-hc2.0` on `qwen3.8:27b`** if ≥24 GB VRAM; else stay `qwen3:8b`; else `qwen3.6:35b-a3b` (~18 GB) | only reasoning stage; 27B dense is the best-overall 2026 local pick; 35B-A3B is the faster MoE fallback |
| `dev` | `scout-dev` (FROM `scout-core1.0.5`) | same, or `qwen2.5-coder:7b` fork if code-heavy | debug/process upkeep; coder 7B best-per-size if its tasks grow codey |
| `alert` / `vet` | `scout-alert`, `scout-vet1.0.6` | `qwen3:8b` no-think (current) | contract-only one-line decisions; bigger models waste latency |
| `intel` | `scout-intel` | same base; add JSON-schema `format` | one JSON object contract; deterministic by construction |
| `rank` | `scout-rank` | same base; add JSON-schema `format` | `{"ranked":[...],"top_id":"..."}` — never invent candidates |
| `core` | `scout-core1.0.5` | same base; temp 0.0 | driver package builder; lowest variance that parses |
| **embedding** (blackboard RAG) | none | **`qwen3-embedding:0.6b`** (fallback `nomic-embed-text`) | 0.6B, 32k ctx, adjustable dims (MRL 32–1024), Apache-2.0; best quality-per-VRAM, ~1.5 GB — see §4.3 |

Why specialists stay on the 8B no-think tier: they are one-shot narrow contracts (max_tokens 512–2048), latency and token budget of the shard-serving box matter more than scale, and the plan's determinism wins come from `format` + `/no_think` + temp 0, not from a bigger dense model.

### 3.4 Build / integration changes (`llm/`)

1. Add a hermes-hc2.0 build path that accepts a base model override (`FROM qwen3.8:27b` or `qwen3.6:35b-a3b` — one env var, e.g. `HERMES_BASE_MODEL`), so the same `build_hermes_hc.sh` flow works with today's 8B base and the upgrade path without branching scripts.
2. Decide on the `-64k` hermes variants: `num_ctx` 4096–8192 suffices for a single driver session + transcript window. If 64k is not exercised, drop the `-64k` tags to shrink roster surface.
3. Add JSON-schema `format` to the `rank`, `intel`, and `core` Modelfiles (Ollama `format` operand) and confirm the change against `eval_llm_set.py --threshold 0.9`.
4. Keep `/no_think` forced-template behavior as the canonical no-think mechanism (language-native, peer-consistent); do not fork `Qwen3-NoThink` community weight files for the mesh.

---

## 4. Harness changes (`routing`)

### 4.1 Runtime / env
- Headless trixie box: CLI-only, no GUI; `verify_agent_box.sh` gates on mesh up + Ollama tags present.
- Python bootstrap: no system `uv`/venv/sudo on the box → use user-site `pip install crewai==1.15.21` and the `stack/commands/*.sh` python-resolution fallbacks already landed in PR #11 (`which python3` → `~/.local/bin` → lazy venv). Freeze this in `bootstrap_agent_box.sh` so every box ends bit-identical.
- `SCOUT_HEADLESS` stays the toggle that skips GUI/desktop wiring (`vehicle_stack.env`).

### 4.2 Mesh + sharding backend
- Every agent box serves its **own shard backend on `:18080`**; the hub keeps the blackboard (`:8765`) and is just the source for shard sync.
- `install_services.sh --with-shards <user@host[:port]>` pulls MVT shards to `~/.scanner_stream/map_cache/shards/` (+ text roots `vlm_text_map_shards/`, `vlm_text_map_shards_chunked/`) via `map_server_setup/new/sync_shards.sh`; `MAP_SHARD_STATE` (default `AZ`) selects the shard set; `MAP_STATE`, `MAP_CACHE_DIR`, `REMOTE_REPO_ROOT`, `SYNC_TEXT_SHARDS`, `MIRROR`, `VERBOSE` control the sync. Requires passwordless SSH (`./ssh_setup.sh send-key`).
- SCOUT maps `SCOUT_MAP_BASE_URL` → local `http://127.0.0.1:18080` (or hub for first boot before shards land); peer IPs stay mesh-only (`scoutwg0`, `10.66.0.0/16`, hub default `10.66.2.3`).

### 4.3 Blackboard embedding / RAG
- Add a vector store to the blackboard (Postgres `vector` or in-file ANN) keyed by dispatch/transcript summaries so manager synthesis can retrieve **relevant prior intel** instead of only the current transcript.
- Embedding model: **`qwen3-embedding:0.6b`** default; `nomic-embed-text` (137M, 8k ctx, most-tested with RAG frameworks) as the low-footprint fallback; add `qwen3-reranker-0.6b` later only if recall actually lags (reranking lifts quality far more than swapping bi-encoders).
- **Dimension decision is a one-time index choice:** qwen3-embedding supports MRL truncation (32–1024). Pick **1024** for headroom, or **512** to halve index size — write it into `store` config so all boxes agree. Do not silently mix dimensions.

### 4.4 Observability
- `CREWAI_TRACING_ENABLED=true` is the default on the box (`vehicle_stack.env`) so every local run can print an ephemeral trace link without an account.

### 4.5 Model-roster surface
- Extend `scout <...> status` output (already has `role_assignments`, `leftover_llama_installs`) with the target-roster diff: a table of role / in-use model / planned model so a mismatch between hubs and the plan (3.3) is visible at a glance.

---

## 5. Hardware sizing guide (for the next/new agent box)

| Budget (single box) | Manager brain | Specialists | Workable? |
|---|---|---|---|
| ≥ 28 GB VRAM | `qwen3.8:27b` hermes-hc2.0 | 8B no-think (all) | yes — full plan |
| ~ 16–24 GB VRAM | `qwen3.6:35b-a3b` MoE | 8B no-think | yes — fastest runtime |
| ~ 12 GB VRAM | `qwen3:8b` hermes-hc1.1 | 8B no-think | yes — MVP tier |
| ~ <12 GB | `qwen3:8b`, shrink `rank`/`intel` ctx | 4B–1.7B no-think subset | MVP only; keep all tags present on at least one peer |

Rule: every peer must still **install the full tag set** (or the agreed subset) so crews started on any host resolve identically (`resolve_role_model` falls to big-list order, not to whichever peer is closest).

---

## 6. Rollout order

1. Land the open PRs in the order they come out (per operator): map-server/blackboard (PR #10/#8/#7-family), agent-box suite (PR #11), orchestrated manager (scout_crew PR #2).
2. This plan's code follow-ups, split into two resulting PRs:
   - **A (agents):** orchestrated default + `output_pydantic` synthesis + guardrail + temp alignment + alias/roster cleanup + status diff table → `scout_crew`.
   - **B (models/harness):** hermes-hc2.0 build override, `format` on rank/intel/core, 64k tag decision, embedding store + dim lock, shard-backend wiring confirmation → `routing`.
3. Rebuild + eval (`build_llm_set.sh`, `eval_llm_set.py --threshold 0.9`) before enabling orchestrated-by-default.
4. Smoke on one trixie box end-to-end (mesh up → shards synced → dark crew → light crew) with traces on.

---

## 7. Sources

Researched 2026-09-12 (web):

- **Qwen3 thinking/non-thinking semantics + sampling:** Qwen3 HF card (thinking temp ≈0.6/top-p 0.95; non-thinking 0.7/0.8; `enable_thinking` hard switch, `/think` `/no_think` soft switch; Instruct-2507 variants); Qwen3-NoThink community weights exist.
- **Qwen3.8 27B / Qwen3.6 27B & 35B-A3B and tool-call/agentic positioning:** Ollama library (`qwen3.8`, `qwen3.6`), Chutes & llm-stats/OpenRouter comparison listings (context 262k, tool-capable).
- **Gemma 4 family (E2B/E4B/12B/26B-A4B/31B, Apache-2.0), 26B-A4B best-reasoning-per-RAM (~15 GB), ~89% AIME-2026 tier:** Gemma 4 wiki + Ollama `gemma4` library. Evaluated as an alternative; not adopted (lineage + peer-consistency rule).
- **Nemotron 3.5 Lightning (30B-A3B MoE, always-on agents, 1M ctx, OpenMDW-1.1 license):** NVIDIA release coverage, Aug 2026. **Excluded on license + non-Qwen lineage.**
- **Qwen2.5-Coder 7B (best coding per size) and Phi-4-mini (CPU-only ~2.5 GB):** 2026 local-model roundups (CanIRun/llm-stats model list, Ollama search).
- **Embedding models:** Ollama embedding library + D-Central 2026 local-embedding roundup: `qwen3-embedding:0.6b` = best quality-per-size (MTEB-eng-v2 ~70.7, Apache-2.0, MRL 32–1024 dims, 32k ctx); `nomic-embed-text` = simplest/most-tested (137M, 8k ctx, 768 dims); `bge-m3` = multilingual dense+sparse hybrid; reranking stage outweighs bi-encoder swaps. Benchmarks are only comparable within the same MTEB benchmark family.
- **Ollama JSON-schema structured output (`format`):** Ollama API support for constrained JSON generation.