import sys
import unittest
from pathlib import Path


ADAPTER_DIR = Path(__file__).resolve().parent
if str(ADAPTER_DIR) not in sys.path:
    sys.path.insert(0, str(ADAPTER_DIR))

from llm import ChatTesterLlmAdapter
from generate_initial_test import render_test_source


class ChatTesterLlmAdapterTest(unittest.TestCase):
    def test_extracts_junit4_method_and_imports(self):
        response = """Here is the test:
```java
import org.junit.Test;
import static org.junit.Assert.assertEquals;

@Test
public void addsTwoNumbers() {
    assertEquals(3, Calculator.add(1, 2));
}
```
"""
        method, imports = ChatTesterLlmAdapter.return_code(response)
        self.assertIn("@Test", method)
        self.assertIn("public void addsTwoNumbers()", method)
        self.assertTrue(method.endswith("}"))
        self.assertIn("import org.junit.Test;", imports)
        self.assertIn("import static org.junit.Assert.assertEquals;", imports)

    def test_adds_missing_test_annotation(self):
        response = "public void generatedTest() {\n    int value = 1;\n}\n"
        method, imports = ChatTesterLlmAdapter.return_code(response)
        self.assertTrue(method.startswith("@Test\npublic void"))
        self.assertEqual("", imports)

    def test_renders_test_in_same_package(self):
        context = {
            "package_name": "org.example",
            "simple_class_name": "Sample",
            "imports": ["import java.util.List;"],
        }
        class_name, source = render_test_source(
            context,
            "@Test\npublic void generated() {\n    assertTrue(true);\n}",
            "import java.time.Instant;",
        )
        self.assertEqual("Sample_ChatTesterTest", class_name)
        self.assertIn("package org.example;", source)
        self.assertIn("import org.junit.Test;", source)
        self.assertIn("public class Sample_ChatTesterTest", source)


if __name__ == "__main__":
    unittest.main()
