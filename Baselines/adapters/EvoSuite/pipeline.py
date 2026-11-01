"""Independent EvoSuite 1.0.6 generation and evaluation pipeline."""

from __future__ import print_function

import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path


ADAPTER_DIR = Path(__file__).resolve().parent
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from java_test_pipeline import JavaTestPipeline, _java_tool, _parse_source_identity, build_project_classpath
from manifest_paths import DEFAULT_TARGET_MANIFEST
from mutant_execution import _group_mutant_records, execute_mutants
from summarize_efficiency import summarize_batch as summarize_efficiency_batch
from summarize_mutation_batch import summarize_batch as summarize_mutation_batch
from target_context import load_method_groups
from source_cleaner import clean_tree


BASELINES_DIR = ADAPTER_DIR.parents[1]
DEFAULT_MANIFEST = DEFAULT_TARGET_MANIFEST
RUNS_DIR = BASELINES_DIR / "runs"
CONFIG_FILE = ADAPTER_DIR / "evosuite.properties"
BASELINE = "EvoSuite-1.0.6"


def _now():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _stamp():
    value = datetime.now().astimezone()
    return value.strftime("%Y%m%d%H%M%S") + "{0:03d}".format(value.microsecond // 1000)


def _read_properties(path=CONFIG_FILE):
    result = {}
    path = Path(path)
    if not path.is_file():
        return result
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def _write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _write_jsonl(path, values):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(value, ensure_ascii=False) + "\n" for value in values), encoding="utf-8")


def _sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _safe(value):
    return "".join(character if character.isalnum() or character in "._-" else "_" for character in str(value))[:120]


def _definition(properties, budget_seconds=0):
    jar_value = properties.get("evosuite.jar") or "lib/evosuite-master-1.0.6.jar"
    jar = Path(jar_value)
    if not jar.is_absolute():
        jar = ADAPTER_DIR / jar
    budget = int(budget_seconds or properties.get("evosuite.search.budget.seconds", "60"))
    return {
        "baseline": BASELINE,
        "jar": jar.resolve(),
        "budget": budget,
        "test_timeout_seconds": int(properties.get("evosuite.test.timeout.seconds", "4")),
        "max_size": int(properties.get("evosuite.max.size", "20")),
    }


def _validate(definition):
    if not definition["jar"].is_file():
        raise FileNotFoundError("EvoSuite jar does not exist: " + str(definition["jar"]))
    if definition["test_timeout_seconds"] < 1:
        raise ValueError("evosuite.test.timeout.seconds must be at least 1")


def default_batch_dir():
    stamp = _stamp()
    for sequence in range(1, 1000):
        candidate = RUNS_DIR / "{0}_evosuite_batch_{1:03d}".format(stamp, sequence)
        if not candidate.exists():
            return candidate
    raise RuntimeError("Unable to allocate EvoSuite batch directory")


def _generator_classpath(target, generation_dir):
    classes_dir = generation_dir / "classpath-classes"
    report_dir = generation_dir / "classpath-report"
    classes_dir.mkdir(parents=True, exist_ok=True)
    report_dir.mkdir(parents=True, exist_ok=True)
    return build_project_classpath(target, classes_dir, report_dir, timeout_sec=180)


def _run_command(command, cwd, timeout, log_path):
    started_at = _now()
    started = time.perf_counter()
    timeout_hit = False
    try:
        completed = subprocess.run(
            command,
            cwd=str(cwd),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            universal_newlines=True,
            timeout=timeout,
        )
        output = completed.stdout or ""
        returncode = completed.returncode
    except subprocess.TimeoutExpired as error:
        output = (error.stdout or "") + "\nGENERATOR PROCESS TIMEOUT"
        returncode = -1
        timeout_hit = True
    log_path.write_text(output, encoding="utf-8")
    return {
        "started_at": started_at,
        "finished_at": _now(),
        "elapsed_millis": int((time.perf_counter() - started) * 1000),
        "command": command,
        "returncode": returncode,
        "timeout": timeout_hit,
        "log_file": str(log_path.resolve()),
    }


