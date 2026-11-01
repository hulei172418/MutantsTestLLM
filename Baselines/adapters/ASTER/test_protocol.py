from __future__ import print_function

import sys
import tempfile
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from context import _source_roots, build_aster_context
from protocol import (
    BASELINE, build_coverage_prompt, build_initial_prompt, build_repair_prompt,
    normalize_test_source, test_count as count_tests,
)
from run_pipeline import _focal_coverage


class AsterProtocolTest(unittest.TestCase):
    def _target(self, root):
        source = root / "src" / "p" / "Subject.java"
        source.parent.mkdir(parents=True)
        source.write_text(
            "package p;\n"
            "public class Subject {\n"
            "  private int value;\n"
            "  public Subject() {}\n"
            "  public void setValue(int value) { this.value = value; }\n"
            "  public int getValue() { return value; }\n"
            "  public int add(int x) { return helper(x) + value; }\n"
            "  private int helper(int x) { return x + 1; }\n"
            "}\n",
            encoding="utf-8",
        )
        context = {
            "source_path": str(source), "class_name": "p.Subject",
            "simple_class_name": "Subject", "package_name": "p",
            "method_name": "add", "method_signature": "int_add(int)",
            "method_declaration": "public int add(int x)",
            "method_body": "public int add(int x) { return helper(x) + value; }",
            "imports": [], "fields": ["private int value;"],
            "constructors": ["public Subject() {}"],
            "all_method_signatures": [
                "public void setValue(int value)", "public int getValue()",
                "public int add(int x)", "private int helper(int x)",
            ],
        }
        settings = {
            "max_transitive_constructor_depth": 2, "max_context_types": 20,
            "mockable_api_prefixes": "",
        }
        return build_aster_context(context, root, settings)

    def test_context_contains_paper_static_information(self):
        with tempfile.TemporaryDirectory() as directory:
            context = self._target(Path(directory))
        self.assertIn("public Subject()", context["relevant_constructors"])
        self.assertIn("public void setValue(int value)", context["setter_signatures"])
        self.assertIn("public int getValue()", context["getter_signatures"])
        self.assertIn("add -> helper", context["private_call_chains"])
        self.assertTrue(context["focal_method_lines"])

    def test_context_search_is_bounded_to_java_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            module = root / "module"
            source = module / "src" / "main" / "java" / "p" / "Subject.java"
            dependency = module / "src" / "main" / "java" / "q" / "Dependency.java"
            dependency.parent.mkdir(parents=True)
            source.parent.mkdir(parents=True)
            source.write_text(
                "package p;\nimport q.Dependency;\n"
                "public class Subject { public void use(Dependency value) {} }\n",
                encoding="utf-8",
            )
            dependency.write_text(
                "package q;\npublic class Dependency { public Dependency() {} }\n",
                encoding="utf-8",
            )
            historical = root / "llm" / "execution-report" / "old"
            historical.mkdir(parents=True)
            roots = _source_roots(root, source)
            self.assertIn((module / "src" / "main" / "java").resolve(), roots)
            self.assertFalse(any(str(historical) in str(path) for path in roots))
            context = build_aster_context(
                {
                    "source_path": str(source), "class_name": "p.Subject",
                    "simple_class_name": "Subject", "package_name": "p",
                    "method_name": "use", "method_signature": "void_use(q.Dependency)",
                    "method_declaration": "public void use(Dependency value)",
                    "method_body": "public void use(Dependency value) {}",
                    "imports": ["import q.Dependency;"], "fields": [],
                    "constructors": [], "all_method_signatures": [],
                },
                root,
                {"max_transitive_constructor_depth": 2, "max_context_types": 20,
                 "mockable_api_prefixes": ""},
            )
        self.assertEqual("Dependency", context["application_types"][0]["type"])

    def test_multiline_throws_declaration_maps_focal_lines(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "src" / "p" / "Subject.java"
            source.parent.mkdir(parents=True)
            source.write_text(
                "package p;\npublic class Subject {\n"
                "  public static String[] parse(String value)\n"
                "      throws IllegalArgumentException {\n"
                "    return new String[] { value };\n  }\n}\n",
                encoding="utf-8",
            )
            target = {
                "source_path": str(source), "class_name": "p.Subject",
                "simple_class_name": "Subject", "package_name": "p",
                "method_name": "parse", "method_signature": "java.lang.String[]_parse(java.lang.String)",
                "method_declaration": "public static String[] parse(String value) throws IllegalArgumentException",
                "method_body": "public static String[] parse(String value) throws IllegalArgumentException {\n return new String[] { value };\n}",
                "imports": [], "fields": [], "constructors": [],
                "all_method_signatures": [],
            }
            context = build_aster_context(target, root, {
                "max_transitive_constructor_depth": 2, "max_context_types": 20,
                "mockable_api_prefixes": "",
            })
        self.assertEqual(context["focal_method_lines"], [3, 4, 5, 6])

    def test_initial_prompt_has_no_mutation_information(self):
        with tempfile.TemporaryDirectory() as directory:
            context = self._target(Path(directory))
        prompt = build_initial_prompt(context, "Subject_ASTERTest")
        self.assertIn("Relevant constructors", prompt)
        self.assertIn("private call chains", prompt)
        self.assertNotIn("mutation_statement", prompt)
        self.assertNotIn("mutant_graph", prompt)

    def test_empty_mock_configuration_does_not_invent_mockito(self):
        with tempfile.TemporaryDirectory() as directory:
            context = self._target(Path(directory))
        prompt = build_initial_prompt(context, "Subject_ASTERTest")
        self.assertNotIn("Mockito.mock", prompt)

    def test_normalization_converts_junit5_and_class_name(self):
        source = """package wrong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class WrongName { @Test public void testAdd() { assertEquals(2, 2); } }
"""
        normalized = normalize_test_source(source, "p", "Subject_ASTERTest")
        self.assertIn("package p;", normalized)
        self.assertIn("org.junit.Test", normalized)
        self.assertIn("class Subject_ASTERTest", normalized)
        self.assertEqual(count_tests(normalized), 1)

    def test_repair_prompt_contains_real_feedback(self):
        context = {"method_signature": "int_add(int)", "focal_method": "int add(int x) { return x; }", "package_name": "p"}
        prompt = build_repair_prompt(context, "class T {}", "T.java:4", "cannot find symbol", "T")
        self.assertIn("T.java:4", prompt)
        self.assertIn("cannot find symbol", prompt)
        self.assertIn("class T {}", prompt)

    def test_coverage_prompt_contains_only_requested_lines(self):
        context = {
            "method_signature": "int_add(int)", "package_name": "p",
            "focal_method_numbered_source": "10: if (x > 0) {\n11: return x;\n12: }",
        }
        prompt = build_coverage_prompt(context, "public class T {}", [11], "T")
        self.assertIn("11: return x;", prompt)
        self.assertNotIn("10: if", prompt)

    def test_focal_coverage_intersects_class_report(self):
        context = {"focal_method_lines": [10, 11, 12]}
        report = {"covered_lines": [1, 10, 12], "missed_lines": [2, 11, 20]}
        result = _focal_coverage(context, report)
        self.assertEqual(result["covered_focal_lines"], [10, 12])
        self.assertEqual(result["missed_focal_lines"], [11])
        self.assertAlmostEqual(result["focal_line_covered_ratio"], 2.0 / 3.0)

    def test_baseline_name_marks_reimplementation(self):
        self.assertEqual(BASELINE, "ASTER-Java-DS-Reimplementation")


if __name__ == "__main__":
    unittest.main()
