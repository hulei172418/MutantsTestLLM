"""Compile and execute generated JUnit 4 tests in project-local Baseline dirs."""

from __future__ import print_function

import argparse
import json
import os
import re
import shutil
import subprocess
import time
from datetime import datetime
from pathlib import Path

from project_artifacts import ProjectArtifactLayout


COMMON_DIR = Path(__file__).resolve().parent
BASELINES_DIR = COMMON_DIR.parents[1]
JAVA_CONFIG = BASELINES_DIR / "configs" / "java.properties"


def _now():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def _load_properties(path):
    result = {}
    path = Path(path)
    if not path.is_file():
        return result
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")):
            continue
        if "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def _java_tool(name):
    executable = name + (".exe" if os.name == "nt" else "")
    java_home = os.environ.get("JAVA_HOME", "").strip()
    if java_home:
        candidate = Path(java_home) / "bin" / executable
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(executable)
    if found:
        return found
    raise FileNotFoundError("Java tool not found: " + executable)


def _module_root(metadata):
    candidates = []
    configured = str(metadata.get("build_root") or "").strip()
    if configured:
        candidates.append(Path(configured).resolve())
    source = Path(metadata["source_path"]).resolve()
    candidates.extend(source.parents)
    for candidate in candidates:
        if any(
            (candidate / name).is_file()
            for name in ("pom.xml", "build.xml", "build.gradle", "build.gradle.kts")
        ):
            return candidate
    raise RuntimeError(
        "Cannot locate project module root for source: {0}".format(source)
    )


def _parse_source_identity(source):
    package_match = re.search(r"(?m)^\s*package\s+([\w.]+)\s*;", source)
    class_match = re.search(
        r"(?m)^\s*public\s+(?:final\s+)?class\s+([A-Za-z_$][\w$]*)", source
    )
    if not class_match:
        raise ValueError("Generated source has no public class declaration")
    package_name = package_match.group(1) if package_match else ""
    class_name = class_match.group(1)
    fqn = package_name + "." + class_name if package_name else class_name
    return package_name, class_name, fqn


def _add_existing(entries, path):
    path = Path(path)
    if path.exists():
        resolved = str(path.resolve())
        if resolved not in entries:
            entries.append(resolved)


def _maven_classpath(module_root, report_dir, timeout_sec):
    if not (module_root / "pom.xml").is_file():
        return [], None
    mvn = shutil.which("mvn.cmd" if os.name == "nt" else "mvn") or shutil.which("mvn")
    if not mvn:
        return [], "Maven executable not found"
    output = Path(report_dir) / "maven-classpath.txt"
    command = [
        mvn,
        "-q",
        "-DincludeScope=test",
        "-Dmdep.outputAbsoluteArtifactFilename=true",
        "-Dmdep.outputFile={0}".format(output),
        "dependency:build-classpath",
    ]
    completed = subprocess.run(
        command,
        cwd=str(module_root),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        universal_newlines=True,
        timeout=timeout_sec,
    )
    (Path(report_dir) / "maven-classpath.log").write_text(
        completed.stdout or "", encoding="utf-8"
    )
    if completed.returncode != 0 or not output.is_file():
        return [], "Maven dependency:build-classpath failed ({0})".format(
            completed.returncode
        )
    return [value for value in output.read_text(encoding="utf-8").strip().split(os.pathsep) if value], None


def _configured_junit_entries():
    properties = _load_properties(JAVA_CONFIG)
    repository = str(properties.get("java.maven.repository") or "").strip()
    if not repository:
        repository = os.environ.get("MAVEN_REPOSITORY_HOME", "").strip()
    if not repository:
        repository = str(Path.home() / ".m2" / "repository")
    repository = Path(repository)
    entries = []
    junit_root = repository / "junit" / "junit"
    if junit_root.is_dir():
        jars = sorted(junit_root.glob("*/junit-*.jar"), reverse=True)
        if jars:
            entries.append(str(jars[0].resolve()))
    hamcrest_root = repository / "org" / "hamcrest"
    if hamcrest_root.is_dir():
        jars = sorted(hamcrest_root.glob("**/hamcrest-core-*.jar"), reverse=True)
        if jars:
            entries.append(str(jars[0].resolve()))
    return entries


