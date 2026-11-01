"""Run HITS slicing, per-slice generation/repair, merge, and evaluation."""

from __future__ import print_function

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path

from protocol import (
    BASELINE,
    MAX_REPAIR_ROUNDS,
    MAX_SLICE_FORMAT_RETRIES,
    REPAIR_SYSTEM_PROMPT,
    SLICING_SYSTEM_PROMPT,
    TEST_SYSTEM_PROMPT,
    build_repair_prompt,
    build_slice_test_prompt,
    build_slicing_prompt,
    extract_java,
    merge_slice_suites,
    normalize_test_source,
    parse_slices,
)


ADAPTER_DIR = Path(__file__).resolve().parent
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from failure_classification import classify_pipeline_failure
from jacoco_coverage import collect_line_coverage
from java_test_pipeline import JavaTestPipeline
from mutant_execution import execute_mutants
from traced_llm_call import generate_traced


class ModelOutputTruncatedError(ValueError):
    pass


def _now():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _write_state(run_dir, state, **values):
    result = {"state": state, "updated_at": _now()}
    result.update(values)
    _write_json(Path(run_dir) / "state.json", result)


def _safe_identifier(value):
    result = re.sub(r"[^A-Za-z0-9_$]", "_", str(value or ""))
    return result if result and not result[0].isdigit() else "T_" + result


def _require_complete(response, stage):
    reason = str(response.get("finish_reason") or "").lower()
    if reason in ("length", "max_tokens"):
        raise ModelOutputTruncatedError(
            "{0} was truncated at max_tokens={1}".format(stage, response.get("max_tokens"))
        )


def _call(run_dir, stage, system_prompt, user_prompt, label, round_number):
    run_dir = Path(run_dir)
    (run_dir / "prompts" / (label + ".system.txt")).write_text(
        system_prompt + "\n", encoding="utf-8"
    )
    (run_dir / "prompts" / (label + ".user.txt")).write_text(
        user_prompt + "\n", encoding="utf-8"
    )
    response = generate_traced(
        BASELINE,
        stage,
        system_prompt,
        user_prompt,
        run_dir,
        response_file=run_dir / "responses" / (label + ".json"),
        round_number=round_number,
        visible_to_model=False,
        visible_file_paths=[],
        use_cache=False,
    )
    _require_complete(response, stage)
    return response


def _log(outcome):
    if not outcome:
        return "No compiler result was produced."
    if not outcome.get("compile", {}).get("compiled"):
        path = outcome.get("compile", {}).get("log_file")
    else:
        path = outcome.get("execution", {}).get("log_file")
    return (
        Path(path).read_text(encoding="utf-8", errors="replace")
        if path and Path(path).is_file()
        else "No detailed compiler/JUnit log was produced."
    )


def _generate_slices(run_dir, context):
    prompt = build_slicing_prompt(context)
    errors = []
    for attempt in range(MAX_SLICE_FORMAT_RETRIES + 1):
        label = "hits_slicing_{0:02d}".format(attempt)
        response = _call(
            run_dir, "hits_slicing", SLICING_SYSTEM_PROMPT, prompt, label, attempt
        )
        try:
            parsed = parse_slices(response.get("content"))
            parsed["format_attempt"] = attempt
            parsed["format_retry_policy"] = (
                "paper requests gradual top-p increase but gives no schedule; "
                "fixed ChatUniTest implementation retries parsing up to three times"
            )
            _write_json(Path(run_dir) / "slices" / "slice.json", parsed)
            return parsed
        except ValueError as error:
            errors.append({"attempt": attempt, "error": str(error)})
    _write_json(Path(run_dir) / "slices" / "format_failures.json", errors)
    raise ValueError("HITS could not obtain valid slice JSON after {0} calls".format(len(errors)))


