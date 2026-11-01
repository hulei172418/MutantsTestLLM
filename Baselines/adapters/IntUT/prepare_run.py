"""Prepare one IntUT target, PAINT intentions, and project-local RAG offline."""

from __future__ import print_function

import argparse
import hashlib
import json
import re
import sys
import time
from datetime import datetime
from pathlib import Path

from analysis import build_intentions
from protocol import BASELINE, STAGE, build_prompt
from rag import build_rag


ADAPTER_DIR = Path(__file__).resolve().parent
BASELINES_DIR = ADAPTER_DIR.parents[1]
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from manifest_paths import DEFAULT_TARGET_MANIFEST
from target_context import extract_context, load_method_groups


DEFAULT_MANIFEST = DEFAULT_TARGET_MANIFEST
RUNS_DIR = BASELINES_DIR / "runs"
SETTINGS_FILE = ADAPTER_DIR / "intut.properties"


def _now():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _stamp():
    now = datetime.now().astimezone()
    return now.strftime("%Y%m%d%H%M%S") + "{0:03d}".format(now.microsecond // 1000)


def default_run_dir(kind="manual"):
    stamp = _stamp()
    for sequence in range(1, 1000):
        candidate = RUNS_DIR / "{0}_intut_{1}_{2:03d}".format(stamp, kind, sequence)
        if not candidate.exists(): return candidate
    raise RuntimeError("Unable to allocate IntUT run ID")


def _write_json(path, value):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""): digest.update(block)
    return digest.hexdigest()


def _brace_end(source, opening):
    depth = 0
    quote = None
    escaped = False
    for index in range(opening, len(source)):
        char = source[index]
        if quote:
            if escaped: escaped = False
            elif char == "\\": escaped = True
            elif char == quote: quote = None
        elif char in ('"', "'"): quote = char
        elif char == "{": depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0: return index + 1
    return None


def _focal_line_range(source_path, method_body, method_name):
    normalize = lambda value: re.sub(r"\r+\n?", "\n", str(value or ""))
    full = normalize(Path(source_path).read_text(encoding="utf-8", errors="replace"))
    body = normalize(method_body).strip()
    at = full.find(body)
    end = at + len(body) if at >= 0 else None
    if at < 0:
        declaration = body.split("{", 1)[0].strip()
        tokens = re.split(r"\s+", declaration)
        declaration_match = re.search(r"\s+".join(re.escape(value) for value in tokens), full) if tokens else None
        if declaration_match:
            at = declaration_match.start(); opening = full.find("{", declaration_match.end()); end = _brace_end(full, opening) if opening >= 0 else None
    if at < 0 or end is None:
        match = re.search(r"(?m)^\s*[^;{}\n]*\b" + re.escape(method_name) + r"\s*\(", full)
        if not match: return None, None
        at = match.start(); opening = full.find("{", match.end()); end = _brace_end(full, opening) if opening >= 0 else None
    if end is None: return None, None
    return full.count("\n", 0, at) + 1, full.count("\n", 0, end) + 1


def load_settings(path=SETTINGS_FILE):
    raw = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1); raw[key.strip()] = value.strip()
    result = {
        "candidates_per_intention": int(raw.get("intut.candidates.per.intention", "5")),
        "rag_jaccard_threshold": float(raw.get("intut.rag.jaccard.threshold", "0.8")),
        "rag_max_test_examples": int(raw.get("intut.rag.max.test.examples", "3")),
        "rag_max_mock_examples": int(raw.get("intut.rag.max.mock.examples", "5")),
        "max_execution_functions": int(raw.get("intut.max.execution.functions", "10")),
        "max_call_recursion_depth": int(raw.get("intut.max.call.recursion.depth", "2")),
        "max_intentions": int(raw.get("intut.max.intentions", "64")),
    }
    if result["candidates_per_intention"] != 5:
        raise ValueError("IntUT paper experiment generates exactly five candidates per request")
    if not 0 <= result["rag_jaccard_threshold"] <= 1: raise ValueError("Invalid Jaccard threshold")
    if result["max_intentions"] <= 0: raise ValueError("max intentions must be positive")
    return result


