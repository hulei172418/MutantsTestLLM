from __future__ import print_function

import sys
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from context import build_a3test_context
from protocol import build_generation_prompt, verify_and_wrap


class A3TestProtocolTest(unittest.TestCase):
    def context(self):
        return {
            "class_name": "p.C", "simple_class_name": "C",
            "method_name": "value", "method_signature": "int_value()",
            "package_name": "p", "imports": [],
            "translation_source": "[FOCAL_METHOD]\npublic int value(){return 1;}",
        }

    def test_context_uses_fm_fc_c_m_f_and_public_fields_only(self):
        value = build_a3test_context({
            "class_name": "p.C", "simple_class_name": "C",
            "method_name": "value", "method_signature": "int_value()",
            "package_name": "p", "imports": [],
            "method_body": "public int value(){return 1;}",
            "constructors": ["public C() {}"],
            "public_method_signatures": ["public int value()", "public void set(int x)"],
            "fields": ["public int x;", "private int hidden;"],
        })
        self.assertEqual("fm+fc+c+m+f", value["context_encoding"])
        self.assertEqual(["public int x;"], value["public_fields"])
        self.assertNotIn("hidden", value["translation_source"])
        self.assertIn("public void set(int x)", value["translation_source"])

    def test_prompt_is_one_iteration_and_hides_mutants(self):
        prompt = build_generation_prompt(self.context(), "C_A3TestTest")
        self.assertIn("exactly one", prompt)
        self.assertNotIn("mutation_statement", prompt)
        self.assertNotIn("equivalent", prompt)

    def test_verifier_adds_annotation_public_void_and_test_prefix(self):
        source, audit = verify_and_wrap(
            "```java\nint value() { assertEquals(1, new C().value()); }\n```",
            self.context(), "C_A3TestTest",
        )
        self.assertIn("@Test", source)
        self.assertIn("public void testvalue()", source)
        self.assertEqual(1, audit["assertion_count"])
        self.assertTrue(audit["annotation_added"])
        self.assertTrue(audit["test_prefix_added"])

    def test_verifier_preserves_one_complete_class_test(self):
        source, audit = verify_and_wrap(
            """package x;
import org.junit.Test;
public class Old {
 @Test public void testValue() { assertTrue(new C().value() == 1); }
}
""",
            self.context(), "C_A3TestTest",
        )
        self.assertIn("package p;", source)
        self.assertIn("public class C_A3TestTest", source)
        self.assertEqual("testValue", audit["verified_method_name"])

    def test_verifier_accepts_throws_clause(self):
        source, audit = verify_and_wrap(
            "public class X { @Test public void testValue() throws Exception { assertEquals(1, new C().value()); } }",
            self.context(), "C_A3TestTest",
        )
        self.assertIn("public void testValue() throws Exception", source)
        self.assertEqual("throws Exception", audit["throws_clause_preserved"])
        self.assertEqual(1, audit["assertion_count"])

    def test_multiple_tests_are_rejected_for_single_iteration(self):
        with self.assertRaises(ValueError):
            verify_and_wrap(
                "public class X { @Test public void testA(){} @Test public void testB(){} }",
                self.context(), "C_A3TestTest",
            )

    def test_author_style_unbalanced_method_closes_after_last_statement(self):
        source, audit = verify_and_wrap(
            "@Test public void value() { assertEquals(1, new C().value());",
            self.context(), "C_A3TestTest",
        )
        self.assertTrue(audit["parenthesis_repaired"])
        self.assertTrue(source.rstrip().endswith("}"))


if __name__ == "__main__":
    unittest.main()
