"""Build the frozen baseline target manifest from MutantsTestLLM's Excel file."""

from __future__ import print_function

import argparse
import hashlib
import json
import os
from collections import Counter
from datetime import datetime
from pathlib import Path

import openpyxl

from manifest_paths import (
    DEFAULT_MUTANT_WORKBOOK,
    DEFAULT_TARGET_MANIFEST,
)


DEFAULT_INPUT = DEFAULT_MUTANT_WORKBOOK
DEFAULT_PROGRAMS_ROOT = Path(r"E:/PHD/testJava/Programs")
DEFAULT_OUTPUT = DEFAULT_TARGET_MANIFEST


def _progress(message):
    timestamp = datetime.now().astimezone().isoformat(timespec="milliseconds")
    print("[{0}] {1}".format(timestamp, message), flush=True)


REQUIRED_COLUMNS = {
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
}
IGNORED_SOURCE_DIRS = {
    ".git",
    ".mujava_tmp",
    ".gradle",
    "evoSuite",
    "build",
    "dist",
    "llm",
    "mujava_logs",
    "out",
    "result",
    "target",
}


def _sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _clean_extended_path(value):
    text = str(value or "").strip()
    if text.startswith("\\\\?\\"):
        text = text[4:]
    return str(Path(text).resolve()) if text else ""


def _target_class(raw_package, class_name):
    value = str(raw_package or "").strip()
    # Some mutation workbooks store the source-root segment as part of the
    # package-like value (for example ``java.org.apache...`` for ``src/java``).
    # It is a filesystem marker, not part of the JVM binary name.  Keep this
    # aligned with the historical EvoSuiteTestGenerator normalization.
    for prefix in ("main.java.", "test.java.", "main.", "test.", "java."):
        if value.startswith(prefix):
            value = value[len(prefix) :]
            break
    if not value:
        return str(class_name or "").strip()
    return value


class SourceResolver(object):
    def __init__(self, programs_root):
        self.programs_root = Path(programs_root).resolve()
        self._indices = {}
        self._build_roots = {}

    def _index_project(self, project):
        if project in self._indices:
            return
        project_root = self.programs_root / project
        _progress("Indexing project source: {0}".format(project_root))
        if not project_root.is_dir():
            self._indices[project] = {}
            self._build_roots[project] = ""
            _progress("Project directory missing: {0}".format(project_root))
            return

        index = {}
        build_candidates = []
        for current, directories, files in os.walk(str(project_root)):
            directories[:] = [
                name for name in directories if name not in IGNORED_SOURCE_DIRS
            ]
            current_path = Path(current)
            if any(
                name in files
                for name in (
                    "pom.xml",
                    "build.gradle",
                    "build.gradle.kts",
                    "settings.gradle",
                    "settings.gradle.kts",
                    "build.xml",
                )
            ):
                build_candidates.append(current_path)
            for filename in files:
                if filename.endswith(".java"):
                    path = (current_path / filename).resolve()
                    index.setdefault(filename, []).append(path)

        build_candidates.sort(
            key=lambda path: (len(path.parts), str(path).lower())
        )
        self._indices[project] = index
        self._build_roots[project] = (
            str(build_candidates[0].resolve()) if build_candidates else ""
        )
        _progress(
            "Indexed project={0}, source_paths={1}, build_candidates={2}".format(
                project,
                sum(len(paths) for paths in index.values()),
                len(build_candidates),
            )
        )

    def resolve(self, project, target_class):
        self._index_project(project)
        relative = target_class.replace(".", "/") + ".java"
        relative_parts = tuple(relative.split("/"))
        filename = relative_parts[-1]
        matches = [
            path
            for path in self._indices.get(project, {}).get(filename, [])
            if tuple(path.parts[-len(relative_parts) :]) == relative_parts
        ]
        if not matches:
            return ""

        def source_rank(path):
            normalized = "/".join(path.parts).lower()
            if "/src/main/java/" in normalized:
                priority = 0
            elif "/src/main/" in normalized:
                priority = 1
            elif "/src/java/" in normalized:
                priority = 2
            elif "/src/" in normalized and "/test" not in normalized:
                priority = 3
            elif "/test" in normalized:
                priority = 5
            else:
                priority = 4
            return (priority, len(path.parts), str(path).lower())

        return str(sorted(matches, key=source_rank)[0])

    def build_root(self, project):
        self._index_project(project)
        return self._build_roots.get(project, "")


