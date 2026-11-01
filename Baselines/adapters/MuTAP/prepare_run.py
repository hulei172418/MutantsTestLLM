"""Prepare one isolated MuTAP target without calling an LLM."""

from __future__ import print_function

import argparse
import hashlib
import json
import sys
import time
from datetime import datetime
from pathlib import Path

from protocol import BASELINE, DEFAULT_SURVIVOR_SEED, MAX_SYNTAX_REPAIR_ROUNDS, UPSTREAM_COMMIT


ADAPTER_DIR = Path(__file__).resolve().parent
BASELINES_DIR = ADAPTER_DIR.parents[1]
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from manifest_paths import DEFAULT_TARGET_MANIFEST
from target_context import extract_context, focal_class_context, load_method_groups


DEFAULT_MANIFEST = DEFAULT_TARGET_MANIFEST
RUNS_DIR = BASELINES_DIR / "runs"


def _now():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _run_id():
    now = datetime.now().astimezone()
    return now.strftime("%Y%m%d%H%M%S") + "{0:03d}".format(now.microsecond // 1000)


def default_run_dir(kind="manual"):
    stamp = _run_id()
    for sequence in range(1, 1000):
        candidate = RUNS_DIR / "{0}_mutap_{1}_{2:03d}".format(stamp, kind, sequence)
        if not candidate.exists():
            return candidate
    raise RuntimeError("Unable to allocate MuTAP run ID")


def _sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def prepare(manifest, run_dir):
    preparation_started_at = _now()
    preparation_started = time.perf_counter()
    manifest = Path(manifest).resolve()
    run_dir = Path(run_dir).resolve()
    if manifest.is_dir():
        raise IsADirectoryError("MuTAP manifest must be a JSONL file: {0}".format(manifest))
    if not manifest.is_file():
        raise FileNotFoundError("MuTAP manifest does not exist: {0}".format(manifest))
    if run_dir.exists():
        raise FileExistsError("Run directory already exists: {0}".format(run_dir))
    groups = load_method_groups(manifest, limit=1)
    records = next(iter(groups.values()))
    target = records[0]
    if not target.get("source_path"):
        raise RuntimeError("Selected target has no resolved source_path")
    for name in ("prompts", "responses", "parsed", "raw_tests", "rounds", "calls"):
        (run_dir / name).mkdir(parents=True, exist_ok=False)
    context = extract_context(target, run_dir / "target_context.json")
    java_context = focal_class_context(context)
    (run_dir / "parsed" / "original_context.java").write_text(java_context + "\n", encoding="utf-8")
    with (run_dir / "source_records.jsonl").open("w", encoding="utf-8", newline="\n") as stream:
        for record in records:
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")
    prepared_at = _now()
    metadata = {
        "schema_version": 1,
        "baseline": BASELINE,
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
        "prompt_type": "zero-shot",
        "syntax_repair_rounds": MAX_SYNTAX_REPAIR_ROUNDS,
        "survivor_order": "seeded_random_without_replacement",
        "survivor_seed": DEFAULT_SURVIVOR_SEED,
        "information_policy": "one_surviving_mutant_source_only_at_paper_defined_augmentation_turn",
    }
    _write_json(run_dir / "metadata.json", metadata)
    _write_json(run_dir / "state.json", {
        "state": "PREPARED_API_NOT_CALLED", "api_called": False,
        "next_stage": "mutap_initial", "updated_at": _now(),
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
