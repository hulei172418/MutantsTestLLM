"""Unit checks for the shared isolated mutant-result semantics."""

import sys
import json
import tempfile
import unittest
from pathlib import Path


COMMON_DIR = Path(__file__).resolve().parents[1] / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from mutant_execution import (
    _classify_mutant,
    _group_mutant_records,
    _mutant_uid,
    _score,
    _resolve_test_artifact,
    _worker_count,
)


def execution(successful=True, returncode=0, status="PASS", infrastructure=False):
    return {
        "timeout": False,
        "startup_timeout": False,
        "lifecycle_timeout": False,
        "execution_started": True,
        "returncode": returncode,
        "worker_result": {
            "successful": successful,
            "infrastructure_error": infrastructure,
            "run_count": 1,
            "failure_count": 0 if successful else 1,
            "tests": [
                {
                    "id": "ExampleTest#testValue",
                    "status": status,
                    "exception_class": "java.lang.AssertionError" if status == "FAIL" else "",
                }
            ],
        },
    }


class MutantExecutionSemanticsTest(unittest.TestCase):
    def setUp(self):
        self.original = execution()

    def test_passing_mutant_is_live(self):
        self.assertEqual(("LIVE", False, []), _classify_mutant(execution(), self.original))

    def test_original_pass_mutant_assertion_failure_is_killed(self):
        status, killed, killing_tests = _classify_mutant(
            execution(successful=False, returncode=1, status="FAIL"), self.original
        )
        self.assertEqual("KILLED", status)
        self.assertTrue(killed)
        self.assertEqual(["ExampleTest#testValue"], killing_tests)

    def test_mutant_only_timeout_is_killed(self):
        mutant = execution()
        mutant["timeout"] = True
        mutant["timeout_phase"] = "TEST_METHOD"
        mutant["timed_out_test_id"] = "ExampleTest#testValue"
        mutant["returncode"] = -1
        status, killed, killing_tests = _classify_mutant(mutant, self.original)
        self.assertEqual("KILLED_TIMEOUT", status)
        self.assertTrue(killed)
        self.assertEqual(["ExampleTest#testValue"], killing_tests)

    def test_unmapped_suite_timeout_is_not_mislabeled_as_a_kill(self):
        mutant = execution()
        mutant["timeout"] = True
        mutant["timeout_phase"] = "TEST_METHOD"
        mutant["returncode"] = -1
        status, killed, _ = _classify_mutant(mutant, self.original)
        self.assertEqual("ERROR_UNMAPPED_TEST_TIMEOUT", status)
        self.assertFalse(killed)

    def test_worker_startup_timeout_is_infrastructure_not_kill(self):
        mutant = execution()
        mutant["startup_timeout"] = True
        mutant["execution_started"] = False
        mutant["returncode"] = -1
        status, killed, _ = _classify_mutant(mutant, self.original)
        self.assertEqual("ERROR_STARTUP_TIMEOUT", status)
        self.assertFalse(killed)

    def test_loader_failure_is_not_a_kill(self):
        mutant = execution(successful=False, returncode=2, status="FAIL", infrastructure=True)
        status, killed, _ = _classify_mutant(mutant, self.original)
        self.assertEqual("ERROR_INFRASTRUCTURE", status)
        self.assertFalse(killed)

    def test_duplicate_operator_names_keep_distinct_file_paths(self):
        first = {"operator": "LOI_1", "file_path": "a/Date.class"}
        second = {"operator": "LOI_1", "file_path": "b/Date.class"}
        self.assertNotEqual(_mutant_uid(first), _mutant_uid(second))

    def test_equivalent_labels_do_not_change_executable_ms(self):
        rows = [
            {"mutant_uid": "a", "status": "KILLED", "equivalent": 0},
            {"mutant_uid": "b", "status": "LIVE", "equivalent": 0},
            {"mutant_uid": "c", "status": "KILLED", "equivalent": 1},
        ]
        score = _score(rows)
        self.assertEqual(3, score["executable_denominator"])
        self.assertEqual(2.0 / 3.0, score["executable_ms"])

    def test_duplicate_artifact_rows_execute_as_one_group(self):
        rows = [
            {"file_path": "same/Date.class", "equivalent": 0},
            {"file_path": "same/Date.class", "equivalent": 1},
            {"file_path": "other/Date.class", "equivalent": 0},
        ]
        groups = _group_mutant_records(rows)
        self.assertEqual(2, len(groups))
        self.assertEqual(2, len(groups[0]))

    def test_equivalent_conflict_is_retained_and_reported(self):
        rows = [
            {"mutant_uid": "same", "status": "LIVE", "equivalent": 0},
            {"mutant_uid": "same", "status": "LIVE", "equivalent": 1},
        ]
        score = _score(rows)
        self.assertEqual(1, score["executable_denominator"])
        self.assertEqual(1, score["equivalent_label_conflicts"])

    def test_worker_count_uses_config_override_and_task_bound(self):
        self.assertEqual(4, _worker_count({"java.mutant.workers": "4"}, 0, 10))
        self.assertEqual(2, _worker_count({"java.mutant.workers": "4"}, 8, 2))
        self.assertEqual(1, _worker_count({"java.mutant.workers": "4"}, 1, 10))

    def test_explicit_round_snapshot_must_compile_and_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            classes = root / "classes"
            classes.mkdir()
            result = root / "result.json"
            result.write_text(
                json.dumps(
                    {
                        "compile": {"compiled": True, "classes_dir": str(classes)},
                        "execution": {"passed": True},
                    }
                ),
                encoding="utf-8",
            )
            _, resolved, result_path, source = _resolve_test_artifact(root, result)
            self.assertEqual(classes.resolve(), resolved)
            self.assertEqual(result.resolve(), result_path)
            self.assertEqual("explicit_round_snapshot", source)


if __name__ == "__main__":
    unittest.main()
