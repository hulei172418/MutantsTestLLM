"""Apply ChatTester's paper-priority compile-only refinement protocol."""

from __future__ import print_function

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path

from feedback import build_compile_prompt
from llm import ChatTesterLlmAdapter
from generate_initial_test import render_test_source


ADAPTER_DIR = Path(__file__).resolve().parent
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from failure_classification import classify_pipeline_failure  # noqa: E402
from java_test_pipeline import JavaTestPipeline  # noqa: E402
from summarize_token_usage import summarize_run  # noqa: E402


def _now():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _write_json(path, value):
    Path(path).write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def _write_state(run_dir, state, **values):
    result = {"state": state, "updated_at": _now()}
    result.update(values)
    _write_json(Path(run_dir) / "state.json", result)


def _log_text(result, kind):
    path = result[kind].get("log_file")
    return Path(path).read_text(encoding="utf-8", errors="replace") if path else ""


def _class_information(context):
    signatures = context.get("all_method_signatures") or []
    fields = context.get("fields") or []
    constructors = context.get("constructors") or []
    return "\n".join(
        ["# Focal class public information"]
        + list(fields)
        + list(constructors)
        + list(signatures)
    )


def _focal_method(run_dir, context):
    parsed = Path(run_dir) / "parsed" / "focal_method_context.txt"
    if parsed.is_file():
        return parsed.read_text(encoding="utf-8").strip()
    return str(context.get("method_body") or "")


def _initial_intention(run_dir):
    path = Path(run_dir) / "parsed" / "chattester_intention_0.txt"
    return path.read_text(encoding="utf-8").strip() if path.is_file() else ""


def _raw_test_file(run_dir):
    state = _read_json(Path(run_dir) / "state.json")
    configured = str(state.get("test_file") or "").strip()
    if configured and Path(configured).is_file():
        return Path(configured)
    matches = sorted((Path(run_dir) / "raw_tests").glob("*.java"))
    if len(matches) != 1:
        raise RuntimeError("Expected exactly one generated raw test")
    return matches[0]


def _write_prompt(run_dir, stage, round_number, prompt):
    path = Path(run_dir) / "prompts" / "{0}_{1}.user.txt".format(
        stage, round_number
    )
    path.write_text(prompt + "\n", encoding="utf-8")


def _complete_compiled_outcome(
    run_dir, pipeline, outcome, history, repair_rounds, invalid_refinements
):
    """Finish after compilation without feeding execution failures to the LLM."""
    if outcome["execution"]["passed"]:
        final = pipeline.publish_final(outcome)
        _write_state(
            run_dir,
            "ORIGINAL_PASSED",
            api_called=True,
            project_artifact_root=outcome["project_artifact_root"],
            final_source_root=final["final_source_root"],
            final_classes_root=final["final_classes_root"],
            repair_rounds=repair_rounds,
            invalid_refinements=invalid_refinements,
            feedback_protocol="paper_compile_only",
            next_stage="mutant_execution",
        )
        return {"state": "ORIGINAL_PASSED", "history": history, "final": final}

    execution = outcome["execution"]
    state = "ORIGINAL_TIMEOUT" if execution.get("timeout") else "ORIGINAL_FAILED"
    _write_state(
        run_dir,
        state,
        api_called=True,
        project_artifact_root=outcome["project_artifact_root"],
        repair_rounds=repair_rounds,
        invalid_refinements=invalid_refinements,
        feedback_protocol="paper_compile_only",
        execution_status=execution.get("status"),
        next_stage=None,
    )
    return {"state": state, "history": history}


