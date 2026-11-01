"""Execute one accepted JUnit suite against its mapped MuJava mutants.

The original program is executed once. Every mutant is then executed in a
fresh JVM with a one-class overlay placed before the original project classes.
"""

from __future__ import print_function

import argparse
import csv
import hashlib
import json
import os
import re
import shutil
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path

from java_test_pipeline import _load_properties, _process_environment


COMMON_DIR = Path(__file__).resolve().parent
BASELINES_DIR = COMMON_DIR.parents[1]
JAVA_CONFIG = BASELINES_DIR / "configs" / "java.properties"
JAVA_TOOLS_JAR = COMMON_DIR / "java-tools" / "target" / "baseline-java-tools-1.0.0.jar"
JAVA_TOOLS_RUNTIME_CLASSPATH = COMMON_DIR / "java-tools" / "target" / "runtime-classpath.txt"

SOURCE_FIELDS = [
    "operator", "line", "method", "class", "class_f", "mutation_statement",
    "package", "project", "file_path", "original_graph_path",
    "mutant_graph_path", "equivalent",
]
RESULT_FIELDS = SOURCE_FIELDS + [
    "source_record_uid", "mutant_uid", "baseline", "test_class", "status", "killed", "executed",
    "timeout", "startup_timeout", "lifecycle_timeout", "execution_started", "returncode",
    "fail_fast", "fail_fast_stop_requested", "stopped_early",
    "timeout_phase", "timed_out_test_id",
    "duration_ms", "total_duration_ms", "startup_duration_ms", "execution_duration_ms",
    "tests_started", "tests_run", "tests_failed",
    "test_duration_sum_ms", "test_duration_max_ms", "test_duration_avg_ms",
    "killing_tests", "execution_dir", "overlay_class_file", "result_json",
    "execution_log", "error",
]


def _now():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _run_id():
    return datetime.now().strftime("%Y%m%d%H%M%S%f")[:-3]


def _read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(str(temporary), str(path))


def _read_jsonl(path):
    rows = []
    with Path(path).open("r", encoding="utf-8-sig") as handle:
        for number, raw in enumerate(handle, 1):
            if not raw.strip():
                continue
            value = json.loads(raw)
            value["_source_record_number"] = number
            rows.append(value)
    return rows


def _safe(value, fallback="item"):
    cleaned = re.sub(r"[^A-Za-z0-9_.-]+", "_", str(value or "")).strip("_.-")
    return (cleaned or fallback)[:80]


def _truthy(value):
    return str(value or "").strip().lower() in {
        "1",
        "true",
        "yes",
        "y",
        "equivalent",
        "\u662f",
    }


def _mutant_uid(row):
    existing = str(row.get("mutant_uid") or "").strip()
    if existing:
        return existing
    # One physical MuJava class is one executable mutant even when the workbook
    # contains duplicate association rows for it.
    identity = "|".join(str(row.get(key, "")) for key in (
        "project", "class", "method", "file_path",
    ))
    digest = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:16]
    return "{0}_{1}".format(_safe(row.get("operator"), "mutant"), digest)


def _source_record_uid(row):
    existing = str(row.get("source_record_uid") or "").strip()
    if existing:
        return existing
    identity = "|".join(str(row.get(key, "")) for key in SOURCE_FIELDS)
    identity += "|record={0}".format(row.get("_source_record_number", ""))
    return "record_" + hashlib.sha256(identity.encode("utf-8")).hexdigest()[:16]


def _group_mutant_records(rows):
    groups = []
    indices = {}
    for row in rows:
        key = os.path.normcase(os.path.normpath(str(row.get("file_path") or "")))
        if key not in indices:
            indices[key] = len(groups)
            groups.append([])
        groups[indices[key]].append(row)
    return groups


def _worker_count(properties, requested, task_count):
    requested = int(requested or 0)
    configured = int(properties.get("java.mutant.workers", "4"))
    value = requested if requested > 0 else configured
    if value < 1:
        raise ValueError("mutant worker count must be at least 1")
    return min(value, max(1, int(task_count)))


