"""Prepare one isolated A3Test target without calling an LLM."""

from __future__ import print_function

import argparse
import hashlib
import json
import sys
import time
from datetime import datetime
from pathlib import Path

from context import build_a3test_context
from protocol import BASELINE, UPSTREAM_COMMIT, build_generation_prompt


ADAPTER_DIR = Path(__file__).resolve().parent
BASELINES_DIR = ADAPTER_DIR.parents[1]
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from manifest_paths import DEFAULT_TARGET_MANIFEST
from target_context import extract_context, load_method_groups


DEFAULT_MANIFEST = DEFAULT_TARGET_MANIFEST
RUNS_DIR = BASELINES_DIR / "runs"


def _now():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _stamp():
    now = datetime.now().astimezone()
    return now.strftime("%Y%m%d%H%M%S") + "{0:03d}".format(now.microsecond // 1000)


def default_run_dir(kind="manual"):
    stamp = _stamp()
    for sequence in range(1, 1000):
        candidate = RUNS_DIR / "{0}_a3test_{1}_{2:03d}".format(stamp, kind, sequence)
        if not candidate.exists():
            return candidate
    raise RuntimeError("Unable to allocate A3Test run ID")


def _write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def prepare(manifest, run_dir):
    preparation_started_at = _now()
    started = time.perf_counter()
    manifest = Path(manifest).resolve()
    run_dir = Path(run_dir).resolve()
    if manifest.is_dir():
        raise IsADirectoryError("A3Test manifest must be a JSONL file: {0}".format(manifest))
    if not manifest.is_file():
        raise FileNotFoundError("A3Test manifest does not exist: {0}".format(manifest))
    if run_dir.exists():
        raise FileExistsError("Run directory already exists: {0}".format(run_dir))
    groups = load_method_groups(manifest, limit=1)
    records = next(iter(groups.values()))
    target = records[0]
    if not target.get("source_path"):
        raise RuntimeError("Selected A3Test target has no resolved source_path")
    for name in ("prompts", "responses", "raw_tests", "parsed", "rounds", "calls", "coverage"):
        (run_dir / name).mkdir(parents=True, exist_ok=False)

    target_context = extract_context(target, run_dir / "target_context.json")
    context = build_a3test_context(target_context)
    _write_json(run_dir / "a3test_context.json", context)
    test_class = context["simple_class_name"] + "_A3TestTest"
    (run_dir / "prompts" / "a3test_generation.user.txt").write_text(
        build_generation_prompt(context, test_class) + "\n", encoding="utf-8"
    )
    with (run_dir / "source_records.jsonl").open("w", encoding="utf-8", newline="\n") as stream:
        for record in records:
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")
    metadata = {
        "schema_version": 1,
        "baseline": BASELINE,
        "implementation_status": "DEEPSEEK_ADAPTATION_OF_ASSERTION_AUGMENTED_MODEL",
        "upstream_commit": UPSTREAM_COMMIT,
        "preparation_started_at": preparation_started_at,
        "prepared_at": _now(),
        "preprocessing_elapsed_millis": int((time.perf_counter() - started) * 1000),
        "manifest": str(manifest),
        "manifest_sha256": _sha256(manifest),
        "project": target["project"],
        "project_root": target["project_root"],
        "build_root": target["build_root"],
        "class_name": target["class_name"],
        "method_signature": target["method_signature"],
        "source_path": target["source_path"],
        "mutant_source_record_count": len(records),
        "test_framework": "junit4",
        "model_required": "deepseek-v4-flash",
        "native_generation_iterations": 1,
        "llm_repair_rounds": 0,
        "context_encoding": "fm+fc+c+m+f",
        "information_policy": "no_mutant_information_in_generation_prompt",
    }
    _write_json(run_dir / "metadata.json", metadata)
    _write_json(run_dir / "state.json", {
        "state": "PREPARED_API_NOT_CALLED", "api_called": False,
        "next_stage": "a3test_generation", "updated_at": _now(),
    })
    return {"run_dir": str(run_dir), "state": "PREPARED_API_NOT_CALLED", "source_record_count": len(records)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST))
    parser.add_argument("--run-dir", default=None)
    args = parser.parse_args()
    print(json.dumps(prepare(args.manifest, args.run_dir or default_run_dir()), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
