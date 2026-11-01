"""Map MuJava pretty-printed mutant locations back to current project source lines."""

from __future__ import print_function

import re
from pathlib import Path


def _compact(value):
    return re.sub(r"\s+", "", str(value or ""))


def _method_span(source_lines, method_body):
    declaration = next(
        (line for line in str(method_body or "").splitlines() if line.strip()), ""
    )
    declaration_key = _compact(declaration.split("{", 1)[0])
    starts = [
        index
        for index, line in enumerate(source_lines)
        if declaration_key and declaration_key in _compact(line)
    ]
    if len(starts) != 1:
        return None
    start = starts[0]
    balance = 0
    opened = False
    for index in range(start, len(source_lines)):
        line = source_lines[index]
        balance += line.count("{") - line.count("}")
        opened = opened or "{" in line
        if opened and balance == 0:
            return start + 1, index + 1
    return None


def _mutant_line(record):
    path = str(record.get("mutated_source_path") or "").strip()
    number = record.get("line") or record.get("mutation_line")
    if not path or not number:
        return ""
    try:
        lines = Path(path).read_text(encoding="utf-8", errors="replace").splitlines()
        index = int(number) - 1
        return lines[index] if 0 <= index < len(lines) else ""
    except (OSError, TypeError, ValueError):
        return ""


def _find_compact_fragment(lines, start_line, fragment):
    compact = ""
    line_map = []
    for offset, line in enumerate(lines, start_line):
        for character in line:
            if not character.isspace():
                compact += character
                line_map.append(offset)
    positions = []
    cursor = 0
    while fragment and True:
        found = compact.find(fragment, cursor)
        if found < 0:
            break
        positions.append(line_map[found])
        cursor = found + 1
    return sorted(set(positions))


def map_mutant_line(record, source_path, method_body):
    source_lines = Path(source_path).read_text(
        encoding="utf-8", errors="replace"
    ).splitlines()
    span = _method_span(source_lines, method_body)
    result = {
        "file_path": record.get("file_path"),
        "operator": record.get("operator"),
        "workbook_mutant_line": record.get("line"),
        "mutation_statement": record.get("mutation_statement"),
        "mapped_source_line": None,
        "strategy": "unresolved",
        "method_span": list(span) if span else None,
    }
    if not span:
        result["reason"] = "focal_method_span_not_found"
        return result
    method_lines = source_lines[span[0] - 1 : span[1]]
    mutation = str(record.get("mutation_statement") or "")
    before, separator, after = mutation.partition("=>")
    before_key = _compact(before)
    after_key = _compact(after)
    mutant_line = _mutant_line(record)
    mutant_line_key = _compact(mutant_line)

    if separator and before_key and after_key and after_key in mutant_line_key:
        reconstructed = mutant_line_key.replace(after_key, before_key, 1)
        matches = [
            span[0] + index
            for index, line in enumerate(method_lines)
            if _compact(line) == reconstructed
        ]
        if len(matches) == 1:
            result.update(
                {
                    "mapped_source_line": matches[0],
                    "strategy": "reverse_mutated_line_exact",
                    "mutant_source_line": mutant_line,
                }
            )
            return result

    if before_key:
        matches = _find_compact_fragment(method_lines, span[0], before_key)
        if len(matches) == 1:
            result.update(
                {
                    "mapped_source_line": matches[0],
                    "strategy": "original_fragment_unique_in_method",
                    "mutant_source_line": mutant_line,
                }
            )
            return result
        result["candidate_source_lines"] = matches
        result["reason"] = "original_fragment_not_unique"
    else:
        result["reason"] = "mutation_statement_has_no_original_fragment"
    return result