def _classpath_from_final(final_result, final_classes):
    command = final_result.get("execution", {}).get("command") or []
    if "-cp" not in command or command.index("-cp") + 1 >= len(command):
        raise RuntimeError("Final accepted execution has no recorded Java classpath")
    recorded = command[command.index("-cp") + 1].split(os.pathsep)
    artifact_root = Path(final_result["project_artifact_root"]).resolve()
    entries = []
    for value in recorded:
        if not value:
            continue
        candidate = Path(value).resolve()
        try:
            candidate.relative_to(artifact_root)
            continue
        except ValueError:
            pass
        if str(candidate) not in entries:
            entries.append(str(candidate))
    return [str(Path(final_classes).resolve())] + entries


def _java_executable(final_result):
    command = final_result.get("execution", {}).get("command") or []
    if command and Path(command[0]).is_file():
        return command[0]
    found = shutil.which("java.exe" if os.name == "nt" else "java")
    if not found:
        raise FileNotFoundError("Java executable not found")
    return found


def _java_tools_entries():
    if not JAVA_TOOLS_RUNTIME_CLASSPATH.is_file():
        raise FileNotFoundError(
            "Common Java tools runtime classpath is missing: {0}".format(
                JAVA_TOOLS_RUNTIME_CLASSPATH
            )
        )
    values = [str(JAVA_TOOLS_JAR.resolve())]
    raw = JAVA_TOOLS_RUNTIME_CLASSPATH.read_text(encoding="utf-8").strip()
    for value in raw.split(os.pathsep):
        path = Path(value)
        # JUnit and Hamcrest must come from the evaluated project, exactly as
        # they did during final original-program validation. The worker only
        # needs its JSON serializer in addition to its own jar.
        if (
            value
            and path.is_file()
            and re.match(r"json-[^\\/]+\.jar$", path.name, re.IGNORECASE)
            and str(path.resolve()) not in values
        ):
            values.append(str(path.resolve()))
    return values