def generate_class(target, generation_dir, seed, definition):
    generation_dir = Path(generation_dir).resolve()
    raw_dir = generation_dir / "raw_tests"
    report_dir = generation_dir / "generator_report"
    raw_dir.mkdir(parents=True)
    report_dir.mkdir()
    module_root, classpath_entries, classpath_warning = _generator_classpath(target, generation_dir)
    class_name = target["class_name"]
    command = [
        _java_tool("java"),
        "-jar", str(definition["jar"]),
        "-generateSuite",
        "-class", class_name,
        "-projectCP", os.pathsep.join(classpath_entries),
        "-Dsearch_budget={0}".format(definition["budget"]),
        "-Dtimeout={0}".format(definition["test_timeout_seconds"] * 1000),
        "-Dmax_size={0}".format(definition["max_size"]),
        "-Dtest_dir={0}".format(raw_dir),
        "-Dreport_dir={0}".format(report_dir),
        "-seed", str(seed),
    ]
    result = _run_command(
        command,
        module_root,
        max(120, definition["budget"] + 120),
        generation_dir / "generator.log",
    )
    result.update(
        {
            "schema_version": 1,
            "tool": "evosuite",
            "baseline": BASELINE,
            "class_key": "{0}|{1}".format(target["project"], class_name),
            "project": target["project"],
            "class_name": class_name,
            "seed": int(seed),
            "budget_seconds": definition["budget"],
            "test_timeout_seconds": definition["test_timeout_seconds"],
            "max_size": definition["max_size"],
            "jar": str(definition["jar"]),
            "jar_sha256": _sha256(definition["jar"]),
            "raw_tests_dir": str(raw_dir),
            "generated_java_file_count": len(list(raw_dir.rglob("*.java"))),
            "classpath_warning": classpath_warning or "",
        }
    )
    _write_json(generation_dir / "generation.json", result)
    return result


def _safe_generate_class(target, generation_dir, seed, definition):
    try:
        return generate_class(target, generation_dir, seed, definition)
    except Exception as error:
        generation_dir = Path(generation_dir).resolve()
        raw_dir = generation_dir / "raw_tests"
        raw_dir.mkdir(parents=True, exist_ok=True)
        log_path = generation_dir / "generator.log"
        log_path.write_text("{0}: {1}\n".format(type(error).__name__, error), encoding="utf-8")
        result = {
            "schema_version": 1,
            "tool": "evosuite",
            "baseline": BASELINE,
            "class_key": "{0}|{1}".format(target["project"], target["class_name"]),
            "project": target["project"],
            "class_name": target["class_name"],
            "seed": int(seed),
            "budget_seconds": definition["budget"],
            "test_timeout_seconds": definition["test_timeout_seconds"],
            "max_size": definition["max_size"],
            "jar": str(definition["jar"]),
            "jar_sha256": _sha256(definition["jar"]),
            "raw_tests_dir": str(raw_dir),
            "generated_java_file_count": 0,
            "started_at": _now(),
            "finished_at": _now(),
            "elapsed_millis": 0,
            "command": [],
            "returncode": -1,
            "timeout": False,
            "log_file": str(log_path),
            "setup_error_type": type(error).__name__,
            "setup_error_message": str(error),
        }
        _write_json(generation_dir / "generation.json", result)
        return result