def prepare(manifest, run_dir):
    preparation_started_at = _now(); started = time.perf_counter()
    manifest = Path(manifest).resolve(); run_dir = Path(run_dir).resolve()
    if manifest.is_dir(): raise IsADirectoryError("IntUT manifest must be a JSONL file: " + str(manifest))
    if not manifest.is_file(): raise FileNotFoundError("IntUT manifest does not exist: " + str(manifest))
    if run_dir.exists(): raise FileExistsError("Run directory already exists: " + str(run_dir))
    records = next(iter(load_method_groups(manifest, limit=1).values())); target = records[0]
    if not target.get("source_path"): raise RuntimeError("Selected IntUT target has no source_path")
    for name in ("prompts", "responses", "raw_tests", "parsed", "rounds", "calls", "coverage"):
        (run_dir / name).mkdir(parents=True, exist_ok=False)
    settings = load_settings()
    context = extract_context(target, run_dir / "target_context.json")
    context["source_path"] = target["source_path"]
    start_line, end_line = _focal_line_range(target["source_path"], context.get("method_body"), context["method_name"])
    context["focal_start_line"] = start_line; context["focal_end_line"] = end_line
    _write_json(run_dir / "target_context.json", context)
    # The manifest project_root also contains MuJava's large `result` tree.
    # IntUT's dynamic RAG is built from the imported source project only.
    rag = build_rag(target["build_root"], context, settings)
    paint = build_intentions(context, rag, settings)
    _write_json(run_dir / "intut_rag.json", rag)
    _write_json(run_dir / "paint_intentions.json", paint)
    test_class = context["simple_class_name"] + "_IntUTTest"
    first_prompt = build_prompt(context, rag, paint["intentions"][0], test_class)
    (run_dir / "prompts" / "intention_001_candidate_01.user.txt").write_text(first_prompt + "\n", encoding="utf-8")
    with (run_dir / "source_records.jsonl").open("w", encoding="utf-8", newline="\n") as stream:
        for record in records: stream.write(json.dumps(record, ensure_ascii=False) + "\n")
    metadata = {
        "schema_version": 1, "baseline": BASELINE,
        "implementation_status": "INDEPENDENT_REIMPLEMENTATION_FROM_PAPER_NO_PUBLIC_SOURCE",
        "preparation_started_at": preparation_started_at, "prepared_at": _now(),
        "preprocessing_elapsed_millis": int((time.perf_counter() - started) * 1000),
        "manifest": str(manifest), "manifest_sha256": _sha256(manifest),
        "project": target["project"], "project_root": target["project_root"],
        "build_root": target["build_root"], "class_name": target["class_name"],
        "method_signature": target["method_signature"], "source_path": target["source_path"],
        "mutant_source_record_count": len(records), "test_framework": "junit4",
        "model_required": "deepseek-v4-flash", "paper_model_replaced": "CodeLLaMA/LLaMA -> DeepSeek",
        "paper_stages": ["PAINT", "project_RAG", "test_intention_guided_generation", "developer_candidate_selection"],
        "llm_stages": [STAGE], "repair_stage_present": False, "coverage_feedback_present": False,
        "coverage_reporting": "post-selection focal-method JaCoCo line/branch counters; never visible to model or candidate selector",
        "settings": settings,
        "candidate_selection_adaptation": "five independent DeepSeek calls per intention; first original-passing candidate replaces paper developer selection",
        "information_policy": "no mutant information or mutation feedback visible to PAINT, RAG, or LLM",
        "source_availability": "No verifiable public IntUT implementation found; DOI paper is authoritative.",
    }
    _write_json(run_dir / "metadata.json", metadata)
    _write_json(run_dir / "state.json", {"state": "PREPARED_API_NOT_CALLED", "api_called": False, "next_stage": STAGE, "updated_at": _now()})
    return {"run_dir": str(run_dir), "state": "PREPARED_API_NOT_CALLED", "source_record_count": len(records), "intention_count": len(paint["intentions"]), "retrieved_test_example_count": len(rag["test_examples"]), "retrieved_mock_example_count": len(rag["mock_examples"])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST)); parser.add_argument("--run-dir", default=None)
    args = parser.parse_args(); print(json.dumps(prepare(args.manifest, args.run_dir or default_run_dir()), ensure_ascii=False, indent=2))


if __name__ == "__main__": main()

