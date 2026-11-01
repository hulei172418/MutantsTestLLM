"""Generate ChatTester tests for the first N unique methods in a manifest.

Each method gets an isolated run directory. The generation prompt contains no
mutant details, while every model call remains linked to all mutant rows for the
selected method through the existing source_records and trace logs.
"""

from __future__ import print_function

import argparse
import json
import re
import sys
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path

from generate_initial_test import run
from prepare_run import DEFAULT_MANIFEST, RUNS_DIR, prepare


ADAPTER_DIR = Path(__file__).resolve().parent
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from summarize_token_usage import summarize_run  # noqa: E402
from token_accounting import aggregate_token_usage  # noqa: E402
from llm_gateway import load_config  # noqa: E402


def _local_timestamp_millis():
    now = datetime.now().astimezone()
    return now.strftime("%Y%m%d%H%M%S") + "{0:03d}".format(
        now.microsecond // 1000
    )


def _local_iso_millis():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _progress(message):
    print("[{0}] {1}".format(_local_iso_millis(), message), flush=True)


def _write_json(path, value):
    Path(path).write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def _write_jsonl(path, records):
    Path(path).write_text(
        "".join(json.dumps(item, ensure_ascii=False) + "\n" for item in records),
        encoding="utf-8",
    )


def _safe_label(value):
    value = re.sub(r"[^A-Za-z0-9_.-]+", "_", str(value)).strip("_.-")
    return (value or "target")[:80]


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


def _generate_target(index, total, key, records, manifests_dir, targets_dir):
    project, class_name, method_signature = key
    label = "{0:03d}".format(index)
    method_manifest = manifests_dir / ("{0:03d}.jsonl".format(index))
    run_dir = targets_dir / label
    _write_jsonl(method_manifest, records)
    item = {
        "index": index,
        "project": project,
        "class_name": class_name,
        "method_signature": method_signature,
        "mutant_count": len(records),
        "run_dir": str(run_dir),
        "started_at": _local_iso_millis(),
    }
    _progress(
        "Target {0}/{1}: {2}#{3} ({4} mutants)".format(
            index, total, class_name, method_signature, len(records)
        )
    )
    try:
        # Calls inside one method remain sequential and author-faithful. Only
        # independent method targets are processed concurrently.
        prepare(method_manifest, run_dir)
        generation = run(run_dir)
        token_summary = summarize_run(run_dir)
        item.update(
            {
                "status": "SUCCEEDED",
                "test_file": generation["test_file"],
                "token_totals": token_summary["totals"],
            }
        )
        _progress(
            "Target {0}/{1} succeeded: {2}".format(
                index, total, generation["test_file"]
            )
        )
    except Exception as error:
        item.update(
            {
                "status": "FAILED",
                "error_type": type(error).__name__,
                "error_message": str(error),
            }
        )
        _progress(
            "Target {0}/{1} failed: {2}: {3}".format(
                index, total, type(error).__name__, error
            )
        )
        calls_dir = run_dir / "calls"
        if calls_dir.is_dir():
            try:
                token_summary = summarize_run(run_dir)
                item["token_totals"] = token_summary["totals"]
            except Exception as summary_error:
                item["token_summary_error"] = "{0}: {1}".format(
                    type(summary_error).__name__, summary_error
                )
    item["finished_at"] = _local_iso_millis()
    return item


def default_batch_dir():
    timestamp = _local_timestamp_millis()
    for sequence in range(1, 1000):
        candidate = RUNS_DIR / (
            "{0}_chattester_batch_{1:03d}".format(timestamp, sequence)
        )
        if not candidate.exists():
            return candidate
    raise RuntimeError("Unable to allocate a batch ID for " + timestamp)


def load_first_method_groups(manifest, limit):
    groups = OrderedDict()
    with Path(manifest).open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, start=1):
            if not line.strip():
                continue
            item = json.loads(line)
            key = (
                item["project"],
                item["class_name"],
                item["method_signature"],
            )
            if key not in groups:
                if len(groups) >= limit:
                    continue
                groups[key] = []
            groups[key].append(item)
    if not groups:
        raise ValueError("Manifest has no target records: {0}".format(manifest))
    return groups


def _aggregate_batch_token_usage(results):
    records = []
    for result in results:
        report = Path(result["run_dir"]) / "token_usage_calls.jsonl"
        if not report.is_file():
            continue
        with report.open("r", encoding="utf-8") as stream:
            for line in stream:
                if line.strip():
                    records.append(json.loads(line))
    return aggregate_token_usage(records)


def summarize_existing_batch(batch_dir):
    """Backfill per-target token reports and recompute an existing batch."""
    batch_dir = Path(batch_dir).resolve()
    results_path = batch_dir / "batch_results.jsonl"
    if not results_path.is_file():
        raise FileNotFoundError("Missing batch results: {0}".format(results_path))
    results = []
    with results_path.open("r", encoding="utf-8") as stream:
        for line in stream:
            if line.strip():
                results.append(json.loads(line))
    for item in results:
        calls_dir = Path(item["run_dir"]) / "calls"
        if not calls_dir.is_dir():
            continue
        try:
            token_summary = summarize_run(item["run_dir"])
            item["token_totals"] = token_summary["totals"]
            item.pop("token_summary_error", None)
        except Exception as error:
            item["token_summary_error"] = "{0}: {1}".format(
                type(error).__name__, error
            )
    _write_jsonl(results_path, results)

    old_summary_path = batch_dir / "batch_summary.json"
    old_summary = (
        json.loads(old_summary_path.read_text(encoding="utf-8"))
        if old_summary_path.is_file()
        else {}
    )
    succeeded = sum(item.get("status") == "SUCCEEDED" for item in results)
    summary = dict(old_summary)
    summary.update(
        {
            "schema_version": 1,
            "baseline": "ChatTester-DS-Adapted",
            "batch_dir": str(batch_dir),
            "resummarized_at": _local_iso_millis(),
            "selected_method_count": len(results),
            "succeeded_method_count": succeeded,
            "failed_method_count": len(results) - succeeded,
            "token_totals": _aggregate_batch_token_usage(results),
            "results_file": str(results_path),
        }
    )
    _write_json(old_summary_path, summary)
    return summary


