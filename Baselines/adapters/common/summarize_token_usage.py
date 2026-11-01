"""Backfill per-call token accounting and write run-level token reports."""

from __future__ import print_function

import argparse
import json
import os
from collections import defaultdict
from datetime import datetime
from pathlib import Path

from token_accounting import aggregate_token_usage, normalize_usage


BASELINES_DIR = Path(__file__).resolve().parents[2]
RUNS_DIR = BASELINES_DIR / "runs"


def latest_manual_run_with_calls():
    candidates = [
        path
        for path in RUNS_DIR.glob("*_chattester_manual_*")
        if path.is_dir() and (path / "calls").is_dir()
    ]
    if not candidates:
        raise RuntimeError(
            "No manual run with model calls found. Generate a test first."
        )
    return sorted(candidates, key=lambda value: value.name)[-1]


def _read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    os.replace(str(temporary), str(path))


def summarize_run(run_dir, update_call_logs=True):
    run_dir = Path(run_dir).resolve()
    calls_dir = run_dir / "calls"
    if not calls_dir.is_dir():
        raise RuntimeError("Run has no calls directory: {0}".format(run_dir))

    rows = []
    grouped = defaultdict(list)
    usage_by_call_id = {}
    for call_dir in sorted(path for path in calls_dir.iterdir() if path.is_dir()):
        call_path = call_dir / "call.json"
        response_path = call_dir / "response.json"
        if not call_path.is_file() or not response_path.is_file():
            continue
        call = _read_json(call_path)
        response = _read_json(response_path)
        raw_usage = response.get("usage") or (
            (response.get("raw_response") or {}).get("usage") or {}
        )
        local_cache_hit = bool(response.get("cache_hit", call.get("cache_hit", False)))
        normalized = normalize_usage(raw_usage, local_cache_hit)
        call["token_usage"] = normalized
        response["token_usage"] = normalized
        if update_call_logs:
            _write_json(call_path, call)
            _write_json(response_path, response)

        row = {
            "run_id": run_dir.name,
            "call_id": call.get("call_id", call_dir.name),
            "baseline": call.get("baseline", response.get("baseline", "")),
            "stage": call.get("stage", response.get("stage", "")),
            "status": call.get("status", ""),
            "provider": call.get("provider", response.get("provider", "")),
            "model": call.get("model", response.get("model", "")),
            "attempts": (
                0
                if bool(response.get("cache_hit", call.get("cache_hit", False)))
                else int(call.get("attempts") or response.get("attempts") or 0)
            ),
            "elapsed_millis": int(
                call.get("logical_call_elapsed_millis")
                or call.get("elapsed_millis")
                or response.get("elapsed_millis")
                or 0
            ),
        }
        row.update(normalized)
        rows.append(row)
        usage_by_call_id[row["call_id"]] = normalized
        grouped[(row["baseline"], row["stage"])].append(normalized)

    trace_backfilled_rows = 0
    trace_file = run_dir / "call_mutant_trace.jsonl"
    if update_call_logs and trace_file.is_file():
        updated_trace = []
        with trace_file.open("r", encoding="utf-8") as stream:
            for line in stream:
                if not line.strip():
                    continue
                trace = json.loads(line)
                usage = usage_by_call_id.get(trace.get("call_id"))
                if usage is not None:
                    trace["schema_version"] = max(2, int(trace.get("schema_version", 1)))
                    trace["token_usage"] = usage
                    trace_backfilled_rows += 1
                updated_trace.append(trace)
        temporary_trace = trace_file.with_suffix(".jsonl.tmp")
        temporary_trace.write_text(
            "".join(
                json.dumps(trace, ensure_ascii=False) + "\n"
                for trace in updated_trace
            ),
            encoding="utf-8",
        )
        os.replace(str(temporary_trace), str(trace_file))

    summary = {
        "schema_version": 1,
        "run_id": run_dir.name,
        "generated_at": datetime.now().astimezone().isoformat(
            timespec="milliseconds"
        ),
        "definition": {
            "cache_hit_rate": (
                "cache_read_input_tokens / "
                "(cache_read_input_tokens + uncached_input_tokens)"
            ),
            "local_response_cache": (
                "A local response-cache hit performs no API request; current-call "
                "token counters are zero and historical response usage is not recounted."
            ),
            "unreported_cache_write": (
                "cache_write_input_tokens is 0 with "
                "cache_write_tokens_reported=false when the provider omits the field."
            ),
        },
        "totals": aggregate_token_usage([row for row in rows]),
        "by_baseline_stage": [
            {
                "baseline": baseline,
                "stage": stage,
                "totals": aggregate_token_usage(records),
            }
            for (baseline, stage), records in sorted(grouped.items())
        ],
        "call_report": str(run_dir / "token_usage_calls.jsonl"),
        "trace_token_usage_backfilled_rows": trace_backfilled_rows,
    }
    summary["totals"]["provider_api_request_attempt_count"] = sum(
        int(row.get("attempts") or 0) for row in rows
    )
    summary["totals"]["llm_elapsed_millis"] = sum(
        int(row.get("elapsed_millis") or 0) for row in rows
    )
    calls_report = run_dir / "token_usage_calls.jsonl"
    calls_report.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
        encoding="utf-8",
    )
    _write_json(run_dir / "token_usage_summary.json", summary)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--run-dir",
        default=None,
        help="default: latest *_chattester_manual_* run containing calls",
    )
    parser.add_argument(
        "--no-update-call-logs",
        action="store_true",
        help="write aggregate reports without backfilling call/response JSON",
    )
    args = parser.parse_args()
    run_dir = args.run_dir or latest_manual_run_with_calls()
    summary = summarize_run(run_dir, update_call_logs=not args.no_update_call_logs)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
