#!/usr/bin/env python3
"""Evaluation harness for the proprietary "scout" LLM set.

Runs the labeled transcripts in eval_cases.json against the unified
task-routed scout-core model (or overrides) via llm_set_client and reports:
- alert decision accuracy, false positives, false negatives (per group)
- location echo compliance in ALERT sentences
- intel JSON validity plus per-field expected-substring recall
- latency stats per model

Usage:
  python3 llm/build/eval_llm_set.py                 # full eval
  python3 llm/build/eval_llm_set.py --alert-only
  python3 llm/build/eval_llm_set.py --intel-only
  python3 llm/build/eval_llm_set.py --alert-model qwen3:8b   # compare baselines
  python3 llm/build/eval_llm_set.py --json /tmp/report.json  # machine-readable report

Exit code 0 when alert accuracy >= --threshold (default 0.9), else 1.
"""

import argparse
import json
import os
import statistics
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
_LLM_ROOT = os.path.dirname(_HERE)
_CLIENT_DIR = os.path.join(_LLM_ROOT, "client")
_REPO_ROOT = os.path.dirname(_LLM_ROOT)
for _p in (_CLIENT_DIR, _REPO_ROOT):
    if _p not in sys.path:
        sys.path.insert(0, _p)

import llm_set_client  # noqa: E402

CASES_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "eval_cases.json")


def load_cases(path):
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)["cases"]


def eval_alert(cases, model, timeout_seconds):
    results = []
    for case in cases:
        started = time.time()
        out = llm_set_client.query_alert(
            case["transcript"], timeout_seconds=timeout_seconds, model=model
        )
        elapsed = time.time() - started
        response = (out.get("response") or "").strip()
        is_alert = response.upper().startswith("ALERT")
        expected = bool(case.get("alert"))
        echo_expected = case.get("location_echo") or []
        echo_hits = [frag for frag in echo_expected if frag.lower() in response.lower()]
        echo_ok = (not expected) or (not echo_expected) or (len(echo_hits) == len(echo_expected))
        results.append(
            {
                "id": case["id"],
                "group": case.get("group", ""),
                "expected_alert": expected,
                "got_alert": is_alert,
                "decision_ok": is_alert == expected,
                "echo_expected": echo_expected,
                "echo_ok": echo_ok,
                "response": response[:200],
                "latency_s": round(elapsed, 2),
                "model": out.get("model"),
                "used_fallback": out.get("used_fallback"),
                "error": out.get("error"),
            }
        )
    return results


def eval_intel(cases, model, timeout_seconds):
    results = []
    for case in cases:
        expectations = case.get("intel")
        if not expectations:
            continue
        started = time.time()
        out = llm_set_client.query_intel(
            case["transcript"], timeout_seconds=timeout_seconds, model=model
        )
        elapsed = time.time() - started
        intel = out.get("intel")
        checks = {}
        if intel is None:
            ok = False
        else:
            ok = True
            call_types_any = expectations.get("call_types_any")
            if call_types_any:
                got = [str(v).lower() for v in intel.get("call_types", [])]
                checks["call_types"] = any(want in got for want in call_types_any)
            expected_priority = expectations.get("priority")
            if expected_priority:
                checks["priority"] = intel.get("priority") == expected_priority
            for field in ("codes", "units", "locations", "pois"):
                wants = expectations.get(field)
                if not wants:
                    continue
                joined = " | ".join(str(v).lower() for v in intel.get(field, []))
                checks[field] = all(want.lower() in joined for want in wants)
            ok = all(checks.values()) if checks else True
        results.append(
            {
                "id": case["id"],
                "group": case.get("group", ""),
                "json_valid": intel is not None,
                "checks": checks,
                "ok": ok,
                "intel": intel,
                "latency_s": round(elapsed, 2),
                "model": out.get("model"),
                "used_fallback": out.get("used_fallback"),
                "parse_error": out.get("parse_error"),
                "error": out.get("error"),
            }
        )
    return results


