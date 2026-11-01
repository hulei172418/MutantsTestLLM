"""Run ChatTester's compile, JUnit, and feedback-repair phase for a batch."""

from __future__ import print_function

import argparse
import json
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path

from run_feedback_pipeline import run_feedback


ADAPTER_DIR = Path(__file__).resolve().parent
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from failure_classification import classify_pipeline_failure  # noqa: E402
from summarize_token_usage import summarize_run  # noqa: E402
from token_accounting import aggregate_token_usage  # noqa: E402
from llm_gateway import load_config  # noqa: E402


def _now():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _read_jsonl(path):
    records = []
    with Path(path).open("r", encoding="utf-8") as stream:
        for line in stream:
            if line.strip():
                records.append(json.loads(line))
    return records


def _write_json(path, value):
    Path(path).write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def _write_jsonl(path, records):
    Path(path).write_text(
        "".join(json.dumps(item, ensure_ascii=False) + "\n" for item in records),
        encoding="utf-8",
    )


def _aggregate_tokens(results):
    records = []
    for item in results:
        report = Path(item["run_dir"]) / "token_usage_calls.jsonl"
        if not report.is_file():
            continue
        records.extend(_read_jsonl(report))
    return aggregate_token_usage(records)


def _worker_count(requested, task_count):
    requested = int(requested or 0)
    value = (
        requested
        if requested > 0
        else int(load_config(require_api_key=False)["thread_count"])
    )
    if value < 1:
        raise ValueError("worker count must be at least 1")
    return min(value, max(1, int(task_count)))


