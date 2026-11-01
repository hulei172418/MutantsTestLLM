from __future__ import print_function

import json
import sys
import tempfile
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from context import build_hits_context
from protocol import (
    MAX_REPAIR_ROUNDS,
    build_slice_test_prompt,
    merge_slice_suites,
    normalize_test_source,
    parse_slices,
)


class HITSProtocolTest(unittest.TestCase):
    def test_paper_repair_limit_is_ten(self):
        self.assertEqual(10, MAX_REPAIR_ROUNDS)

    def test_parse_slice_json_and_decode_source(self):
        value = parse_slices(
            "```json\n"
            + json.dumps(
                {
                    "summarization": "x",
                    "invoked_outside_vars": [],
                    "invoked_outside_methods": [],
                    "steps": [{"desp": "branch", "code": "if (a &lt; b) {}"}],
                }
            )
            + "\n```"
        )
        self.assertEqual("if (a < b) {}", value["steps"][0]["code"])

    def test_normalize_jupiter_to_junit4(self):
        source = normalize_test_source(
            """```java
package old;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class OldTest {
 @Test public void works() { assertEquals(1, 1); }
}
```""",
            "p",
            "C_HITS_Slice001Test",
        )
        self.assertIn("package p;", source)
        self.assertIn("org.junit.Test", source)
        self.assertIn("org.junit.Assert", source)
        self.assertIn("class C_HITS_Slice001Test", source)
        self.assertNotIn("jupiter", source)

    def test_merge_slice_classes_into_one_junit4_suite(self):
        first = """package p;
import org.junit.Test;
public class A { @Test public void a() {} }
"""
        second = """package p;
import org.junit.Test;
public class B { @Test public void b() {} }
"""
        merged = merge_slice_suites([first, second], "p", "C_HITSTest")
        self.assertIn("@RunWith(Suite.class)", merged)
        self.assertIn("C_HITSTest.Slice001.class", merged)
        self.assertIn("C_HITSTest.Slice002.class", merged)
        self.assertIn("public static class Slice001", merged)

    def test_prompt_contains_slice_but_no_mutant_fields(self):
        context = {
            "focal_context": "class C { int f(int x) { return x; } }",
            "dependent_classes": [],
            "dependent_methods": [],
            "package_name": "p",
        }
        prompt = build_slice_test_prompt(
            context, {"desp": "return", "code": "return x;"}, "CTest", 1
        )
        self.assertIn("return x;", prompt)
        self.assertNotIn("mutation_statement", prompt)
        self.assertNotIn("equivalent", prompt)

    def test_context_resolves_invoked_same_class_method(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "C.java"
            source.write_text(
                "package p;\nclass C {\n int f(int x){ return helper(x); }\n"
                "/** helper docs */\n int helper(int x){ return x + 1; }\n}",
                encoding="utf-8",
            )
            target = {
                "source_path": str(source),
                "class_name": "p.C",
                "simple_class_name": "C",
                "method_name": "f",
                "method_signature": "int_f(int)",
                "method_body": "int f(int x){ return helper(x); }",
                "package_name": "p",
                "imports": [],
                "class_declaration": "class C {",
                "fields": [],
                "constructors": [],
                "all_method_signatures": ["int f(int x)", "int helper(int x)"],
            }
            context = build_hits_context(target, root)
            self.assertEqual(["helper"], context["invoked_method_names"])
            self.assertIn("helper", context["dependent_methods"][0]["source"])
            self.assertIn("class C {", context["focal_context"])
            self.assertIn("int helper(int x);", context["focal_context"])


if __name__ == "__main__":
    unittest.main()
