"""Functional checks for the per-@Test mutation execution watchdog."""

import os
import shutil
import tempfile
import unittest
from pathlib import Path

from mutant_execution import (
    JAVA_TOOLS_JAR,
    JAVA_TOOLS_RUNTIME_CLASSPATH,
    _execute_worker,
)


COMMON_DIR = Path(__file__).resolve().parent
TEST_CLASSES = COMMON_DIR / "java-tools" / "target" / "test-classes"


def _java():
    return shutil.which("java.exe" if os.name == "nt" else "java")


def _classpath():
    values = [str(TEST_CLASSES), str(JAVA_TOOLS_JAR)]
    values.extend(
        value
        for value in JAVA_TOOLS_RUNTIME_CLASSPATH.read_text(encoding="utf-8").split(os.pathsep)
        if value
    )
    return values


@unittest.skipUnless(_java() and JAVA_TOOLS_JAR.is_file() and TEST_CLASSES.is_dir(),
                     "common Java tools must be built before functional timeout checks")
class PerTestTimeoutExecutionTests(unittest.TestCase):
    def test_mutant_fail_fast_stops_after_first_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            execution = _execute_worker(
                _java(), _classpath(),
                "baselines.common.tools.FailFastFixture",
                root / "execution", root,
                test_timeout_sec=4,
                startup_timeout_sec=10,
                lifecycle_timeout_sec=10,
                fail_fast=True,
            )
        worker = execution["worker_result"]
        self.assertEqual(1, execution["returncode"])
        self.assertTrue(execution["fail_fast"])
        self.assertTrue(execution["fail_fast_stop_requested"])
        self.assertTrue(execution["stopped_early"])
        self.assertEqual(1, worker["run_count"])
        self.assertEqual(1, worker["failure_count"])
        self.assertEqual("test01KillsMutant", worker["tests"][0]["method_name"])

    def test_original_non_fail_fast_still_runs_complete_suite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            execution = _execute_worker(
                _java(), _classpath(),
                "baselines.common.tools.FailFastFixture",
                root / "execution", root,
                test_timeout_sec=4,
                startup_timeout_sec=10,
                lifecycle_timeout_sec=10,
                fail_fast=False,
            )
        worker = execution["worker_result"]
        self.assertEqual(1, execution["returncode"])
        self.assertFalse(execution["fail_fast"])
        self.assertFalse(execution["fail_fast_stop_requested"])
        self.assertFalse(execution["stopped_early"])
        self.assertEqual(2, worker["run_count"])
        self.assertEqual(1, worker["failure_count"])

    def test_suite_can_exceed_one_test_budget_when_each_test_is_within_budget(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            execution = _execute_worker(
                _java(), _classpath(),
                "baselines.common.tools.PerTestTimeoutFixture",
                root / "execution", root,
                test_timeout_sec=2,
                startup_timeout_sec=10,
                lifecycle_timeout_sec=10,
            )
        self.assertFalse(execution["timeout"])
        self.assertEqual(0, execution["returncode"])
        self.assertEqual(2, execution["worker_result"]["run_count"])
        self.assertGreaterEqual(execution["execution_duration_ms"], 2200)
        durations = [
            test["duration_ms"] for test in execution["worker_result"]["tests"]
        ]
        self.assertEqual(2, len(durations))
        self.assertTrue(all(1000 <= value < 2000 for value in durations))

    def test_only_the_active_test_method_uses_the_test_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            execution = _execute_worker(
                _java(), _classpath(),
                "baselines.common.tools.SingleTestTimeoutFixture",
                root / "execution", root,
                test_timeout_sec=1,
                startup_timeout_sec=10,
                lifecycle_timeout_sec=10,
            )
        self.assertTrue(execution["timeout"])
        self.assertEqual("TEST_METHOD", execution["timeout_phase"])
        self.assertTrue(execution["timed_out_test_id"].endswith("#testTooSlow"))


if __name__ == "__main__":
    unittest.main()