def _execute_worker(
    java,
    classpath_entries,
    test_class,
    execution_dir,
    working_dir,
    test_timeout_sec,
    startup_timeout_sec=60,
    lifecycle_timeout_sec=60,
    jvm_args=None,
    fail_fast=False,
):
    execution_dir = Path(execution_dir)
    execution_dir.mkdir(parents=True, exist_ok=False)
    result_path = execution_dir / "junit-result.json"
    execution_started_path = execution_dir / "junit-execution-started.json"
    progress_path = execution_dir / "junit-test-progress.json"
    log_path = execution_dir / "execution.log"
    metadata_path = execution_dir / "execution.json"
    command = [java] + list(jvm_args or []) + [
        "-cp", os.pathsep.join(classpath_entries),
        "baselines.common.tools.JUnitResultWorker", test_class, str(result_path),
        str(execution_started_path), str(progress_path),
        "true" if fail_fast else "false",
    ]
    started_at = _now()
    started = time.perf_counter()
    timed_out = False
    startup_timed_out = False
    lifecycle_timed_out = False
    execution_started = False
    execution_started_at = None
    startup_duration_ms = None
    execution_duration_ms = None
    timeout_phase = ""
    timed_out_test_id = ""
    observed_test_count = 0

    def stop_process(process):
        if process.poll() is None:
            process.kill()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.terminate()

    with log_path.open("w", encoding="utf-8") as log_stream:
        process = subprocess.Popen(
            command,
            cwd=str(working_dir),
            env=_process_environment(),
            stdout=log_stream,
            stderr=subprocess.STDOUT,
            universal_newlines=True,
        )
        startup_deadline = time.perf_counter() + startup_timeout_sec
        while True:
            if execution_started_path.is_file():
                execution_started = True
                execution_started_at = _now()
                execution_started_clock = time.perf_counter()
                startup_duration_ms = int((execution_started_clock - started) * 1000)
                break
            if process.poll() is not None:
                break
            if time.perf_counter() >= startup_deadline:
                startup_timed_out = True
                stop_process(process)
                log_stream.write(
                    "\nWORKER STARTUP TIMEOUT BEFORE JUNIT EXECUTION AFTER {0}s\n".format(
                        startup_timeout_sec
                    )
                )
                break
            time.sleep(0.01)

        if execution_started and process.poll() is None:
            # One isolated JVM runs the complete JUnit class, but the behavior
            # deadline belongs to the currently active @Test method. Every
            # TEST_STARTED event therefore resets the deadline. A large suite
            # never has to share one fixed timeout budget.
            lifecycle_deadline = time.perf_counter() + lifecycle_timeout_sec
            active_test_id = ""
            active_test_deadline = None
            last_progress_sequence = -1
            while process.poll() is None:
                if progress_path.is_file():
                    try:
                        progress = _read_json(progress_path)
                    except (OSError, ValueError):
                        progress = None
                    if progress:
                        sequence = int(progress.get("sequence") or 0)
                        if sequence != last_progress_sequence:
                            last_progress_sequence = sequence
                            state = str(progress.get("state") or "")
                            now_clock = time.perf_counter()
                            if state == "TEST_STARTED":
                                active_test_id = str(progress.get("test_id") or "")
                                active_test_deadline = now_clock + test_timeout_sec
                                observed_test_count += 1
                            elif state in {"TEST_FINISHED", "TEST_IGNORED"}:
                                active_test_id = ""
                                active_test_deadline = None
                                lifecycle_deadline = now_clock + lifecycle_timeout_sec

                now_clock = time.perf_counter()
                if active_test_deadline is not None and now_clock >= active_test_deadline:
                    timed_out = True
                    timeout_phase = "TEST_METHOD"
                    timed_out_test_id = active_test_id
                    stop_process(process)
                    log_stream.write(
                        "\nJUNIT TEST METHOD TIMEOUT: {0} AFTER {1}s\n".format(
                            active_test_id or "<unknown>", test_timeout_sec
                        )
                    )
                    break
                if active_test_deadline is None and now_clock >= lifecycle_deadline:
                    lifecycle_timed_out = True
                    timeout_phase = "JUNIT_LIFECYCLE"
                    stop_process(process)
                    log_stream.write(
                        "\nJUNIT LIFECYCLE TIMEOUT WITHOUT ACTIVE TEST AFTER {0}s\n".format(
                            lifecycle_timeout_sec
                        )
                    )
                    break
                time.sleep(0.01)
            returncode = process.poll()
            if returncode is None:
                stop_process(process)
                returncode = process.poll()
            execution_duration_ms = int(
                (time.perf_counter() - execution_started_clock) * 1000
            )
        else:
            returncode = process.poll()
            if returncode is None:
                stop_process(process)
                returncode = process.poll()

    total_duration_ms = int((time.perf_counter() - started) * 1000)
    if startup_duration_ms is None:
        startup_duration_ms = total_duration_ms
    if execution_duration_ms is None and execution_started:
        execution_duration_ms = max(0, total_duration_ms - startup_duration_ms)
    worker_result = None
    if result_path.is_file():
        try:
            worker_result = _read_json(result_path)
        except (OSError, ValueError):
            pass
    execution = {
        "schema_version": 1, "started_at": started_at, "finished_at": _now(),
        "duration_ms": execution_duration_ms if execution_duration_ms is not None else 0,
        "total_duration_ms": total_duration_ms,
        "startup_duration_ms": startup_duration_ms,
        "execution_duration_ms": execution_duration_ms,
        "startup_timeout_seconds": startup_timeout_sec,
        "test_method_timeout_seconds": test_timeout_sec,
        "lifecycle_timeout_seconds": lifecycle_timeout_sec,
        "timeout_seconds": test_timeout_sec,
        "timeout_scope": "each_test_method",
        "fail_fast": bool(fail_fast),
        "fail_fast_stop_requested": bool(
            (worker_result or {}).get("fail_fast_stop_requested")
        ),
        "stopped_early": bool((worker_result or {}).get("stopped_early")),
        "startup_timeout": startup_timed_out,
        "lifecycle_timeout": lifecycle_timed_out,
        "execution_started": execution_started,
        "execution_started_at": execution_started_at,
        "execution_started_json": str(execution_started_path),
        "test_progress_json": str(progress_path),
        "timeout": timed_out, "timeout_phase": timeout_phase,
        "timed_out_test_id": timed_out_test_id,
        "observed_test_count": observed_test_count,
        "returncode": returncode, "test_class": test_class,
        "command": command, "working_directory": str(Path(working_dir).resolve()),
        "classpath_entries": classpath_entries, "result_json": str(result_path),
        "execution_log": str(log_path), "worker_result": worker_result,
    }
    _write_json(metadata_path, execution)
    return execution


