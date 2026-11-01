"""Summarize baseline time, LLM-call, repair, token, cache, and cost metrics.

The report keeps logical LLM calls, provider request attempts, and native LLM
repair rounds separate.  This distinction is required because provider retries
are not method-level repair rounds, and mutation-feedback generation is not a
compilation repair.
"""

from __future__ import print_function

import argparse
import json
import re
from collections import Counter
from datetime import datetime
from pathlib import Path

from summarize_token_usage import summarize_run as summarize_token_run
from mutant_execution import _group_mutant_records


BASELINES_DIR = Path(__file__).resolve().parents[2]
LLM_CONFIG = BASELINES_DIR / "configs" / "llm.properties"

REPAIR_STAGES = {
    "chattester_compile_repair",
    "chattester_runtime_repair",
    "mutgen_compile_repair",
    "mutgen_assert_repair",
    "mutap_syntax_repair",
    "hits_test_repair",
    "aster_repair",
}
FEEDBACK_GENERATION_STAGES = {
    "mutgen_generation",
    "mutap_survivor_augmentation",
    "aster_coverage",
}


def _now():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _read_jsonl(path):
    values = []
    path = Path(path)
    if not path.is_file():
        return values
    with path.open("r", encoding="utf-8") as stream:
        for line in stream:
            if line.strip():
                values.append(json.loads(line))
    return values


def _write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def _timestamp_millis(start, finish):
    if not start or not finish:
        return None
    try:
        left = datetime.fromisoformat(str(start).replace("Z", "+00:00"))
        right = datetime.fromisoformat(str(finish).replace("Z", "+00:00"))
        return max(0, int((right - left).total_seconds() * 1000))
    except (TypeError, ValueError):
        return None


def _elapsed_millis(value):
    if not isinstance(value, dict):
        return None
    for key in ("elapsed_millis", "duration_ms"):
        try:
            if value.get(key) is not None:
                return max(0, int(value[key]))
        except (TypeError, ValueError):
            pass
    return _timestamp_millis(
        value.get("started_at") or value.get("created_at"),
        value.get("finished_at") or value.get("completed_at"),
    )


def _properties(path):
    result = {}
    path = Path(path)
    if not path.is_file():
        return result
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")) or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def _optional_float(properties, key):
    raw = str(properties.get(key) or "").strip()
    if not raw:
        return None
    try:
        value = float(raw)
    except ValueError:
        raise RuntimeError("Invalid pricing configuration {0}: {1}".format(key, raw))
    if value < 0:
        raise RuntimeError("Pricing configuration must not be negative: " + key)
    return value


def _pricing():
    properties = _properties(LLM_CONFIG)
    names = {
        "uncached_input": "llm.pricing.uncached.input.usd.per.million",
        "cache_read_input": "llm.pricing.cache.read.input.usd.per.million",
        "cache_write_input": "llm.pricing.cache.write.input.usd.per.million",
        "output": "llm.pricing.output.usd.per.million",
    }
    rates = {name: _optional_float(properties, key) for name, key in names.items()}
    configured = all(value is not None for value in rates.values())
    return {"configured": configured, "rates": rates, "keys": names}


def _provider_billed_cost(response):
    """Return only explicitly USD-labelled provider cost; never infer billing."""
    candidates = [
        (response, "provider_billed_cost_usd"),
        (response, "billed_cost_usd"),
        (response, "cost_usd"),
        (response.get("usage") or {}, "billed_cost_usd"),
        ((response.get("raw_response") or {}).get("usage") or {}, "billed_cost_usd"),
    ]
    for mapping, key in candidates:
        if not isinstance(mapping, dict) or mapping.get(key) is None:
            continue
        try:
            return float(mapping[key])
        except (TypeError, ValueError):
            continue
    return None


def _failed_attempt_count(*messages):
    for message in messages:
        match = re.search(r"after\s+(\d+)\s+attempt", str(message or ""), re.IGNORECASE)
        if match:
            return int(match.group(1))
    return 0