def _feedback_target(item, baseline_name):
    index = int(item["index"])
    result = {
        "index": index,
        "project": item.get("project"),
        "class_name": item.get("class_name"),
        "method_signature": item.get("method_signature"),
        "run_dir": item["run_dir"],
        "started_at": _now(),
    }
    if item.get("status") != "SUCCEEDED":
        result.update({"status": "SKIPPED_GENERATION_FAILED", "finished_at": _now()})
        return result
    print(
        "[{0}] Feedback target {1}: {2}#{3}".format(
            _now(), index, item.get("class_name"), item.get("method_signature")
        ),
        flush=True,
    )
    try:
        # Each target keeps its paper-defined internal repair sequence. Only
        # independent targets are concurrent.
        feedback = run_feedback(item["run_dir"], baseline_name)
        result.update(
            {
                "status": feedback["state"],
                "resumed": bool(feedback.get("resumed")),
            }
        )
        final = feedback.get("final") or {}
        if final:
            result["project_artifact_root"] = final.get("project_artifact_root")
            result["final_source_root"] = final.get("final_source_root")
            result["final_classes_root"] = final.get("final_classes_root")
    except Exception as error:
        result.update(
            {
                "status": "FAILED",
                "error_type": type(error).__name__,
                "error_message": str(error),
            }
        )
        state_path = Path(item["run_dir"]) / "state.json"
        try:
            existing = json.loads(state_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            existing = {}
        _write_json(
            state_path,
            {
                "state": classify_pipeline_failure(error, existing.get("state")),
                "updated_at": _now(),
                "error_type": type(error).__name__,
                "error_message": str(error),
            },
        )
    try:
        token_summary = summarize_run(item["run_dir"])
        result["token_totals"] = token_summary["totals"]
    except Exception as error:
        result["token_summary_error"] = "{0}: {1}".format(
            type(error).__name__, error
        )
    result["finished_at"] = _now()
    return result


def run_batch_feedback(
    batch_dir,
    selected_indices=None,
    continue_on_error=True,
    baseline_name="ChatTester",
    workers=0,
):
    batch_dir = Path(batch_dir).resolve()
    generation_results = batch_dir / "batch_results.jsonl"
    if not generation_results.is_file():
        raise FileNotFoundError("Missing batch results: {0}".format(generation_results))

    selected = set(selected_indices or [])
    source_records = _read_jsonl(generation_results)
    tasks = []
    for item in source_records:
        index = int(item["index"])
        if selected and index not in selected:
            continue
        tasks.append(item)
    worker_count = _worker_count(workers, len(tasks))
    if not continue_on_error and worker_count > 1:
        raise ValueError("--stop-on-error requires --workers 1")

    results = []
    if worker_count == 1:
        for item in tasks:
            result = _feedback_target(item, baseline_name)
            results.append(result)
            results.sort(key=lambda value: value["index"])
            _write_jsonl(batch_dir / "feedback_results.jsonl", results)
            if result["status"] == "FAILED" and not continue_on_error:
                raise RuntimeError(result["error_message"])
    else:
        with ThreadPoolExecutor(max_workers=worker_count) as executor:
            futures = {
                executor.submit(_feedback_target, item, baseline_name): int(item["index"])
                for item in tasks
            }
            for future in as_completed(futures):
                results.append(future.result())
                results.sort(key=lambda value: value["index"])
                _write_jsonl(batch_dir / "feedback_results.jsonl", results)

    return summarize_feedback_results(batch_dir, results, worker_count)


def summarize_feedback_results(batch_dir, results, worker_count=0):
    batch_dir = Path(batch_dir).resolve()
    passed = sum(item["status"] == "ORIGINAL_PASSED" for item in results)
    original_failed = sum(item["status"] == "ORIGINAL_FAILED" for item in results)
    original_timeout = sum(item["status"] == "ORIGINAL_TIMEOUT" for item in results)
    limited = sum(item["status"] == "REPAIR_LIMIT_REACHED" for item in results)
    failed = sum(item["status"] == "FAILED" for item in results)
    skipped = sum(item["status"] == "SKIPPED_GENERATION_FAILED" for item in results)
    summary = {
        "schema_version": 1,
        "baseline": "ChatTester-DS-Adapted",
        "batch_dir": str(batch_dir),
        "finished_at": _now(),
        "selected_target_count": len(results),
        "original_passed_count": passed,
        "original_failed_count": original_failed,
        "original_timeout_count": original_timeout,
        "repair_limit_reached_count": limited,
        "failed_count": failed,
        "skipped_generation_failed_count": skipped,
        "worker_count": worker_count,
        "token_totals": _aggregate_tokens(results),
        "results_file": str(batch_dir / "feedback_results.jsonl"),
    }
    _write_json(batch_dir / "feedback_summary.json", summary)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--batch-dir", required=True)
    parser.add_argument(
        "--indices",
        default="",
        help="optional comma-separated target indices; default is every target",
    )
    parser.add_argument("--baseline-name", default="ChatTester")
    parser.add_argument(
        "--workers",
        type=int,
        default=0,
        help="parallel target workers; 0 uses llm.threads from llm.properties",
    )
    parser.add_argument("--stop-on-error", action="store_true")
    parser.add_argument(
        "--summarize-existing",
        action="store_true",
        help="rebuild feedback_summary.json without compiling, executing, or calling the LLM",
    )
    args = parser.parse_args()
    if args.summarize_existing:
        batch_dir = Path(args.batch_dir).resolve()
        results = _read_jsonl(batch_dir / "feedback_results.jsonl")
        previous_summary_path = batch_dir / "feedback_summary.json"
        previous_worker_count = 0
        if previous_summary_path.is_file():
            previous_summary = json.loads(
                previous_summary_path.read_text(encoding="utf-8")
            )
            previous_worker_count = int(previous_summary.get("worker_count") or 0)
        summary = summarize_feedback_results(
            batch_dir, results, worker_count=previous_worker_count
        )
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return
    selected = [
        int(value.strip()) for value in args.indices.split(",") if value.strip()
    ]
    summary = run_batch_feedback(
        args.batch_dir,
        selected_indices=selected,
        continue_on_error=not args.stop_on_error,
        baseline_name=args.baseline_name,
        workers=args.workers,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
