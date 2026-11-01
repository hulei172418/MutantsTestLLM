from __future__ import print_function

import unittest

import pipeline as subject


class RandoopPipelineTest(unittest.TestCase):
    def test_multi_part_output_gets_local_junit_suite(self):
        target = {"class_name": "example.Target"}
        sources = [
            "package example;\npublic class RegressionTest0 {}",
            "package example;\npublic class RegressionTest1 {}",
        ]
        suite = subject._suite_identity(target, sources)
        self.assertEqual("example.BaselineRandoopSuite", suite)
        self.assertIn("RegressionTest0.class", sources[-1])
        self.assertIn("RegressionTest1.class", sources[-1])

    def test_existing_randoop_suite_is_reused(self):
        target = {"class_name": "example.Target"}
        sources = ["package example;\npublic class RegressionTest {}"]
        self.assertEqual("example.RegressionTest", subject._suite_identity(target, sources))

    def test_pinned_configuration_and_all_jar(self):
        definition = subject._definition(subject._read_properties())
        subject._validate(definition)
        self.assertEqual("Randoop-4.3.4", definition["baseline"])
        self.assertEqual(60, definition["budget"])
        self.assertEqual("randoop-all-4.3.4.jar", definition["jar"].name)


if __name__ == "__main__":
    unittest.main()
