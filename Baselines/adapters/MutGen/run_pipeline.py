"""Run MutGen's summary, four mutation-guided rounds, repair, and final evaluation."""

from __future__ import print_function

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path

import yaml

from protocol import (
    BASELINE,
    ASSERT_REPAIR_SYSTEM_PROMPT,
    COMPILE_REPAIR_SYSTEM_PROMPT,
    GENERATION_SYSTEM_PROMPT,
    MAX_GENERATION_ROUNDS,
    SUMMARY_SYSTEM_PROMPT,
    VISIBLE_MUTANT_STATUSES,
    build_generation_prompt,
    build_mutation_feedback,
    build_repair_prompt,
    build_summary_prompt,
    physical_key,
)


ADAPTER_DIR = Path(__file__).resolve().parent
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from failure_classification import classify_pipeline_failure
from java_test_pipeline import JavaTestPipeline
from jacoco_coverage import collect_line_coverage
from mutant_execution import execute_mutants
from mutation_line_mapping import map_mutant_line
from traced_llm_call import generate_traced


class ModelOutputTruncatedError(ValueError):
    """Structured MutGen output ended at the configured author token limit."""


def require_complete_structured_response(response, stage):
    finish_reason = str(response.get("finish_reason") or "").strip().lower()
    if finish_reason not in ("length", "max_tokens"):
        return
    usage = response.get("usage") or {}
    output_tokens = usage.get("completion_tokens") or usage.get("output_tokens")
    raise ModelOutputTruncatedError(
        "{0} response was truncated at max_tokens={1}; output_tokens={2}; "
        "finish_reason={3}".format(
            stage, response.get("max_tokens"), output_tokens, finish_reason
        )
    )


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


def _normalize_yaml_literal_blocks(candidate):
    """Repair only indentation lost inside MutGen's two YAML literal fields.

    DeepSeek occasionally emits one Java line at the YAML field indentation
    instead of the required literal-content indentation. Java 8 ignores this
    whitespace, but the YAML parser correctly rejects the document. The
    author schema has fixed ``test_code``/``new_imports`` literal fields, so we
    can restore the YAML envelope deterministically without changing tokens in
    the generated Java line or consuming an LLM repair call.
    """
    lines = str(candidate).splitlines()
    result = []
    literal_indent = None
    for line in lines:
        field = re.match(
            r"^(?P<space>\s*)(?:test_code|new_imports_|new_imports_code)\s*:\s*\|[+-]?\s*$",
            line,
        )
        item = re.match(r"^\s*-\s+test_behavior\s*:", line)
        next_field = re.match(
            r"^\s*(?:test_behavior|test_name|test_code|new_imports_|new_imports_code)\s*:",
            line,
        )
        if literal_indent is not None and (item or next_field):
            literal_indent = None
        if literal_indent is not None and line.strip():
            current_indent = len(line) - len(line.lstrip(" "))
            if current_indent < literal_indent:
                line = " " * (literal_indent - current_indent) + line
        result.append(line)
        if field:
            literal_indent = len(field.group("space")) + 2
    return "\n".join(result)


def parse_new_tests(content, diagnostics=None):
    """Parse the author-compatible YAML response into normalized test entries."""
    text = str(content or "").strip()
    candidates = []

    # DeepSeek commonly wraps the entire author-compatible payload in one YAML
    # fence.  Strip that outer fence first instead of relying only on a global
    # non-greedy regex: the closing three backticks can otherwise also be seen
    # as zero-length fenced blocks when responses contain adjacent backticks.
    outer = re.match(
        r"^\s*```(?:yaml|yml)?[ \t]*\r?\n(?P<body>[\s\S]*?)\r?\n```\s*$",
        text,
        re.IGNORECASE,
    )
    if outer:
        candidates.append(outer.group("body"))

    fences = re.findall(
        r"```(?:yaml|yml)?[ \t]*\r?\n([\s\S]*?)\r?\n```",
        text,
        re.IGNORECASE,
    )
    # Prefer the substantive fenced payload and ignore empty fence artifacts.
    candidates.extend(sorted((item for item in fences if item.strip()), key=len, reverse=True))
    candidates.append(text)

    # Keep candidate ordering deterministic without parsing the same payload twice.
    unique_candidates = []
    seen = set()
    for candidate in candidates:
        normalized = candidate.strip()
        if normalized and normalized not in seen:
            seen.add(normalized)
            unique_candidates.append(normalized)

    # Try the exact response first, then the schema-bounded deterministic
    # indentation normalization. This keeps valid author output untouched.
    parse_candidates = []
    for candidate in unique_candidates:
        parse_candidates.append((candidate, False))
        repaired = _normalize_yaml_literal_blocks(candidate)
        if repaired != candidate and repaired not in seen:
            seen.add(repaired)
            parse_candidates.append((repaired, True))

    last_error = None
    for candidate, literal_indent_repaired in parse_candidates:
        try:
            value = yaml.safe_load(candidate)
        except yaml.YAMLError as error:
            last_error = error
            continue
        if not isinstance(value, dict) or not isinstance(value.get("new_tests"), list):
            continue
        tests = []
        for item in value["new_tests"]:
            if not isinstance(item, dict):
                continue
            code = str(item.get("test_code") or "").strip()
            if not code:
                continue
            tests.append(
                {
                    "test_behavior": str(item.get("test_behavior") or "").strip(),
                    "test_name": str(item.get("test_name") or "").strip(),
                    "test_code": code,
                    "new_imports": str(
                        item.get("new_imports_") or item.get("new_imports_code") or ""
                    ).strip(),
                }
            )
        if tests:
            if diagnostics is not None:
                diagnostics.update(
                    {
                        "schema": "mutgen_new_tests_yaml",
                        "parsed_test_count": len(tests),
                        "literal_block_indentation_repaired": literal_indent_repaired,
                    }
                )
            return tests
    raise ValueError("MutGen response has no valid new_tests YAML: {0}".format(last_error or "schema mismatch"))