def _prefer_junit4(entries):
    """Remove legacy JUnit 3 jars when a JUnit 4 jar is available.

    Some projects (notably Ant) ship both junit-3.x and junit-4.x.  Keeping the
    JUnit 3 jar earlier on the classpath can shadow ``junit.runner.Version`` and
    produce a mixed runtime even though ``org.junit.runner.JUnitCore`` comes
    from JUnit 4.
    """
    has_junit4 = any(
        re.match(r"junit-4(?:\.|-).*\.jar$", Path(value).name, re.IGNORECASE)
        for value in entries
    )
    if not has_junit4:
        return entries
    return [
        value
        for value in entries
        if not re.match(
            r"junit-(?:[0-3])(?:\.|-).*\.jar$",
            Path(value).name,
            re.IGNORECASE,
        )
    ]


def build_project_classpath(metadata, classes_dir, report_dir, timeout_sec=120):
    module_root = _module_root(metadata)
    project_root = Path(metadata["project_root"]).resolve()
    entries = []
    for relative in (
        "target/classes",
        "target/test-classes",
        "build/classes",
        "build/test-classes",
        "classes",
    ):
        _add_existing(entries, module_root / relative)

    maven_entries, maven_error = _maven_classpath(
        module_root, report_dir, timeout_sec
    )
    for value in maven_entries:
        _add_existing(entries, value)

    jar_roots = [module_root / "lib"]
    generated_library_roots = [
        module_root / "build" / "lib",
        module_root / "bootstrap" / "lib",
        module_root / "dist" / "lib",
    ]
    jar_roots.extend(root for root in generated_library_roots if root.is_dir())
    # Ant distributions duplicate the same library set in build/bootstrap/dist.
    # The first complete generated library root is sufficient and keeps the
    # Windows command line below its length limit.
    if len(jar_roots) > 2:
        jar_roots = jar_roots[:2]
    for root in jar_roots:
        if not root.is_dir():
            continue
        for jar in sorted(root.rglob("*.jar")):
            _add_existing(entries, jar)

    if not any(Path(value).name.startswith("junit-4") for value in entries):
        for value in _configured_junit_entries():
            _add_existing(entries, value)

    entries = _prefer_junit4(entries)
    _add_existing(entries, classes_dir)
    junit_present = any(
        re.match(r"junit-4(?:\.|-).*\.jar$", Path(value).name, re.IGNORECASE)
        for value in entries
    )
    if not junit_present:
        raise FileNotFoundError(
            "JUnit 4 jar not found for project {0}; configure {1}".format(
                project_root, JAVA_CONFIG
            )
        )
    classpath = os.pathsep.join(entries)
    Path(report_dir).mkdir(parents=True, exist_ok=True)
    (Path(report_dir) / "classpath.txt").write_text(classpath + "\n", encoding="utf-8")
    return module_root, entries, maven_error


def _process_environment():
    environment = os.environ.copy()
    existing = environment.get("JAVA_TOOL_OPTIONS", "").strip()
    required = "-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8"
    environment["JAVA_TOOL_OPTIONS"] = (existing + " " + required).strip()
    return environment


def _error_count(output):
    matches = re.findall(r"(?im)^.*?\berror:\s", output or "")
    if matches:
        return len(matches)
    summary = re.search(r"(?im)\b(\d+)\s+errors?\b", output or "")
    return int(summary.group(1)) if summary else (1 if output else 0)