def _valid_original(execution):
    worker = execution.get("worker_result") or {}
    return (
        not execution.get("startup_timeout")
        and bool(execution.get("execution_started"))
        and not execution.get("timeout")
        and not execution.get("lifecycle_timeout")
        and execution.get("returncode") == 0
        and bool(worker.get("successful"))
        and not bool(worker.get("infrastructure_error"))
        and int(worker.get("run_count") or 0) > 0
    )


def _classify_mutant(execution, original):
    worker = execution.get("worker_result") or {}
    original_worker = original.get("worker_result") or {}
    original_passes = {
        test.get("id") for test in original_worker.get("tests", [])
        if test.get("status") == "PASS"
    }
    if execution.get("startup_timeout"):
        return "ERROR_STARTUP_TIMEOUT", False, []
    if execution.get("lifecycle_timeout"):
        return "ERROR_LIFECYCLE_TIMEOUT", False, []
    if execution.get("timeout") and execution.get("execution_started"):
        timed_out_test = str(execution.get("timed_out_test_id") or "")
        if timed_out_test and timed_out_test in original_passes:
            return "KILLED_TIMEOUT", True, [timed_out_test]
        return "ERROR_UNMAPPED_TEST_TIMEOUT", False, []
    if not worker:
        return "ERROR_WORKER", False, []
    if worker.get("infrastructure_error") or execution.get("returncode") == 2:
        return "ERROR_INFRASTRUCTURE", False, []
    tests = worker.get("tests") or []
    failures = [test for test in tests if test.get("status") == "FAIL"]
    killing_tests = [str(test.get("id")) for test in failures if test.get("id") in original_passes]
    resource_types = {"java.lang.OutOfMemoryError", "java.lang.StackOverflowError"}
    if any(test.get("exception_class") in resource_types for test in failures):
        return "KILLED_RESOURCE", True, killing_tests
    if failures or not worker.get("successful") or execution.get("returncode") == 1:
        return "KILLED", True, killing_tests
    return "LIVE", False, []


def _copy_mutant_overlay(row, class_name, overlay_root):
    source = Path(str(row.get("file_path") or ""))
    if not source.is_file():
        raise FileNotFoundError("Mutant class does not exist: {0}".format(source))
    destination = Path(overlay_root) / Path(*class_name.split(".")).with_suffix(".class")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(str(source), str(destination))
    return destination


def _result_row(row, uid, baseline, test_class, status, killed, execution, paths):
    worker = execution.get("worker_result") or {}
    tests = worker.get("tests", [])
    failures = [test.get("id", "") for test in tests if test.get("status") == "FAIL"]
    durations = [float(test.get("duration_ms") or 0.0) for test in tests]
    duration_sum = sum(durations)
    value = {key: row.get(key, "") for key in SOURCE_FIELDS}
    value.update({
        "source_record_uid": _source_record_uid(row), "mutant_uid": uid,
        "baseline": baseline, "test_class": test_class,
        "status": status, "killed": bool(killed), "executed": True,
        "timeout": bool(execution.get("timeout")),
        "startup_timeout": bool(execution.get("startup_timeout")),
        "lifecycle_timeout": bool(execution.get("lifecycle_timeout")),
        "execution_started": bool(execution.get("execution_started")),
        "fail_fast": bool(execution.get("fail_fast")),
        "fail_fast_stop_requested": bool(execution.get("fail_fast_stop_requested")),
        "stopped_early": bool(execution.get("stopped_early")),
        "timeout_phase": execution.get("timeout_phase") or "",
        "timed_out_test_id": execution.get("timed_out_test_id") or "",
        "returncode": execution.get("returncode"),
        "duration_ms": execution.get("duration_ms"),
        "tests_started": int(execution.get("observed_test_count") or 0),
        "tests_run": int(worker.get("run_count") or 0),
        "total_duration_ms": execution.get("total_duration_ms"),
        "startup_duration_ms": execution.get("startup_duration_ms"),
        "execution_duration_ms": execution.get("execution_duration_ms"),
        "tests_failed": int(worker.get("failure_count") or 0),
        "test_duration_sum_ms": duration_sum,
        "test_duration_max_ms": max(durations) if durations else 0.0,
        "test_duration_avg_ms": duration_sum / len(durations) if durations else 0.0,
        "killing_tests": paths.get("killing_tests") or failures,
        "execution_dir": str(paths["execution_dir"]),
        "overlay_class_file": str(paths.get("overlay_class_file") or ""),
        "result_json": str(paths["result_json"]), "execution_log": str(paths["execution_log"]),
        "error": "",
    })
    return value