def run_batch(
    manifest,
    limit=10,
    batch_dir=None,
    continue_on_error=True,
    selected_indices=None,
    workers=0,
):
    manifest = Path(manifest).resolve()
    batch_dir = Path(batch_dir or default_batch_dir()).resolve()
    if batch_dir.exists():
        raise FileExistsError(
            "Batch directory already exists; choose a new path: {0}".format(
                batch_dir
            )
        )
    if limit < 1:
        raise ValueError("limit must be at least 1")

    groups = load_first_method_groups(manifest, limit)
    if selected_indices:
        available = list(groups.items())
        invalid = [value for value in selected_indices if value < 1 or value > len(available)]
        if invalid:
            raise ValueError(
                "selected indices outside 1..{0}: {1}".format(
                    len(available), ",".join(str(value) for value in invalid)
                )
            )
        groups = OrderedDict(available[value - 1] for value in selected_indices)
    unresolved = [key for key, rows in groups.items() if not rows[0].get("source_path")]
    if unresolved:
        preview = "; ".join("|".join(key) for key in unresolved[:3])
        raise RuntimeError(
            "Selected methods have no resolved source_path ({0}/{1}): {2}".format(
                len(unresolved), len(groups), preview
            )
        )

    worker_count = _worker_count(workers, len(groups))
    if not continue_on_error and worker_count > 1:
        raise ValueError("--stop-on-error requires --workers 1")

    manifests_dir = batch_dir / "manifests"
    targets_dir = batch_dir / "targets"
    manifests_dir.mkdir(parents=True)
    targets_dir.mkdir()
    started_at = _local_iso_millis()
    metadata = {
        "schema_version": 1,
        "baseline": "ChatTester-DS-Adapted",
        "started_at": started_at,
        "manifest": str(manifest),
        "requested_method_limit": limit,
        "selected_method_count": len(groups),
        "selected_indices": list(selected_indices or []),
        "continue_on_error": bool(continue_on_error),
        "worker_count": worker_count,
        "worker_source": "--workers" if int(workers or 0) > 0 else "llm.threads",
    }
    _write_json(batch_dir / "batch_metadata.json", metadata)

    results = []
    tasks = [
        (index, key, records)
        for index, (key, records) in enumerate(groups.items(), start=1)
    ]
    if worker_count == 1:
        for index, key, records in tasks:
            item = _generate_target(
                index, len(tasks), key, records, manifests_dir, targets_dir
            )
            results.append(item)
            results.sort(key=lambda value: value["index"])
            _write_jsonl(batch_dir / "batch_results.jsonl", results)
            if item["status"] == "FAILED" and not continue_on_error:
                raise RuntimeError(item["error_message"])
    else:
        with ThreadPoolExecutor(max_workers=worker_count) as executor:
            future_map = {
                executor.submit(
                    _generate_target,
                    index,
                    len(tasks),
                    key,
                    records,
                    manifests_dir,
                    targets_dir,
                ): index
                for index, key, records in tasks
            }
            for future in as_completed(future_map):
                results.append(future.result())
                results.sort(key=lambda value: value["index"])
                _write_jsonl(batch_dir / "batch_results.jsonl", results)

    succeeded = sum(item["status"] == "SUCCEEDED" for item in results)
    failed = len(results) - succeeded
    summary = {
        "schema_version": 1,
        "baseline": "ChatTester-DS-Adapted",
        "batch_dir": str(batch_dir),
        "started_at": started_at,
        "finished_at": _local_iso_millis(),
        "selected_method_count": len(groups),
        "succeeded_method_count": succeeded,
        "failed_method_count": failed,
        "worker_count": worker_count,
        "token_totals": _aggregate_batch_token_usage(results),
        "results_file": str(batch_dir / "batch_results.jsonl"),
    }
    _write_json(batch_dir / "batch_summary.json", summary)
    _progress(
        "Batch completed: succeeded={0}, failed={1}, directory={2}".format(
            succeeded, failed, batch_dir
        )
    )
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST))
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument("--batch-dir", default=None)
    parser.add_argument(
        "--workers",
        type=int,
        default=0,
        help="parallel method workers; 0 uses llm.threads from llm.properties",
    )
    parser.add_argument(
        "--summarize-existing",
        default=None,
        help="recompute reports for an existing batch without calling the model",
    )
    parser.add_argument(
        "--indices",
        default="",
        help="optional comma-separated 1-based positions within the first --limit methods",
    )
    parser.add_argument(
        "--stop-on-error",
        action="store_true",
        help="stop immediately instead of recording the failure and continuing",
    )
    args = parser.parse_args()
    if args.summarize_existing:
        print(
            json.dumps(
                summarize_existing_batch(args.summarize_existing),
                ensure_ascii=False,
                indent=2,
            )
        )
        return
    selected_indices = [
        int(value.strip()) for value in args.indices.split(",") if value.strip()
    ]
    summary = run_batch(
        manifest=args.manifest,
        limit=args.limit,
        batch_dir=args.batch_dir,
        continue_on_error=not args.stop_on_error,
        selected_indices=selected_indices,
        workers=args.workers,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
