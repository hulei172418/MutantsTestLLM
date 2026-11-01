"""Run A3Test end to end for the first N unique focal methods in parallel."""

from __future__ import print_function

import argparse
import json
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path

from protocol import BASELINE
from prepare_run import DEFAULT_MANIFEST, RUNS_DIR, prepare
from run_pipeline import ModelOutputTruncatedError, run_pipeline


ADAPTER_DIR = Path(__file__).resolve().parent
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from llm_gateway import load_config
from failure_classification import classify_pipeline_failure
from mutant_execution import _group_mutant_records
from summarize_efficiency import summarize_batch as summarize_efficiency_batch
from summarize_mutation_batch import summarize_batch
from summarize_token_usage import summarize_run
from target_context import load_method_groups


def _now():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _stamp():
    now = datetime.now().astimezone()
    return now.strftime("%Y%m%d%H%M%S") + "{0:03d}".format(now.microsecond // 1000)


def _write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _write_jsonl(path, values):
    Path(path).write_text("".join(json.dumps(value, ensure_ascii=False) + "\n" for value in values), encoding="utf-8")


def default_batch_dir():
    stamp = _stamp()
    for sequence in range(1, 1000):
        candidate = RUNS_DIR / "{0}_a3test_batch_{1:03d}".format(stamp, sequence)
        if not candidate.exists():
            return candidate
    raise RuntimeError("Unable to allocate A3Test batch ID")


def _run_target(index, total, records, manifests_dir, targets_dir, mutant_workers):
    method_manifest = manifests_dir / "{0:03d}.jsonl".format(index)
    run_dir = targets_dir / "{0:03d}".format(index)
    _write_jsonl(method_manifest, records)
    item = {
        "index": index, "project": records[0]["project"],
        "class_name": records[0]["class_name"],
        "method_signature": records[0]["method_signature"],
        "run_dir": str(run_dir), "started_at": _now(),
    }
    print("[{0}] A3Test target {1}/{2}: {3}#{4}".format(
        _now(), index, total, item["class_name"], item["method_signature"]
    ), flush=True)
    prepared = False
    try:
        prepare(method_manifest, run_dir)
        prepared = True
        result = run_pipeline(run_dir, mutant_workers=mutant_workers)
        succeeded = result.get("state") == "COMPLETED"
        item.update({
            "status": "SUCCEEDED" if succeeded else "FAILED",
            "terminal_state": result.get("state"),
            "accepted_test_count": int(result.get("accepted_test_count") or 0),
        })
        if not succeeded:
            item["error_type"] = result.get("state") or "PIPELINE_TERMINAL_FAILURE"
            item["error_message"] = "A3Test ended without an original-passing single test"
    except Exception as error:
        item.update({"status": "FAILED", "error_type": type(error).__name__, "error_message": str(error)})
        if not prepared:
            run_dir.mkdir(parents=True, exist_ok=True)
            _write_jsonl(run_dir / "source_records.jsonl", records)
            _write_json(run_dir / "state.json", {
                "state": "DATA_OR_TOOL_MISMATCH", "updated_at": _now(),
                "error_type": type(error).__name__, "error_message": str(error),
            })
        else:
            state_path = run_dir / "state.json"
            existing = {}
            if state_path.is_file():
                try:
                    existing = json.loads(state_path.read_text(encoding="utf-8"))
                except (OSError, ValueError):
                    pass
            failure = classify_pipeline_failure(error, existing.get("state"))
            _write_json(state_path, {
                "state": failure, "updated_at": _now(),
                "error_type": type(error).__name__, "error_message": str(error),
            })
    if (run_dir / "calls").is_dir():
        try:
            item["token_totals"] = summarize_run(run_dir)["totals"]
        except Exception as error:
            item["token_summary_error"] = "{0}: {1}".format(type(error).__name__, error)
    item["finished_at"] = _now()
    return item


def run_batch(manifest=DEFAULT_MANIFEST, limit=5, batch_dir=None, workers=0, mutant_workers=0):
    manifest = Path(manifest).resolve()
    batch_dir = Path(batch_dir or default_batch_dir()).resolve()
    if batch_dir.exists():
        raise FileExistsError("Batch directory already exists: {0}".format(batch_dir))
    groups = load_method_groups(manifest, limit=limit)
    unresolved = [key for key, records in groups.items() if not records[0].get("source_path")]
    if unresolved:
        raise RuntimeError("Selected A3Test targets contain unresolved source paths: {0}".format(unresolved[:3]))
    configured = int(load_config(require_api_key=False)["thread_count"])
    worker_count = min(len(groups), int(workers) if int(workers) > 0 else configured)
    selection = []
    for index, records in enumerate(groups.values(), 1):
        selection.append({
            "index": index, "project": records[0]["project"],
            "class_name": records[0]["class_name"],
            "method_signature": records[0]["method_signature"],
            "source_record_count": len(records),
            "unique_physical_mutant_count": len(_group_mutant_records(records)),
        })
    selected_source_records = sum(item["source_record_count"] for item in selection)
    selected_unique_mutants = sum(item["unique_physical_mutant_count"] for item in selection)
    print("[{0}] A3Test selection: methods={1}, source_records={2}, unique_physical_mutants={3}".format(
        _now(), len(selection), selected_source_records, selected_unique_mutants
    ), flush=True)
    manifests_dir = batch_dir / "manifests"
    targets_dir = batch_dir / "targets"
    manifests_dir.mkdir(parents=True)
    targets_dir.mkdir()
    started_at = _now()
    _write_json(batch_dir / "batch_metadata.json", {
        "schema_version": 1, "baseline": BASELINE, "started_at": started_at,
        "manifest": str(manifest),
        "selection_policy": "first_N_unique_methods_then_all_rows_for_each_selected_method",
        "native_generation_policy": "one_best_test_per_focal_method_one_iteration",
        "selected_method_count": len(groups),
        "selected_source_record_count": selected_source_records,
        "selected_unique_physical_mutant_count": selected_unique_mutants,
        "generation_workers": worker_count, "mutant_workers_per_target": mutant_workers,
        "targets": selection,
    })
    tasks = list(enumerate(groups.values(), 1))
    results = []
    if worker_count == 1:
        for index, records in tasks:
            results.append(_run_target(index, len(tasks), records, manifests_dir, targets_dir, mutant_workers))
            _write_jsonl(batch_dir / "batch_results.jsonl", results)
    else:
        with ThreadPoolExecutor(max_workers=worker_count) as executor:
            futures = {
                executor.submit(_run_target, index, len(tasks), records, manifests_dir, targets_dir, mutant_workers): index
                for index, records in tasks
            }
            for future in as_completed(futures):
                results.append(future.result())
                results.sort(key=lambda item: item["index"])
                _write_jsonl(batch_dir / "batch_results.jsonl", results)
    mutation_summary = summarize_batch(batch_dir)
    summary = {
        "schema_version": 1, "baseline": BASELINE, "batch_dir": str(batch_dir),
        "finished_at": _now(), "selected_method_count": len(results),
        "succeeded_method_count": sum(item["status"] == "SUCCEEDED" for item in results),
        "failed_method_count": sum(item["status"] != "SUCCEEDED" for item in results),
        "mutation_summary": mutation_summary,
    }
    _write_json(batch_dir / "batch_summary.json", summary)
    summary["efficiency_summary"] = summarize_efficiency_batch(batch_dir)
    _write_json(batch_dir / "batch_summary.json", summary)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST))
    parser.add_argument("--limit", type=int, default=5)
    parser.add_argument("--batch-dir", default=None)
    parser.add_argument("--workers", type=int, default=0)
    parser.add_argument("--mutant-workers", type=int, default=0)
    args = parser.parse_args()
    print(json.dumps(run_batch(args.manifest, args.limit, args.batch_dir, args.workers, args.mutant_workers), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
