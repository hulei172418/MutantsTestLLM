"""Run MuTAP initial generation, survivor augmentation, minimization and evaluation."""

from __future__ import print_function

import argparse
import json
import random
import re
import sys
from datetime import datetime
from pathlib import Path

from protocol import (
    BASELINE,
    DEFAULT_SURVIVOR_SEED,
    MAX_SYNTAX_REPAIR_ROUNDS,
    SYSTEM_PROMPT,
    build_augmentation_prompt,
    build_initial_prompt,
    build_syntax_repair_prompt,
)


ADAPTER_DIR = Path(__file__).resolve().parent
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from failure_classification import classify_pipeline_failure
from java_test_pipeline import JavaTestPipeline
from mutant_execution import execute_mutants
from traced_llm_call import generate_traced


class ModelOutputTruncatedError(ValueError):
    pass


class ModelOutputInvalidError(ValueError):
    pass


def _now():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _read_jsonl(path):
    rows = []
    with Path(path).open("r", encoding="utf-8-sig") as stream:
        for line in stream:
            if line.strip():
                rows.append(json.loads(line))
    return rows


def _write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _write_state(run_dir, state, **values):
    result = {"state": state, "updated_at": _now()}
    result.update(values)
    _write_json(Path(run_dir) / "state.json", result)


def _require_complete(response, stage):
    reason = str(response.get("finish_reason") or "").lower()
    if reason in ("length", "max_tokens"):
        raise ModelOutputTruncatedError("{0} was truncated at max_tokens={1}".format(stage, response.get("max_tokens")))


def _safe_identifier(value):
    value = re.sub(r"[^A-Za-z0-9_$]", "_", str(value))
    return value if value and not value[0].isdigit() else "T_" + value


def _extract_java(content):
    text = str(content or "").strip()
    fenced = re.findall(r"```(?:java)?\s*\r?\n([\s\S]*?)\r?\n```", text, re.IGNORECASE)
    if fenced:
        return max(fenced, key=len).strip()
    return text


def _brace_end(source, opening):
    depth = 0
    in_string = False
    quote = ""
    escaped = False
    for index in range(opening, len(source)):
        char = source[index]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                in_string = False
            continue
        if char in ('"', "'"):
            in_string = True
            quote = char
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index + 1
    raise ModelOutputInvalidError("Unbalanced braces in generated Java")


def extract_test_methods(source):
    """Extract complete JUnit 4 methods while retaining author-generated text."""
    pattern = re.compile(
        r"(?m)^\s*@(?:org\.junit\.)?Test(?:\s*\([^\n]*\))?\s+"
        r"\s*(?:public\s+)?void\s+[A-Za-z_$][\w$]*\s*\([^)]*\)\s*(?:throws\s+[^\{]+)?\{"
    )
    methods = []
    spans = []
    for match in pattern.finditer(source):
        if spans and match.start() < spans[-1][1]:
            continue
        opening = source.find("{", match.start(), match.end())
        end = _brace_end(source, opening)
        methods.append(source[match.start():end].strip())
        spans.append((match.start(), end))
    return methods, spans


def _without_test_methods(source):
    _, spans = extract_test_methods(source)
    for start, end in reversed(spans):
        source = source[:start] + source[end:]
    return source


def render_methods(base_source, methods):
    base = _without_test_methods(base_source).rstrip()
    closing = base.rfind("}")
    if closing < 0:
        raise ValueError("Generated Java test class has no closing brace")
    body = "\n\n".join(method.strip() for method in methods if method.strip())
    return base[:closing].rstrip() + "\n\n" + body + "\n" + base[closing:] + "\n"


def normalize_initial_source(content, test_class_name):
    source = _extract_java(content)
    match = re.search(r"\bpublic\s+class\s+([A-Za-z_$][\w$]*)", source)
    if not match:
        raise ValueError("MuTAP initial response has no public test class")
    source = source[:match.start(1)] + test_class_name + source[match.end(1):]
    if "org.junit.Test" not in source and "@Test" in source:
        package_match = re.search(r"(?m)^\s*package\s+[^;]+;\s*", source)
        insert_at = package_match.end() if package_match else 0
        source = source[:insert_at] + "\nimport org.junit.Test;\nimport static org.junit.Assert.*;\n" + source[insert_at:]
    return source.strip() + "\n"