def _validate_slice(run_dir, pipeline, context, slice_info, slice_number, step):
    test_class = _safe_identifier(
        context["simple_class_name"] + "_HITS_Slice{0:03d}Test".format(slice_number)
    )
    prompt = build_slice_test_prompt(context, slice_info, test_class, slice_number)
    response = _call(
        run_dir,
        "hits_slice_test",
        TEST_SYSTEM_PROMPT,
        prompt,
        "hits_slice_{0:03d}_initial".format(slice_number),
        slice_number,
    )
    current = extract_java(response.get("content"))
    attempts = []
    accepted_source = None
    accepted_outcome = None
    for repair_round in range(MAX_REPAIR_ROUNDS + 1):
        normalized = None
        outcome = None
        normalization_error = None
        try:
            normalized = normalize_test_source(
                current, context.get("package_name") or "", test_class
            )
            step += 1
            outcome = pipeline.compile_and_run(normalized, step)
            passed = bool(
                outcome["compile"]["compiled"] and outcome["execution"]["passed"]
            )
        except Exception as error:
            normalization_error = "{0}: {1}".format(type(error).__name__, error)
            passed = False
        attempt = {
            "repair_round": repair_round,
            "pipeline_step": step if outcome else None,
            "normalized": normalized is not None,
            "normalization_error": normalization_error,
            "compiled": bool(outcome and outcome["compile"]["compiled"]),
            "original_passed": passed,
        }
        attempts.append(attempt)
        attempt_dir = Path(run_dir) / "slices" / "{0:03d}".format(slice_number)
        attempt_dir.mkdir(parents=True, exist_ok=True)
        (attempt_dir / "attempt_{0:02d}.java".format(repair_round)).write_text(
            normalized or current, encoding="utf-8"
        )
        if passed:
            accepted_source = normalized
            accepted_outcome = outcome
            break
        if repair_round >= MAX_REPAIR_ROUNDS:
            break
        error_report = normalization_error or _log(outcome)
        repair_prompt = build_repair_prompt(
            context, slice_info, normalized or current, error_report, test_class
        )
        repair = _call(
            run_dir,
            "hits_test_repair",
            REPAIR_SYSTEM_PROMPT,
            repair_prompt,
            "hits_slice_{0:03d}_repair_{1:02d}".format(slice_number, repair_round + 1),
            repair_round + 1,
        )
        current = extract_java(repair.get("content"))
    result = {
        "slice": slice_number,
        "description": slice_info["desp"],
        "test_class": test_class,
        "accepted": accepted_source is not None,
        "repair_round_count": max(0, len(attempts) - 1),
        "attempts": attempts,
    }
    _write_json(Path(run_dir) / "slices" / "{0:03d}".format(slice_number) / "result.json", result)
    return accepted_source, accepted_outcome, step, result


def _test_count(source):
    return len(re.findall(r"@(?:org\.junit\.)?Test\b", source or ""))