class JavaTestPipeline(object):
    def __init__(self, run_dir, baseline_name="ChatTester"):
        self.run_dir = Path(run_dir).resolve()
        self.metadata = _read_json(self.run_dir / "metadata.json")
        self.layout = ProjectArtifactLayout(
            self.metadata, self.run_dir, baseline_name
        )
        self.layout.initialize()

    def compile_and_run(
        self,
        source,
        round_number,
        compile_timeout_sec=120,
        test_timeout_sec=60,
    ):
        """Compile and run one generated JUnit source (legacy-compatible API)."""
        package_name, class_name, fqn = _parse_source_identity(source)
        return self.compile_and_run_suite(
            [source],
            [fqn],
            round_number,
            compile_timeout_sec=compile_timeout_sec,
            test_timeout_sec=test_timeout_sec,
        )

    def compile_and_run_suite(
        self,
        sources,
        test_classes,
        round_number,
        extra_classpath_entries=None,
        compile_timeout_sec=120,
        test_timeout_sec=60,
    ):
        """Compile multiple generated sources and run their JUnit suite.

        Some generators emit a suite plus helper/scaffolding sources. The old
        single-source entry point remains unchanged and delegates here, so
        existing Baselines retain exactly the same behavior.
        """
        sources = list(sources or [])
        test_classes = [str(value) for value in (test_classes or []) if str(value)]
        if not sources:
            raise ValueError("At least one generated Java source is required")
        if not test_classes:
            raise ValueError("At least one JUnit test class is required")
        paths = self.layout.round_paths(round_number)
        if paths["root"].exists():
            raise FileExistsError(
                "Round artifact directory already exists: {0}".format(paths["root"])
            )
        for key in ("src", "classes", "report"):
            paths[key].mkdir(parents=True, exist_ok=True)
        java_files = []
        source_identities = []
        seen_fqns = set()
        for source in sources:
            package_name, class_name, fqn = _parse_source_identity(source)
            if fqn in seen_fqns:
                raise ValueError("Duplicate generated public class: " + fqn)
            seen_fqns.add(fqn)
            package_path = Path(*package_name.split(".")) if package_name else Path()
            java_file = paths["src"] / package_path / (class_name + ".java")
            java_file.parent.mkdir(parents=True, exist_ok=True)
            java_file.write_text(source, encoding="utf-8")
            java_files.append(java_file)
            source_identities.append(
                {
                    "package_name": package_name,
                    "class_name": class_name,
                    "fqn": fqn,
                    "java_file": str(java_file),
                }
            )

        compile_started_at = _now()
        compile_started = time.perf_counter()
        classpath_started = time.perf_counter()
        module_root, classpath_entries, maven_error = build_project_classpath(
            self.metadata,
            paths["classes"],
            paths["report"],
            timeout_sec=compile_timeout_sec,
        )
        for value in extra_classpath_entries or []:
            _add_existing(classpath_entries, value)
        classpath_elapsed_millis = int(
            (time.perf_counter() - classpath_started) * 1000
        )
        classpath = os.pathsep.join(classpath_entries)
        compile_command = [
            _java_tool("javac"),
            "-encoding",
            "UTF-8",
            "-cp",
            classpath,
            "-d",
            str(paths["classes"]),
        ] + [str(value) for value in java_files]
        javac_started = time.perf_counter()
        try:
            completed = subprocess.run(
                compile_command,
                cwd=str(module_root),
                env=_process_environment(),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                universal_newlines=True,
                timeout=compile_timeout_sec,
            )
            compile_output = completed.stdout or ""
            compile_returncode = completed.returncode
            compile_timeout = False
        except subprocess.TimeoutExpired as error:
            compile_output = (error.stdout or "") + "\nCOMPILATION TIMEOUT"
            compile_returncode = -1
            compile_timeout = True
        compile_log = paths["report"] / "compile.log"
        compile_log.write_text(compile_output, encoding="utf-8")
        expected_classes = [
            paths["classes"] / Path(*value.split(".")) .with_suffix(".class")
            for value in test_classes
        ]
        compiled = compile_returncode == 0 and all(
            value.is_file() for value in expected_classes
        )
        compile_result = {
            "schema_version": 1,
            "started_at": compile_started_at,
            "finished_at": _now(),
            "elapsed_millis": int(
                (time.perf_counter() - compile_started) * 1000
            ),
            "classpath_elapsed_millis": classpath_elapsed_millis,
            "javac_elapsed_millis": int(
                (time.perf_counter() - javac_started) * 1000
            ),
            "round": int(round_number),
            "command": compile_command,
            "module_root": str(module_root),
            "java_file": str(java_files[0]),
            "java_files": [str(value) for value in java_files],
            "classes_dir": str(paths["classes"]),
            "expected_class_file": str(expected_classes[0]),
            "expected_class_files": [str(value) for value in expected_classes],
            "compiled": compiled,
            "returncode": compile_returncode,
            "timeout": compile_timeout,
            "error_count": 0 if compiled else _error_count(compile_output),
            "log_file": str(compile_log),
            "maven_classpath_warning": maven_error or "",
        }
        _write_json(paths["report"] / "compile.json", compile_result)

        execution_result = {
            "schema_version": 1,
            "round": int(round_number),
            "executed": False,
            "passed": False,
            "returncode": None,
            "timeout": False,
            "test_class": test_classes[0],
            "test_classes": list(test_classes),
        }
        if compiled:
            execute_command = [
                _java_tool("java"),
                "-cp",
                classpath,
                "org.junit.runner.JUnitCore",
            ] + list(test_classes)
            execution_started = time.perf_counter()
            execution_result.update(
                {"executed": True, "command": execute_command, "started_at": _now()}
            )
            try:
                completed = subprocess.run(
                    execute_command,
                    cwd=str(module_root),
                    env=_process_environment(),
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    universal_newlines=True,
                    timeout=test_timeout_sec,
                )
                execution_output = completed.stdout or ""
                execution_result["returncode"] = completed.returncode
                execution_result["passed"] = completed.returncode == 0
            except subprocess.TimeoutExpired as error:
                execution_output = (error.stdout or "") + "\nTEST TIMEOUT"
                execution_result["returncode"] = -1
                execution_result["timeout"] = True
            execution_log = paths["report"] / "execution.log"
            execution_log.write_text(execution_output, encoding="utf-8")
            execution_result["log_file"] = str(execution_log)
            execution_result["finished_at"] = _now()
            execution_result["elapsed_millis"] = int(
                (time.perf_counter() - execution_started) * 1000
            )
        _write_json(paths["report"] / "execution.json", execution_result)
        result = {
            "round": int(round_number),
            "project_artifact_root": str(self.layout.root),
            "source_file": str(java_files[0]),
            "source_files": [str(value) for value in java_files],
            "source_identities": source_identities,
            "class_file": str(expected_classes[0]),
            "class_files": [str(value) for value in expected_classes],
            "test_class": test_classes[0],
            "test_classes": list(test_classes),
            "compile": compile_result,
            "execution": execution_result,
        }
        _write_json(paths["report"] / "result.json", result)
        return result

    def load_round_result(self, round_number):
        path = self.layout.round_paths(round_number)["report"] / "result.json"
        if not path.is_file():
            raise FileNotFoundError("Round result does not exist: {0}".format(path))
        return _read_json(path)

    def publish_final(self, round_result):
        round_paths = self.layout.round_paths(round_result["round"])
        final_paths = self.layout.final_paths()
        if final_paths["root"].exists():
            raise FileExistsError(
                "Final artifact directory already exists: {0}".format(
                    final_paths["root"]
                )
            )
        shutil.copytree(str(round_paths["src"]), str(final_paths["src"]))
        shutil.copytree(str(round_paths["classes"]), str(final_paths["classes"]))
        shutil.copytree(str(round_paths["report"]), str(final_paths["report"]))
        summary = dict(round_result)
        summary["published_at"] = _now()
        summary["final_source_root"] = str(final_paths["src"])
        summary["final_classes_root"] = str(final_paths["classes"])
        _write_json(final_paths["root"] / "result.json", summary)
        _write_json(self.run_dir / "project_artifacts_final.json", summary)
        return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True)
    parser.add_argument("--test-file", required=True)
    parser.add_argument("--baseline", default="ChatTester")
    parser.add_argument("--round", type=int, default=0)
    parser.add_argument("--publish-final", action="store_true")
    args = parser.parse_args()
    source = Path(args.test_file).read_text(encoding="utf-8")
    pipeline = JavaTestPipeline(args.run_dir, args.baseline)
    result = pipeline.compile_and_run(source, args.round)
    if args.publish_final and result["execution"]["passed"]:
        result = pipeline.publish_final(result)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