def _artifact_result_path(pipeline, outcome):
    return pipeline.layout.round_paths(outcome["round"])["report"] / "result.json"


def _log(outcome, kind):
    path = (outcome.get(kind) or {}).get("log_file")
    return Path(path).read_text(encoding="utf-8", errors="replace") if path else ""


def _physical_key(record):
    return str(record.get("file_path") or "").replace("\\", "/").lower()


def _status_sets(summary):
    killed, live, problematic = set(), set(), set()
    for row in _read_jsonl(summary["results_jsonl"]):
        key = _physical_key(row)
        status = str(row.get("status") or "").upper()
        if status.startswith("KILLED"):
            killed.add(key)
        elif status == "LIVE":
            live.add(key)
        else:
            problematic.add(key)
    return killed, live, problematic


def _mutant_source(record):
    candidates = [record.get("mutated_source_path")]
    file_path = str(record.get("file_path") or "")
    if file_path:
        candidates.append(str(Path(file_path).with_suffix(".java")))
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return Path(candidate).read_text(encoding="utf-8", errors="replace")
    return None


def _call(run_dir, stage, prompt, response_name, round_number, visible_paths=None):
    (Path(run_dir) / "prompts" / (response_name + ".system.txt")).write_text(SYSTEM_PROMPT + "\n", encoding="utf-8")
    (Path(run_dir) / "prompts" / (response_name + ".user.txt")).write_text(prompt + "\n", encoding="utf-8")
    response = generate_traced(
        BASELINE, stage, SYSTEM_PROMPT, prompt, run_dir,
        response_file=Path(run_dir) / "responses" / (response_name + ".json"),
        round_number=round_number,
        visible_to_model=bool(visible_paths),
        visible_file_paths=visible_paths or [],
        use_cache=False,
    )
    _require_complete(response, stage)
    return response


def _compile_with_syntax_repair(run_dir, pipeline, source, test_class_name, step, label):
    repairs = []
    outcome = pipeline.compile_and_run(source, step)
    while not outcome["compile"]["compiled"] and len(repairs) < MAX_SYNTAX_REPAIR_ROUNDS:
        prompt = build_syntax_repair_prompt(source, _log(outcome, "compile"), test_class_name)
        number = len(repairs) + 1
        response = _call(run_dir, "mutap_syntax_repair", prompt, "{0}_syntax_{1:02d}".format(label, number), step, [])
        source = normalize_initial_source(response.get("content"), test_class_name)
        step += 1
        outcome = pipeline.compile_and_run(source, step)
        repairs.append({"repair": number, "step": step, "compiled": outcome["compile"]["compiled"]})
    return source, outcome, step, repairs


def _semantic_filter(pipeline, source, step):
    """Deterministic Java adaptation of IntendedBehaviorFixer.

    The Python artifact can evaluate one expression and rewrite its expected
    value. Generic Java/JUnit does not expose a safe expression evaluator, so
    assertions that fail on the original program are discarded at test-method
    granularity; no LLM sees runtime feedback.
    """
    methods, _ = extract_test_methods(source)
    accepted = []
    attempts = []
    for index, method in enumerate(methods, 1):
        candidate = render_methods(source, accepted + [method])
        step += 1
        outcome = pipeline.compile_and_run(candidate, step)
        passed = outcome["compile"]["compiled"] and outcome["execution"]["passed"]
        attempts.append({"method": index, "step": step, "accepted": passed})
        if passed:
            accepted.append(method)
    if not accepted:
        return source, None, step, attempts
    filtered = render_methods(source, accepted)
    step += 1
    outcome = pipeline.compile_and_run(filtered, step)
    attempts.append({"final_filtered_suite": True, "step": step, "accepted": bool(
        outcome["compile"]["compiled"] and outcome["execution"]["passed"]
    )})
    return filtered, outcome, step, attempts


def _validate_suite(run_dir, pipeline, source, test_class_name, step, label):
    source, outcome, step, repairs = _compile_with_syntax_repair(
        run_dir, pipeline, source, test_class_name, step, label
    )
    semantic = []
    if outcome["compile"]["compiled"] and not outcome["execution"]["passed"]:
        source, outcome, step, semantic = _semantic_filter(pipeline, source, step)
    passed = bool(outcome and outcome["compile"]["compiled"] and outcome["execution"]["passed"])
    return source, outcome, step, {"syntax_repairs": repairs, "semantic_filter": semantic, "passed": passed}


