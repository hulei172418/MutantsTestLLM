import sys
import unittest
from pathlib import Path


ADAPTER_DIR = Path(__file__).resolve().parent
if str(ADAPTER_DIR) not in sys.path:
    sys.path.insert(0, str(ADAPTER_DIR))

from protocol import (
    MAX_SYNTAX_REPAIR_ROUNDS,
    build_augmentation_prompt,
    build_initial_prompt,
    build_syntax_repair_prompt,
)
from run_pipeline import (
    ModelOutputInvalidError,
    ModelOutputTruncatedError,
    _extract_java,
    _require_complete,
    extract_test_methods,
    normalize_initial_source,
    render_methods,
)


class MuTAPProtocolTest(unittest.TestCase):
    def test_initial_prompt_is_zero_shot_and_requests_junit4(self):
        prompt = build_initial_prompt("class C { int m(){return 1;} }", "C_MuTAPTest")
        self.assertIn("Generate test cases for", prompt)
        self.assertIn("JUnit 4", prompt)
        self.assertIn("C_MuTAPTest", prompt)
        self.assertNotIn("mutant", prompt.lower())

    def test_augmentation_exposes_exactly_supplied_survivor(self):
        prompt = build_augmentation_prompt("initial", "class T {}", "class C { return 2; }")
        self.assertIn("previous test class cannot detect", prompt)
        self.assertIn("return 2", prompt)
        self.assertIn("new JUnit 4", prompt)

    def test_syntax_repair_limit_follows_paper_priority(self):
        self.assertEqual(10, MAX_SYNTAX_REPAIR_ROUNDS)
        prompt = build_syntax_repair_prompt("public class T {", "reached end", "T")
        self.assertIn("entire", prompt)
        self.assertIn("reached end", prompt)

    def test_java_fence_and_class_name_are_normalized(self):
        source = normalize_initial_source(
            "```java\nimport org.junit.Test;\npublic class Wrong { @Test public void x(){} }\n```",
            "Right",
        )
        self.assertIn("public class Right", source)
        self.assertNotIn("class Wrong", source)

    def test_methods_with_nested_braces_are_extracted_and_merged(self):
        source = """import org.junit.Test;
public class T {
 @Test
 public void a() { if (true) { int x = 1; } }
 @Test
 public void b() { String s = "}"; }
}
"""
        methods, spans = extract_test_methods(source)
        self.assertEqual(2, len(methods))
        self.assertEqual(2, len(spans))
        merged = render_methods(source, [methods[1]])
        self.assertNotIn("void a()", merged)
        self.assertIn("void b()", merged)
        self.assertEqual(1, merged.count("public class"))

    def test_augmentation_method_only_response_is_extracted(self):
        response = _extract_java("```java\n@Test\npublic void detectsFault(){ assertTrue(true); }\n```")
        methods, _ = extract_test_methods(response)
        self.assertEqual(1, len(methods))

    def test_same_line_test_annotation_is_supported(self):
        methods, _ = extract_test_methods(
            "public class T {\n@Test public void x(){ assertTrue(true); }\n}"
        )
        self.assertEqual(1, len(methods))

    def test_truncated_output_is_rejected(self):
        with self.assertRaises(ModelOutputTruncatedError):
            _require_complete({"finish_reason": "length", "max_tokens": 16000}, "mutap_initial")

    def test_unbalanced_generated_method_is_model_output_invalid(self):
        with self.assertRaises(ModelOutputInvalidError):
            extract_test_methods("@Test public void broken() {")


if __name__ == "__main__":
    unittest.main()
