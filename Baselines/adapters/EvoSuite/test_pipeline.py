from __future__ import print_function

import tempfile
import unittest
from collections import OrderedDict
from pathlib import Path

import pipeline as subject
from source_cleaner import clean_source
from manifest_paths import FIRST_TEN_TARGET_MANIFEST
from summarize_mutation_batch import _effective_state
from target_context import load_method_groups


class EvoSuitePipelineTest(unittest.TestCase):
    def test_first_five_methods_share_three_class_generations(self):
        groups = load_method_groups(FIRST_TEN_TARGET_MANIFEST, limit=5)
        classes = OrderedDict()
        for records in groups.values():
            classes.setdefault((records[0]["project"], records[0]["class_name"]), True)
        self.assertEqual(5, len(groups))
        self.assertEqual(3, len(classes))
        self.assertGreater(sum(len(value) for value in groups.values()), 5)

    def test_expected_evosuite_suite_identity(self):
        target = {"class_name": "example.Target"}
        sources = ["package example;\npublic class Target_ESTest {}"]
        self.assertEqual("example.Target_ESTest", subject._suite_identity(target, sources))

    def test_native_unsupported_package_is_scoreable_invalid_target(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "generator.log"
            log.write_text(
                "Cannot consider example.JUnitTest because it belongs to one of "
                "the packages EvoSuite cannot currently handle",
                encoding="utf-8",
            )
            state = subject._failure_state({"log_file": str(log)})
        self.assertEqual("GENERATOR_UNSUPPORTED_TARGET", state)

    def test_generation_timeout_is_scoreable_invalid_target(self):
        self.assertEqual(
            "GENERATOR_TIMEOUT",
            subject._failure_state({"timeout": True, "log_file": "missing.log"}),
        )

    def test_legacy_evosuite_timeout_is_recovered_from_generation_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            batch = Path(directory)
            target = batch / "targets" / "003"
            generation = batch / "class_generations" / "002_C" / "generation.json"
            target.mkdir(parents=True)
            generation.parent.mkdir(parents=True)
            (target / "metadata.json").write_text(
                '{"baseline":"EvoSuite-1.0.6","class_name":"p.C"}\n',
                encoding="utf-8",
            )
            generation.write_text(
                '{"class_name":"p.C","timeout":true}\n', encoding="utf-8"
            )
            recovered = _effective_state(
                {"state": "DATA_OR_TOOL_MISMATCH"}, target
            )
        self.assertEqual("GENERATOR_TIMEOUT", recovered)

    def test_pinned_configuration(self):
        definition = subject._definition(subject._read_properties())
        self.assertEqual("EvoSuite-1.0.6", definition["baseline"])
        self.assertEqual(60, definition["budget"])
        self.assertEqual(4, definition["test_timeout_seconds"])
        self.assertEqual(20, definition["max_size"])
        self.assertTrue(definition["jar"].is_file())

    def test_cleaner_preserves_native_evosuite_junit4_source(self):
        source = (
            "import org.junit.Test;\n"
            "import org.evosuite.runtime.EvoRunner;\n"
            "public class Example_ESTest extends Example_ESTest_scaffolding {}\n"
        )
        cleaned, audit = clean_source(source)
        self.assertEqual(source, cleaned)
        self.assertFalse(audit["changed"])
        self.assertTrue(audit["evosuite_runtime_preserved"])
        self.assertTrue(audit["evosuite_scaffolding_preserved"])

    def test_cleaner_converts_common_junit5_source_to_junit4(self):
        source = (
            "import org.junit.jupiter.api.Test;\n"
            "import org.junit.jupiter.api.BeforeEach;\n"
            "import static org.junit.jupiter.api.Assertions.*;\n"
            "public class ExampleTest {\n"
            "  @BeforeEach public void setUp() {}\n"
            "  @Test public void testIt() {\n"
            "    assertEquals(1, 1, \"message\");\n"
            "    assertTrue(true, \"condition\");\n"
            "  }\n"
            "}\n"
        )
        cleaned, audit = clean_source(source)
        self.assertIn("import org.junit.Test;", cleaned)
        self.assertIn("import org.junit.Before;", cleaned)
        self.assertIn("import static org.junit.Assert.*;", cleaned)
        self.assertIn("@Before public void setUp()", cleaned)
        self.assertIn('assertEquals(\"message\", 1, 1);', cleaned)
        self.assertIn('assertTrue(\"condition\", true);', cleaned)
        self.assertNotIn("org.junit.jupiter", cleaned)
        self.assertTrue(audit["junit5_detected"])
        self.assertFalse(audit["semantic_statement_deletion"])

    def test_cleaner_sets_each_evosuite_test_timeout_to_four_seconds(self):
        source = (
            "import org.junit.Test;\n"
            "public class Example_ESTest {\n"
            "  @Test(timeout = 10000) public void test0() {}\n"
            "  @Test(timeout = 10000) public void test1() {}\n"
            "}\n"
        )
        cleaned, audit = clean_source(source)
        self.assertEqual(2, cleaned.count("@Test(timeout = 4000)"))
        self.assertNotIn("timeout = 10000", cleaned)
        self.assertEqual(2, audit["test_timeout_annotation_change_count"])
        self.assertEqual(4000, audit["configured_test_timeout_millis"])


if __name__ == "__main__":
    unittest.main()