def _minimize(run_dir, pipeline, source, step, mutant_workers):
    methods, _ = extract_test_methods(source)
    if len(methods) <= 1:
        return source, None, step, {"candidate_count": len(methods), "selected": list(range(len(methods)))}
    candidates = []
    for index, method in enumerate(methods):
        step += 1
        candidate_source = render_methods(source, [method])
        outcome = pipeline.compile_and_run(candidate_source, step)
        killed = set()
        if outcome["compile"]["compiled"] and outcome["execution"]["passed"]:
            mutation = execute_mutants(
                run_dir, execution_id="mutap-min-{0:03d}".format(index + 1), workers=mutant_workers,
                artifact_result=_artifact_result_path(pipeline, outcome), publish_latest=False,
            )
            killed, _, _ = _status_sets(mutation)
        candidates.append({"index": index, "method": method, "kills": killed})
    ordered = sorted(candidates, key=lambda item: (-len(item["kills"]), item["index"]))
    selected, covered = [], set()
    for item in ordered:
        if item["kills"] - covered:
            selected.append(item)
            covered.update(item["kills"])
    if not selected:
        selected = [ordered[0]]
    selected.sort(key=lambda item: item["index"])
    minimized = render_methods(source, [item["method"] for item in selected])
    step += 1
    outcome = pipeline.compile_and_run(minimized, step)
    if not outcome["compile"]["compiled"] or not outcome["execution"]["passed"]:
        return source, None, step, {"candidate_count": len(methods), "fallback": "pre_minimized_suite"}
    report = {
        "candidate_count": len(methods),
        "selected": [item["index"] for item in selected],
        "killed_union_count": len(covered),
        "individual_kill_counts": {str(item["index"]): len(item["kills"]) for item in candidates},
    }
    return minimized, outcome, step, report