def build_manifest(
    input_excel,
    programs_root,
    output,
    projects=None,
    class_names=None,
    method_signatures=None,
    max_methods=0,
    progress_every=1000,
):
    input_excel = Path(input_excel).resolve()
    programs_root = Path(programs_root).resolve()
    output = Path(output).resolve()
    selected_projects = set(projects or [])
    selected_classes = set(class_names or [])
    selected_methods = set(method_signatures or [])

    _progress("Loading workbook: {0}".format(input_excel))

    workbook = openpyxl.load_workbook(
        str(input_excel), read_only=True, data_only=True
    )
    sheet = workbook[workbook.sheetnames[0]]
    rows = sheet.iter_rows(values_only=True)
    headers = [str(value or "").strip() for value in next(rows)]
    missing = sorted(REQUIRED_COLUMNS.difference(headers))
    if missing:
        raise ValueError("Excel is missing columns: " + ", ".join(missing))

    resolver = SourceResolver(programs_root)
    accepted_methods = set()
    counts = Counter()
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    _progress(
        "Building manifest: sheet={0}, rows={1}, temporary={2}".format(
            sheet.title, sheet.max_row, temporary
        )
    )

    with temporary.open("w", encoding="utf-8", newline="\n") as destination:
        for row_number, values in enumerate(rows, start=2):
            counts["input_rows"] += 1
            if progress_every > 0 and counts["input_rows"] % progress_every == 0:
                _progress(
                    "Progress input_rows={0}, accepted_methods={1}, mutants={2}".format(
                        counts["input_rows"], len(accepted_methods), counts["mutants"]
                    )
                )
            record = dict(zip(headers, values))
            project = str(record.get("project") or "").strip()
            if not project or (selected_projects and project not in selected_projects):
                continue

            method_signature = str(record.get("method") or "").strip()
            class_name = str(record.get("class") or "").strip()
            target_class = _target_class(record.get("package"), class_name)
            if selected_classes and target_class not in selected_classes:
                continue
            if selected_methods and method_signature not in selected_methods:
                continue
            method_key = (project, target_class, method_signature)
            if method_key not in accepted_methods:
                if max_methods and len(accepted_methods) >= max_methods:
                    continue
                accepted_methods.add(method_key)

            mutant_id = str(record.get("operator") or "").strip()
            mutant_artifact = _clean_extended_path(record.get("file_path"))
            mutated_source = ""
            if mutant_artifact:
                source_candidate = str(Path(mutant_artifact).with_suffix(".java"))
                if Path(source_candidate).is_file():
                    mutated_source = source_candidate

            source_path = resolver.resolve(project, target_class)
            method_group_id = "|".join((project, target_class, method_signature))
            normalized_mutant_path = os.path.normcase(
                os.path.normpath(mutant_artifact)
            )
            mutant_digest = hashlib.sha256(
                (method_group_id + "|" + normalized_mutant_path).encode("utf-8")
            ).hexdigest()[:16]
            mutant_uid = "{0}_{1}".format(mutant_id or "mutant", mutant_digest)
            source_identity = [
                str(record.get(name) if record.get(name) is not None else "")
                for name in (
                    "operator", "line", "method", "class", "class_f",
                    "mutation_statement", "package", "project", "file_path",
                    "original_graph_path", "mutant_graph_path", "equivalent",
                )
            ]
            source_identity.append("row={0}".format(row_number))
            source_record_uid = "record_" + hashlib.sha256(
                json.dumps(
                    source_identity, ensure_ascii=False, separators=(",", ":")
                ).encode("utf-8")
            ).hexdigest()[:16]
            target_hash = hashlib.sha256(
                method_group_id.encode("utf-8")
            ).hexdigest()
            item = {
                "schema_version": 3,
                # Original mutant_total.xlsx columns. Keep these names stable so
                # every later API/execution log can reproduce the source row.
                "operator": str(record.get("operator") or ""),
                "line": int(record.get("line") or 0),
                "method": str(record.get("method") or ""),
                "class": str(record.get("class") or ""),
                "class_f": str(record.get("class_f") or ""),
                "mutation_statement": str(record.get("mutation_statement") or ""),
                "package": str(record.get("package") or ""),
                "project": project,
                "file_path": str(record.get("file_path") or ""),
                "original_graph_path": str(record.get("original_graph_path") or ""),
                "mutant_graph_path": str(record.get("mutant_graph_path") or ""),
                "equivalent": record.get("equivalent"),
                # Normalized execution aliases.
                "method_group_id": method_group_id,
                "mutant_uid": mutant_uid,
                "source_record_uid": source_record_uid,
                "source_row_number": row_number,
                # Legacy aliases retained for existing adapters only.
                "target_id": method_group_id,
                "target_hash": target_hash,
                "project_root": str((programs_root / project).resolve()),
                "build_root": resolver.build_root(project),
                "class_name": target_class,
                "simple_class_name": class_name,
                "method_signature": method_signature,
                "source_path": source_path,
                "mutant_id": mutant_id,
                "mutation_operator": mutant_id.split("_", 1)[0],
                "mutation_line": int(record.get("line") or 0),
                "mutant_artifact_path": mutant_artifact,
                "original_source_path": source_path,
                "mutated_source_path": mutated_source,
                "normalized_original_graph_path": _clean_extended_path(
                    record.get("original_graph_path")
                ),
                "normalized_mutant_graph_path": _clean_extended_path(
                    record.get("mutant_graph_path")
                ),
                "classpath": [],
                "classpath_status": "deferred_to_project_builder",
                "test_framework": "junit4",
                "source_resolved": bool(source_path),
            }
            destination.write(json.dumps(item, ensure_ascii=False) + "\n")
            counts["mutants"] += 1
            counts["source_resolved"] += int(bool(source_path))
            counts["mutated_source_resolved"] += int(bool(mutated_source))

    temporary.replace(output)
    workbook.close()

    summary = {
        "schema_version": 2,
        "input_excel": str(input_excel),
        "input_sha256": _sha256_file(input_excel),
        "sheet": sheet.title,
        "programs_root": str(programs_root),
        "projects_filter": sorted(selected_projects),
        "class_names_filter": sorted(selected_classes),
        "method_signatures_filter": sorted(selected_methods),
        "max_methods": int(max_methods),
        "method_count": len(accepted_methods),
        "mutant_count": counts["mutants"],
        "source_resolved_count": counts["source_resolved"],
        "mutated_source_resolved_count": counts["mutated_source_resolved"],
        "output": str(output),
        "output_sha256": _sha256_file(output),
    }
    summary_path = output.with_suffix(output.suffix + ".summary.json")
    summary_path.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    _progress(
        "Completed methods={0}, mutants={1}, output={2}".format(
            len(accepted_methods), counts["mutants"], output
        )
    )
    return summary


def _parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input", default=str(DEFAULT_INPUT), help="mutant_total.xlsx"
    )
    parser.add_argument(
        "--programs-root", default=str(DEFAULT_PROGRAMS_ROOT)
    )
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT))
    parser.add_argument("--project", action="append", default=[])
    parser.add_argument("--class-name", action="append", default=[])
    parser.add_argument("--method-signature", action="append", default=[])
    parser.add_argument(
        "--max-methods",
        type=int,
        default=0,
        help="0 keeps every method; useful for a bounded smoke manifest",
    )
    parser.add_argument(
        "--progress-every",
        type=int,
        default=1000,
        help="print millisecond progress every N input rows; 0 disables row logs",
    )
    return parser.parse_args()


def main():
    args = _parse_args()
    summary = build_manifest(
        args.input,
        args.programs_root,
        args.output,
        projects=args.project,
        class_names=args.class_name,
        method_signatures=args.method_signature,
        max_methods=args.max_methods,
        progress_every=args.progress_every,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