def latency_stats(latencies):
    if not latencies:
        return {}
    ordered = sorted(latencies)
    return {
        "mean_s": round(statistics.mean(ordered), 2),
        "p50_s": round(ordered[len(ordered) // 2], 2),
        "max_s": round(ordered[-1], 2),
    }


def summarize_alert(results):
    total = len(results)
    correct = sum(1 for r in results if r["decision_ok"])
    false_pos = [r["id"] for r in results if r["got_alert"] and not r["expected_alert"]]
    false_neg = [r["id"] for r in results if not r["got_alert"] and r["expected_alert"]]
    echo_checked = [r for r in results if r["expected_alert"] and r["echo_expected"]]
    echo_ok = sum(1 for r in echo_checked if r["echo_ok"] and r["decision_ok"])
    groups = {}
    for r in results:
        bucket = groups.setdefault(r["group"], {"total": 0, "correct": 0})
        bucket["total"] += 1
        bucket["correct"] += 1 if r["decision_ok"] else 0
    return {
        "total": total,
        "correct": correct,
        "accuracy": round(correct / total, 3) if total else 0.0,
        "false_positives": false_pos,
        "false_negatives": false_neg,
        "location_echo_checked": len(echo_checked),
        "location_echo_ok": echo_ok,
        "by_group": groups,
        "latency": latency_stats([r["latency_s"] for r in results]),
    }


def summarize_intel(results):
    total = len(results)
    valid = sum(1 for r in results if r["json_valid"])
    ok = sum(1 for r in results if r["ok"])
    failed = [r["id"] for r in results if not r["ok"]]
    return {
        "total": total,
        "json_valid": valid,
        "all_checks_ok": ok,
        "check_pass_rate": round(ok / total, 3) if total else 0.0,
        "failed_cases": failed,
        "latency": latency_stats([r["latency_s"] for r in results]),
    }


def main():
    parser = argparse.ArgumentParser(description="Evaluate the scout LLM set")
    parser.add_argument("--cases", default=CASES_PATH)
    parser.add_argument("--alert-only", action="store_true")
    parser.add_argument("--intel-only", action="store_true")
    parser.add_argument("--alert-model", default=llm_set_client.ALERT_MODEL)
    parser.add_argument("--intel-model", default=llm_set_client.INTEL_MODEL)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--threshold", type=float, default=0.9)
    parser.add_argument("--json", dest="json_path", default="", help="Write full report JSON here")
    args = parser.parse_args()

    cases = load_cases(args.cases)
    report = {"cases": len(cases), "alert": None, "intel": None}

    if not args.intel_only:
        print(f"== alert-task eval ({args.alert_model}) over {len(cases)} cases ==")
        alert_results = eval_alert(cases, args.alert_model, args.timeout)
        summary = summarize_alert(alert_results)
        report["alert"] = {"summary": summary, "results": alert_results}
        print(json.dumps(summary, indent=2))
        for r in alert_results:
            if not r["decision_ok"] or not r["echo_ok"]:
                print(
                    f"  FAIL {r['id']} [{r['group']}] expected_alert={r['expected_alert']} "
                    f"got={r['got_alert']} echo_ok={r['echo_ok']} -> {r['response'][:110]}"
                )

    if not args.alert_only:
        intel_cases = [c for c in cases if c.get("intel")]
        print(f"== intel-task eval ({args.intel_model}) over {len(intel_cases)} cases ==")
        intel_results = eval_intel(cases, args.intel_model, args.timeout)
        summary = summarize_intel(intel_results)
        report["intel"] = {"summary": summary, "results": intel_results}
        print(json.dumps(summary, indent=2))
        for r in intel_results:
            if not r["ok"]:
                failed_checks = {k: v for k, v in r["checks"].items() if not v}
                print(
                    f"  FAIL {r['id']} [{r['group']}] json_valid={r['json_valid']} "
                    f"failed_checks={failed_checks} intel={json.dumps(r['intel'])[:160]}"
                )

    if args.json_path:
        with open(args.json_path, "w", encoding="utf-8") as fh:
            json.dump(report, fh, indent=2)
        print(f"report written: {args.json_path}")

    alert_summary = (report.get("alert") or {}).get("summary")
    if alert_summary and alert_summary["accuracy"] < args.threshold:
        print(
            f"alert accuracy {alert_summary['accuracy']} below threshold {args.threshold}",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