def run_pipeline(run_dir, mutant_workers=0, survivor_seed=DEFAULT_SURVIVOR_SEED):
    run_dir = Path(run_dir).resolve()
    state = _read_json(run_dir / "state.json")
    if state.get("state") != "PREPARED_API_NOT_CALLED":
        raise RuntimeError("MuTAP run is not freshly prepared: {0}".format(state))
    context = _read_json(run_dir / "target_context.json")
    records = _read_jsonl(run_dir / "source_records.jsonl")
    unique_records = {}
    for record in records:
        unique_records.setdefault(_physical_key(record), record)
    test_class_name = _safe_identifier(context["simple_class_name"] + "_MuTAPTest")
    java_context = (run_dir / "parsed" / "original_context.java").read_text(encoding="utf-8")
    initial_prompt = build_initial_prompt(java_context, test_class_name)
    _write_state(run_dir, "CALLING_INITIAL", api_called=True, next_stage="mutap_initial")
    response = _call(run_dir, "mutap_initial", initial_prompt, "mutap_initial_000", 0, [])
    source = normalize_initial_source(response.get("content"), test_class_name)
    (run_dir / "raw_tests" / "initial.java").write_text(source, encoding="utf-8")

    pipeline = JavaTestPipeline(run_dir, "MuTAP")
    step = 0
    source, outcome, step, initial_validation = _validate_suite(
        run_dir, pipeline, source, test_class_name, step, "initial"
    )
    _write_json(run_dir / "rounds" / "000_initial_validation.json", initial_validation)
    if not initial_validation["passed"]:
        _write_state(run_dir, "ORIGINAL_FAILED", api_called=True, reason="initial_suite_not_valid")
        return {"state": "ORIGINAL_FAILED", "accepted_test_count": 0}

    order = list(unique_records)
    random.Random(int(survivor_seed)).shuffle(order)
    used = set()
    rounds = []
    mutation = execute_mutants(
        run_dir, execution_id="mutap-r000", workers=mutant_workers,
        artifact_result=_artifact_result_path(pipeline, outcome), publish_latest=False,
    )
    _, live, problematic = _status_sets(mutation)
    round_number = 0
    while live - used:
        survivor = next(key for key in order if key in live and key not in used)
        used.add(survivor)
        record = unique_records[survivor]
        mutant_source = _mutant_source(record)
        round_number += 1
        round_dir = run_dir / "rounds" / "{0:03d}".format(round_number)
        round_dir.mkdir(parents=True, exist_ok=False)
        if mutant_source is None:
            summary = {"round": round_number, "survivor": survivor, "accepted": False, "reason": "mutant_source_unavailable"}
            _write_json(round_dir / "round_summary.json", summary)
            rounds.append(summary)
            continue
        prompt = build_augmentation_prompt(initial_prompt, source, mutant_source)
        _write_state(run_dir, "CALLING_AUGMENTATION", api_called=True, round=round_number, next_stage="mutap_survivor_augmentation")
        augmentation = _call(
            run_dir, "mutap_survivor_augmentation", prompt,
            "mutap_survivor_augmentation_{0:03d}".format(round_number), round_number,
            [record.get("file_path")],
        )
        new_methods, _ = extract_test_methods(_extract_java(augmentation.get("content")))
        if not new_methods:
            summary = {"round": round_number, "survivor": survivor, "accepted": False, "reason": "no_junit_test_method"}
            _write_json(round_dir / "round_summary.json", summary)
            rounds.append(summary)
            continue
        old_methods, _ = extract_test_methods(source)
        candidate = render_methods(source, old_methods + new_methods)
        candidate, candidate_outcome, step, validation = _validate_suite(
            run_dir, pipeline, candidate, test_class_name, step + 1, "augmentation_{0:03d}".format(round_number)
        )
        accepted = validation["passed"]
        if accepted:
            source, outcome = candidate, candidate_outcome
            mutation = execute_mutants(
                run_dir, execution_id="mutap-r{0:03d}".format(round_number), workers=mutant_workers,
                artifact_result=_artifact_result_path(pipeline, outcome), publish_latest=False,
            )
            _, live, problematic = _status_sets(mutation)
        summary = {
            "round": round_number, "survivor": survivor, "accepted": accepted,
            "generated_test_method_count": len(new_methods), "remaining_live": len(live),
            "problematic_mutants": len(problematic), "validation": validation,
            "mutation_execution_id": mutation["execution_id"] if accepted else None,
        }
        _write_json(round_dir / "round_summary.json", summary)
        rounds.append(summary)

    minimized, minimized_outcome, step, minimization = _minimize(
        run_dir, pipeline, source, step, mutant_workers
    )
    if minimized_outcome is not None:
        source, outcome = minimized, minimized_outcome
    _write_json(run_dir / "minimization.json", minimization)
    (run_dir / "raw_tests" / "final.java").write_text(source, encoding="utf-8")
    final = pipeline.publish_final(outcome)
    accepted_count = len(extract_test_methods(source)[0])
    _write_state(
        run_dir, "ORIGINAL_PASSED", api_called=True, rounds_completed=len(rounds),
        accepted_test_count=accepted_count, project_artifact_root=final["project_artifact_root"],
        final_source_root=final["final_source_root"], final_classes_root=final["final_classes_root"],
        next_stage=None,
    )
    final_mutation = execute_mutants(run_dir, workers=mutant_workers, publish_latest=True)
    result = {
        "state": "COMPLETED", "run_dir": str(run_dir), "accepted_test_count": accepted_count,
        "rounds": rounds, "minimization": minimization, "final": final,
        "final_mutation": final_mutation,
    }
    _write_json(run_dir / "mutap_result.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True)
    parser.add_argument("--mutant-workers", type=int, default=0)
    parser.add_argument("--survivor-seed", type=int, default=DEFAULT_SURVIVOR_SEED)
    args = parser.parse_args()
    try:
        result = run_pipeline(args.run_dir, args.mutant_workers, args.survivor_seed)
    except Exception as error:
        run_dir = Path(args.run_dir).resolve()
        state_path = run_dir / "state.json"
        state = _read_json(state_path) if state_path.is_file() else {}
        failure_state = classify_pipeline_failure(error, state.get("state"))
        _write_state(run_dir, failure_state, api_called=bool(state.get("api_called")), error_type=type(error).__name__, error_message=str(error))
        raise
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
