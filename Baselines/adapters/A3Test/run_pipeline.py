"""Run one A3Test generation, deterministic verification, and evaluation."""

from __future__ import print_function

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path

from protocol import (
    BASELINE,
    GENERATION_STAGE,
    SYSTEM_PROMPT,
    build_generation_prompt,
    verify_and_wrap,
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


def _call_generation(run_dir, context, test_class):
    run_dir = Path(run_dir)
    user_prompt = build_generation_prompt(context, test_class)
    (run_dir / "prompts" / "a3test_generation.system.txt").write_text(
        SYSTEM_PROMPT + "\n", encoding="utf-8"
    )
    (run_dir / "prompts" / "a3test_generation.user.txt").write_text(
        user_prompt + "\n", encoding="utf-8"
    )
    response = generate_traced(
        BASELINE,
        GENERATION_STAGE,
        SYSTEM_PROMPT,
        user_prompt,
        run_dir,
        response_file=run_dir / "responses" / "a3test_generation.json",
        round_number=0,
        visible_to_model=False,
        visible_file_paths=[],
        use_cache=False,
    )
    if str(response.get("finish_reason") or "").lower() in ("length", "max_tokens"):
        raise ModelOutputTruncatedError(
            "A3Test generation was truncated at max_tokens={0}".format(response.get("max_tokens"))
        )
    return response


def _terminal(run_dir, state, result):
    _write_state(run_dir, state, api_called=True, next_stage=None)
    value = {"state": state, "run_dir": str(Path(run_dir).resolve())}
    value.update(result)
    _write_json(Path(run_dir) / "a3test_result.json", value)
    return value


def run_pipeline(run_dir, mutant_workers=0):
    run_dir = Path(run_dir).resolve()
    state = _read_json(run_dir / "state.json")
    if state.get("state") != "PREPARED_API_NOT_CALLED":
        raise RuntimeError("A3Test run is not freshly prepared: {0}".format(state))
    context = _read_json(run_dir / "a3test_context.json")
    metadata = _read_json(run_dir / "metadata.json")
    test_class = _safe_identifier(context["simple_class_name"] + "_A3TestTest")

    _write_state(run_dir, "CALLING_GENERATION", api_called=True, next_stage=GENERATION_STAGE)
    response = _call_generation(run_dir, context, test_class)
    raw = str(response.get("content") or "")
    (run_dir / "raw_tests" / "model_output.txt").write_text(raw + "\n", encoding="utf-8")
    try:
        source, verification = verify_and_wrap(raw, context, test_class)
    except ValueError as error:
        _write_json(run_dir / "parsed" / "verification_error.json", {
            "error_type": type(error).__name__, "error_message": str(error),
        })
        return _terminal(run_dir, "VERIFICATION_FAILED", {"error_message": str(error)})
    (run_dir / "raw_tests" / "verified.java").write_text(source, encoding="utf-8")
    _write_json(run_dir / "parsed" / "verification.json", verification)

    pipeline = JavaTestPipeline(run_dir, "A3Test")
    outcome = pipeline.compile_and_run(source, 1)
    if not outcome["compile"]["compiled"]:
        return _terminal(run_dir, "COMPILE_FAILED", {
            "verification": verification, "outcome": outcome,
            "accepted_test_count": 0,
        })
    if not outcome["execution"]["passed"]:
        return _terminal(run_dir, "ORIGINAL_FAILED", {
            "verification": verification, "outcome": outcome,
            "accepted_test_count": 0,
        })

    final = pipeline.publish_final(outcome)
    accepted_count = len(re.findall(r"@(?:org\.junit\.)?Test\b", source))
    _write_state(
        run_dir, "ORIGINAL_PASSED", api_called=True,
        accepted_test_count=accepted_count,
        project_artifact_root=final["project_artifact_root"],
        final_source_root=final["final_source_root"],
        final_classes_root=final["final_classes_root"],
        next_stage="mutant_execution",
    )

    coverage = None
    coverage_error = None
    try:
        artifact_result = pipeline.layout.round_paths(outcome["round"])["report"] / "result.json"
        coverage = collect_line_coverage(artifact_result, metadata, run_dir / "coverage" / "final")
    except Exception as error:
        coverage_error = "{0}: {1}".format(type(error).__name__, error)
        _write_json(run_dir / "coverage_error.json", {"error": coverage_error})

    mutation = execute_mutants(run_dir, workers=mutant_workers, publish_latest=True)
    _write_state(
        run_dir, "ORIGINAL_PASSED", api_called=True,
        accepted_test_count=accepted_count,
        project_artifact_root=final["project_artifact_root"],
        final_source_root=final["final_source_root"],
        final_classes_root=final["final_classes_root"],
        mutation_execution_id=mutation["execution_id"], next_stage=None,
    )
    result = {
        "state": "COMPLETED", "run_dir": str(run_dir),
        "accepted_test_count": accepted_count,
        "verification": verification, "final": final,
        "coverage": coverage, "coverage_error": coverage_error,
        "final_mutation": mutation,
    }
    _write_json(run_dir / "a3test_result.json", result)
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
        _write_state(
            run_dir, failure, api_called=bool(state.get("api_called")),
            error_type=type(error).__name__, error_message=str(error), next_stage=None,
        )
        raise
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
