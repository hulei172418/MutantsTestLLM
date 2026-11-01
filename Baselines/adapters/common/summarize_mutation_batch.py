"""Summarize end-to-end mutation effectiveness for one baseline batch.

The executable score keeps the conventional killed/(killed+live) diagnostic.
The end-to-end score additionally counts every unique mapped mutant belonging
to a target whose generated test could not reach mutation execution.
"""

from __future__ import print_function

import argparse
import json
from collections import Counter
from datetime import datetime
from pathlib import Path

from failure_classification import is_model_provider_failure
from mutant_execution import _group_mutant_records, _mutant_uid
from summarize_efficiency import summarize_batch as summarize_efficiency_batch


INFRASTRUCTURE_FAILURE_STATES = {
    "ENV_SETUP_FAILURE",
    "BUILD_FAILURE",
    "MUTATION_INFRASTRUCTURE_FAILED",
    "DATA_OR_TOOL_MISMATCH",
    "MODEL_PROVIDER_FAILED",
}

EXPERIMENT_FAILURE_STATES = {
    "PIPELINE_FAILED",
}


def _now():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


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


def _effective_state(state, target_dir=None):
    """Classify legacy generic states without rewriting their raw evidence."""
    recorded = str(state.get("state") or "")
    error_message = str(state.get("error_message") or "")
    if recorded in {
        "PIPELINE_FAILED",
        "API_OR_PARSE_FAILED",
        "COMPILE_EXECUTE_OR_REPAIR_FAILED",
    } and is_model_provider_failure(error_message):
        return "MODEL_PROVIDER_FAILED"
    # Early MutGen wrappers used PIPELINE_FAILED for two observable Baseline
    # outcomes. Preserve the raw state, but recover the scoreable outcome from
    # its specific error and retained candidate-validation artifacts.
    if recorded == "PIPELINE_FAILED" and error_message.startswith(
        "MutGen response has no valid new_tests YAML:"
    ):
        return "MODEL_OUTPUT_INVALID"
    if (
        recorded == "PIPELINE_FAILED"
        and state.get("error_type") in {"ValueError", "ModelOutputInvalidError"}
        and error_message == "Unbalanced braces in generated Java"
    ):
        return "MODEL_OUTPUT_INVALID"
    if recorded == "DATA_OR_TOOL_MISMATCH" and target_dir is not None:
        metadata_path = Path(target_dir) / "metadata.json"
        try:
            metadata = _read_json(metadata_path)
        except (OSError, ValueError, TypeError):
            metadata = {}
        if str(metadata.get("baseline") or "").startswith("EvoSuite-"):
            class_name = str(metadata.get("class_name") or "")
            generation_root = Path(target_dir).parent.parent / "class_generations"
            for generation_path in generation_root.glob("*/generation.json"):
                try:
                    generation = _read_json(generation_path)
                except (OSError, ValueError, TypeError):
                    continue
                if (
                    str(generation.get("class_name") or "") == class_name
                    and bool(generation.get("timeout"))
                ):
                    return "GENERATOR_TIMEOUT"
    if (
        recorded == "PIPELINE_FAILED"
        and state.get("error_type") == "KeyError"
        and "accepted_test_count" in error_message
        and target_dir is not None
    ):
        accepted_files = sorted(
            (Path(target_dir) / "rounds").glob("*/accepted_tests.json")
        )
        if accepted_files:
            try:
                if all(not _read_json(path) for path in accepted_files):
                    return "NO_EXECUTABLE_CANDIDATE"
            except (OSError, ValueError, TypeError):
                pass
    return recorded


def _execution_results(target_dir):
    latest = target_dir / "mutant_execution" / "latest.json"
    if not latest.is_file():
        return {}, None
    summary = _read_json(latest)
    results_path = Path(summary["results_jsonl"])
    rows = _read_jsonl(results_path) if results_path.is_file() else []
    grouped = {}
    for row in rows:
        grouped.setdefault(row["mutant_uid"], row)
    return grouped, summary


