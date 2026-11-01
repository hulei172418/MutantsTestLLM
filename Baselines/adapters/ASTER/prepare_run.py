"""Prepare one isolated ASTER target without calling an LLM."""

from __future__ import print_function

import argparse
import hashlib
import json
import sys
import time
from datetime import datetime
from pathlib import Path

from context import build_aster_context
from protocol import BASELINE, INITIAL_STAGE, UPSTREAM_COMMIT, build_initial_prompt


ADAPTER_DIR = Path(__file__).resolve().parent
BASELINES_DIR = ADAPTER_DIR.parents[1]
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from manifest_paths import DEFAULT_TARGET_MANIFEST
from target_context import extract_context, load_method_groups


DEFAULT_MANIFEST = DEFAULT_TARGET_MANIFEST
RUNS_DIR = BASELINES_DIR / "runs"
SETTINGS_FILE = ADAPTER_DIR / "aster.properties"


def _now():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _stamp():
    now = datetime.now().astimezone()
    return now.strftime("%Y%m%d%H%M%S") + "{0:03d}".format(now.microsecond // 1000)


def default_run_dir(kind="manual"):
    stamp = _stamp()
    for sequence in range(1, 1000):
        candidate = RUNS_DIR / "{0}_aster_{1}_{2:03d}".format(stamp, kind, sequence)
        if not candidate.exists():
            return candidate
    raise RuntimeError("Unable to allocate ASTER run ID")


def _write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_settings(path=SETTINGS_FILE):
    raw = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            raw[key.strip()] = value.strip()
    result = {
        "max_repair_rounds": int(raw.get("aster.max.repair.rounds", "3")),
        "max_coverage_rounds": int(raw.get("aster.max.coverage.rounds", "1")),
        "target_focal_line_coverage": float(raw.get("aster.target.focal.line.coverage", "1.0")),
        "mockable_api_prefixes": raw.get("aster.mockable.api.prefixes", ""),
        "max_transitive_constructor_depth": int(raw.get("aster.max.transitive.constructor.depth", "2")),
        "max_context_types": int(raw.get("aster.max.context.types", "20")),
    }
    if result["max_repair_rounds"] < 0 or result["max_coverage_rounds"] < 0:
        raise ValueError("ASTER iteration limits must be non-negative")
    if not 0 <= result["target_focal_line_coverage"] <= 1:
        raise ValueError("ASTER focal line target must be between 0 and 1")
    return result


def prepare(manifest, run_dir):
    preparation_started_at = _now()
    started = time.perf_counter()
    manifest = Path(manifest).resolve()
    run_dir = Path(run_dir).resolve()
    if manifest.is_dir():
        raise IsADirectoryError("ASTER manifest must be a JSONL file: {0}".format(manifest))
    if not manifest.is_file():
        raise FileNotFoundError("ASTER manifest does not exist: {0}".format(manifest))
    if run_dir.exists():
        raise FileExistsError("Run directory already exists: {0}".format(run_dir))
    groups = load_method_groups(manifest, limit=1)
    records = next(iter(groups.values()))
    target = records[0]
    if not target.get("source_path"):
        raise RuntimeError("Selected ASTER target has no resolved source_path")
    for name in ("prompts", "responses", "raw_tests", "parsed", "rounds", "calls", "coverage"):
        (run_dir / name).mkdir(parents=True, exist_ok=False)

    settings = load_settings()
    target_context = extract_context(target, run_dir / "target_context.json")
    context = build_aster_context(target_context, target["project_root"], settings)
    _write_json(run_dir / "aster_context.json", context)
    test_class = context["simple_class_name"] + "_ASTERTest"
    (run_dir / "prompts" / "aster_initial.user.txt").write_text(
        build_initial_prompt(context, test_class) + "\n", encoding="utf-8"
    )
    with (run_dir / "source_records.jsonl").open("w", encoding="utf-8", newline="\n") as stream:
        for record in records:
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")

    metadata = {
        "schema_version": 1,
        "baseline": BASELINE,
        "implementation_status": "INDEPENDENT_JAVA_REIMPLEMENTATION_FROM_PAPER_AND_PUBLIC_ARTIFACT",
        "upstream_artifact_commit": UPSTREAM_COMMIT,
        "preparation_started_at": preparation_started_at,
        "prepared_at": _now(),
        "preprocessing_elapsed_millis": int((time.perf_counter() - started) * 1000),
        "manifest": str(manifest),
        "manifest_sha256": _sha256(manifest),
        "project": target["project"], "project_root": target["project_root"],
        "build_root": target["build_root"], "class_name": target["class_name"],
        "method_signature": target["method_signature"], "source_path": target["source_path"],
        "mutant_source_record_count": len(records),
        "test_framework": "junit4_evaluation_adaptation",
        "paper_java_framework_observed_in_artifact": "junit4_and_junit5_outputs_present",
        "model_required": "deepseek-v4-flash",
        "paper_stages": ["test_generation", "error_remediation", "coverage_augmentation"],
        "reimplementation_bounds": settings,
        "bounds_provenance": "repair count and target threshold are undisclosed; one coverage round is inferred from Test/Coverage_Test artifact pairs",
        "information_policy": "no_mutant_information_in_generation_repair_or_coverage_prompts",
        "context_policy": context["context_policy"],
        "mockable_api_policy": "user-specified prefixes only; empty by default rather than inventing APIs",
    }
    _write_json(run_dir / "metadata.json", metadata)
    _write_json(run_dir / "state.json", {
        "state": "PREPARED_API_NOT_CALLED", "api_called": False,
        "next_stage": INITIAL_STAGE, "updated_at": _now(),
    })
    return {
        "run_dir": str(run_dir), "state": "PREPARED_API_NOT_CALLED",
        "source_record_count": len(records),
        "relevant_constructor_count": len(context["relevant_constructors"]),
        "accessor_count": len(context["setter_signatures"]) + len(context["getter_signatures"]),
        "private_call_chain_count": len(context["private_call_chains"]),
        "mock_field_count": len(context["mock_fields"]),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST))
    parser.add_argument("--run-dir", default=None)
    args = parser.parse_args()
    print(json.dumps(prepare(args.manifest, args.run_dir or default_run_dir()), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
