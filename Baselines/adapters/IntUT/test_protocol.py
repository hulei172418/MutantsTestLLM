from __future__ import print_function
import sys, tempfile, unittest
from pathlib import Path
HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path: sys.path.insert(0, str(HERE))
from analysis import build_intentions
from protocol import BASELINE, build_prompt, normalize_test_source, test_count
from rag import build_rag
from prepare_run import _focal_line_range
from summarize_coverage import summarize as summarize_coverage
from summarize_efficiency import _failed_attempt_count
from run_batch import _failure_state
from llm_gateway import _is_retryable_provider_error, _retry_delay_seconds


class IntUTProtocolTest(unittest.TestCase):
    def setUp(self):
        self.context = {"source_path": "p/Subject.java", "class_name": "p.Subject", "simple_class_name": "Subject", "package_name": "p", "method_name": "classify", "method_signature": "int_classify(int)", "method_declaration": "public int classify(int x)", "method_body": "public int classify(int x) { if (x > 0) { return 1; } return 0; }", "imports": [], "fields": ["private int state;"], "all_method_signatures": ["public int classify(int x)"]}
        self.rag = {"test_examples": [], "mock_examples": [], "historical_mock_calls": []}
        self.settings = {"max_intentions": 64}

    def test_two_intentions_for_if(self):
        result = build_intentions(self.context, self.rag, self.settings)
        self.assertEqual(len(result["intentions"]), 2)
        self.assertIn("x > 0", result["intentions"][0]["test_intention"])

    def test_straight_line_gets_one_intention(self):
        self.context["method_body"] = "public int classify(int x) { return x; }"
        self.assertEqual(len(build_intentions(self.context, self.rag, self.settings)["intentions"]), 1)

    def test_historical_mock_evidence_controls_mock(self):
        self.context["method_body"] = "public int classify(int x) { if (service.ready()) return 1; return 0; }"
        self.rag["historical_mock_calls"] = ["ready"]
        text = build_intentions(self.context, self.rag, self.settings)["intentions"][0]["test_intention"]
        self.assertIn("Mock `service.ready`", text)

    def test_prompt_has_four_paper_sections(self):
        intention = build_intentions(self.context, self.rag, self.settings)["intentions"][0]
        prompt = build_prompt(self.context, self.rag, intention, "Subject_IntUTTest")
        for section in ("1. Program context", "2. Example code", "3. Test code prefix", "4. Test intention"):
            self.assertIn(section, prompt)
        self.assertNotIn("mutation_statement", prompt)

    def test_normalization(self):
        source = "package wrong; import org.junit.jupiter.api.Test; public class Wrong { @Test public void ok() {} }"
        normalized = normalize_test_source(source, "p", "Subject_IntUTTest")
        self.assertIn("package p;", normalized); self.assertIn("org.junit.Test", normalized); self.assertIn("class Subject_IntUTTest", normalized); self.assertEqual(test_count(normalized), 1)

    def test_identity_marks_reimplementation(self): self.assertEqual(BASELINE, "IntUT-DS-Reimplementation")

    def test_focal_line_range_uses_full_overload_declaration(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "Overload.java"
            source.write_text(
                "class Overload {\n"
                "  void write(byte[] value) {}\n"
                "  void write(byte[] value, String encoding, boolean mode) {\n"
                "    if (mode) { value[0] = 1; }\n"
                "  }\n}\n", encoding="utf-8",
            )
            body = "void write(byte[] value, String encoding, boolean mode) {\r\r\n if (mode) { value[0] = 1; }\r\r\n}"
            start, end = _focal_line_range(source, body, "write")
        self.assertEqual((start, end), (3, 5))

    def test_rag_compares_historical_focal_not_test_text(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            production = root / "src" / "main" / "p" / "Other.java"
            tests = root / "src" / "test" / "p" / "OtherTest.java"
            production.parent.mkdir(parents=True); tests.parent.mkdir(parents=True)
            production.write_text("package p; public class Other {\n public int classify(int x) { if (x >= 1) { return 1; } return 0; }\n}\n", encoding="utf-8")
            tests.write_text("package p; public class OtherTest { @org.junit.Test public void testClassify() { new Other().classify(1); } }", encoding="utf-8")
            rag = build_rag(root, self.context, {"rag_jaccard_threshold": 0.8, "rag_max_test_examples": 3, "rag_max_mock_examples": 5})
        self.assertEqual(len(rag["test_examples"]), 1)
        self.assertIn("historical_focal_path", rag["test_examples"][0])

    def test_coverage_summary_excludes_invalid_target(self):
        with tempfile.TemporaryDirectory() as directory:
            batch = Path(directory); valid = batch / "targets" / "001"; invalid = batch / "targets" / "002"
            report = valid / "coverage" / "final_focal_method" / "line_coverage.json"
            report.parent.mkdir(parents=True); invalid.mkdir(parents=True)
            (valid / "state.json").write_text('{"state":"COMPLETED"}', encoding="utf-8")
            (invalid / "state.json").write_text('{"state":"NO_EXECUTABLE_CANDIDATE"}', encoding="utf-8")
            report.write_text('{"scope_start_line":1,"scope_end_line":3,"covered_line_count":2,"missed_line_count":1,"line_covered_ratio":0.6666667,"covered_branch_count":1,"missed_branch_count":1,"branch_covered_ratio":0.5}', encoding="utf-8")
            value = summarize_coverage(batch)
        self.assertEqual(value["coverage_available_target_count"], 1)
        self.assertEqual(value["missing_or_invalid_target_count"], 1)
        self.assertAlmostEqual(value["branch_covered_ratio"], 0.5)

    def test_failed_provider_retry_count_is_recovered(self):
        self.assertEqual(
            _failed_attempt_count("LLM API failed after 2 attempt(s): DNS"), 2
        )

    def test_provider_failure_has_precise_non_scoring_state(self):
        error = RuntimeError("LLM API failed after 5 attempt(s): getaddrinfo failed")
        self.assertEqual(_failure_state(error, "GENERATING_CANDIDATES"), "MODEL_PROVIDER_FAILED")
        self.assertEqual(_failure_state(RuntimeError("parser bug"), "GENERATING_CANDIDATES"), "PIPELINE_FAILED")

    def test_provider_retry_uses_bounded_exponential_backoff(self):
        config = {"retry_initial_delay_millis": 1000, "retry_max_delay_millis": 10000}
        self.assertEqual([_retry_delay_seconds(config, value) for value in range(1, 6)], [1.0, 2.0, 4.0, 8.0, 10.0])

    def test_provider_retry_is_limited_to_recoverable_failures(self):
        self.assertTrue(_is_retryable_provider_error(OSError("getaddrinfo failed")))
        self.assertTrue(_is_retryable_provider_error(ValueError("malformed JSON")))
        self.assertTrue(_is_retryable_provider_error(RuntimeError("LLM HTTP error 429: busy")))
        self.assertTrue(_is_retryable_provider_error(RuntimeError("LLM HTTP error 503: unavailable")))
        self.assertFalse(_is_retryable_provider_error(RuntimeError("LLM HTTP error 401: bad key")))
        self.assertFalse(_is_retryable_provider_error(RuntimeError("Unsupported LLM API URL")))

if __name__ == "__main__": unittest.main()