def _safe_identifier(value):
    value = re.sub(r"[^A-Za-z0-9_$]", "_", str(value))
    return value if value and not value[0].isdigit() else "T_" + value


def render_suite(context, tests):
    package_line = ""
    if context.get("package_name"):
        package_line = "package {0};\n\n".format(context["package_name"])
    imports = ["import org.junit.Test;", "import static org.junit.Assert.*;"]
    imports.extend(context.get("imports") or [])
    for test in tests:
        imports.extend(
            line.strip()
            for line in test.get("new_imports", "").splitlines()
            if line.strip().startswith("import ")
        )
    imports = list(dict.fromkeys(imports))
    class_name = _safe_identifier(context["simple_class_name"] + "_MutGenTest")
    methods = "\n\n".join(test["test_code"].strip() for test in tests)
    source = (
        package_line
        + "\n".join(imports)
        + "\n\npublic class "
        + class_name
        + " {\n"
        + methods
        + "\n}\n"
    )
    return class_name, source


def _log_text(outcome, kind):
    path = outcome[kind].get("log_file")
    return Path(path).read_text(encoding="utf-8", errors="replace") if path else ""


def author_duplicate_method_fix(candidate, compiler_output):
    """Apply the exact deterministic duplicate-name branch in unit_test_gen.py."""
    match = re.search(
        r"method\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^()]*\)\s+is already",
        str(compiler_output),
    )
    if not match:
        return None
    method_name = match.group(1)
    fixed = dict(candidate)
    fixed["test_code"] = re.sub(
        r"\b{0}\b".format(re.escape(method_name)),
        "dedul_" + method_name,
        candidate["test_code"],
    )
    return fixed


def _artifact_result_path(pipeline, step):
    return pipeline.layout.round_paths(step)["report"] / "result.json"


def _status_map(summary, covered_lines=None, line_mapping=None):
    covered_lines = set(int(value) for value in (covered_lines or []))
    line_mapping = line_mapping or {}
    rows = _read_jsonl(summary["results_jsonl"])
    result = {}
    infrastructure = []
    for row in rows:
        key = str(row.get("file_path") or "").replace("\\", "/").lower()
        status = str(row.get("status") or "").upper()
        if status.startswith("KILLED"):
            result[key] = "KILLED"
        elif status == "LIVE":
            mapped_line = (line_mapping.get(key) or {}).get("mapped_source_line")
            result[key] = (
                "NO_COVERAGE"
                if mapped_line is not None and int(mapped_line) not in covered_lines
                else "LIVE"
            )
        else:
            infrastructure.append({"file_path": row.get("file_path"), "status": status})
    if infrastructure:
        raise RuntimeError(
            "MutGen round has mutation infrastructure errors; feedback would be incomplete: {0}".format(
                infrastructure[:3]
            )
        )
    return result


def _visible_paths(records, status_by_file):
    paths = []
    for record in records:
        key = physical_key(record)
        status = str(status_by_file.get(key, "NO_COVERAGE")).upper()
        if status in VISIBLE_MUTANT_STATUSES:
            paths.append(record.get("file_path"))
    return paths


