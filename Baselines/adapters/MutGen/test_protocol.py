import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ADAPTER_DIR = Path(__file__).resolve().parent
if str(ADAPTER_DIR) not in sys.path:
    sys.path.insert(0, str(ADAPTER_DIR))

from protocol import (
    MAX_GENERATION_ROUNDS,
    build_generation_prompt,
    build_mutation_feedback,
    build_repair_prompt,
    build_summary_prompt,
)
from run_pipeline import (
    ModelOutputTruncatedError,
    _status_map,
    author_duplicate_method_fix,
    parse_new_tests,
    render_suite,
    require_complete_structured_response,
)
from mutation_line_mapping import map_mutant_line
import run_batch as mutgen_batch


class MutGenProtocolTest(unittest.TestCase):
    def test_summary_prompt_preserves_original_context(self):
        prompt = build_summary_prompt("class C { /* contract */ int m(){return 1;} }")
        self.assertIn("1-3 sentences", prompt)
        self.assertIn("contract", prompt)

    def test_feedback_deduplicates_and_never_leaks_audit_fields(self):
        row = {
            "project": "p",
            "class": "C",
            "method": "m()",
            "operator": "AOR",
            "line": 7,
            "mutation_statement": "return a - b;",
            "file_path": "p/mutants/A.class",
            "equivalent": True,
            "original_graph_path": "secret-original-graph",
            "mutant_graph_path": "secret-mutant-graph",
        }
        feedback = build_mutation_feedback([row, dict(row)])
        self.assertEqual(1, len(feedback))
        serialized = json.dumps(feedback)
        self.assertNotIn("equivalent", serialized)
        self.assertNotIn("graph", serialized)
        self.assertNotIn("file_path", serialized)
        self.assertEqual("NO_COVERAGE", feedback[0]["status"])

    def test_killed_mutants_are_not_reprompted(self):
        row = {"file_path": "x.class", "mutation_statement": "return false;"}
        feedback = build_mutation_feedback([row], {"x.class": "KILLED"})
        self.assertEqual([], feedback)

    def test_generation_and_repair_contract(self):
        prompt = build_generation_prompt("summary", "class C {}", [])
        self.assertIn("JUnit 4", prompt)
        self.assertIn("new_tests:", prompt)
        repair = build_repair_prompt("@Test public void x() {}", "expected 1", "ASSERTION")
        self.assertIn("ASSERTION", repair)
        self.assertEqual(4, MAX_GENERATION_ROUNDS)

    def test_author_yaml_response_is_parsed(self):
        tests = parse_new_tests(
            """```yaml
new_tests:
  - test_behavior: boundary
    test_name: testBoundary
    test_code: |
      @Test
      public void testBoundary() { assertTrue(true); }
    new_imports_: |
      import java.util.List;
```"""
        )
        self.assertEqual(1, len(tests))
        self.assertIn("testBoundary", tests[0]["test_code"])
        self.assertIn("java.util.List", tests[0]["new_imports"])

    def test_full_yaml_fence_is_preferred_over_empty_fence_artifacts(self):
        content = """```yaml
new_tests:
  - test_behavior: response-shaped regression
    test_name: testGenerated
    test_code: |
      @Test
      public void testGenerated() { assertEquals(1, 1); }
    new_imports_: ""
```"""
        tests = parse_new_tests(content)
        self.assertEqual(1, len(tests))
        self.assertEqual("testGenerated", tests[0]["test_name"])

    def test_lost_literal_block_indent_is_repaired_without_changing_java(self):
        diagnostics = {}
        tests = parse_new_tests(
            """```yaml
new_tests:
  - test_behavior: malformed model indentation
    test_name: testGenerated
    test_code: |
      @Test
    public void testGenerated() {
          assertEquals(1, 1);
      }
    new_imports_: |
      import static org.junit.Assert.assertEquals;
```""",
            diagnostics,
        )
        self.assertEqual(1, len(tests))
        self.assertIn("public void testGenerated()", tests[0]["test_code"])
        self.assertIn("assertEquals(1, 1);", tests[0]["test_code"])
        self.assertTrue(diagnostics["literal_block_indentation_repaired"])

    def test_structured_output_truncation_is_explicit(self):
        with self.assertRaisesRegex(ModelOutputTruncatedError, "4096"):
            require_complete_structured_response(
                {
                    "finish_reason": "length",
                    "max_tokens": 4096,
                    "usage": {"completion_tokens": 4096},
                },
                "mutgen_generation",
            )

    def test_batch_preserves_original_failed_terminal_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifests = root / "manifests"
            targets = root / "targets"
            manifests.mkdir()
            targets.mkdir()
            records = [
                {
                    "project": "p",
                    "class_name": "p.C",
                    "method_signature": "m()",
                    "file_path": "m.class",
                }
            ]

            def fake_prepare(_manifest, run_dir):
                Path(run_dir).mkdir(parents=True)
                (Path(run_dir) / "state.json").write_text(
                    json.dumps({"state": "ORIGINAL_FAILED"}), encoding="utf-8"
                )
                return {"state": "PREPARED_API_NOT_CALLED"}

            with patch.object(mutgen_batch, "prepare", side_effect=fake_prepare), patch.object(
                mutgen_batch,
                "run_pipeline",
                return_value={"state": "ORIGINAL_FAILED", "rounds": []},
            ):
                item = mutgen_batch._run_target(
                    1, 1, records, manifests, targets, mutant_workers=1
                )

            self.assertEqual("FAILED", item["status"])
            self.assertEqual("ORIGINAL_FAILED", item["terminal_state"])
            state = json.loads((targets / "001" / "state.json").read_text(encoding="utf-8"))
            self.assertEqual("ORIGINAL_FAILED", state["state"])

    def test_cumulative_suite_uses_one_project_local_test_class(self):
        context = {
            "package_name": "p",
            "simple_class_name": "C",
            "imports": ["import java.util.List;"],
        }
        _, source = render_suite(
            context,
            [
                {"test_code": "@Test public void a() {}", "new_imports": ""},
                {"test_code": "@Test public void b() {}", "new_imports": ""},
            ],
        )
        self.assertEqual(1, source.count("public class"))
        self.assertIn("void a()", source)
        self.assertIn("void b()", source)

    def test_author_duplicate_name_fix_does_not_call_llm(self):
        candidate = {
            "test_code": "@Test public void testValue() { testValueHelper(); }",
            "new_imports": "",
        }
        fixed = author_duplicate_method_fix(
            candidate, "method testValue() is already defined in class C"
        )
        self.assertIn("dedul_testValue()", fixed["test_code"])
        self.assertIn("testValueHelper", fixed["test_code"])

    def test_surviving_mutant_status_uses_mapped_coverage_line(self):
        import tempfile
        from pathlib import Path

        with tempfile.TemporaryDirectory() as directory:
            results = Path(directory) / "results.jsonl"
            results.write_text(
                json.dumps({"file_path": "a.class", "status": "LIVE"}) + "\n",
                encoding="utf-8",
            )
            summary = {"results_jsonl": str(results)}
            mapping = {"a.class": {"mapped_source_line": 10}}
            self.assertEqual(
                "NO_COVERAGE", _status_map(summary, {11}, mapping)["a.class"]
            )
            self.assertEqual("LIVE", _status_map(summary, {10}, mapping)["a.class"])

    def test_mutant_pretty_print_line_maps_back_to_current_source(self):
        import tempfile
        from pathlib import Path

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "C.java"
            mutant = root / "C_mutant.java"
            source.write_text(
                "class C {\n  public void setThread(int thread) {\n"
                "    this.value = thread;\n  }\n}\n",
                encoding="utf-8",
            )
            mutant.write_text(
                "class C {\n public void setThread( int thread ) {\n"
                " this.value = ++thread;\n }\n}\n",
                encoding="utf-8",
            )
            mapped = map_mutant_line(
                {
                    "line": 3,
                    "mutation_statement": "thread => ++thread",
                    "mutated_source_path": str(mutant),
                    "file_path": "C.class",
                },
                source,
                "public void setThread(int thread) {\n this.value = thread;\n}",
            )
            self.assertEqual(3, mapped["mapped_source_line"])
            self.assertEqual("reverse_mutated_line_exact", mapped["strategy"])


if __name__ == "__main__":
    unittest.main()