def _call_metrics(run_dir):
    run_dir = Path(run_dir)
    calls_dir = run_dir / "calls"
    stage_counts = Counter()
    stage_attempts = Counter()
    elapsed = 0
    logical_calls = 0
    provider_attempts = 0
    failed_calls = 0
    provider_cost = 0.0
    provider_cost_reported = 0
    if calls_dir.is_dir():
        for call_dir in sorted(path for path in calls_dir.iterdir() if path.is_dir()):
            call_path = call_dir / "call.json"
            response_path = call_dir / "response.json"
            if not call_path.is_file():
                continue
            call = _read_json(call_path)
            response = _read_json(response_path) if response_path.is_file() else {}
            stage = str(call.get("stage") or response.get("stage") or "unknown")
            logical_calls += 1
            stage_counts[stage] += 1
            local_cache_hit = bool(
                call.get("cache_hit") or response.get("cache_hit")
            )
            attempts = 0 if local_cache_hit else int(
                call.get("attempts") or response.get("attempts") or 0
            )
            if attempts == 0 and not local_cache_hit and call.get("status") == "SUCCESS":
                attempts = 1
            if attempts == 0 and not local_cache_hit and call.get("status") == "ERROR":
                attempts = _failed_attempt_count(
                    call.get("error_message"), response.get("error_message")
                )
            provider_attempts += attempts
            stage_attempts[stage] += attempts
            elapsed += int(
                call.get("logical_call_elapsed_millis")
                or call.get("elapsed_millis")
                or response.get("elapsed_millis")
                or 0
            )
            failed_calls += int(str(call.get("status") or "").upper() == "ERROR")
            billed = None if local_cache_hit else _provider_billed_cost(response)
            if billed is not None:
                provider_cost += billed
                provider_cost_reported += 1
    return {
        "logical_llm_call_count": logical_calls,
        "provider_api_request_attempt_count": provider_attempts,
        "failed_logical_call_count": failed_calls,
        "llm_elapsed_millis": elapsed,
        "llm_repair_round_count": sum(stage_counts[name] for name in REPAIR_STAGES),
        "llm_repair_provider_attempt_count": sum(stage_attempts[name] for name in REPAIR_STAGES),
        "feedback_generation_call_count": sum(
            stage_counts[name] for name in FEEDBACK_GENERATION_STAGES
        ),
        "calls_by_stage": dict(sorted(stage_counts.items())),
        "provider_attempts_by_stage": dict(sorted(stage_attempts.items())),
        "provider_billed_cost_usd": (
            provider_cost if provider_cost_reported == logical_calls and logical_calls else None
        ),
        "provider_billed_cost_reported_call_count": provider_cost_reported,
    }


def _java_metrics(run_dir):
    run_dir = Path(run_dir)
    mapping_path = run_dir / "project_artifacts.json"
    metrics = {
        "compile_attempt_count": 0,
        "compile_success_count": 0,
        "compile_elapsed_millis": 0,
        "original_test_execution_attempt_count": 0,
        "original_test_execution_success_count": 0,
        "original_test_execution_elapsed_millis": 0,
        "artifact_root_available": False,
    }
    if not mapping_path.is_file():
        return metrics
    artifact_root = Path(_read_json(mapping_path).get("project_artifact_root") or "")
    rounds = artifact_root / "rounds"
    if not rounds.is_dir():
        return metrics
    metrics["artifact_root_available"] = True
    for report in sorted(rounds.glob("*/report/result.json")):
        result = _read_json(report)
        compile_result = result.get("compile") or {}
        execution = result.get("execution") or {}
        metrics["compile_attempt_count"] += 1
        metrics["compile_success_count"] += int(bool(compile_result.get("compiled")))
        metrics["compile_elapsed_millis"] += int(_elapsed_millis(compile_result) or 0)
        if execution.get("executed"):
            metrics["original_test_execution_attempt_count"] += 1
            metrics["original_test_execution_success_count"] += int(
                bool(execution.get("passed"))
            )
            metrics["original_test_execution_elapsed_millis"] += int(
                _elapsed_millis(execution) or 0
            )
    return metrics


def _mutation_metrics(run_dir):
    root = Path(run_dir) / "mutant_execution"
    summaries = []
    # Efficiency must describe the same formal execution selected by the
    # mutation-score summarizer.  Historical reruns and --no-publish-latest
    # smoke executions remain auditable on disk but must not be accumulated.
    latest_path = root / "latest.json"
    if latest_path.is_file():
        try:
            latest = _read_json(latest_path)
            execution_id = str(latest.get("execution_id") or "").strip()
            summary_path = root / execution_id / "summary.json"
            if execution_id and summary_path.is_file():
                summaries.append(_read_json(summary_path))
        except (OSError, ValueError):
            pass
    return {
        "mutation_execution_invocation_count": len(summaries),
        "mutation_execution_elapsed_millis": sum(
            int(_elapsed_millis(value) or 0) for value in summaries
        ),
        "mutant_jvm_execution_count": sum(
            int(value.get("mutants_executed") or 0) for value in summaries
        ),
    }


