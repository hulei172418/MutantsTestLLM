"""Run MuTAP end to end for the first N unique focal methods in parallel."""

from __future__ import print_function

import argparse
import json
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path

from prepare_run import DEFAULT_MANIFEST, RUNS_DIR, prepare
from run_pipeline import ModelOutputTruncatedError, run_pipeline


ADAPTER_DIR = Path(__file__).resolve().parent
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from llm_gateway import load_config
from failure_classification import classify_pipeline_failure
from mutant_execution import _group_mutant_records
from summarize_mutation_batch import summarize_batch
from summarize_efficiency import summarize_batch as summarize_efficiency_batch
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
        candidate = RUNS_DIR / "{0}_mutap_batch_{1:03d}".format(stamp, sequence)
        if not candidate.exists():
            return candidate
    raise RuntimeError("Unable to allocate MuTAP batch ID")


def _run_target(index, total, records, manifests_dir, targets_dir, mutant_workers, survivor_seed):
    method_manifest = manifests_dir / "{0:03d}.jsonl".format(index)
    run_dir = targets_dir / "{0:03d}".format(index)
    _write_jsonl(method_manifest, records)
    item = {
        "index": index, "project": records[0]["project"], "class_name": records[0]["class_name"],
        "method_signature": records[0]["method_signature"], "run_dir": str(run_dir), "started_at": _now(),
    }
    print("[{0}] MuTAP target {1}/{2}: {3}#{4}".format(_now(), index, total, item["class_name"], item["method_signature"]), flush=True)
    prepared = False
    try:
        prepare(method_manifest, run_dir)
        prepared = True
        result = run_pipeline(run_dir, mutant_workers=mutant_workers, survivor_seed=survivor_seed)
        if result.get("state") == "COMPLETED":
            item.update({"status": "SUCCEEDED", "terminal_state": "COMPLETED", "accepted_test_count": result["accepted_test_count"]})
        else:
            item.update({"status": "FAILED", "terminal_state": result.get("state"), "error_type": result.get("state") or "PIPELINE_TERMINAL_FAILURE", "error_message": "MuTAP ended without a final original-passing suite", "accepted_test_count": int(result.get("accepted_test_count") or 0)})
    except Exception as error:
        item.update({"status": "FAILED", "error_type": type(error).__name__, "error_message": str(error)})
        if not prepared:
            run_dir.mkdir(parents=True, exist_ok=True)
            _write_jsonl(run_dir / "source_records.jsonl", records)
            _write_json(run_dir / "state.json", {"state": "DATA_OR_TOOL_MISMATCH", "updated_at": _now(), "error_type": type(error).__name__, "error_message": str(error)})
        elif run_dir.is_dir():
            state = {}
            state_path = run_dir / "state.json"
            if state_path.is_file():
                try:
                    state = json.loads(state_path.read_text(encoding="utf-8"))
                except (OSError, ValueError):
                    pass
            failure = classify_pipeline_failure(error, state.get("state"))
            _write_json(state_path, {"state": failure, "updated_at": _now(), "error_type": type(error).__name__, "error_message": str(error)})
    if (run_dir / "calls").is_dir():
        try:
            item["token_totals"] = summarize_run(run_dir)["totals"]
        except Exception as error:
            item["token_summary_error"] = "{0}: {1}".format(type(error).__name__, error)
    item["finished_at"] = _now()
    return item


