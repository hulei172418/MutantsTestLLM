import json
import sys
import tempfile
import unittest
from pathlib import Path


ADAPTER_DIR = Path(__file__).resolve().parent
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from summarize_mutation_batch import summarize_batch


def write_json(path, value):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(value) + "\n", encoding="utf-8")


def write_jsonl(path, values):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(
        "".join(json.dumps(value) + "\n" for value in values), encoding="utf-8"
    )


def source(mutant, file_path):
    return {
        "project": "p",
        "class": "C",
        "class_name": "p.C",
        "method": "m()",
        "method_signature": "m()",
        "operator": mutant,
        "file_path": file_path,
    }


class MutationBatchSummaryTest(unittest.TestCase):
    def test_invalid_test_mutants_lower_end_to_end_score(self):
        with tempfile.TemporaryDirectory() as directory:
            batch = Path(directory)
            passed = batch / "targets" / "001"
            failed = batch / "targets" / "002"
            passed_rows = [source("A", "a.class"), source("B", "b.class")]
            failed_rows = [
                source("C", "c.class"),
                source("C-duplicate", "c.class"),
                source("D", "d.class"),
            ]
            write_json(passed / "state.json", {"state": "ORIGINAL_PASSED"})
            write_jsonl(passed / "source_records.jsonl", passed_rows)
            write_json(failed / "state.json", {"state": "ORIGINAL_FAILED"})
            write_jsonl(failed / "source_records.jsonl", failed_rows)

            results_path = passed / "mx-results.jsonl"
            # Importing the shared UID function is deliberately avoided here:
            # the summarizer must derive the same identity from source rows.
            from mutant_execution import _mutant_uid

            write_jsonl(
                results_path,
                [
                    {"mutant_uid": _mutant_uid(passed_rows[0]), "status": "KILLED"},
                    {"mutant_uid": _mutant_uid(passed_rows[1]), "status": "LIVE"},
                ],
            )
            write_json(
                passed / "mutant_execution" / "latest.json",
                {"execution_id": "x", "results_jsonl": str(results_path)},
            )

            summary = summarize_batch(batch)
            self.assertEqual(4, summary["total_unique_mapped_mutants"])
            self.assertEqual(2, summary["not_executed_invalid_test"])
            self.assertEqual(0.5, summary["executable_ms"])
            self.assertEqual(0.25, summary["end_to_end_ms"])

    def test_passed_target_without_mutation_result_rejects_formal_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            batch = Path(directory)
            passed = batch / "targets" / "001"
            write_json(passed / "state.json", {"state": "ORIGINAL_PASSED"})
            write_jsonl(passed / "source_records.jsonl", [source("A", "a.class")])

            with self.assertRaisesRegex(RuntimeError, "001"):
                summarize_batch(batch)

            diagnostic = summarize_batch(batch, allow_incomplete=True)
            self.assertFalse(diagnostic["formal_score_valid"])
            self.assertEqual(["001"], diagnostic["incomplete_passed_targets"])

    def test_evaluation_infrastructure_failure_does_not_penalize_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            batch = Path(directory)
            target = batch / "targets" / "001"
            write_json(
                target / "state.json", {"state": "MUTATION_INFRASTRUCTURE_FAILED"}
            )
            write_jsonl(target / "source_records.jsonl", [source("A", "a.class")])
            summary = summarize_batch(batch)
            self.assertEqual(0, summary["not_executed_invalid_test"])
            self.assertEqual(1, summary["other_not_executed_or_error"])
            self.assertEqual(0, summary["end_to_end_denominator"])
            self.assertFalse(summary["formal_score_valid"])
            self.assertEqual(["001"], summary["infrastructure_failure_targets"])

    def test_pipeline_failure_is_non_scorable_not_assumed_infrastructure(self):
        with tempfile.TemporaryDirectory() as directory:
            batch = Path(directory)
            target = batch / "targets" / "001"
            write_json(target / "state.json", {"state": "PIPELINE_FAILED"})
            write_jsonl(target / "source_records.jsonl", [source("A", "a.class")])
            summary = summarize_batch(batch)
            self.assertEqual(1, summary["total_unique_mapped_mutants"])
            self.assertEqual(0, summary["not_executed_invalid_test"])
            self.assertEqual(1, summary["other_not_executed_or_error"])
            self.assertEqual(0, summary["end_to_end_denominator"])
            self.assertFalse(summary["formal_score_valid"])
            self.assertEqual([], summary["infrastructure_failure_targets"])
            self.assertEqual(["001"], summary["experiment_failure_targets"])

    def test_legacy_pipeline_network_failure_is_inferred_without_rewrite(self):
        with tempfile.TemporaryDirectory() as directory:
            batch = Path(directory)
            target = batch / "targets" / "001"
            recorded = {
                "state": "PIPELINE_FAILED",
                "error_message": "LLM API failed after 2 attempt(s): getaddrinfo failed",
            }
            write_json(target / "state.json", recorded)
            write_jsonl(target / "source_records.jsonl", [source("A", "a.class")])
            summary = summarize_batch(batch)
            self.assertFalse(summary["formal_score_valid"])
            self.assertEqual(["001"], summary["infrastructure_failure_targets"])
            self.assertEqual([], summary["experiment_failure_targets"])
            self.assertEqual("PIPELINE_FAILED", json.loads((target / "state.json").read_text(encoding="utf-8"))["state"])
            self.assertEqual("MODEL_PROVIDER_FAILED", summary["targets"][0]["state"])
            self.assertEqual("PIPELINE_FAILED", summary["targets"][0]["recorded_state"])

    def test_legacy_mutgen_wrapper_failure_recovers_invalid_test_outcome(self):
        with tempfile.TemporaryDirectory() as directory:
            batch = Path(directory)
            target = batch / "targets" / "001"
            write_json(
                target / "state.json",
                {
                    "state": "PIPELINE_FAILED",
                    "error_type": "KeyError",
                    "error_message": "'accepted_test_count'",
                },
            )
            write_jsonl(target / "source_records.jsonl", [source("A", "a.class")])
            write_json(target / "rounds" / "001" / "accepted_tests.json", [])
            summary = summarize_batch(batch)
            self.assertTrue(summary["formal_score_valid"])
            self.assertEqual(1, summary["not_executed_invalid_test"])
            self.assertEqual(1, summary["end_to_end_denominator"])
            self.assertEqual("NO_EXECUTABLE_CANDIDATE", summary["targets"][0]["state"])
            self.assertEqual("PIPELINE_FAILED", summary["targets"][0]["recorded_state"])

    def test_legacy_mutgen_invalid_yaml_is_baseline_output_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            batch = Path(directory)
            target = batch / "targets" / "001"
            write_json(
                target / "state.json",
                {
                    "state": "PIPELINE_FAILED",
                    "error_type": "ValueError",
                    "error_message": "MutGen response has no valid new_tests YAML: scanner error",
                },
            )
            write_jsonl(target / "source_records.jsonl", [source("A", "a.class")])
            summary = summarize_batch(batch)
            self.assertTrue(summary["formal_score_valid"])
            self.assertEqual(1, summary["not_executed_invalid_test"])
            self.assertEqual("MODEL_OUTPUT_INVALID", summary["targets"][0]["state"])

    def test_declared_batch_target_cannot_disappear_from_formal_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            batch = Path(directory)
            target = batch / "targets" / "001"
            write_json(
                batch / "batch_metadata.json", {"selected_method_count": 2}
            )
            write_json(target / "state.json", {"state": "ORIGINAL_FAILED"})
            write_jsonl(target / "source_records.jsonl", [source("A", "a.class")])

            with self.assertRaisesRegex(RuntimeError, "002"):
                summarize_batch(batch)

            diagnostic = summarize_batch(batch, allow_incomplete=True)
            self.assertFalse(diagnostic["formal_score_valid"])
            self.assertEqual(["002"], diagnostic["missing_or_invalid_targets"])


if __name__ == "__main__":
    unittest.main()
