"""Prepare one isolated MutGen target without calling an LLM."""

from __future__ import print_function

import argparse
import hashlib
import json
import sys
import time
from datetime import datetime
from pathlib import Path


ADAPTER_DIR = Path(__file__).resolve().parent
BASELINES_DIR = ADAPTER_DIR.parents[1]
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from manifest_paths import DEFAULT_TARGET_MANIFEST
from target_context import extract_context, focal_class_context, load_method_groups, strip_java_comments


DEFAULT_MANIFEST = DEFAULT_TARGET_MANIFEST
RUNS_DIR = BASELINES_DIR / "runs"
UPSTREAM_COMMIT = "69dd37ddfab16b8c5efd22117dc3d213ab05418d"


def _now():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _run_id():
    now = datetime.now().astimezone()
    return now.strftime("%Y%m%d%H%M%S") + "{0:03d}".format(now.microsecond // 1000)


def default_run_dir(kind="manual"):
    stamp = _run_id()
    for sequence in range(1, 1000):
        candidate = RUNS_DIR / "{0}_mutgen_{1}_{2:03d}".format(stamp, kind, sequence)
        if not candidate.exists():
            return candidate
    raise RuntimeError("Unable to allocate MutGen run ID")


def _sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _write_json(path, value):
    Path(path).write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def prepare(manifest, run_dir):
    preparation_started_at = _now()
    preparation_started = time.perf_counter()
    manifest = Path(manifest).resolve()
    run_dir = Path(run_dir).resolve()
    if manifest.is_dir():
        raise IsADirectoryError(
            "MutGen manifest must be a JSONL file, but a directory was given: {0}. "
            "Use data/target_manifest.first10.jsonl for --manifest and a runs/... "
            "directory only for --run-dir.".format(manifest)
        )
    if not manifest.is_file():
        raise FileNotFoundError("MutGen manifest file does not exist: {0}".format(manifest))
    if run_dir.exists():
        raise FileExistsError("Run directory already exists: {0}".format(run_dir))

    # Validate the manifest before creating any run artifacts. A mistyped
    # manifest must not leave a partial run directory behind.
    groups = load_method_groups(manifest, limit=1)
    records = next(iter(groups.values()))
    for name in ("prompts", "responses", "parsed", "raw_tests", "rounds", "calls"):
        (run_dir / name).mkdir(parents=True, exist_ok=(name != "prompts"))

    target = records[0]
    if not target.get("source_path"):
        raise RuntimeError("Selected target has no resolved source_path")
    context = extract_context(target, run_dir / "target_context.json")
    original_context = focal_class_context(context)
    comment_free = strip_java_comments(original_context)
    (run_dir / "parsed" / "original_context.java").write_text(
        original_context + "\n", encoding="utf-8"
    )
    (run_dir / "parsed" / "source_without_comments.java").write_text(
        comment_free + "\n", encoding="utf-8"
    )
    with (run_dir / "source_records.jsonl").open("w", encoding="utf-8", newline="\n") as stream:
        for record in records:
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")
    prepared_at = _now()
    metadata = {
        "schema_version": 1,
        "baseline": "MutGen-DS-Adapted",
        "upstream_commit": UPSTREAM_COMMIT,
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
        "mutant_source_record_count": len(records),
        "test_framework": "junit4",
        "model_required": "deepseek-v4-flash",
        "max_generation_rounds": 4,
        "information_policy": "mutation_feedback_only_at_paper_defined_stages",
    }
    _write_json(run_dir / "metadata.json", metadata)
    _write_json(
        run_dir / "state.json",
        {"state": "PREPARED_API_NOT_CALLED", "api_called": False, "next_stage": "mutgen_summary", "updated_at": _now()},
    )
    return {"run_dir": str(run_dir), "state": "PREPARED_API_NOT_CALLED", "source_record_count": len(records)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST))
    parser.add_argument("--run-dir", default=None)
    args = parser.parse_args()
    print(json.dumps(prepare(args.manifest, args.run_dir or default_run_dir()), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
