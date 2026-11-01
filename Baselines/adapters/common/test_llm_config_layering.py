"""Structural invariants for common plus baseline-local LLM configuration."""

import unittest
from pathlib import Path

from llm_gateway import (
    ADAPTERS_DIR,
    DEFAULT_CONFIG_PATH,
    _discover_local_config,
    _load_properties,
)


EXPECTED = {
    "ChatTester": {
        "chattester_intention": 0.0,
        "chattester_initial": 0.0,
        "chattester_compile_repair": 0.0,
        "chattester_runtime_repair": 0.0,
    },
    "MutGen": {
        "mutgen_summary": 0.0,
        "mutgen_generation": 0.0,
        "mutgen_compile_repair": 0.0,
        "mutgen_assert_repair": 0.0,
    },
    "MuTAP": {
        "mutap_initial": 0.8,
        "mutap_syntax_repair": 0.8,
        "mutap_survivor_augmentation": 0.8,
    },
    "HITS": {
        "hits_slicing": 0.0,
        "hits_slice_test": 0.0,
        "hits_test_repair": 0.0,
    },
    "A3Test": {"a3test_generation": 0.0},
    "ASTER": {
        "aster_initial": 0.0,
        "aster_repair": 0.0,
        "aster_coverage": 0.0,
    },
    "IntUT": {"intut_generation": 0.2},
}


class LlmConfigurationLayeringTests(unittest.TestCase):
    def test_common_file_contains_no_baseline_stage_keys(self):
        common = _load_properties(DEFAULT_CONFIG_PATH)
        self.assertFalse([key for key in common if key.startswith("llm.stages.")])

    def test_each_stage_has_one_local_owner_and_preserves_frozen_values(self):
        found = set()
        for adapter, stages in EXPECTED.items():
            path = (ADAPTERS_DIR / adapter / "llm.properties").resolve()
            properties = _load_properties(path)
            self.assertTrue(properties)
            self.assertTrue(all(key.startswith("llm.stages.") for key in properties))
            for stage, temperature in stages.items():
                found.add(stage)
                self.assertEqual(
                    "16000", properties["llm.stages.{0}.max.tokens".format(stage)]
                )
                self.assertEqual(
                    temperature,
                    float(properties["llm.stages.{0}.temperature".format(stage)]),
                )
                self.assertEqual(path, _discover_local_config(stage))
        self.assertEqual(19, len(found))

    def test_non_llm_baselines_do_not_need_empty_local_files(self):
        self.assertFalse((ADAPTERS_DIR / "EvoSuite" / "llm.properties").exists())
        self.assertFalse((ADAPTERS_DIR / "Randoop" / "llm.properties").exists())


if __name__ == "__main__":
    unittest.main()