def _prepare_target(records, manifest, run_dir, definition, generation, generation_owner):
    started = time.perf_counter()
    started_at = _now()
    run_dir = Path(run_dir).resolve()
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "raw_tests").mkdir(exist_ok=True)
    (run_dir / "clean_tests").mkdir(exist_ok=True)
    _write_jsonl(run_dir / "source_records.jsonl", records)
    target = records[0]
    metadata = {
        "schema_version": 1,
        "baseline": BASELINE,
        "preparation_started_at": started_at,
        "prepared_at": _now(),
        "preprocessing_elapsed_millis": int((time.perf_counter() - started) * 1000),
        "manifest": str(Path(manifest).resolve()),
        "manifest_sha256": _sha256(manifest),
        "project": target["project"],
        "project_root": target["project_root"],
        "build_root": target["build_root"],
        "class_name": target["class_name"],
        "method_signature": target["method_signature"],
        "source_path": target["source_path"],
        "mutant_count": len(records),
        "mutant_ids": [item["mutant_id"] for item in records],
        "test_framework": "junit4",
        "model_required": None,
        "llm_applicable": False,
        "information_policy": "evosuite_native_search_no_mutant_information",
        "generator_jar": str(definition["jar"]),
        "generator_jar_sha256": _sha256(definition["jar"]),
        "generation_scope": "once_per_selected_project_and_class",
        "generation_shared_key": generation["class_key"],
        "generation_shared_owner": bool(generation_owner),
        "native_generation_elapsed_millis": int(generation["elapsed_millis"]) if generation_owner else 0,
        "native_generation_actual_elapsed_millis": int(generation["elapsed_millis"]),
        "random_seed": generation["seed"],
        "generation_budget_seconds": generation["budget_seconds"],
        "test_method_timeout_seconds": generation["test_timeout_seconds"],
        "evosuite_max_size": generation.get("max_size"),
    }
    _write_json(run_dir / "metadata.json", metadata)
    _write_json(run_dir / "generation_reference.json", generation)
    _write_json(run_dir / "state.json", {"state": "PREPARED", "updated_at": _now()})


def _copy_sources(generation, run_dir):
    raw_source = Path(generation["raw_tests_dir"])
    raw_target = Path(run_dir) / "raw_tests"
    clean_target = Path(run_dir) / "clean_tests"
    test_timeout_millis = int(generation.get("test_timeout_seconds", 4)) * 1000
    selected, file_audits, cleaning_diff = clean_tree(
        raw_source, raw_target, clean_target, test_timeout_millis
    )
    _write_json(
        Path(run_dir) / "cleaning_audit.json",
        {
            "schema_version": 1,
            "created_at": _now(),
            "policy": "evosuite_non_destructive_junit4_normalization",
            "test_method_timeout_millis": test_timeout_millis,
            "source_file_count": len(file_audits),
            "formal_source_files": [str(path.relative_to(clean_target)) for path in selected],
            "changed_source_file_count": sum(item["changed"] for item in file_audits),
            "junit5_source_file_count": sum(item["junit5_detected"] for item in file_audits),
            "remaining_junit5_symbols": sorted(
                set(
                    symbol
                    for item in file_audits
                    for symbol in item["remaining_junit5_symbols"]
                )
            ),
            "semantic_statement_deletion": False,
            "evosuite_runtime_preserved": True,
            "evosuite_scaffolding_preserved": True,
            "files": file_audits,
        },
    )
    (Path(run_dir) / "cleaning.diff").write_text(
        cleaning_diff
        or "# No source normalization was necessary; formal sources equal raw sources.\n",
        encoding="utf-8",
    )
    return selected


def _failure_state(generation):
    if generation.get("timeout"):
        return "GENERATOR_TIMEOUT"
    try:
        output = Path(generation["log_file"]).read_text(encoding="utf-8", errors="replace").lower()
    except OSError:
        output = ""
    if "belongs to one of the packages evosuite cannot currently handle" in output or (
        "cannot consider" in output and "evosuite cannot" in output
    ):
        return "GENERATOR_UNSUPPORTED_TARGET"
    if "no test cases were generated" in output:
        return "NO_TESTS_GENERATED"
    return "DATA_OR_TOOL_MISMATCH"


def _suite_identity(target, sources):
    fqns = [_parse_source_identity(source)[2] for source in sources]
    expected = target["class_name"] + "_ESTest"
    if expected not in fqns:
        raise RuntimeError("EvoSuite did not generate expected suite: " + expected)
    return expected


