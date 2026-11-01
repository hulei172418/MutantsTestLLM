"""Regression checks for formal-run efficiency accounting."""

import json
import sys
import tempfile
import unittest
from pathlib import Path

COMMON_DIR = Path(__file__).resolve().parent
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from summarize_efficiency import _mutation_metrics


class MutationEfficiencyTests(unittest.TestCase):
    def test_only_latest_published_execution_is_counted(self):
        with tempfile.TemporaryDirectory() as temporary:
            run_dir = Path(temporary)
            root = run_dir / "mutant_execution"
            historical = root / "five_seconds"
            current = root / "ten_seconds"
            historical.mkdir(parents=True)
            current.mkdir(parents=True)
            (historical / "summary.json").write_text(
                json.dumps({"elapsed_millis": 5000, "mutants_executed": 231}),
                encoding="utf-8",
            )
            (current / "summary.json").write_text(
                json.dumps({"elapsed_millis": 10000, "mutants_executed": 118}),
                encoding="utf-8",
            )
            (root / "latest.json").write_text(
                json.dumps({"execution_id": "ten_seconds"}), encoding="utf-8"
            )

            self.assertEqual(
                {
                    "mutation_execution_invocation_count": 1,
                    "mutation_execution_elapsed_millis": 10000,
                    "mutant_jvm_execution_count": 118,
                },
                _mutation_metrics(run_dir),
            )

    def test_unpublished_history_is_not_counted_without_latest(self):
        with tempfile.TemporaryDirectory() as temporary:
            run_dir = Path(temporary)
            history = run_dir / "mutant_execution" / "smoke"
            history.mkdir(parents=True)
            (history / "summary.json").write_text(
                json.dumps({"elapsed_millis": 1000, "mutants_executed": 1}),
                encoding="utf-8",
            )

            self.assertEqual(0, _mutation_metrics(run_dir)["mutant_jvm_execution_count"])


if __name__ == "__main__":
    unittest.main()
