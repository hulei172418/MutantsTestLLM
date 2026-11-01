from __future__ import print_function

import unittest
from pathlib import Path

from build_target_manifest import DEFAULT_INPUT, DEFAULT_OUTPUT, _target_class
from manifest_paths import DEFAULT_MUTANT_WORKBOOK, DEFAULT_TARGET_MANIFEST


class TargetClassNormalizationTest(unittest.TestCase):
    def test_builder_uses_the_common_workbook_and_manifest_paths(self):
        self.assertEqual(DEFAULT_MUTANT_WORKBOOK, DEFAULT_INPUT)
        self.assertEqual(DEFAULT_TARGET_MANIFEST, DEFAULT_OUTPUT)

    def test_strips_source_root_java_prefix(self):
        self.assertEqual(
            "org.apache.commons.jxpath.Example",
            _target_class("java.org.apache.commons.jxpath.Example", "Example"),
        )

    def test_strips_maven_main_java_prefix(self):
        self.assertEqual(
            "org.example.Example",
            _target_class("main.java.org.example.Example", "Example"),
        )

    def test_keeps_real_java_identifier_prefixes(self):
        self.assertEqual(
            "javax.example.Example",
            _target_class("javax.example.Example", "Example"),
        )

    def test_every_baseline_uses_the_common_default_manifest(self):
        baselines_dir = Path(__file__).resolve().parents[2]
        entrypoints = [
            "ChatTester/prepare_run.py",
            "MutGen/prepare_run.py",
            "MuTAP/prepare_run.py",
            "HITS/prepare_run.py",
            "A3Test/prepare_run.py",
            "ASTER/prepare_run.py",
            "IntUT/prepare_run.py",
            "EvoSuite/pipeline.py",
            "Randoop/pipeline.py",
        ]
        for relative in entrypoints:
            source = (baselines_dir / "adapters" / relative).read_text(encoding="utf-8")
            self.assertIn("from manifest_paths import DEFAULT_TARGET_MANIFEST", source)
            self.assertIn("DEFAULT_MANIFEST = DEFAULT_TARGET_MANIFEST", source)

    def test_adapter_python_names_do_not_repeat_the_baseline_directory(self):
        baselines_dir = Path(__file__).resolve().parents[2]
        names = {
            "A3Test": "a3test",
            "ASTER": "aster",
            "ChatTester": "chattester",
            "EvoSuite": "evosuite",
            "HITS": "hits",
            "IntUT": "intut",
            "MuTAP": "mutap",
            "MutGen": "mutgen",
            "Randoop": "randoop",
        }
        for directory, prefix in names.items():
            for script in (baselines_dir / "adapters" / directory).glob("*.py"):
                stem = script.stem.lower()
                self.assertFalse(stem.startswith(prefix + "_"), script)
                self.assertFalse(stem.startswith("test_" + prefix + "_"), script)


if __name__ == "__main__":
    unittest.main()