def run_pipeline(run_dir, mutant_workers=0, max_rounds=MAX_GENERATION_ROUNDS):
    run_dir = Path(run_dir).resolve()
    if max_rounds != MAX_GENERATION_ROUNDS:
        raise ValueError("Native MutGen protocol requires exactly four maximum rounds")
    state = _read_json(run_dir / "state.json")
    if state.get("state") != "PREPARED_API_NOT_CALLED":
        raise RuntimeError("MutGen run is not freshly prepared: {0}".format(state))
    context = _read_json(run_dir / "target_context.json")
    metadata = _read_json(run_dir / "metadata.json")
    records = _read_jsonl(run_dir / "source_records.jsonl")
    original_context = (run_dir / "parsed" / "original_context.java").read_text(encoding="utf-8")
    source_without_comments = (run_dir / "parsed" / "source_without_comments.java").read_text(encoding="utf-8")
    pipeline = JavaTestPipeline(run_dir, "MutGen")
    accepted = []
    validation_step = 0
    last_passed_outcome = None
    status_by_file = {}
    round_summaries = []
    line_mapping = {}
    for record in records:
        key = physical_key(record)
        if key not in line_mapping:
            line_mapping[key] = map_mutant_line(
                record, metadata["source_path"], context.get("method_body")
            )
    _write_json(run_dir / "parsed" / "mutation_line_mapping.json", line_mapping)

    summary_prompt = build_summary_prompt(original_context)
    (run_dir / "prompts" / "mutgen_summary_0.system.txt").write_text(SUMMARY_SYSTEM_PROMPT + "\n", encoding="utf-8")
    (run_dir / "prompts" / "mutgen_summary_0.user.txt").write_text(summary_prompt + "\n", encoding="utf-8")
    _write_state(run_dir, "CALLING_SUMMARY", api_called=True, next_stage="mutgen_summary")
    response = generate_traced(
        BASELINE, "mutgen_summary", SUMMARY_SYSTEM_PROMPT, summary_prompt, run_dir,
        response_file=run_dir / "responses" / "mutgen_summary_0.json", round_number=0,
        visible_to_model=False,
        use_cache=False,
    )
    summary_text = " ".join(str(response.get("content") or "").split())
    if not summary_text:
        raise RuntimeError("MutGen summary response is empty")
    (run_dir / "parsed" / "mutgen_summary.txt").write_text(summary_text + "\n", encoding="utf-8")

    for round_number in range(1, max_rounds + 1):
        round_dir = run_dir / "rounds" / "{0:03d}".format(round_number)
        round_dir.mkdir(parents=True, exist_ok=False)
        feedback = build_mutation_feedback(records, status_by_file)
        _write_json(round_dir / "mutation_feedback.json", feedback)
        if not feedback:
            round_summaries.append({"round": round_number, "state": "ALL_MUTANTS_KILLED", "accepted_total": len(accepted)})
            break
        generation_prompt = build_generation_prompt(summary_text, source_without_comments, feedback)
        (run_dir / "prompts" / "mutgen_generation_{0}.system.txt".format(round_number)).write_text(
            GENERATION_SYSTEM_PROMPT + "\n", encoding="utf-8"
        )
        (run_dir / "prompts" / "mutgen_generation_{0}.user.txt".format(round_number)).write_text(
            generation_prompt + "\n", encoding="utf-8"
        )
        _write_state(run_dir, "CALLING_GENERATION", api_called=True, round=round_number, next_stage="mutgen_generation")
        generation = generate_traced(
            BASELINE, "mutgen_generation", GENERATION_SYSTEM_PROMPT, generation_prompt, run_dir,
            response_file=run_dir / "responses" / "mutgen_generation_{0}.json".format(round_number),
            round_number=round_number, visible_to_model=True,
            visible_file_paths=_visible_paths(records, status_by_file),
            use_cache=False,
        )
        require_complete_structured_response(generation, "mutgen_generation")
        parse_diagnostics = {}
        candidates = parse_new_tests(generation.get("content"), parse_diagnostics)
        _write_json(round_dir / "response_parse.json", parse_diagnostics)
        _write_json(round_dir / "parsed_candidates.json", candidates)
        validations = []
        accepted_this_round = 0
        for candidate_index, candidate in enumerate(candidates, 1):
            validation_step += 1
            _, source = render_suite(context, accepted + [candidate])
            outcome = pipeline.compile_and_run(source, validation_step)
            entry = {"candidate": candidate_index, "initial_step": validation_step, "initial": outcome}
            selected = candidate
            if not outcome["compile"]["compiled"] or not outcome["execution"]["passed"]:
                failure_type = "COMPILATION" if not outcome["compile"]["compiled"] else "ASSERTION"
                stage = "mutgen_compile_repair" if failure_type == "COMPILATION" else "mutgen_assert_repair"
                repair_system = COMPILE_REPAIR_SYSTEM_PROMPT if failure_type == "COMPILATION" else ASSERT_REPAIR_SYSTEM_PROMPT
                error_text = _log_text(outcome, "compile" if failure_type == "COMPILATION" else "execution")
                repaired = (
                    author_duplicate_method_fix(candidate, error_text)
                    if failure_type == "COMPILATION"
                    else None
                )
                if repaired is not None:
                    entry["repair_stage"] = "author_deterministic_duplicate_rename"
                else:
                    repair_prompt = build_repair_prompt(candidate["test_code"], error_text, failure_type)
                    (run_dir / "prompts" / "{0}_{1}_{2}.user.txt".format(stage, round_number, candidate_index)).write_text(
                        repair_prompt + "\n", encoding="utf-8"
                    )
                    repair = generate_traced(
                        BASELINE, stage, repair_system, repair_prompt, run_dir,
                        response_file=run_dir / "responses" / "{0}_{1}_{2}.json".format(stage, round_number, candidate_index),
                        round_number=round_number, visible_to_model=False, use_cache=False,
                    )
                    require_complete_structured_response(repair, stage)
                    repaired = parse_new_tests(repair.get("content"))[0]
                validation_step += 1
                _, repaired_source = render_suite(context, accepted + [repaired])
                outcome = pipeline.compile_and_run(repaired_source, validation_step)
                entry.update({"repair_stage": entry.get("repair_stage", stage), "repair_step": validation_step, "repair": outcome})
                selected = repaired
            if outcome["compile"]["compiled"] and outcome["execution"]["passed"]:
                accepted.append(selected)
                accepted_this_round += 1
                last_passed_outcome = outcome
                entry["accepted"] = True
            else:
                entry["accepted"] = False
            validations.append(entry)
        _write_json(round_dir / "validations.json", validations)
        _write_json(round_dir / "accepted_tests.json", accepted)
        if last_passed_outcome is None:
            _write_state(run_dir, "ORIGINAL_FAILED", api_called=True, round=round_number, reason="no_generated_test_passed")
            return {"state": "ORIGINAL_FAILED", "rounds": round_summaries}

        artifact_result = _artifact_result_path(pipeline, last_passed_outcome["round"])
        mutation = execute_mutants(
            run_dir,
            execution_id="mutgen-r{0:02d}".format(round_number),
            workers=mutant_workers,
            artifact_result=artifact_result,
            publish_latest=False,
        )
        coverage = collect_line_coverage(
            artifact_result, metadata, round_dir / "coverage"
        )
        status_by_file = _status_map(
            mutation,
            covered_lines=coverage["covered_lines"],
            line_mapping=line_mapping,
        )
        _write_json(round_dir / "mutation_status_by_file.json", status_by_file)
        round_summary = {
            "round": round_number,
            "candidate_count": len(candidates),
            "accepted_this_round": accepted_this_round,
            "accepted_total": len(accepted),
            "mutation_execution_id": mutation["execution_id"],
            "mutation_score": mutation["executable_ms"],
            "remaining_live": sum(value == "LIVE" for value in status_by_file.values()),
            "remaining_no_coverage": sum(
                value == "NO_COVERAGE" for value in status_by_file.values()
            ),
            "covered_line_count": coverage["covered_line_count"],
            "line_covered_ratio": coverage["line_covered_ratio"],
            "coverage_file": str(round_dir / "coverage" / "line_coverage.json"),
            "artifact_result": str(artifact_result),
        }
        _write_json(round_dir / "round_summary.json", round_summary)
        round_summaries.append(round_summary)

    if last_passed_outcome is None:
        raise RuntimeError("MutGen completed no original-passing test suite")
    final = pipeline.publish_final(last_passed_outcome)
    _write_state(
        run_dir,
        "ORIGINAL_PASSED",
        api_called=True,
        rounds_completed=len([item for item in round_summaries if "mutation_execution_id" in item]),
        accepted_test_count=len(accepted),
        project_artifact_root=final["project_artifact_root"],
        final_source_root=final["final_source_root"],
        final_classes_root=final["final_classes_root"],
        next_stage=None,
    )
    final_mutation = execute_mutants(run_dir, workers=mutant_workers, publish_latest=True)
    result = {
        "state": "COMPLETED",
        "run_dir": str(run_dir),
        "accepted_test_count": len(accepted),
        "rounds": round_summaries,
        "final": final,
        "final_mutation": final_mutation,
    }
    _write_json(run_dir / "mutgen_result.json", result)
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
        failure_state = classify_pipeline_failure(error, state.get("state"))
        _write_state(
            run_dir,
            failure_state,
            api_called=bool(state.get("api_called")),
            error_type=type(error).__name__,
            error_message=str(error),
        )
        raise
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
