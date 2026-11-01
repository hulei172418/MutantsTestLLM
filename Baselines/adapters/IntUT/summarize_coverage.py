"""Summarize post-selection focal-method JaCoCo metrics for an IntUT batch."""

from __future__ import print_function

import argparse
import json
from datetime import datetime
from pathlib import Path


def _read(path): return json.loads(Path(path).read_text(encoding="utf-8"))
def _write(path, value): Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def summarize(batch_dir):
    batch_dir = Path(batch_dir).resolve(); targets = []
    totals = {"covered_lines": 0, "missed_lines": 0, "covered_branches": 0, "missed_branches": 0}
    for target in sorted(path for path in (batch_dir / "targets").iterdir() if path.is_dir()):
        state = _read(target / "state.json").get("state") if (target / "state.json").is_file() else "MISSING"
        report = target / "coverage" / "final_focal_method" / "line_coverage.json"
        row = {"target": target.name, "state": state, "coverage_available": report.is_file()}
        if report.is_file():
            value = _read(report)
            row.update({
                "start_line": value.get("scope_start_line"), "end_line": value.get("scope_end_line"),
                "covered_lines": int(value.get("covered_line_count") or 0), "missed_lines": int(value.get("missed_line_count") or 0),
                "covered_branches": int(value.get("covered_branch_count") or 0), "missed_branches": int(value.get("missed_branch_count") or 0),
                "line_covered_ratio": value.get("line_covered_ratio"), "branch_covered_ratio": value.get("branch_covered_ratio"),
                "report": str(report),
            })
            for key in totals: totals[key] += row[key]
        targets.append(row)
    line_denominator = totals["covered_lines"] + totals["missed_lines"]
    branch_denominator = totals["covered_branches"] + totals["missed_branches"]
    result = {
        "schema_version": 1, "created_at": datetime.now().astimezone().isoformat(timespec="milliseconds"),
        "batch_dir": str(batch_dir), "scope": "micro aggregate over original-passing focal methods with coverage reports",
        "warning": "Invalid/no-executable targets are listed but excluded because their static focal branch denominators are not available from an accepted runtime artifact; do not label this as all-target coverage.",
        "target_count": len(targets), "coverage_available_target_count": sum(row["coverage_available"] for row in targets),
        "missing_or_invalid_target_count": sum(not row["coverage_available"] for row in targets),
        "covered_line_count": totals["covered_lines"], "missed_line_count": totals["missed_lines"],
        "line_covered_ratio": float(totals["covered_lines"]) / line_denominator if line_denominator else None,
        "covered_branch_count": totals["covered_branches"], "missed_branch_count": totals["missed_branches"],
        "branch_covered_ratio": float(totals["covered_branches"]) / branch_denominator if branch_denominator else None,
        "targets": targets,
    }
    _write(batch_dir / "intut_coverage_summary.json", result); return result


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--batch-dir", required=True)
    args = parser.parse_args(); print(json.dumps(summarize(args.batch_dir), ensure_ascii=False, indent=2))
if __name__ == "__main__": main()