def _run_prepared_target(index, total, run_dir, mutant_workers, survivor_seed):
    """Execute one target previously materialized by ``prepare_batch``."""
    run_dir = Path(run_dir).resolve()
    metadata = json.loads((run_dir / "metadata.json").read_text(encoding="utf-8"))
    item = {
        "index": index,
        "project": metadata["project"],
        "class_name": metadata["class_name"],
        "method_signature": metadata["method_signature"],
        "run_dir": str(run_dir),
        "started_at": _now(),
    }
    print(
        "[{0}] MuTAP prepared target {1}/{2}: {3}#{4}".format(
            _now(), index, total, item["class_name"], item["method_signature"]
        ),
        flush=True,
    )
    try:
        result = run_pipeline(
            run_dir, mutant_workers=mutant_workers, survivor_seed=survivor_seed
        )
        if result.get("state") == "COMPLETED":
            item.update(
                {
                    "status": "SUCCEEDED",
                    "terminal_state": "COMPLETED",
                    "accepted_test_count": result["accepted_test_count"],
                }
            )
        else:
            item.update(
                {
                    "status": "FAILED",
                    "terminal_state": result.get("state"),
                    "error_type": result.get("state") or "PIPELINE_TERMINAL_FAILURE",
                    "error_message": "MuTAP ended without a final original-passing suite",
                    "accepted_test_count": int(result.get("accepted_test_count") or 0),
                }
            )
    except Exception as error:
        item.update(
            {
                "status": "FAILED",
                "error_type": type(error).__name__,
                "error_message": str(error),
            }
        )
        state_path = run_dir / "state.json"
        state = {}
        if state_path.is_file():
            try:
                state = json.loads(state_path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                pass
        failure = classify_pipeline_failure(error, state.get("state"))
        _write_json(
            state_path,
            {
                "state": failure,
                "updated_at": _now(),
                "error_type": type(error).__name__,
                "error_message": str(error),
            },
        )
    if (run_dir / "calls").is_dir():
        try:
            item["token_totals"] = summarize_run(run_dir)["totals"]
        except Exception as error:
            item["token_summary_error"] = "{0}: {1}".format(
                type(error).__name__, error
            )
    item["finished_at"] = _now()
    return item


def mark_batch_interrupted(batch_dir, reason="manual interruption"):
    """Make an interrupted F5/CLI batch explicitly ineligible for scoring."""
    metadata_path = Path(batch_dir).resolve() / "batch_metadata.json"
    if not metadata_path.is_file():
        return
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if metadata.get("batch_state") == "COMPLETED":
        return
    metadata.update(
        {
            "batch_state": "INTERRUPTED",
            "interrupted_at": _now(),
            "formal_score_valid": False,
            "interruption_reason": str(reason),
        }
    )
    _write_json(metadata_path, metadata)
    terminal_states = {
        "ORIGINAL_PASSED",
        "ORIGINAL_FAILED",
        "DATA_OR_TOOL_MISMATCH",
        "MODEL_OUTPUT_TRUNCATED",
        "MODEL_PROVIDER_FAILED",
        "MUTATION_INFRASTRUCTURE_FAILED",
        "PIPELINE_FAILED",
    }
    for target in metadata.get("targets") or []:
        state_path = Path(target.get("run_dir") or "") / "state.json"
        if not state_path.is_file():
            continue
        try:
            state = json.loads(state_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if state.get("state") in terminal_states:
            continue
        state.update(
            {
                "state": "INTERRUPTED",
                "updated_at": _now(),
                "next_stage": None,
                "formal_score_valid": False,
                "interruption_reason": str(reason),
            }
        )
        _write_json(state_path, state)


def prepare_batch(manifest=DEFAULT_MANIFEST, limit=5, batch_dir=None):
    """Prepare the first N unique methods and all records belonging to them."""
    manifest = Path(manifest).resolve()
    batch_dir = Path(batch_dir or default_batch_dir()).resolve()
    if batch_dir.exists():
        raise FileExistsError("Batch directory already exists: {0}".format(batch_dir))
    groups = load_method_groups(manifest, limit=limit)
    unresolved = [
        key for key, records in groups.items() if not records[0].get("source_path")
    ]
    if unresolved:
        raise RuntimeError(
            "Selected MuTAP targets contain unresolved source paths: {0}".format(
                unresolved[:3]
            )
        )
    manifests_dir = batch_dir / "manifests"
    targets_dir = batch_dir / "targets"
    manifests_dir.mkdir(parents=True)
    targets_dir.mkdir()
    selection = []
    prepared = []
    for index, records in enumerate(groups.values(), 1):
        method_manifest = manifests_dir / "{0:03d}.jsonl".format(index)
        run_dir = targets_dir / "{0:03d}".format(index)
        _write_jsonl(method_manifest, records)
        selected = {
            "index": index,
            "project": records[0]["project"],
            "class_name": records[0]["class_name"],
            "method_signature": records[0]["method_signature"],
            "source_record_count": len(records),
            "unique_physical_mutant_count": len(_group_mutant_records(records)),
            "run_dir": str(run_dir),
        }
        selection.append(selected)
        try:
            result = prepare(method_manifest, run_dir)
            prepared.append(dict(selected, status="PREPARED", prepare_result=result))
        except Exception as error:
            run_dir.mkdir(parents=True, exist_ok=True)
            _write_jsonl(run_dir / "source_records.jsonl", records)
            _write_json(
                run_dir / "state.json",
                {
                    "state": "DATA_OR_TOOL_MISMATCH",
                    "updated_at": _now(),
                    "error_type": type(error).__name__,
                    "error_message": str(error),
                },
            )
            prepared.append(
                dict(
                    selected,
                    status="FAILED",
                    error_type=type(error).__name__,
                    error_message=str(error),
                )
            )
    selected_source_records = sum(item["source_record_count"] for item in selection)
    selected_unique_mutants = sum(
        item["unique_physical_mutant_count"] for item in selection
    )
    metadata = {
        "schema_version": 1,
        "baseline": "MuTAP-DS-Adapted",
        "batch_state": "PREPARED_API_NOT_CALLED",
        "started_at": _now(),
        "manifest": str(manifest),
        "selection_policy": "first_N_unique_methods_then_all_rows_for_each_selected_method",
        "selected_method_count": len(groups),
        "selected_source_record_count": selected_source_records,
        "selected_unique_physical_mutant_count": selected_unique_mutants,
        "targets": selection,
    }
    _write_json(batch_dir / "batch_metadata.json", metadata)
    _write_jsonl(batch_dir / "prepare_results.jsonl", prepared)
    if any(item["status"] != "PREPARED" for item in prepared):
        raise RuntimeError("One or more MuTAP targets failed during batch preparation")
    return {
        "batch_dir": str(batch_dir),
        "state": "PREPARED_API_NOT_CALLED",
        "selected_method_count": len(groups),
        "selected_source_record_count": selected_source_records,
        "selected_unique_physical_mutant_count": selected_unique_mutants,
    }


def run_prepared_batch(batch_dir, workers=0, mutant_workers=0, survivor_seed=42):
    """Run all targets from a five-method (or other N) prepared batch."""
    batch_dir = Path(batch_dir).resolve()
    metadata_path = batch_dir / "batch_metadata.json"
    if not metadata_path.is_file():
        raise FileNotFoundError("Prepared batch metadata does not exist: {0}".format(metadata_path))
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if metadata.get("batch_state") != "PREPARED_API_NOT_CALLED":
        raise RuntimeError("MuTAP batch is not freshly prepared: {0}".format(metadata.get("batch_state")))
    if (batch_dir / "batch_summary.json").exists():
        raise FileExistsError("Prepared batch has already been summarized: {0}".format(batch_dir))
    targets = metadata.get("targets") or []
    if len(targets) != int(metadata.get("selected_method_count") or 0):
        raise RuntimeError("Prepared MuTAP target count does not match batch metadata")
    configured = int(load_config(require_api_key=False)["thread_count"])
    worker_count = min(
        len(targets), int(workers) if int(workers) > 0 else configured
    )
    metadata.update(
        {
            "batch_state": "RUNNING",
            "execution_started_at": _now(),
            "generation_workers": worker_count,
            "mutant_workers_per_target": mutant_workers,
            "survivor_seed": survivor_seed,
        }
    )
    _write_json(metadata_path, metadata)
    results = []
    if worker_count == 1:
        for target in targets:
            results.append(
                _run_prepared_target(
                    target["index"], len(targets), target["run_dir"],
                    mutant_workers, survivor_seed,
                )
            )
            _write_jsonl(batch_dir / "batch_results.jsonl", results)
    else:
        with ThreadPoolExecutor(max_workers=worker_count) as executor:
            futures = {
                executor.submit(
                    _run_prepared_target,
                    target["index"], len(targets), target["run_dir"],
                    mutant_workers, survivor_seed,
                ): target["index"]
                for target in targets
            }
            for future in as_completed(futures):
                results.append(future.result())
                results.sort(key=lambda item: item["index"])
                _write_jsonl(batch_dir / "batch_results.jsonl", results)
    mutation_summary = summarize_batch(batch_dir)
    summary = {
        "schema_version": 1,
        "baseline": "MuTAP-DS-Adapted",
        "batch_dir": str(batch_dir),
        "finished_at": _now(),
        "selected_method_count": len(results),
        "succeeded_method_count": sum(item["status"] == "SUCCEEDED" for item in results),
        "failed_method_count": sum(item["status"] != "SUCCEEDED" for item in results),
        "mutation_summary": mutation_summary,
    }
    _write_json(batch_dir / "batch_summary.json", summary)
    metadata["batch_state"] = "COMPLETED"
    metadata["execution_finished_at"] = _now()
    _write_json(metadata_path, metadata)
    summary["efficiency_summary"] = summarize_efficiency_batch(batch_dir)
    _write_json(batch_dir / "batch_summary.json", summary)
    return summary


def run_batch(manifest=DEFAULT_MANIFEST, limit=5, batch_dir=None, workers=0, mutant_workers=0, survivor_seed=42):
    manifest = Path(manifest).resolve()
    batch_dir = Path(batch_dir or default_batch_dir()).resolve()
    if batch_dir.exists():
        raise FileExistsError("Batch directory already exists: {0}".format(batch_dir))
    groups = load_method_groups(manifest, limit=limit)
    unresolved = [key for key, records in groups.items() if not records[0].get("source_path")]
    if unresolved:
        raise RuntimeError("Selected MuTAP targets contain unresolved source paths: {0}".format(unresolved[:3]))
    configured = int(load_config(require_api_key=False)["thread_count"])
    worker_count = min(len(groups), int(workers) if int(workers) > 0 else configured)
    selection = []
    for index, records in enumerate(groups.values(), 1):
        selection.append({
            "index": index, "project": records[0]["project"], "class_name": records[0]["class_name"],
            "method_signature": records[0]["method_signature"], "source_record_count": len(records),
            "unique_physical_mutant_count": len(_group_mutant_records(records)),
        })
    selected_source_records = sum(item["source_record_count"] for item in selection)
    selected_unique_mutants = sum(item["unique_physical_mutant_count"] for item in selection)
    manifests_dir = batch_dir / "manifests"
    targets_dir = batch_dir / "targets"
    manifests_dir.mkdir(parents=True)
    targets_dir.mkdir()
    _write_json(batch_dir / "batch_metadata.json", {
        "schema_version": 1, "baseline": "MuTAP-DS-Adapted", "started_at": _now(), "manifest": str(manifest),
        "selection_policy": "first_N_unique_methods_then_all_rows_for_each_selected_method",
        "selected_method_count": len(groups), "selected_source_record_count": selected_source_records,
        "selected_unique_physical_mutant_count": selected_unique_mutants, "generation_workers": worker_count,
        "mutant_workers_per_target": mutant_workers, "survivor_seed": survivor_seed, "targets": selection,
    })
    tasks = list(enumerate(groups.values(), 1))
    results = []
    if worker_count == 1:
        for index, records in tasks:
            results.append(_run_target(index, len(tasks), records, manifests_dir, targets_dir, mutant_workers, survivor_seed))
            _write_jsonl(batch_dir / "batch_results.jsonl", results)
    else:
        with ThreadPoolExecutor(max_workers=worker_count) as executor:
            futures = {executor.submit(_run_target, index, len(tasks), records, manifests_dir, targets_dir, mutant_workers, survivor_seed): index for index, records in tasks}
            for future in as_completed(futures):
                results.append(future.result())
                results.sort(key=lambda item: item["index"])
                _write_jsonl(batch_dir / "batch_results.jsonl", results)
    mutation_summary = summarize_batch(batch_dir)
    summary = {
        "schema_version": 1, "baseline": "MuTAP-DS-Adapted", "batch_dir": str(batch_dir), "finished_at": _now(),
        "selected_method_count": len(results), "succeeded_method_count": sum(item["status"] == "SUCCEEDED" for item in results),
        "failed_method_count": sum(item["status"] != "SUCCEEDED" for item in results), "mutation_summary": mutation_summary,
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
    parser.add_argument("--survivor-seed", type=int, default=42)
    args = parser.parse_args()
    try:
        result = run_batch(
            args.manifest, args.limit, args.batch_dir, args.workers,
            args.mutant_workers, args.survivor_seed,
        )
    except KeyboardInterrupt:
        if args.batch_dir:
            mark_batch_interrupted(args.batch_dir, "one-click batch interrupted from CLI/F5")
        raise
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