def summarize_target(run_dir, write_report=True):
    run_dir = Path(run_dir).resolve()
    metadata = _read_json(run_dir / "metadata.json")
    token = (
        summarize_token_run(run_dir).get("totals", {})
        if (run_dir / "calls").is_dir()
        else {}
    )
    calls = _call_metrics(run_dir)
    native_generation = int(metadata.get("native_generation_elapsed_millis") or 0)
    generation_elapsed = calls["llm_elapsed_millis"] + native_generation
    java = _java_metrics(run_dir)
    mutation = _mutation_metrics(run_dir)
    preprocessing = metadata.get("preprocessing_elapsed_millis")
    known_components = (
        int(preprocessing or 0)
        + generation_elapsed
        + java["compile_elapsed_millis"]
        + java["original_test_execution_elapsed_millis"]
        + mutation["mutation_execution_elapsed_millis"]
    )
    pricing = _pricing()
    estimated_cost = None
    if pricing["configured"]:
        rates = pricing["rates"]
        estimated_cost = (
            int(token.get("uncached_input_tokens") or 0) * rates["uncached_input"]
            + int(token.get("cache_read_input_tokens") or 0) * rates["cache_read_input"]
            + int(token.get("cache_write_input_tokens") or 0) * rates["cache_write_input"]
            + int(token.get("output_tokens") or 0) * rates["output"]
        ) / 1000000.0
    report = {
        "schema_version": 1,
        "generated_at": _now(),
        "run_dir": str(run_dir),
        "baseline": metadata.get("baseline"),
        "project": metadata.get("project"),
        "class_name": metadata.get("class_name"),
        "method_signature": metadata.get("method_signature"),
        "time": {
            "preprocessing_elapsed_millis": preprocessing,
            "llm_generation_elapsed_millis": calls["llm_elapsed_millis"],
            "native_generation_elapsed_millis": native_generation,
            "generation_elapsed_millis": generation_elapsed,
            "compile_elapsed_millis": java["compile_elapsed_millis"],
            "original_test_execution_elapsed_millis": java["original_test_execution_elapsed_millis"],
            "mutation_execution_elapsed_millis": mutation["mutation_execution_elapsed_millis"],
            "measured_component_total_millis": known_components,
            "preprocessing_timing_available": preprocessing is not None,
        },
        "llm": calls,
        "java_validation": java,
        "mutation_evaluation": mutation,
        "tokens": token,
        "cost": {
            "provider_billed_cost_usd": calls["provider_billed_cost_usd"],
            "provider_billed_cost_complete": (
                calls["logical_llm_call_count"] > 0
                and calls["provider_billed_cost_reported_call_count"]
                == calls["logical_llm_call_count"]
            ),
            "estimated_cost_usd": estimated_cost,
            "estimate_source": (
                "configured_token_rates" if pricing["configured"] else "UNAVAILABLE"
            ),
            "pricing_rates_usd_per_million": pricing["rates"],
        },
    }
    if write_report:
        _write_json(run_dir / "efficiency_summary.json", report)
    return report


def _sum_nested(reports, section, key):
    return sum(int((report.get(section) or {}).get(key) or 0) for report in reports)