def run_target(records, manifest, run_dir, definition, generation, generation_owner, mutant_workers=0):
    run_dir = Path(run_dir).resolve()
    _prepare_target(records, manifest, run_dir, definition, generation, generation_owner)
    if generation["returncode"] != 0 or generation["timeout"]:
        state = _failure_state(generation)
        _write_json(
            run_dir / "state.json",
            {"state": state, "updated_at": _now(), "error_type": "EVOSUITE_GENERATION_FAILURE", "error_message": "EvoSuite exited with code {0}; see {1}".format(generation["returncode"], generation["log_file"])},
        )
        return {"state": state}
    files = _copy_sources(generation, run_dir)
    if not files:
        _write_json(run_dir / "state.json", {"state": "NO_TESTS_GENERATED", "updated_at": _now()})
        return {"state": "NO_TESTS_GENERATED"}
    sources = [path.read_text(encoding="utf-8") for path in files]
    test_class = _suite_identity(records[0], sources)
    pipeline = JavaTestPipeline(run_dir, BASELINE)
    result = pipeline.compile_and_run_suite(
        sources,
        [test_class],
        0,
        extra_classpath_entries=[definition["jar"]],
        compile_timeout_sec=180,
        test_timeout_sec=60,
    )
    if not result["compile"]["compiled"]:
        state = "COMPILE_FAILED"
    elif not result["execution"]["passed"]:
        state = "ORIGINAL_FAILED"
    else:
        pipeline.publish_final(result)
        state = "ORIGINAL_PASSED"
    _write_json(run_dir / "state.json", {"state": state, "updated_at": _now()})
    if state != "ORIGINAL_PASSED":
        return {"state": state, "test_class": test_class}
    try:
        mutation = execute_mutants(run_dir, workers=mutant_workers)
        return {"state": state, "test_class": test_class, "mutation": mutation}
    except Exception as error:
        _write_json(run_dir / "state.json", {"state": "MUTATION_INFRASTRUCTURE_FAILED", "updated_at": _now(), "error_type": type(error).__name__, "error_message": str(error)})
        return {"state": "MUTATION_INFRASTRUCTURE_FAILED", "error": str(error)}


