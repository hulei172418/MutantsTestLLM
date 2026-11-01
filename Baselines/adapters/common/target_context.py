"""Shared focal-method manifest selection and Java context extraction."""

from __future__ import print_function

import json
import os
import re
import subprocess
from collections import OrderedDict
from pathlib import Path

from manifest_paths import DEFAULT_TARGET_MANIFEST


COMMON_DIR = Path(__file__).resolve().parent
JAVA_TOOLS_DIR = COMMON_DIR / "java-tools"
TOOLS_JAR = JAVA_TOOLS_DIR / "target" / "baseline-java-tools-1.0.0.jar"
RUNTIME_CLASSPATH_FILE = JAVA_TOOLS_DIR / "target" / "runtime-classpath.txt"
TARGET_CONTEXT_CLI = "baselines.common.tools.TargetContextCli"


def strip_java_comments(source):
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


def load_method_groups(manifest_path=DEFAULT_TARGET_MANIFEST, limit=None):
    manifest_path = Path(manifest_path).resolve()
    if not manifest_path.exists():
        raise FileNotFoundError(
            "Target manifest does not exist: {0}. Build it with "
            "adapters/common/build_target_manifest.py".format(manifest_path)
        )
    if not manifest_path.is_file():
        raise IsADirectoryError(
            "Target manifest must be a JSONL file, not a directory: {0}".format(
                manifest_path
            )
        )
    groups = OrderedDict()
    with manifest_path.open("r", encoding="utf-8") as stream:
        for line in stream:
            if not line.strip():
                continue
            row = json.loads(line)
            key = (row["project"], row["class_name"], row["method_signature"])
            if key not in groups:
                if limit is not None and len(groups) >= int(limit):
                    continue
                groups[key] = []
            groups[key].append(row)
    if not groups:
        raise ValueError("Manifest has no target records: {0}".format(manifest_path))
    return groups


def _java_executable():
    java_home = os.environ.get("JAVA_HOME", "").strip()
    if java_home:
        candidate = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.is_file():
            return str(candidate)
    return "java"


def _runtime_classpath():
    if not TOOLS_JAR.is_file() or not RUNTIME_CLASSPATH_FILE.is_file():
        raise RuntimeError("Baseline Java context tools are not built: {0}".format(JAVA_TOOLS_DIR))
    dependencies = RUNTIME_CLASSPATH_FILE.read_text(encoding="utf-8").strip()
    return os.pathsep.join([str(TOOLS_JAR)] + ([dependencies] if dependencies else []))


def extract_context(target, output_path):
    output_path = Path(output_path)
    command = [
        _java_executable(), "-cp", _runtime_classpath(), TARGET_CONTEXT_CLI,
        "--source", target["source_path"], "--class", target["class_name"],
        "--method", target["method_signature"], "--output", str(output_path),
    ]
    completed = subprocess.run(
        command,
        cwd=str(JAVA_TOOLS_DIR),
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


def focal_class_context(context):
    parts = list(context.get("imports") or [])
    parts.append(context.get("class_declaration") or "")
    parts.extend(context.get("fields") or [])
    parts.extend(context.get("constructors") or [])
    parts.append(context.get("method_body") or "")
    parts.append("}")
    return "\n".join(value for value in parts if str(value).strip())