def _error_row(row, uid, baseline, test_class, status, error, execution_dir):
    value = {key: row.get(key, "") for key in SOURCE_FIELDS}
    value.update({
        "source_record_uid": _source_record_uid(row), "mutant_uid": uid,
        "baseline": baseline, "test_class": test_class,
        "status": status, "killed": False, "executed": False, "timeout": False,
        "startup_timeout": False, "lifecycle_timeout": False, "execution_started": False,
        "fail_fast": True, "fail_fast_stop_requested": False, "stopped_early": False,
        "timeout_phase": "", "timed_out_test_id": "",
        "returncode": "", "duration_ms": 0, "tests_started": 0,
        "tests_run": 0, "tests_failed": 0,
        "test_duration_sum_ms": 0, "test_duration_max_ms": 0,
        "test_duration_avg_ms": 0,
        "total_duration_ms": 0, "startup_duration_ms": 0, "execution_duration_ms": 0,
        "killing_tests": [], "execution_dir": str(execution_dir),
        "overlay_class_file": "", "result_json": "", "execution_log": "", "error": str(error),
    })
    return value


def _write_rows(root, rows):
    jsonl_path = Path(root) / "mutant-results.jsonl"
    with jsonl_path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    csv_path = Path(root) / "mutant-results.csv"
    with csv_path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=RESULT_FIELDS, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            csv_row = dict(row)
            csv_row["killing_tests"] = json.dumps(row.get("killing_tests") or [], ensure_ascii=False)
            writer.writerow(csv_row)
    return jsonl_path, csv_path


def _score(rows):
    grouped = {}
    for row in rows:
        grouped.setdefault(row["mutant_uid"], []).append(row)
    selected = []
    conflicts = 0
    for values in grouped.values():
        labels = {_truthy(row.get("equivalent")) for row in values}
        if len(labels) > 1:
            conflicts += 1
        # Equivalent labels are audit metadata only. They never alter the
        # single primary metric: Executable MS = killed / (killed + live).
        selected.append(values[0])
    killed = sum(1 for row in selected if row["status"].startswith("KILLED"))
    live = sum(1 for row in selected if row["status"] == "LIVE")
    denominator = killed + live
    return {
        "total_unique_mutants": len(selected), "killed": killed, "live": live,
        "errors_or_not_executed": len(selected) - denominator,
        "executable_denominator": denominator,
        "executable_ms": (float(killed) / denominator) if denominator else None,
        "equivalent_label_conflicts": conflicts,
    }


def _resolve_test_artifact(run_dir, artifact_result=None):
    """Resolve either the published final suite or an explicit round snapshot."""
    run_dir = Path(run_dir).resolve()
    if artifact_result is None:
        result_path = run_dir / "project_artifacts_final.json"
        result = _read_json(result_path)
        classes_root = result.get("final_classes_root")
        source = "published_final"
    else:
        result_path = Path(artifact_result).resolve()
        result = _read_json(result_path)
        classes_root = result.get("compile", {}).get("classes_dir")
        source = "explicit_round_snapshot"
        if not result.get("compile", {}).get("compiled"):
            raise RuntimeError("Explicit artifact did not compile: {0}".format(result_path))
        if not result.get("execution", {}).get("passed"):
            raise RuntimeError(
                "Explicit artifact did not pass the original program: {0}".format(
                    result_path
                )
            )
    if not classes_root:
        raise RuntimeError("Accepted test artifact has no classes directory: {0}".format(result_path))
    classes_root = Path(classes_root).resolve()
    if not classes_root.is_dir():
        raise FileNotFoundError("Accepted test classes do not exist: {0}".format(classes_root))
    return result, classes_root, result_path, source


