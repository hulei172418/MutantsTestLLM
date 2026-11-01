import json
import sys
import tempfile
import unittest
from pathlib import Path


ADAPTER_DIR = Path(__file__).resolve().parent
if str(ADAPTER_DIR) not in sys.path:
    sys.path.insert(0, str(ADAPTER_DIR))

from feedback import (
    advance_feedback_counters,
    extract_error_line,
    should_stop,
)
from run_feedback_pipeline import _complete_compiled_outcome


class FakePipeline:
    def __init__(self):
        self.publish_calls = 0

    def publish_final(self, outcome):
        self.publish_calls += 1
        return {
            "final_source_root": "final/src",
            "final_classes_root": "final/classes",
        }


class ChatTesterFeedbackProtocolTest(unittest.TestCase):
    def test_compiled_execution_failure_is_terminal_without_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            pipeline = FakePipeline()
            outcome = {
                "project_artifact_root": "artifact",
                "execution": {"passed": False, "timeout": False, "status": "FAIL"},
            }
            result = _complete_compiled_outcome(
                Path(directory), pipeline, outcome, [outcome], 1, 0
            )
            state = json.loads(
                (Path(directory) / "state.json").read_text(encoding="utf-8")
            )
            self.assertEqual("ORIGINAL_FAILED", result["state"])
            self.assertEqual("paper_compile_only", state["feedback_protocol"])
            self.assertEqual(0, pipeline.publish_calls)

    def test_compiled_passing_execution_is_published_for_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            pipeline = FakePipeline()
            outcome = {
                "project_artifact_root": "artifact",
                "execution": {"passed": True, "timeout": False, "status": "PASS"},
            }
            result = _complete_compiled_outcome(
                Path(directory), pipeline, outcome, [outcome], 2, 1
            )
            state = json.loads(
                (Path(directory) / "state.json").read_text(encoding="utf-8")
            )
            self.assertEqual("ORIGINAL_PASSED", result["state"])
            self.assertEqual("mutant_execution", state["next_stage"])
            self.assertEqual(1, pipeline.publish_calls)

    def test_pinned_strict_stop_boundaries(self):
        self.assertFalse(should_stop(3, 5, 30))
        self.assertTrue(should_stop(4, 5, 30))
        self.assertTrue(should_stop(3, 6, 30))
        self.assertTrue(should_stop(3, 5, 31))

    def test_extracts_javac_and_junit_lines(self):
        self.assertEqual(17, extract_error_line("Example.java:17: error: bad"))
        self.assertEqual(23, extract_error_line("at p.Example.test(Example.java:23)"))

    def test_compile_to_test_transition_counts_first_test_failure(self):
        state = advance_feedback_counters(
            "compileRepair", True, False, 0, [], 0, 1, 0
        )
        self.assertEqual("testRepair", state["repair_tag"])
        self.assertEqual(1, state["iter_test"])
        self.assertFalse(state["restore_initial"])

    def test_test_repair_compile_failure_keeps_tag_and_restores_initial(self):
        state = advance_feedback_counters(
            "testRepair", False, False, 2, [], 0, 1, 0
        )
        self.assertEqual("testRepair", state["repair_tag"])
        self.assertEqual(1, state["iter_test"])
        self.assertTrue(state["restore_initial"])

    def test_non_improving_compile_errors_match_author_counter(self):
        first = advance_feedback_counters(
            "compileRepair", False, False, 2, [], 0, 1, 0
        )
        second = advance_feedback_counters(
            first["repair_tag"],
            False,
            False,
            2,
            first["error_numbers"],
            first["non_improving"],
            first["iter_compile"],
            first["iter_test"],
        )
        self.assertEqual(1, second["non_improving"])
        self.assertEqual(3, second["iter_compile"])


if __name__ == "__main__":
    unittest.main()