def summarize_batch(batch_dir, write_target_reports=True):
    batch_dir = Path(batch_dir).resolve()
    targets_dir = batch_dir / "targets"
    if not targets_dir.is_dir():
        raise FileNotFoundError("Missing batch targets directory: " + str(targets_dir))
    target_reports = []
    for target_dir in sorted(path for path in targets_dir.iterdir() if path.is_dir()):
        if (target_dir / "metadata.json").is_file():
            target_reports.append(summarize_target(target_dir, write_target_reports))

    result_rows = _read_jsonl(batch_dir / "batch_results.jsonl")
    result_by_run = {
        str(Path(row["run_dir"]).resolve()): row
        for row in result_rows
        if row.get("run_dir")
    }
    target_total_millis = 0
    target_total_sources = Counter()
    for report in target_reports:
        row = result_by_run.get(str(Path(report["run_dir"]).resolve()))
        wall = _timestamp_millis(
            row.get("started_at") if row else None,
            row.get("finished_at") if row else None,
        )
        preprocessing = int(report["time"].get("preprocessing_elapsed_millis") or 0)
        prepared_at = _read_json(Path(report["run_dir"]) / "metadata.json").get(
            "preparation_started_at"
        )
        row_started = row.get("started_at") if row else None
        includes_preparation = False
        if wall is not None and prepared_at and row_started:
            includes_preparation = _timestamp_millis(row_started, prepared_at) is not None
            try:
                includes_preparation = datetime.fromisoformat(
                    str(row_started).replace("Z", "+00:00")
                ) <= datetime.fromisoformat(str(prepared_at).replace("Z", "+00:00"))
            except ValueError:
                includes_preparation = False
        component_value = int(report["time"]["measured_component_total_millis"])
        if wall is not None:
            wall_value = wall if includes_preparation else wall + preprocessing
            if component_value > wall_value:
                value = component_value
                source = "measured_components_exceed_partial_target_wall"
            else:
                value = wall_value
                source = "target_wall_clock" if includes_preparation else "prepared_target_execution_wall_plus_preprocessing"
        else:
            value = component_value
            source = "measured_components_fallback"
        report["time"]["paper_total_processing_millis"] = value
        report["time"]["paper_total_processing_source"] = source
        _write_json(Path(report["run_dir"]) / "efficiency_summary.json", report)
        target_total_millis += value
        target_total_sources[source] += 1

    count = len(target_reports)
    preprocessing_complete = all(
        value["time"].get("preprocessing_elapsed_millis") is not None
        for value in target_reports
    ) if target_reports else False
    preprocessing_total = _sum_nested(
        target_reports, "time", "preprocessing_elapsed_millis"
    )
    logical_calls = _sum_nested(target_reports, "llm", "logical_llm_call_count")
    provider_attempts = _sum_nested(
        target_reports, "llm", "provider_api_request_attempt_count"
    )
    repair_rounds = _sum_nested(target_reports, "llm", "llm_repair_round_count")
    feedback_calls = _sum_nested(
        target_reports, "llm", "feedback_generation_call_count"
    )
    calls_by_stage = Counter()
    attempts_by_stage = Counter()
    for value in target_reports:
        calls_by_stage.update((value.get("llm") or {}).get("calls_by_stage") or {})
        attempts_by_stage.update(
            (value.get("llm") or {}).get("provider_attempts_by_stage") or {}
        )
    token_names = (
        "input_tokens", "uncached_input_tokens", "cache_read_input_tokens",
        "cache_write_input_tokens", "output_tokens", "reasoning_output_tokens",
        "total_tokens",
    )
    tokens = {name: _sum_nested(target_reports, "tokens", name) for name in token_names}
    cache_denominator = tokens["cache_read_input_tokens"] + tokens["uncached_input_tokens"]
    tokens["cache_hit_rate"] = (
        float(tokens["cache_read_input_tokens"]) / cache_denominator
        if cache_denominator else 0.0
    )
    tokens["input_tokens_millions"] = tokens["input_tokens"] / 1000000.0
    tokens["output_tokens_millions"] = tokens["output_tokens"] / 1000000.0

    provider_cost_values = [
        report["cost"]["provider_billed_cost_usd"] for report in target_reports
    ]
    estimated_values = [report["cost"]["estimated_cost_usd"] for report in target_reports]
    provider_cost = (
        sum(provider_cost_values) if provider_cost_values and all(
            value is not None for value in provider_cost_values
        ) else None
    )
    estimated_cost = (
        sum(estimated_values) if estimated_values and all(
            value is not None for value in estimated_values
        ) else None
    )
    metadata = _read_json(batch_dir / "batch_metadata.json") if (batch_dir / "batch_metadata.json").is_file() else {}
    summary_json = _read_json(batch_dir / "batch_summary.json") if (batch_dir / "batch_summary.json").is_file() else {}
    batch_wall = _timestamp_millis(
        metadata.get("started_at"),
        summary_json.get("finished_at") or metadata.get("execution_finished_at"),
    )
    source_rows_by_target = [
        _read_jsonl(Path(value["run_dir"]) / "source_records.jsonl")
        for value in target_reports
    ]
    mapped_source_record_count = sum(len(values) for values in source_rows_by_target)
    mapped_unique_mutant_count = sum(
        len(_group_mutant_records(values)) for values in source_rows_by_target
    )
    report = {
        "schema_version": 1,
        "generated_at": _now(),
        "batch_dir": str(batch_dir),
        "baseline": metadata.get("baseline") or (target_reports[0].get("baseline") if target_reports else None),
        "denominators": {
            "selected_focal_method_target_count": count,
            "mapped_source_record_count": mapped_source_record_count,
            "mapped_unique_physical_mutant_count": mapped_unique_mutant_count,
            "primary_average_unit": "one deduplicated focal-method generation target",
            "warning": "This implementation generates once per deduplicated focal method, not once per mutant; do not relabel this denominator as |M_NE|.",
        },
        "paper_time_columns": {
            "t_pre_seconds": (preprocessing_total / 1000.0) if preprocessing_complete else None,
            "t_pre_avg_seconds": (preprocessing_total / 1000.0 / count) if count and preprocessing_complete else None,
            "t_pre_complete": preprocessing_complete,
            "t_gen_seconds": _sum_nested(target_reports, "time", "generation_elapsed_millis") / 1000.0,
            "t_gen_avg_seconds": (_sum_nested(target_reports, "time", "generation_elapsed_millis") / 1000.0 / count) if count else None,
            "r_avg": (float(repair_rounds) / count) if count else None,
            "t_total_seconds": target_total_millis / 1000.0,
            "t_avg_seconds": (target_total_millis / 1000.0 / count) if count else None,
            "t_avg_per_mapped_unique_mutant_seconds": (
                target_total_millis / 1000.0 / mapped_unique_mutant_count
                if mapped_unique_mutant_count else None
            ),
            "t_total_complete": preprocessing_complete,
            "target_total_time_sources": dict(sorted(target_total_sources.items())),
            "batch_recorded_phase_wall_clock_seconds": (batch_wall / 1000.0) if batch_wall is not None else None,
            "batch_wall_clock_scope": "batch_metadata.started_at to recorded batch completion; staged/manual phases may be outside this interval",
        },
        "llm_usage": {
            "logical_llm_call_count": logical_calls,
            "api_calls_provider_request_attempts": provider_attempts,
            "generation_or_feedback_call_count": logical_calls - repair_rounds,
            "llm_repair_round_count": repair_rounds,
            "repair_attempted_target_count": sum(
                int(value["llm"].get("llm_repair_round_count") or 0) > 0
                for value in target_reports
            ),
            "feedback_generation_call_count": feedback_calls,
            "logical_calls_by_stage": dict(sorted(calls_by_stage.items())),
            "provider_attempts_by_stage": dict(sorted(attempts_by_stage.items())),
            "tokens": tokens,
            "provider_billed_cost_usd": provider_cost,
            "estimated_cost_usd": estimated_cost,
            "cost_rule": "Provider billed cost is reported only when every call has an explicit USD billing field; configured token-rate estimates remain separate.",
        },
        "execution_counts": {
            "compile_attempt_count": _sum_nested(target_reports, "java_validation", "compile_attempt_count"),
            "original_test_execution_attempt_count": _sum_nested(target_reports, "java_validation", "original_test_execution_attempt_count"),
            "mutation_execution_invocation_count": _sum_nested(target_reports, "mutation_evaluation", "mutation_execution_invocation_count"),
            "mutant_jvm_execution_count": _sum_nested(target_reports, "mutation_evaluation", "mutant_jvm_execution_count"),
        },
        "definitions": {
            "api_calls": "Sum of provider request attempts; local response-cache hits add zero.",
            "repair_round": "One logical LLM call in a baseline-native repair stage. Provider retry attempts and mutation-feedback generation are excluded.",
            "t_gen": "Sum of recorded generation time. For LLM Baselines this is logical LLM-call time; for native tools it is search/generation time. Shared class-level generation is counted once.",
            "t_avg": "Sum of per-target processing wall time divided by selected deduplicated focal-method targets. Parallel batch wall time is reported separately.",
            "cache_hit_rate": "cache_read_input_tokens / (cache_read_input_tokens + uncached_input_tokens)",
        },
        "targets": [str(Path(value["run_dir"]) / "efficiency_summary.json") for value in target_reports],
    }
    _write_json(batch_dir / "efficiency_summary.json", report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", default="", help="one target run directory")
    parser.add_argument("--batch-dir", default="", help="one batch directory")
    args = parser.parse_args()
    if bool(args.run_dir) == bool(args.batch_dir):
        parser.error("provide exactly one of --run-dir or --batch-dir")
    report = summarize_target(args.run_dir) if args.run_dir else summarize_batch(args.batch_dir)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
