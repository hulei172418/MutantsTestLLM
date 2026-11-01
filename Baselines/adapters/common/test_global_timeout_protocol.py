"""Global invariants for isolated mutation-test timeout accounting."""

import unittest
from pathlib import Path


BASELINES_DIR = Path(__file__).resolve().parents[2]


def read_properties(path):
    values = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


class GlobalTimeoutProtocolTests(unittest.TestCase):
    def test_all_baselines_delegate_mutation_execution_to_common_module(self):
        entrypoints = {
            "ChatTester": "run_mutant_execution.py",
            "MutGen": "run_pipeline.py",
            "MuTAP": "run_pipeline.py",
            "HITS": "run_pipeline.py",
            "A3Test": "run_pipeline.py",
            "ASTER": "run_pipeline.py",
            "IntUT": "run_pipeline.py",
            "EvoSuite": "pipeline.py",
            "Randoop": "pipeline.py",
        }
        for baseline, relative in entrypoints.items():
            source = (BASELINES_DIR / "adapters" / baseline / relative).read_text(
                encoding="utf-8"
            )
            self.assertIn("from mutant_execution import", source, baseline)
            self.assertIn("execute_mutants(", source, baseline)

    def test_global_timeout_defaults_are_startup_60_per_test_4_lifecycle_60(self):
        properties = read_properties(BASELINES_DIR / "configs" / "java.properties")
        self.assertEqual("60", properties["java.mutant.startup.timeout.seconds"])
        self.assertEqual("4", properties["java.mutant.test.timeout.seconds"])
        self.assertEqual("60", properties["java.mutant.lifecycle.timeout.seconds"])

    def test_worker_marks_execution_after_non_initializing_load_and_before_run(self):
        source = (
            BASELINES_DIR
            / "adapters"
            / "common"
            / "java-tools"
            / "src"
            / "main"
            / "java"
            / "baselines"
            / "common"
            / "tools"
            / "JUnitResultWorker.java"
        ).read_text(encoding="utf-8")
        load = source.index("Class.forName(args[0], false, loader)")
        marker = source.index('put("state", "JUNIT_EXECUTION_STARTED")')
        run = source.index("core.run(testClass)")
        self.assertLess(load, marker)
        self.assertLess(marker, run)

    def test_worker_publishes_a_fresh_deadline_event_for_every_test_method(self):
        source = (
            BASELINES_DIR
            / "adapters"
            / "common"
            / "java-tools"
            / "src"
            / "main"
            / "java"
            / "baselines"
            / "common"
            / "tools"
            / "JUnitResultWorker.java"
        ).read_text(encoding="utf-8")
        self.assertIn('writeProgress("TEST_STARTED", value)', source)
        self.assertIn('writeProgress("TEST_FINISHED", value)', source)

        executor = (
            BASELINES_DIR / "adapters" / "common" / "mutant_execution.py"
        ).read_text(encoding="utf-8")
        self.assertIn('active_test_deadline = now_clock + test_timeout_sec', executor)
        self.assertNotIn('process.wait(timeout=timeout_sec)', executor)

    def test_original_is_complete_but_mutants_are_fail_fast(self):
        executor = (
            BASELINES_DIR / "adapters" / "common" / "mutant_execution.py"
        ).read_text(encoding="utf-8")
        self.assertIn("fail_fast=False", executor)
        self.assertIn("fail_fast=True", executor)
        self.assertIn('"original_fail_fast": False', executor)
        self.assertIn('"mutant_fail_fast": True', executor)

        worker = (
            BASELINES_DIR
            / "adapters"
            / "common"
            / "java-tools"
            / "src"
            / "main"
            / "java"
            / "baselines"
            / "common"
            / "tools"
            / "JUnitResultWorker.java"
        ).read_text(encoding="utf-8")
        self.assertIn("notifier.pleaseStop()", worker)
        self.assertIn("runFailFast(testClass, listener)", worker)


if __name__ == "__main__":
    unittest.main()