def summarize_batch(batch_dir, output_path=None, allow_incomplete=False):
    batch_dir = Path(batch_dir).resolve()
    targets_dir = batch_dir / "targets"
    if not targets_dir.is_dir():
        raise FileNotFoundError("Missing batch targets directory: {0}".format(targets_dir))

    details = []
    target_summaries = []
    incomplete_passed_targets = []
    infrastructure_failure_targets = []
    experiment_failure_targets = []
    summarized_target_names = []
    for target_dir in sorted(path for path in targets_dir.iterdir() if path.is_dir()):
        state_path = target_dir / "state.json"
        records_path = target_dir / "source_records.jsonl"
        if not state_path.is_file() or not records_path.is_file():
            continue
        state = _read_json(state_path)
        recorded_state = state.get("state")
        target_state = _effective_state(state, target_dir)
        if target_state in INFRASTRUCTURE_FAILURE_STATES:
            infrastructure_failure_targets.append(target_dir.name)
        if target_state in EXPERIMENT_FAILURE_STATES:
            experiment_failure_targets.append(target_dir.name)
        source_rows = _read_jsonl(records_path)
        summarized_target_names.append(target_dir.name)
        physical_groups = _group_mutant_records(source_rows)
        execution_rows, execution_summary = _execution_results(target_dir)
        if target_state == "ORIGINAL_PASSED" and (
            execution_summary is None or not execution_rows
        ):
            incomplete_passed_targets.append(target_dir.name)
        target_counts = Counter()

        for group in physical_groups:
            representative = group[0]
            mutant_uid = _mutant_uid(representative)
            executed = execution_rows.get(mutant_uid)
            if executed is not None:
                status = str(executed.get("status") or "EXECUTION_ERROR")
                killed = status.startswith("KILLED")
                reason = "executed"
            elif target_state in INFRASTRUCTURE_FAILURE_STATES:
                status = "NOT_EXECUTED_INFRASTRUCTURE_{0}".format(target_state)
                killed = False
                reason = "evaluation_infrastructure_failure"
            elif target_state in EXPERIMENT_FAILURE_STATES:
                status = "NOT_EXECUTED_EXPERIMENT_{0}".format(target_state)
                killed = False
                reason = "non_scorable_experiment_failure"
            elif target_state != "ORIGINAL_PASSED":
                status = "NOT_EXECUTED_{0}".format(
                    str(target_state or "UNKNOWN").upper()
                )
                killed = False
                reason = "invalid_generated_test"
            else:
                status = "NOT_EXECUTED_MISSING_RESULT"
                killed = False
                reason = "missing_mutation_result"

            target_counts[status] += 1
            details.append(
                {
                    "target": target_dir.name,
                    # Preserve the original mutant_total.xlsx audit columns in
                    # the batch-level view as well as the low-level executor.
                    "operator": representative.get("operator"),
                    "line": representative.get("line"),
                    "method": representative.get("method"),
                    "class": representative.get("class"),
                    "class_f": representative.get("class_f"),
                    "mutation_statement": representative.get("mutation_statement"),
                    "package": representative.get("package"),
                    "project": representative.get("project"),
                    "file_path": representative.get("file_path"),
                    "original_graph_path": representative.get("original_graph_path"),
                    "mutant_graph_path": representative.get("mutant_graph_path"),
                    "equivalent": representative.get("equivalent"),
                    "class_name": representative.get("class_name")
                    or representative.get("class"),
                    "method_signature": representative.get("method_signature")
                    or representative.get("method"),
                    "target_state": target_state,
                    "recorded_target_state": recorded_state,
                    "mutant_uid": mutant_uid,
                    "source_record_count": len(group),
                    "status": status,
                    "killed": killed,
                    "reason": reason,
                }
            )

        target_summaries.append(
            {
                "target": target_dir.name,
                "state": target_state,
                "recorded_state": recorded_state,
                "source_record_count": len(source_rows),
                "unique_mutant_count": len(physical_groups),
                "mutation_execution_id": (
                    execution_summary.get("execution_id")
                    if execution_summary is not None
                    else None
                ),
                "status_counts": dict(sorted(target_counts.items())),
            }
        )

    metadata_path = batch_dir / "batch_metadata.json"
    expected_target_count = None
    missing_or_invalid_targets = []
    if metadata_path.is_file():
        metadata = _read_json(metadata_path)
        expected_target_count = int(metadata.get("selected_method_count") or 0)
        if expected_target_count > 0:
            expected_names = {
                "{0:03d}".format(index)
                for index in range(1, expected_target_count + 1)
            }
            missing_or_invalid_targets = sorted(
                expected_names.difference(summarized_target_names)
            )

    if (incomplete_passed_targets or missing_or_invalid_targets) and not allow_incomplete:
        raise RuntimeError(
            "Mutation batch is incomplete: ORIGINAL_PASSED targets without usable "
            "mutation results: [{0}]; missing/invalid target records: [{1}]. Run or "
            "repair these targets, or use "
            "--allow-incomplete only for a diagnostic (non-formal) summary.".format(
                ", ".join(incomplete_passed_targets),
                ", ".join(missing_or_invalid_targets),
            )
        )

    status_counts = Counter(row["status"] for row in details)
    killed = sum(count for status, count in status_counts.items() if status.startswith("KILLED"))
    live = status_counts.get("LIVE", 0)
    invalid_test_not_executed = sum(
        count
        for status, count in status_counts.items()
        if status.startswith("NOT_EXECUTED_")
        and status != "NOT_EXECUTED_MISSING_RESULT"
        and not status.startswith("NOT_EXECUTED_INFRASTRUCTURE_")
        and not status.startswith("NOT_EXECUTED_EXPERIMENT_")
    )
    other_not_executed_or_error = len(details) - killed - live - invalid_test_not_executed
    executable_denominator = killed + live
    # Baseline-caused invalid tests lower the end-to-end score. Evaluation
    # infrastructure errors remain separately reported and do not penalize a
    # generation method because they are not evidence that its test survived.
    end_to_end_denominator = killed + live + invalid_test_not_executed

    output_path = (
        Path(output_path).resolve()
        if output_path
        else batch_dir / "mutation_batch_summary.json"
    )
    details_path = output_path.with_name("mutation_batch_results.jsonl")
    summary = {
        "schema_version": 1,
        "created_at": _now(),
        "batch_dir": str(batch_dir),
        "target_count": len(target_summaries),
        "source_record_count": sum(item["source_record_count"] for item in target_summaries),
        "total_unique_mapped_mutants": sum(item["unique_mutant_count"] for item in target_summaries),
        "killed": killed,
        "live": live,
        "not_executed_invalid_test": invalid_test_not_executed,
        "other_not_executed_or_error": other_not_executed_or_error,
        "expected_target_count": expected_target_count,
        "missing_or_invalid_targets": missing_or_invalid_targets,
        "incomplete_passed_targets": incomplete_passed_targets,
        "infrastructure_failure_targets": infrastructure_failure_targets,
        "experiment_failure_targets": experiment_failure_targets,
        "formal_score_valid": (
            not incomplete_passed_targets
            and not missing_or_invalid_targets
            and not infrastructure_failure_targets
            and not experiment_failure_targets
        ),
        "executable_denominator": executable_denominator,
        "executable_ms": (
            float(killed) / executable_denominator if executable_denominator else None
        ),
        "end_to_end_denominator": end_to_end_denominator,
        "end_to_end_ms": (
            float(killed) / end_to_end_denominator if end_to_end_denominator else None
        ),
        "status_counts": dict(sorted(status_counts.items())),
        "targets": target_summaries,
        "details_file": str(details_path),
    }
    _write_jsonl(details_path, details)
    _write_json(output_path, summary)
    efficiency = summarize_efficiency_batch(batch_dir)
    summary["efficiency_summary_file"] = str(
        batch_dir / "efficiency_summary.json"
    )
    summary["efficiency"] = {
        "paper_time_columns": efficiency.get("paper_time_columns"),
        "llm_usage": efficiency.get("llm_usage"),
        "execution_counts": efficiency.get("execution_counts"),
    }
    _write_json(output_path, summary)
    batch_summary_path = batch_dir / "batch_summary.json"
    if output_path == batch_dir / "mutation_batch_summary.json" and batch_summary_path.is_file():
        batch_summary = _read_json(batch_summary_path)
        batch_summary["mutation_summary"] = summary
        batch_summary["formal_score_valid"] = summary["formal_score_valid"]
        _write_json(batch_summary_path, batch_summary)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--batch-dir", required=True)
    parser.add_argument("--output", default=None)
    parser.add_argument(
        "--allow-incomplete",
        action="store_true",
        help="Write a diagnostic summary even when passed targets lack mutation results.",
    )
    args = parser.parse_args()
    print(
        json.dumps(
            summarize_batch(
                args.batch_dir,
                output_path=args.output,
                allow_incomplete=args.allow_incomplete,
            ),
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
