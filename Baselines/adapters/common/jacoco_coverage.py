"""Collect focal-class line and branch coverage for an accepted JUnit suite."""

from __future__ import print_function

import argparse
import json
import os
import subprocess
from datetime import datetime
from pathlib import Path

from java_test_pipeline import _load_properties
from mutant_execution import (
    JAVA_CONFIG,
    JAVA_TOOLS_JAR,
    JAVA_TOOLS_RUNTIME_CLASSPATH,
    _classpath_from_final,
    _execute_worker,
    _java_executable,
    _java_tools_entries,
    _valid_original,
)


JACOCO_CLI = "baselines.common.tools.JacocoLineCoverageCli"


def _now():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def resolve_agent_jar(properties=None):
    properties = properties or _load_properties(JAVA_CONFIG)
    configured = str(properties.get("java.jacoco.agent.jar") or "").strip()
    if configured:
        candidate = Path(configured).resolve()
    else:
        repository = str(properties.get("java.maven.repository") or "").strip()
        if not repository:
            repository = str(Path.home() / ".m2" / "repository")
        version = str(properties.get("java.jacoco.version") or "0.8.12").strip()
        candidate = (
            Path(repository)
            / "org"
            / "jacoco"
            / "org.jacoco.agent"
            / version
            / "org.jacoco.agent-{0}-runtime.jar".format(version)
        ).resolve()
    if not candidate.is_file():
        raise FileNotFoundError(
            "JaCoCo runtime agent not found: {0}; configure java.jacoco.agent.jar".format(
                candidate
            )
        )
    return candidate


def _find_original_class(classpath_entries, class_name, artifact_root):
    relative = Path(*class_name.split(".")).with_suffix(".class")
    artifact_root = Path(artifact_root).resolve()
    for entry in classpath_entries:
        root = Path(entry).resolve()
        if not root.is_dir():
            continue
        try:
            root.relative_to(artifact_root)
            continue
        except ValueError:
            pass
        candidate = root / relative
        if candidate.is_file():
            return candidate.resolve()
    raise FileNotFoundError(
        "Original focal class not found on accepted classpath: {0}".format(class_name)
    )


def _tools_classpath():
    if not JAVA_TOOLS_JAR.is_file() or not JAVA_TOOLS_RUNTIME_CLASSPATH.is_file():
        raise FileNotFoundError("Common Java tools or runtime classpath are not built")
    values = [str(JAVA_TOOLS_JAR.resolve())]
    raw = JAVA_TOOLS_RUNTIME_CLASSPATH.read_text(encoding="utf-8").strip()
    values.extend(value for value in raw.split(os.pathsep) if value)
    return os.pathsep.join(values)


def collect_line_coverage(
    artifact_result, metadata, output_dir, timeout_sec=60,
    start_line=None, end_line=None,
):
    artifact_result_path = Path(artifact_result).resolve()
    accepted = _read_json(artifact_result_path)
    if not accepted.get("compile", {}).get("compiled") or not accepted.get("execution", {}).get("passed"):
        raise RuntimeError("JaCoCo requires an original-passing artifact: {0}".format(artifact_result_path))
    classes_dir = Path(accepted["compile"]["classes_dir"]).resolve()
    artifact_root = Path(accepted["project_artifact_root"]).resolve()
    classpath = _classpath_from_final(accepted, classes_dir)
    original_class = _find_original_class(classpath, metadata["class_name"], artifact_root)
    for offset, value in enumerate(_java_tools_entries(), 1):
        if value not in classpath:
            classpath.insert(offset, value)

    output_dir = Path(output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=False)
    exec_file = output_dir / "jacoco.exec"
    coverage_file = output_dir / "line_coverage.json"
    agent = resolve_agent_jar()
    agent_arg = "-javaagent:{0}=destfile={1},append=false,includes={2}".format(
        agent, exec_file, metadata["class_name"]
    )
    java = _java_executable(accepted)
    execution = _execute_worker(
        java,
        classpath,
        accepted["test_class"],
        output_dir / "execution",
        Path(metadata["build_root"]).resolve(),
        timeout_sec,
        jvm_args=[agent_arg],
    )
    if not _valid_original(execution):
        raise RuntimeError("JaCoCo original-program execution failed")
    if not exec_file.is_file():
        raise RuntimeError("JaCoCo agent produced no exec file: {0}".format(exec_file))

    command = [
        java,
        "-cp",
        _tools_classpath(),
        JACOCO_CLI,
        "--exec",
        str(exec_file),
        "--class-file",
        str(original_class),
        "--class-name",
        metadata["class_name"],
        "--output",
        str(coverage_file),
    ]
    if start_line is not None:
        command.extend(["--start-line", str(int(start_line))])
    if end_line is not None:
        command.extend(["--end-line", str(int(end_line))])
    completed = subprocess.run(
        command,
        cwd=str(Path(metadata["build_root"]).resolve()),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        universal_newlines=True,
        timeout=timeout_sec,
    )
    (output_dir / "analysis.log").write_text(completed.stdout or "", encoding="utf-8")
    if completed.returncode != 0 or not coverage_file.is_file():
        raise RuntimeError(
            "JaCoCo line analysis failed ({0}): {1}".format(
                completed.returncode, (completed.stdout or "").strip()
            )
        )
    coverage = _read_json(coverage_file)
    if not coverage.get("class_id_match"):
        raise RuntimeError(
            "JaCoCo class id mismatch; runtime and analysis class differ: {0}".format(
                original_class
            )
        )
    coverage.update(
        {
            "created_at": _now(),
            "artifact_result": str(artifact_result_path),
            "test_class": accepted["test_class"],
            "agent_jar": str(agent),
            "execution": execution,
            "analysis_command": command,
        }
    )
    _write_json(coverage_file, coverage)
    return coverage


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact-result", required=True)
    parser.add_argument("--metadata", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--timeout-sec", type=int, default=60)
    parser.add_argument("--start-line", type=int, default=None)
    parser.add_argument("--end-line", type=int, default=None)
    args = parser.parse_args()
    metadata = _read_json(args.metadata)
    print(
        json.dumps(
            collect_line_coverage(
                args.artifact_result, metadata, args.output_dir, args.timeout_sec,
                args.start_line, args.end_line,
            ),
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
