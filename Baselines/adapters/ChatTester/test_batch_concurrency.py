"""Concurrency configuration checks without making model calls."""

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import run_batch
import run_feedback_batch


class BatchConcurrencyTest(unittest.TestCase):
    def test_feedback_summary_counts_original_failures_separately(self):
        with tempfile.TemporaryDirectory() as directory:
            run_dir = str(Path(directory) / "target")
            results = [
                {"status": "ORIGINAL_PASSED", "run_dir": run_dir},
                {"status": "ORIGINAL_FAILED", "run_dir": run_dir},
                {"status": "ORIGINAL_FAILED", "run_dir": run_dir},
                {"status": "REPAIR_LIMIT_REACHED", "run_dir": run_dir},
            ]
            summary = run_feedback_batch.summarize_feedback_results(
                directory, results, worker_count=4
            )
            self.assertEqual(1, summary["original_passed_count"])
            self.assertEqual(2, summary["original_failed_count"])
            self.assertEqual(1, summary["repair_limit_reached_count"])
            self.assertEqual(0, summary["failed_count"])

    def test_generation_workers_use_llm_threads_and_task_bound(self):
        with patch.object(run_batch, "load_config", return_value={"thread_count": 8}):
            self.assertEqual(8, run_batch._worker_count(0, 20))
            self.assertEqual(3, run_batch._worker_count(0, 3))
            self.assertEqual(2, run_batch._worker_count(2, 20))

    def test_feedback_workers_use_same_config_contract(self):
        with patch.object(
            run_feedback_batch, "load_config", return_value={"thread_count": 6}
        ):
            self.assertEqual(6, run_feedback_batch._worker_count(0, 20))
            self.assertEqual(2, run_feedback_batch._worker_count(9, 2))

    def test_invalid_worker_count_is_rejected(self):
        with patch.object(run_batch, "load_config", return_value={"thread_count": 0}):
            with self.assertRaises(ValueError):
                run_batch._worker_count(0, 1)


if __name__ == "__main__":
    unittest.main()