def run_batch(manifest=DEFAULT_MANIFEST, limit=5, batch_dir=None, workers=0, mutant_workers=0, seed=None, budget_seconds=0):
    properties = _read_properties()
    definition = _definition(properties, budget_seconds)
    _validate(definition)
    manifest = Path(manifest).resolve()
    batch_dir = Path(batch_dir or default_batch_dir()).resolve()
    if batch_dir.exists():
        raise FileExistsError("Batch directory already exists: " + str(batch_dir))
    groups = load_method_groups(manifest, limit=limit)
    unresolved = [key for key, values in groups.items() if not values[0].get("source_path")]
    if unresolved:
        raise RuntimeError("Selected EvoSuite targets have unresolved source paths: {0}".format(unresolved[:3]))
    selected_seed = int(seed if seed is not None else properties.get("evosuite.random.seed", "42"))
    requested_workers = int(workers or properties.get("evosuite.generation.workers", "1"))
    class_groups = OrderedDict()
    for records in groups.values():
        target = records[0]
        class_groups.setdefault((target["project"], target["class_name"]), target)
    worker_count = max(1, min(len(class_groups), requested_workers))
    manifests_dir = batch_dir / "manifests"
    targets_dir = batch_dir / "targets"
    generations_dir = batch_dir / "class_generations"
    manifests_dir.mkdir(parents=True)
    targets_dir.mkdir()
    generations_dir.mkdir()
    selections = []
    for index, records in enumerate(groups.values(), 1):
        _write_jsonl(manifests_dir / "{0:03d}.jsonl".format(index), records)
        selections.append({"index": index, "project": records[0]["project"], "class_name": records[0]["class_name"], "method_signature": records[0]["method_signature"], "source_record_count": len(records), "unique_physical_mutant_count": len(_group_mutant_records(records))})
    _write_json(
        batch_dir / "batch_metadata.json",
        {"schema_version": 1, "baseline": BASELINE, "started_at": _now(), "manifest": str(manifest), "selection_policy": "first_N_unique_methods_then_all_rows_for_each_selected_method", "generation_scope": "once_per_selected_project_and_class", "selected_method_count": len(groups), "selected_class_count": len(class_groups), "selected_source_record_count": sum(value["source_record_count"] for value in selections), "selected_unique_physical_mutant_count": sum(value["unique_physical_mutant_count"] for value in selections), "generation_workers": worker_count, "mutant_workers_per_target": int(mutant_workers), "random_seed": selected_seed, "generation_budget_seconds_per_class": definition["budget"], "evosuite_max_size": definition["max_size"], "llm_applicable": False, "targets": selections},
    )
    jobs = []
    for ordinal, (key, target) in enumerate(class_groups.items(), 1):
        jobs.append((key, target, generations_dir / "{0:03d}_{1}".format(ordinal, _safe(key[1]))))
    generations = {}
    if worker_count == 1:
        for key, target, path in jobs:
            generations[key] = _safe_generate_class(target, path, selected_seed, definition)
    else:
        with ThreadPoolExecutor(max_workers=worker_count) as executor:
            futures = {executor.submit(_safe_generate_class, target, path, selected_seed, definition): key for key, target, path in jobs}
            for future in as_completed(futures):
                generations[futures[future]] = future.result()
    owner_seen = set()
    results = []
    for index, records in enumerate(groups.values(), 1):
        target = records[0]
        key = (target["project"], target["class_name"])
        run_dir = targets_dir / "{0:03d}".format(index)
        item = dict(selections[index - 1])
        item.update({"run_dir": str(run_dir), "started_at": _now()})
        owner = key not in owner_seen
        owner_seen.add(key)
        try:
            outcome = run_target(records, manifest, run_dir, definition, generations[key], owner, mutant_workers)
            item.update({"terminal_state": outcome["state"], "status": "SUCCEEDED" if outcome["state"] == "ORIGINAL_PASSED" else "FAILED"})
            if outcome.get("error"):
                item["error_message"] = outcome["error"]
        except Exception as error:
            run_dir.mkdir(parents=True, exist_ok=True)
            if not (run_dir / "source_records.jsonl").is_file():
                _write_jsonl(run_dir / "source_records.jsonl", records)
            if not (run_dir / "metadata.json").is_file():
                _prepare_target(records, manifest, run_dir, definition, generations[key], owner)
            _write_json(run_dir / "state.json", {"state": "DATA_OR_TOOL_MISMATCH", "updated_at": _now(), "error_type": type(error).__name__, "error_message": str(error)})
            item.update({"status": "FAILED", "terminal_state": "DATA_OR_TOOL_MISMATCH", "error_type": type(error).__name__, "error_message": str(error)})
        item["finished_at"] = _now()
        results.append(item)
        _write_jsonl(batch_dir / "batch_results.jsonl", results)
    mutation = summarize_mutation_batch(batch_dir)
    summary = {"schema_version": 1, "baseline": BASELINE, "batch_dir": str(batch_dir), "finished_at": _now(), "selected_method_count": len(results), "selected_class_count": len(class_groups), "succeeded_method_count": sum(value["status"] == "SUCCEEDED" for value in results), "failed_method_count": sum(value["status"] != "SUCCEEDED" for value in results), "mutation_summary": mutation}
    _write_json(batch_dir / "batch_summary.json", summary)
    summary["efficiency_summary"] = summarize_efficiency_batch(batch_dir)
    _write_json(batch_dir / "batch_summary.json", summary)
    return summary


def cli():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST))
    parser.add_argument("--limit", type=int, default=5)
    parser.add_argument("--batch-dir", default=None)
    parser.add_argument("--workers", type=int, default=0)
    parser.add_argument("--mutant-workers", type=int, default=0)
    parser.add_argument("--seed", type=int, default=None)
    parser.add_argument("--budget-seconds", type=int, default=0)
    args = parser.parse_args()
    print(json.dumps(run_batch(args.manifest, args.limit, args.batch_dir, args.workers, args.mutant_workers, args.seed, args.budget_seconds), ensure_ascii=False, indent=2))

