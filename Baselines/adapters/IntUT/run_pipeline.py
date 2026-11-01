"""Run native IntUT candidate generation, selection, and common evaluation."""

from __future__ import print_function

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path

from protocol import BASELINE, STAGE, SYSTEM_PROMPT, build_prompt, normalize_test_source, test_count


ADAPTER_DIR = Path(__file__).resolve().parent
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path: sys.path.insert(0, str(COMMON_DIR))

from java_test_pipeline import JavaTestPipeline
from failure_classification import classify_pipeline_failure
from jacoco_coverage import collect_line_coverage
from mutant_execution import execute_mutants
from traced_llm_call import generate_traced


class ModelOutputTruncatedError(ValueError): pass


def _now(): return datetime.now().astimezone().isoformat(timespec="milliseconds")
def _read_json(path): return json.loads(Path(path).read_text(encoding="utf-8"))
def _write_json(path, value):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
def _safe_identifier(value):
    result = re.sub(r"[^A-Za-z0-9_$]", "_", str(value or "")); return result if result and not result[0].isdigit() else "T_" + result
def _state(run_dir, state, **values):
    result = {"state": state, "updated_at": _now()}; result.update(values); _write_json(Path(run_dir) / "state.json", result)


def _call(run_dir, prompt, intention_id, candidate_index):
    label = "{0}_candidate_{1:02d}".format(intention_id, candidate_index)
    prompt_path = Path(run_dir) / "prompts" / (label + ".user.txt")
    prompt_path.write_text(prompt + "\n", encoding="utf-8")
    response = generate_traced(
        BASELINE, STAGE, SYSTEM_PROMPT, prompt, run_dir,
        response_file=Path(run_dir) / "responses" / (label + ".json"),
        round_number=candidate_index, visible_to_model=False, visible_file_paths=[], use_cache=False,
    )
    if str(response.get("finish_reason") or "").lower() in ("length", "max_tokens"):
        raise ModelOutputTruncatedError("IntUT candidate was truncated at max_tokens={0}".format(response.get("max_tokens")))
    return response


def _terminal(run_dir, state, result):
    _state(run_dir, state, api_called=True, next_stage=None)
    value = {"state": state, "run_dir": str(Path(run_dir).resolve())}; value.update(result)
    _write_json(Path(run_dir) / "intut_result.json", value); return value


def run_pipeline(run_dir, mutant_workers=0):
    run_dir = Path(run_dir).resolve(); state = _read_json(run_dir / "state.json")
    if state.get("state") != "PREPARED_API_NOT_CALLED": raise RuntimeError("IntUT run is not freshly prepared: {0}".format(state))
    metadata = _read_json(run_dir / "metadata.json"); context = _read_json(run_dir / "target_context.json")
    rag = _read_json(run_dir / "intut_rag.json"); paint = _read_json(run_dir / "paint_intentions.json")
    candidates_per_intention = int(metadata["settings"]["candidates_per_intention"])
    test_class = _safe_identifier(context["simple_class_name"] + "_IntUTTest")
    pipeline = JavaTestPipeline(run_dir, "IntUT")
    accepted_source = ""; accepted_outcome = None; step = 0; selections = []
    _state(run_dir, "GENERATING_CANDIDATES", api_called=True, next_stage=STAGE)
    for intention_index, intention in enumerate(paint["intentions"], 1):
        candidate_records = []; passing = []
        prompt = build_prompt(context, rag, intention, test_class, accepted_source)
        for candidate_index in range(1, candidates_per_intention + 1):
            response = _call(run_dir, prompt, intention["id"], candidate_index)
            raw = str(response.get("content") or "")
            raw_path = run_dir / "raw_tests" / ("{0}_candidate_{1:02d}.txt".format(intention["id"], candidate_index))
            raw_path.write_text(raw + "\n", encoding="utf-8")
            normalized = None; outcome = None; error = None
            try:
                normalized = normalize_test_source(raw, context.get("package_name") or "", test_class)
                step += 1; outcome = pipeline.compile_and_run(normalized, step)
            except Exception as exc:
                error = "{0}: {1}".format(type(exc).__name__, exc)
            passed = bool(outcome and outcome["compile"]["compiled"] and outcome["execution"]["passed"])
            parsed_path = run_dir / "parsed" / ("{0}_candidate_{1:02d}.java".format(intention["id"], candidate_index))
            parsed_path.write_text(normalized or raw, encoding="utf-8")
            record = {"candidate_index": candidate_index, "pipeline_step": step if outcome else None, "compiled": bool(outcome and outcome["compile"]["compiled"]), "original_passed": passed, "normalization_error": error, "test_count": test_count(normalized or "")}
            candidate_records.append(record)
            if passed: passing.append((candidate_index, normalized, outcome))
        selected = passing[0] if passing else None
        if selected:
            accepted_source, accepted_outcome = selected[1], selected[2]
        selection = {"intention_index": intention_index, "intention_id": intention["id"], "candidate_count": len(candidate_records), "selected_candidate_index": selected[0] if selected else None, "accepted": selected is not None, "candidates": candidate_records}
        selections.append(selection); _write_json(run_dir / "parsed" / "candidate_selection.json", selections)
    if not accepted_source:
        return _terminal(run_dir, "NO_EXECUTABLE_CANDIDATE", {"accepted_test_count": 0, "intention_count": len(paint["intentions"]), "candidate_call_count": len(paint["intentions"]) * candidates_per_intention})
    (run_dir / "raw_tests" / "final.java").write_text(accepted_source, encoding="utf-8")
    coverage = None
    try:
        artifact_result = pipeline.layout.round_paths(accepted_outcome["round"])["report"] / "result.json"
        coverage = collect_line_coverage(
            artifact_result, metadata, run_dir / "coverage" / "final_focal_method",
            start_line=context.get("focal_start_line"), end_line=context.get("focal_end_line"),
        )
    except Exception as error:
        _write_json(run_dir / "coverage" / "error.json", {"error_type": type(error).__name__, "error_message": str(error), "scoring_effect": "none; auxiliary paper metric only"})
    final = pipeline.publish_final(accepted_outcome)
    _state(run_dir, "ORIGINAL_PASSED", api_called=True, next_stage="mutation_evaluation")
    mutation = execute_mutants(run_dir, workers=mutant_workers)
    return _terminal(run_dir, "COMPLETED", {
        "accepted_test_count": test_count(accepted_source), "intention_count": len(paint["intentions"]),
        "candidate_call_count": len(paint["intentions"]) * candidates_per_intention,
        "accepted_intention_count": sum(value["accepted"] for value in selections),
        "coverage": coverage, "project_artifacts": final, "mutation_summary": mutation,
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--run-dir", required=True); parser.add_argument("--mutant-workers", type=int, default=0)
    args = parser.parse_args()
    try:
        result = run_pipeline(args.run_dir, args.mutant_workers)
    except Exception as error:
        run_dir = Path(args.run_dir).resolve()
        state_path = run_dir / "state.json"
        existing = _read_json(state_path) if state_path.is_file() else {}
        _state(
            run_dir,
            classify_pipeline_failure(error, existing.get("state")),
            api_called=bool(existing.get("api_called")),
            error_type=type(error).__name__,
            error_message=str(error),
        )
        raise
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__": main()

