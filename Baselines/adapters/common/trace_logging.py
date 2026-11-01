"""Uniform call-to-mutant audit logging for every baseline."""

from __future__ import print_function

import hashlib
import json
from pathlib import Path


ORIGINAL_MUTANT_FIELDS = (
    "operator",
    "line",
    "method",
    "class",
    "class_f",
    "mutation_statement",
    "package",
    "project",
    "file_path",
    "original_graph_path",
    "mutant_graph_path",
    "equivalent",
)


def sha256_text(value):
    return hashlib.sha256((value or "").encode("utf-8")).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_source_records(run_dir):
    source_file = Path(run_dir) / "source_records.jsonl"
    if not source_file.is_file():
        raise RuntimeError("Missing call association file: {0}".format(source_file))
    records = []
    with source_file.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, start=1):
            if not line.strip():
                continue
            record = json.loads(line)
            missing = [name for name in ORIGINAL_MUTANT_FIELDS if name not in record]
            if missing:
                raise RuntimeError(
                    "source_records.jsonl line {0} misses fields: {1}".format(
                        line_number, ", ".join(missing)
                    )
                )
            records.append(record)
    if not records:
        raise RuntimeError("No mutant records in {0}".format(source_file))
    return records


def write_call_file(call_dir, call_metadata):
    call_dir = Path(call_dir)
    call_dir.mkdir(parents=True, exist_ok=True)
    (call_dir / "call.json").write_text(
        json.dumps(call_metadata, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def append_mutant_trace(
    run_dir,
    call_metadata,
    source_records,
    visible_to_model=False,
    visible_file_paths=None,
):
    visible_paths = {
        str(value).replace("\\", "/").lower()
        for value in (visible_file_paths or [])
        if str(value).strip()
    }
    trace_file = Path(run_dir) / "call_mutant_trace.jsonl"
    with trace_file.open("a", encoding="utf-8", newline="\n") as stream:
        for source in source_records:
            row = {
                "schema_version": 3,
                "run_id": call_metadata["run_id"],
                "call_id": call_metadata["call_id"],
                "baseline": call_metadata["baseline"],
                "implementation_identity": call_metadata["implementation_identity"],
                "stage": call_metadata["stage"],
                "round": call_metadata["round"],
                "method_group_id": call_metadata["method_group_id"],
                "mutant_uid": source.get("mutant_uid", ""),
                "source_record_uid": source.get("source_record_uid", ""),
                # Legacy aliases retained so old run readers do not break.
                "target_id": source.get("target_id", ""),
                "mutant_id": source.get("mutant_id", source.get("operator", "")),
                "source_path": call_metadata["source_path"],
                "source_sha256": call_metadata["source_sha256"],
                "prompt_sha256": call_metadata["prompt_sha256"],
                "status": call_metadata["status"],
                "visible_to_model": bool(visible_to_model) and (
                    not visible_paths
                    or str(source.get("file_path") or "")
                    .replace("\\", "/")
                    .lower()
                    in visible_paths
                ),
                "token_usage": call_metadata.get("token_usage") or {},
            }
            for name in ORIGINAL_MUTANT_FIELDS:
                row[name] = source[name]
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")