def run_pipeline(run_dir, mutant_workers=0):
    run_dir = Path(run_dir).resolve()
    state = _read_json(run_dir / "state.json")
    if state.get("state") != "PREPARED_API_NOT_CALLED":
        raise RuntimeError("HITS run is not freshly prepared: {0}".format(state))
    context = _read_json(run_dir / "hits_context.json")
    metadata = _read_json(run_dir / "metadata.json")

    _write_state(run_dir, "CALLING_SLICING", api_called=True, next_stage="hits_slicing")
    slicing = _generate_slices(run_dir, context)
    pipeline = JavaTestPipeline(run_dir, "HITS")
    accepted_sources = []
    slice_results = []
    step = 0
    for slice_info in slicing["steps"]:
        number = int(slice_info["index"])
        _write_state(
            run_dir,
            "CALLING_SLICE_TEST",
            api_called=True,
            slice=number,
            next_stage="hits_slice_test",
        )
        source, _, step, result = _validate_slice(
            run_dir, pipeline, context, slice_info, number, step
        )
        slice_results.append(result)
        if source is not None:
            accepted_sources.append(source)

    if not accepted_sources:
        _write_state(
            run_dir,
            "NO_EXECUTABLE_SLICE_TEST",
            api_called=True,
            slice_count=len(slicing["steps"]),
            accepted_slice_count=0,
            next_stage=None,
        )
        result = {
            "state": "NO_EXECUTABLE_SLICE_TEST",
            "run_dir": str(run_dir),
            "slice_count": len(slicing["steps"]),
            "accepted_slice_count": 0,
            "slice_results": slice_results,
        }
        _write_json(run_dir / "hits_result.json", result)
        return result

    suite_class = _safe_identifier(context["simple_class_name"] + "_HITSTest")
    merged = merge_slice_suites(
        accepted_sources, context.get("package_name") or "", suite_class
    )
    (run_dir / "raw_tests" / "merged_final.java").write_text(merged, encoding="utf-8")
    step += 1
    merged_outcome = pipeline.compile_and_run(merged, step)
    if not (
        merged_outcome["compile"]["compiled"]
        and merged_outcome["execution"]["passed"]
    ):
        _write_state(
            run_dir,
            "MERGED_SUITE_FAILED",
            api_called=True,
            slice_count=len(slicing["steps"]),
            accepted_slice_count=len(accepted_sources),
            next_stage=None,
        )
        result = {
            "state": "MERGED_SUITE_FAILED",
            "run_dir": str(run_dir),
            "slice_count": len(slicing["steps"]),
            "accepted_slice_count": len(accepted_sources),
            "slice_results": slice_results,
            "merged_outcome": merged_outcome,
        }
        _write_json(run_dir / "hits_result.json", result)
        return result

    final = pipeline.publish_final(merged_outcome)
    _write_state(
        run_dir,
        "ORIGINAL_PASSED",
        api_called=True,
        slice_count=len(slicing["steps"]),
        accepted_slice_count=len(accepted_sources),
        accepted_test_count=_test_count(merged),
        project_artifact_root=final["project_artifact_root"],
        final_source_root=final["final_source_root"],
        final_classes_root=final["final_classes_root"],
        next_stage="mutant_execution",
    )

    coverage = None
    coverage_error = None
    try:
        artifact_result = pipeline.layout.round_paths(merged_outcome["round"])["report"] / "result.json"
        coverage = collect_line_coverage(
            artifact_result, metadata, run_dir / "coverage" / "final"
        )
    except Exception as error:
        coverage_error = "{0}: {1}".format(type(error).__name__, error)
        _write_json(run_dir / "coverage_error.json", {"error": coverage_error})

    mutation = execute_mutants(run_dir, workers=mutant_workers, publish_latest=True)
    _write_state(
        run_dir,
        "ORIGINAL_PASSED",
        api_called=True,
        slice_count=len(slicing["steps"]),
        accepted_slice_count=len(accepted_sources),
        accepted_test_count=_test_count(merged),
        project_artifact_root=final["project_artifact_root"],
        final_source_root=final["final_source_root"],
        final_classes_root=final["final_classes_root"],
        mutation_execution_id=mutation["execution_id"],
        next_stage=None,
    )
    result = {
        "state": "COMPLETED",
        "run_dir": str(run_dir),
        "slice_count": len(slicing["steps"]),
        "accepted_slice_count": len(accepted_sources),
        "accepted_test_count": _test_count(merged),
        "slice_results": slice_results,
        "final": final,
        "coverage": coverage,
        "coverage_error": coverage_error,
        "final_mutation": mutation,
    }
    _write_json(run_dir / "hits_result.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True)
    parser.add_argument("--mutant-workers", type=int, default=0)
    args = parser.parse_args()
    try:
        result = run_pipeline(args.run_dir, args.mutant_workers)
    except Exception as error:
        run_dir = Path(args.run_dir).resolve()
        state_path = run_dir / "state.json"
        state = _read_json(state_path) if state_path.is_file() else {}
        failure = classify_pipeline_failure(error, state.get("state"))
        if failure == "PIPELINE_FAILED" and state.get("state") == "CALLING_SLICING":
            failure = "SLICE_FORMAT_FAILED"
        _write_state(
            run_dir,
            failure,
            api_called=bool(state.get("api_called")),
            error_type=type(error).__name__,
            error_message=str(error),
        )
        raise
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
