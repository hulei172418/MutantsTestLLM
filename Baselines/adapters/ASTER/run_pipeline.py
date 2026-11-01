"""Run ASTER generation, remediation, coverage augmentation, and evaluation."""

from __future__ import print_function

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path

from protocol import (
    BASELINE, COVERAGE_STAGE, COVERAGE_SYSTEM_PROMPT, INITIAL_STAGE,
    INITIAL_SYSTEM_PROMPT, REPAIR_STAGE, REPAIR_SYSTEM_PROMPT,
    build_coverage_prompt, build_initial_prompt, build_repair_prompt,
    extract_java, normalize_test_source, test_count,
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


def _call(run_dir, stage, system_prompt, user_prompt, label, round_number):
    run_dir = Path(run_dir)
    (run_dir / "prompts" / (label + ".system.txt")).write_text(system_prompt + "\n", encoding="utf-8")
    (run_dir / "prompts" / (label + ".user.txt")).write_text(user_prompt + "\n", encoding="utf-8")
    response = generate_traced(
        BASELINE, stage, system_prompt, user_prompt, run_dir,
        response_file=run_dir / "responses" / (label + ".json"),
        round_number=round_number, visible_to_model=False,
        visible_file_paths=[], use_cache=False,
    )
    if str(response.get("finish_reason") or "").lower() in ("length", "max_tokens"):
        raise ModelOutputTruncatedError(
            "{0} was truncated at max_tokens={1}".format(stage, response.get("max_tokens"))
        )
    return response


def _outcome_log(outcome):
    if not outcome:
        return "No compiler or JUnit result was produced."
    section = outcome.get("compile", {}) if not outcome.get("compile", {}).get("compiled") else outcome.get("execution", {})
    path = section.get("log_file")
    return Path(path).read_text(encoding="utf-8", errors="replace") if path and Path(path).is_file() else "No detailed log was produced."


def _error_line(log):
    for pattern in (
        r"(?m)^.*?\.java:(\d+):\s*error:.*$",
        r"(?m)^\s*at\s+[^\n]+\.java:(\d+)\).*$",
        r"(?m)^.*(?:AssertionError|Exception|Error).*$",
    ):
        match = re.search(pattern, log or "")
        if match:
            return match.group(0).strip()
    return "<location not reported>"


def _coverage_for(run_dir, pipeline, outcome, metadata, label):
    artifact_result = pipeline.layout.round_paths(outcome["round"])["report"] / "result.json"
    return collect_line_coverage(artifact_result, metadata, Path(run_dir) / "coverage" / label)


def _focal_coverage(context, coverage):
    focal = set(int(value) for value in context.get("focal_method_lines") or [])
    covered = focal.intersection(int(value) for value in coverage.get("covered_lines") or [])
    missed = focal.intersection(int(value) for value in coverage.get("missed_lines") or [])
    measurable = covered.union(missed)
    ratio = float(len(covered)) / len(measurable) if measurable else 0.0
    return {
        "focal_method_lines": sorted(focal), "measurable_focal_lines": sorted(measurable),
        "covered_focal_lines": sorted(covered), "missed_focal_lines": sorted(missed),
        "covered_focal_line_count": len(covered), "missed_focal_line_count": len(missed),
        "focal_line_covered_ratio": ratio,
    }


def _validate_with_repairs(run_dir, pipeline, context, test_class, raw_source, step, max_repairs, phase_label):
    current = extract_java(raw_source)
    attempts = []
    accepted_source = None
    accepted_outcome = None
    for repair_round in range(max_repairs + 1):
        normalized = None
        outcome = None
        normalization_error = None
        try:
            normalized = normalize_test_source(current, context.get("package_name") or "", test_class)
            step += 1
            outcome = pipeline.compile_and_run(normalized, step)
            passed = bool(outcome["compile"]["compiled"] and outcome["execution"]["passed"])
        except Exception as error:
            normalization_error = "{0}: {1}".format(type(error).__name__, error)
            passed = False
        attempt_dir = Path(run_dir) / "parsed" / phase_label
        attempt_dir.mkdir(parents=True, exist_ok=True)
        (attempt_dir / "attempt_{0:02d}.java".format(repair_round)).write_text(normalized or current, encoding="utf-8")
        attempt = {
            "repair_round": repair_round, "pipeline_step": step if outcome else None,
            "normalized": normalized is not None, "normalization_error": normalization_error,
            "compiled": bool(outcome and outcome["compile"]["compiled"]),
            "original_passed": passed,
        }
        attempts.append(attempt)
        if passed:
            accepted_source, accepted_outcome = normalized, outcome
            break
        if repair_round >= max_repairs:
            break
        log = normalization_error or _outcome_log(outcome)
        prompt = build_repair_prompt(context, normalized or current, _error_line(log), log, test_class)
        response = _call(
            run_dir, REPAIR_STAGE, REPAIR_SYSTEM_PROMPT, prompt,
            "{0}_repair_{1:02d}".format(phase_label, repair_round + 1),
            repair_round + 1,
        )
        current = response.get("content") or ""
    result = {
        "phase": phase_label, "accepted": accepted_source is not None,
        "repair_call_count": max(0, len(attempts) - 1), "attempts": attempts,
    }
    _write_json(Path(run_dir) / "parsed" / phase_label / "result.json", result)
    return accepted_source, accepted_outcome, step, result


def _terminal(run_dir, state, result):
    _write_state(run_dir, state, api_called=True, next_stage=None)
    value = {"state": state, "run_dir": str(Path(run_dir).resolve())}
    value.update(result)
    _write_json(Path(run_dir) / "aster_result.json", value)
    return value


def run_pipeline(run_dir, mutant_workers=0):
    run_dir = Path(run_dir).resolve()
    state = _read_json(run_dir / "state.json")
    if state.get("state") != "PREPARED_API_NOT_CALLED":
        raise RuntimeError("ASTER run is not freshly prepared: {0}".format(state))
    context = _read_json(run_dir / "aster_context.json")
    metadata = _read_json(run_dir / "metadata.json")
    bounds = metadata["reimplementation_bounds"]
    max_repairs = int(bounds["max_repair_rounds"])
    max_coverage = int(bounds["max_coverage_rounds"])
    target_ratio = float(bounds["target_focal_line_coverage"])
    test_class = _safe_identifier(context["simple_class_name"] + "_ASTERTest")
    pipeline = JavaTestPipeline(run_dir, "ASTER")
    step = 0

    _write_state(run_dir, "CALLING_INITIAL", api_called=True, next_stage=INITIAL_STAGE)
    initial_prompt = build_initial_prompt(context, test_class)
    response = _call(run_dir, INITIAL_STAGE, INITIAL_SYSTEM_PROMPT, initial_prompt, "aster_initial", 0)
    raw = str(response.get("content") or "")
    (run_dir / "raw_tests" / "initial_model_output.txt").write_text(raw + "\n", encoding="utf-8")
    source, outcome, step, initial_validation = _validate_with_repairs(
        run_dir, pipeline, context, test_class, raw, step, max_repairs, "initial"
    )
    if source is None:
        return _terminal(run_dir, "NO_EXECUTABLE_INITIAL_TEST", {
            "accepted_test_count": 0, "initial_validation": initial_validation,
        })

    accepted_source, accepted_outcome = source, outcome
    coverage_rounds = []
    try:
        coverage = _coverage_for(run_dir, pipeline, accepted_outcome, metadata, "initial")
        focal = _focal_coverage(context, coverage)
        coverage.update({"focal_method": focal})
        _write_json(run_dir / "coverage" / "initial_focal.json", focal)
    except Exception as error:
        coverage = None
        focal = None
        _write_json(run_dir / "coverage" / "initial_error.json", {"error": "{0}: {1}".format(type(error).__name__, error)})

    for coverage_round in range(1, max_coverage + 1):
        if not focal or focal["focal_line_covered_ratio"] >= target_ratio or not focal["missed_focal_lines"]:
            break
        _write_state(run_dir, "CALLING_COVERAGE", api_called=True, coverage_round=coverage_round, next_stage=COVERAGE_STAGE)
        prompt = build_coverage_prompt(context, accepted_source, focal["missed_focal_lines"], test_class)
        response = _call(
            run_dir, COVERAGE_STAGE, COVERAGE_SYSTEM_PROMPT, prompt,
            "aster_coverage_{0:02d}".format(coverage_round), coverage_round,
        )
        candidate, candidate_outcome, step, validation = _validate_with_repairs(
            run_dir, pipeline, context, test_class, response.get("content") or "",
            step, max_repairs, "coverage_{0:02d}".format(coverage_round),
        )
        record = {"coverage_round": coverage_round, "validation": validation, "accepted": candidate is not None}
        if candidate is not None:
            try:
                candidate_coverage = _coverage_for(
                    run_dir, pipeline, candidate_outcome, metadata,
                    "coverage_{0:02d}".format(coverage_round),
                )
                candidate_focal = _focal_coverage(context, candidate_coverage)
                candidate_coverage.update({"focal_method": candidate_focal})
                record["coverage"] = candidate_coverage
                record["focal_coverage"] = candidate_focal
                accepted_source, accepted_outcome = candidate, candidate_outcome
                coverage, focal = candidate_coverage, candidate_focal
            except Exception as error:
                record["coverage_error"] = "{0}: {1}".format(type(error).__name__, error)
                accepted_source, accepted_outcome = candidate, candidate_outcome
                focal = None
        coverage_rounds.append(record)
        _write_json(run_dir / "coverage" / "coverage_rounds.json", coverage_rounds)

    (run_dir / "raw_tests" / "final.java").write_text(accepted_source, encoding="utf-8")
    final = pipeline.publish_final(accepted_outcome)
    accepted_count = test_count(accepted_source)
    _write_state(
        run_dir, "ORIGINAL_PASSED", api_called=True, accepted_test_count=accepted_count,
        project_artifact_root=final["project_artifact_root"],
        final_source_root=final["final_source_root"], final_classes_root=final["final_classes_root"],
        next_stage="mutant_execution",
    )
    mutation = execute_mutants(run_dir, workers=mutant_workers, publish_latest=True)
    _write_state(
        run_dir, "ORIGINAL_PASSED", api_called=True, accepted_test_count=accepted_count,
        project_artifact_root=final["project_artifact_root"],
        final_source_root=final["final_source_root"], final_classes_root=final["final_classes_root"],
        mutation_execution_id=mutation["execution_id"], next_stage=None,
    )
    result = {
        "state": "COMPLETED", "run_dir": str(run_dir), "accepted_test_count": accepted_count,
        "initial_validation": initial_validation, "coverage_rounds": coverage_rounds,
        "final_coverage": coverage, "final_focal_coverage": focal,
        "final": final, "final_mutation": mutation,
    }
    _write_json(run_dir / "aster_result.json", result)
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
        _write_state(run_dir, failure, api_called=bool(state.get("api_called")), error_type=type(error).__name__, error_message=str(error), next_stage=None)
        raise
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