def run_feedback(run_dir, baseline_name="ChatTester"):
    run_dir = Path(run_dir).resolve()
    state_path = run_dir / "state.json"
    final_path = run_dir / "project_artifacts_final.json"
    if state_path.is_file():
        existing_state = _read_json(state_path)
        if existing_state.get("state") == "ORIGINAL_PASSED" and final_path.is_file():
            return {
                "state": "ORIGINAL_PASSED",
                "resumed": True,
                "history": [],
                "final": _read_json(final_path),
            }
        if existing_state.get("state") in {
            "REPAIR_LIMIT_REACHED",
            "ORIGINAL_FAILED",
            "ORIGINAL_TIMEOUT",
        }:
            return {
                "state": existing_state["state"],
                "resumed": True,
                "history": [],
            }
    context = _read_json(run_dir / "target_context.json")
    current_source = _raw_test_file(run_dir).read_text(encoding="utf-8")
    focal_method = _focal_method(run_dir, context)
    intention = _initial_intention(run_dir)
    class_info = _class_information(context)
    adapter = ChatTesterLlmAdapter(run_dir)
    pipeline = JavaTestPipeline(run_dir, baseline_name)

    round_zero_result = (
        pipeline.layout.round_paths(0)["report"] / "result.json"
    )
    if round_zero_result.is_file():
        outcome = pipeline.load_round_result(0)
    else:
        _write_state(run_dir, "COMPILING_INITIAL", api_called=True)
        outcome = pipeline.compile_and_run(current_source, 0)
    history = [outcome]
    if outcome["compile"]["compiled"]:
        return _complete_compiled_outcome(run_dir, pipeline, outcome, history, 0, 0)

    total_iter = 0
    invalid_refinements = 0
    previous_error_count = int(outcome["compile"].get("error_count") or 0)

    while True:
        prompt = build_compile_prompt(
            current_source,
            _log_text(outcome, "compile"),
            focal_method,
            method_intention=intention,
            regenerate=False,
            class_information=class_info,
        )
        stage = "chattester_compile_repair"

        total_iter += 1
        _write_prompt(run_dir, stage, total_iter, prompt)
        _write_state(
            run_dir,
            "CALLING_REPAIR",
            api_called=True,
            stage=stage,
            total_iter=total_iter,
            invalid_refinements=invalid_refinements,
            feedback_protocol="paper_compile_only",
        )
        method, imports = adapter.method_pred_unit(
            prompt,
            repair_TAG=True,
            repair_stage="compile",
            round_number=total_iter,
        )
        if not method:
            raise RuntimeError("Repair response contains no extractable test method")
        _, candidate_source = render_test_source(context, method, imports)
        current_source = candidate_source
        outcome = pipeline.compile_and_run(current_source, total_iter)
        history.append(outcome)

        if outcome["compile"]["compiled"]:
            return _complete_compiled_outcome(
                run_dir,
                pipeline,
                outcome,
                history,
                total_iter,
                invalid_refinements,
            )

        error_count = int(outcome["compile"].get("error_count") or 0)
        if error_count >= previous_error_count:
            invalid_refinements += 1
        previous_error_count = error_count

        # Section 5.2 uses a maximum invalid-refinement parameter of three and
        # terminates once the accumulated count is larger than that maximum.
        if invalid_refinements > 3:
            _write_state(
                run_dir,
                "REPAIR_LIMIT_REACHED",
                api_called=True,
                project_artifact_root=outcome["project_artifact_root"],
                repair_rounds=total_iter,
                invalid_refinements=invalid_refinements,
                feedback_protocol="paper_compile_only",
                next_stage=None,
            )
            return {"state": "REPAIR_LIMIT_REACHED", "history": history}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True)
    parser.add_argument("--baseline-name", default="ChatTester")
    args = parser.parse_args()
    try:
        result = run_feedback(args.run_dir, args.baseline_name)
        try:
            result["token_summary"] = summarize_run(args.run_dir)
        except Exception as error:
            result["token_summary_error"] = "{0}: {1}".format(
                type(error).__name__, error
            )
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except Exception as error:
        run_dir = Path(args.run_dir).resolve()
        state_path = run_dir / "state.json"
        existing = _read_json(state_path) if state_path.is_file() else {}
        _write_state(
            run_dir,
            classify_pipeline_failure(error, existing.get("state")),
            api_called=True,
            error_type=type(error).__name__,
            error_message=str(error),
        )
        raise


if __name__ == "__main__":
    main()
