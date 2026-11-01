"""Prepare an isolated ChatTester run from the shared target manifest.

Preparation is deliberately API-free. It extracts the focal context and writes
the exact intention prompt, metadata, and state needed by the later generator.
"""

from __future__ import print_function

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

from llm import INTENTION_SYSTEM_PROMPT


ADAPTER_DIR = Path(__file__).resolve().parent
BASELINES_DIR = ADAPTER_DIR.parents[1]
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from manifest_paths import DEFAULT_TARGET_MANIFEST


DEFAULT_MANIFEST = DEFAULT_TARGET_MANIFEST
RUNS_DIR = BASELINES_DIR / "runs"
COMMON_JAVA_DIR = ADAPTER_DIR.parent / "common" / "java-tools"
TOOLS_JAR = COMMON_JAVA_DIR / "target" / "baseline-java-tools-1.0.0.jar"
RUNTIME_CLASSPATH_FILE = COMMON_JAVA_DIR / "target" / "runtime-classpath.txt"
TARGET_CONTEXT_CLI = "baselines.common.tools.TargetContextCli"


def _local_timestamp_millis():
    now = datetime.now().astimezone()
    return now.strftime("%Y%m%d%H%M%S") + "{0:03d}".format(
        now.microsecond // 1000
    )


def _local_iso_millis():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def default_manual_run_dir():
    """Return a collision-safe local-time run directory with millisecond ID."""
    timestamp = _local_timestamp_millis()
    for sequence in range(1, 1000):
        candidate = RUNS_DIR / (
            "{0}_chattester_manual_{1:03d}".format(timestamp, sequence)
        )
        if not candidate.exists():
            return candidate
    raise RuntimeError("Unable to allocate a manual run ID for " + timestamp)


def _java_executable():
    java_home = os.environ.get("JAVA_HOME", "").strip()
    if java_home:
        candidate = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.is_file():
            return str(candidate)
    return "java"


def _runtime_classpath():
    if not TOOLS_JAR.is_file() or not RUNTIME_CLASSPATH_FILE.is_file():
        raise RuntimeError(
            "Baseline Java tools are not built. Run Maven package and "
            "dependency:build-classpath in {0}".format(COMMON_JAVA_DIR)
        )
    dependencies = RUNTIME_CLASSPATH_FILE.read_text(encoding="utf-8").strip()
    entries = [str(TOOLS_JAR)]
    if dependencies:
        entries.append(dependencies)
    return os.pathsep.join(entries)


def _sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _strip_java_comments(source):
    pattern = re.compile(
        r'("(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\r\n]*|/\*.*?\*/)',
        re.DOTALL,
    )

    def replace(match):
        value = match.group(0)
        return "" if value.startswith(("//", "/*")) else value

    return "\n".join(
        line for line in pattern.sub(replace, source).splitlines() if line.strip()
    )


def _load_first_method(manifest_path):
    first_key = None
    selected = []
    with Path(manifest_path).open("r", encoding="utf-8") as stream:
        for line in stream:
            if not line.strip():
                continue
            item = json.loads(line)
            key = (
                item["project"],
                item["class_name"],
                item["method_signature"],
            )
            if first_key is None:
                first_key = key
            if key == first_key:
                selected.append(item)
    if not selected:
        raise ValueError("Manifest has no target records: {0}".format(manifest_path))
    return selected


def _extract_context(target, output_path):
    command = [
        _java_executable(),
        "-cp",
        _runtime_classpath(),
        TARGET_CONTEXT_CLI,
        "--source",
        target["source_path"],
        "--class",
        target["class_name"],
        "--method",
        target["method_signature"],
        "--output",
        str(output_path),
    ]
    completed = subprocess.run(
        command,
        cwd=str(COMMON_JAVA_DIR),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        universal_newlines=True,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            "TargetContextCli failed: {0}".format(
                (completed.stderr or completed.stdout).strip()
            )
        )
    return json.loads(output_path.read_text(encoding="utf-8"))


def _focal_method_context(context):
    parts = [_strip_java_comments(context["class_declaration"])]
    parts.extend(_strip_java_comments(value) for value in context["fields"])
    parts.extend(_strip_java_comments(value) for value in context["constructors"])
    parts.append("# Focal method")
    parts.append(_strip_java_comments(context["method_body"]))
    parts.append("}")
    return "\n".join(value for value in parts if value.strip())


def prepare(manifest, run_dir):
    preparation_started_at = _local_iso_millis()
    preparation_started = time.perf_counter()
    manifest = Path(manifest).resolve()
    run_dir = Path(run_dir).resolve()
    if run_dir.exists():
        raise FileExistsError(
            "Run directory already exists; choose a new run ID: {0}".format(run_dir)
        )
    (run_dir / "prompts").mkdir(parents=True)
    (run_dir / "responses").mkdir()
    (run_dir / "parsed").mkdir()
    (run_dir / "raw_tests").mkdir()

    records = _load_first_method(manifest)
    target = records[0]
    context_path = run_dir / "target_context.json"
    context = _extract_context(target, context_path)
    focal_context = _focal_method_context(context)
    intention_user_prompt = (
        focal_context
        + "\n\nPlease describe the overall intention of the {0} method in as much "
        "detail as possible in one sentence.".format(context["method_signature"])
    )

    (run_dir / "prompts" / "chattester_intention_0.system.txt").write_text(
        INTENTION_SYSTEM_PROMPT + "\n", encoding="utf-8"
    )
    (run_dir / "prompts" / "chattester_intention_0.user.txt").write_text(
        intention_user_prompt + "\n", encoding="utf-8"
    )
    (run_dir / "parsed" / "focal_method_context.txt").write_text(
        focal_context + "\n", encoding="utf-8"
    )
    with (run_dir / "source_records.jsonl").open(
        "w", encoding="utf-8", newline="\n"
    ) as stream:
        for record in records:
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")

    prepared_at = _local_iso_millis()
    metadata = {
        "schema_version": 1,
        "baseline": "ChatTester-DS-Adapted",
        "upstream_commit": "af8ea1c643a401317c54ee7936d829851d512109",
        "preparation_started_at": preparation_started_at,
        "prepared_at": prepared_at,
        "preprocessing_elapsed_millis": int(
            (time.perf_counter() - preparation_started) * 1000
        ),
        "manifest": str(manifest),
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
        "model_required": "deepseek-v4-flash",
        "information_policy": "no_mutant_information_in_generation_prompt",
        "llm_gateway": str((ADAPTER_DIR.parent / "common" / "llm_gateway.py").resolve()),
    }
    (run_dir / "metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    state = {
        "state": "PREPARED_API_NOT_CALLED",
        "api_called": False,
        "next_stage": "chattester_intention",
        "updated_at": _local_iso_millis(),
    }
    (run_dir / "state.json").write_text(
        json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return {"run_dir": str(run_dir), **state, "mutant_count": len(records)}


def _parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST))
    parser.add_argument(
        "--run-dir",
        default=None,
        help=(
            "default: runs/<local timestamp to milliseconds>_"
            "chattester_manual_001"
        ),
    )
    return parser.parse_args()


def main():
    args = _parse_args()
    run_dir = args.run_dir or default_manual_run_dir()
    print(
        json.dumps(
            prepare(args.manifest, run_dir), ensure_ascii=False, indent=2
        )
    )


if __name__ == "__main__":
    main()