def execute_mutants(
    run_dir,
    max_mutants=0,
    execution_id=None,
    workers=0,
    artifact_result=None,
    publish_latest=True,
):
    execution_started = time.perf_counter()
    run_dir = Path(run_dir).resolve()
    metadata = _read_json(run_dir / "metadata.json")
    state = _read_json(run_dir / "state.json")
    if artifact_result is None and state.get("state") != "ORIGINAL_PASSED":
        raise RuntimeError("Mutants require ORIGINAL_PASSED; state is {0}".format(state.get("state")))
    final_result, final_classes, artifact_result_path, artifact_source = (
        _resolve_test_artifact(run_dir, artifact_result)
    )
    rows = _read_jsonl(run_dir / "source_records.jsonl")
    if max_mutants and max_mutants > 0:
        # The limit is expressed in physical mutant artifacts, never raw rows.
        selected_groups = _group_mutant_records(rows)[:max_mutants]
        rows = [row for group in selected_groups for row in group]
    if not rows:
        raise RuntimeError("No mutant source records found")
    mutant_groups = _group_mutant_records(rows)
    if not JAVA_TOOLS_JAR.is_file():
        raise FileNotFoundError("Common Java tools are not built: {0}".format(JAVA_TOOLS_JAR))

    # Preserve the complete adapted implementation identity in every result.
    # The already-published project_artifact_root controls the directory
    # namespace, so there is no need to truncate at the first hyphen.
    baseline = str(metadata.get("baseline") or "Baseline")
    test_class = final_result["test_class"]
    artifact_root = Path(final_result["project_artifact_root"]).resolve()
    execution_id = execution_id or _run_id()
    # Keep the physical project-local path short enough for Java 8 / Windows
    # MAX_PATH. Full meanings and source-record identities remain in index.json
    # and mutant-results.*; no information is encoded only in directory names.
    project_root = artifact_root / "mx" / execution_id
    baseline_root = run_dir / "mutant_execution" / execution_id
    if project_root.exists() or baseline_root.exists():
        raise FileExistsError("Mutant execution id already exists: " + execution_id)
    project_root.mkdir(parents=True)
    baseline_root.mkdir(parents=True)

    properties = _load_properties(JAVA_CONFIG)
    test_method_timeout = int(properties.get("java.mutant.test.timeout.seconds", "4"))
    startup_timeout = int(properties.get("java.mutant.startup.timeout.seconds", "60"))
    lifecycle_timeout = int(properties.get("java.mutant.lifecycle.timeout.seconds", "60"))
    if min(test_method_timeout, startup_timeout, lifecycle_timeout) < 1:
        raise ValueError("All mutation execution timeout values must be at least 1 second")
    requested_workers = int(workers or 0)
    worker_count = _worker_count(properties, requested_workers, len(mutant_groups))
    java = _java_executable(final_result)
    base_classpath = _classpath_from_final(final_result, final_classes)
    for offset, value in enumerate(_java_tools_entries(), 1):
        if value not in base_classpath:
            base_classpath.insert(offset, value)
    working_dir = Path(metadata["build_root"]).resolve()

    mapping = {
        "schema_version": 1, "execution_id": execution_id, "created_at": _now(),
        "baseline": baseline, "baseline_run_dir": str(run_dir),
        "project_artifact_root": str(artifact_root), "project_execution_root": str(project_root),
        "baseline_execution_root": str(baseline_root), "project": metadata["project"],
        "class_name": metadata["class_name"], "method_signature": metadata["method_signature"],
        "test_class": test_class, "mutant_count": len(mutant_groups),
        "test_artifact_result": str(artifact_result_path),
        "test_artifact_source": artifact_source,
        "publish_latest": bool(publish_latest),
        "source_record_count": len(rows),
        "execution_semantics": "fresh-jvm_original-full-suite_mutant-fail-fast_with-per-test-method-sliding-timeout",
        "worker_count": worker_count,
        "worker_source": "--workers" if requested_workers > 0 else "java.mutant.workers",
        "startup_timeout_seconds": startup_timeout,
        "test_method_timeout_seconds": test_method_timeout,
        "lifecycle_timeout_seconds": lifecycle_timeout,
        # Retain these two fields for old result readers. They now mean the
        # per-@Test deadline, never a whole-suite deadline.
        "original_timeout_seconds": test_method_timeout,
        "mutant_timeout_seconds": test_method_timeout,
        "timeout_scope": "each_test_method",
        "timeout_clock_starts_at": "each TEST_STARTED worker progress event",
        "original_fail_fast": False,
        "mutant_fail_fast": True,
    }
    _write_json(project_root / "index.json", mapping)
    _write_json(baseline_root / "index.json", mapping)

    original = _execute_worker(
        java, base_classpath, test_class, project_root / "o", working_dir,
        test_method_timeout, startup_timeout, lifecycle_timeout,
        fail_fast=False,
    )
    if not _valid_original(original):
        summary = dict(mapping)
        summary.update({"completed_at": _now(), "elapsed_millis": int((time.perf_counter() - execution_started) * 1000), "state": "INVALID_ORIGINAL_EXECUTION", "original_execution": original, "mutants_executed": 0})
        _write_json(project_root / "summary.json", summary)
        _write_json(baseline_root / "summary.json", summary)
        raise RuntimeError("Original test suite did not pass in the isolated worker; mutants were not run")

    def execute_group(ordinal, group_rows):
        group_results = []
        row = group_rows[0]
        uid = _mutant_uid(row)
        item_root = project_root / "m" / "{0:04d}".format(ordinal)
        overlay_root = item_root / "o"
        execution_dir = item_root / "e"
        try:
            overlay_class = _copy_mutant_overlay(row, metadata["class_name"], overlay_root)
            execution = _execute_worker(
                java, [str(overlay_root.resolve())] + base_classpath, test_class,
                execution_dir, working_dir, test_method_timeout, startup_timeout,
                lifecycle_timeout,
                fail_fast=True,
            )
            status, killed, killing_tests = _classify_mutant(execution, original)
            paths = {
                "execution_dir": execution_dir, "overlay_class_file": overlay_class,
                "result_json": execution["result_json"], "execution_log": execution["execution_log"],
                "killing_tests": killing_tests,
            }
            for source_row in group_rows:
                group_results.append(_result_row(
                    source_row, uid, baseline, test_class, status, killed, execution, paths
                ))
        except Exception as error:
            item_root.mkdir(parents=True, exist_ok=True)
            (item_root / "setup-error.log").write_text(str(error) + "\n", encoding="utf-8")
            for source_row in group_rows:
                group_results.append(_error_row(
                    source_row, uid, baseline, test_class, "ERROR_SETUP", error, execution_dir
                ))
        return group_results

    # Threads orchestrate external JVM processes; test code never shares a JVM
    # or class loader across mutants. Result ordering remains manifest-stable.
    grouped_results = {}
    if worker_count == 1:
        for ordinal, group_rows in enumerate(mutant_groups, 1):
            grouped_results[ordinal] = execute_group(ordinal, group_rows)
    else:
        with ThreadPoolExecutor(max_workers=worker_count) as executor:
            futures = {
                executor.submit(execute_group, ordinal, group_rows): ordinal
                for ordinal, group_rows in enumerate(mutant_groups, 1)
            }
            for future in as_completed(futures):
                grouped_results[futures[future]] = future.result()
    results = []
    for ordinal in sorted(grouped_results):
        results.extend(grouped_results[ordinal])

    jsonl_path, csv_path = _write_rows(project_root, results)
    summary = dict(mapping)
    summary.update({
        "completed_at": _now(),
        "elapsed_millis": int((time.perf_counter() - execution_started) * 1000),
        "state": "COMPLETED",
        "original_execution_dir": str(project_root / "o"),
        "mutants_executed": len({row["mutant_uid"] for row in results if row["executed"]}),
        "executable_ms": _score(results),
        "results_jsonl": str(jsonl_path), "results_csv": str(csv_path),
    })
    _write_json(project_root / "summary.json", summary)
    _write_json(baseline_root / "summary.json", summary)
    _write_json(baseline_root / "project-result-pointer.json", summary)
    if publish_latest:
        _write_json(artifact_root / "mutant_execution" / "latest.json", summary)
        _write_json(run_dir / "mutant_execution" / "latest.json", summary)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True)
    parser.add_argument("--max-mutants", type=int, default=0)
    parser.add_argument("--execution-id", default="")
    parser.add_argument(
        "--artifact-result",
        default="",
        help="accepted round result.json; enables mutation-guided intermediate rounds",
    )
    parser.add_argument(
        "--no-publish-latest",
        action="store_true",
        help="keep this round snapshot out of the final latest.json pointer",
    )
    parser.add_argument(
        "--workers",
        type=int,
        default=0,
        help="parallel isolated-JVM workers; 0 uses java.mutant.workers",
    )
    args = parser.parse_args()
    print(json.dumps(execute_mutants(
        args.run_dir,
        args.max_mutants,
        args.execution_id or None,
        workers=args.workers,
        artifact_result=args.artifact_result or None,
        publish_latest=not args.no_publish_latest,
    ), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
