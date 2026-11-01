import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


ADAPTER_DIR = Path(__file__).resolve().parent
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from llm_gateway import (
    BASELINES_DIR,
    _apply_thinking_config,
    _discover_local_config,
    load_config,
    prepare_input,
    resolve_max_tokens,
    resolve_temperature,
)


class LlmPropertiesConfigTest(unittest.TestCase):
    def _load(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "llm.properties"
            path.write_text(text, encoding="utf-8")
            return load_config(path)

    def test_selected_provider_profile_is_used(self):
        config = self._load(
            "\n".join(
                (
                    "llm.provider=second",
                    "llm.temperature=0.2",
                    "llm.max.tokens=16000",
                    "llm.max.input.tokens=1234",
                    "llm.max.input.chars=2345",
                    "llm.threads=3",
                    "llm.skip.existing.compiled=false",
                    "llm.force.regenerate=true",
                    "llm.providers.first.api.url=https://first.invalid/v1/chat/completions",
                    "llm.providers.first.model=first-model",
                    "llm.providers.first.api.key=first-secret",
                    "llm.providers.second.api.url=https://second.invalid/v1/chat/completions",
                    "llm.providers.second.model=second-model",
                    "llm.providers.second.api.key=second-secret",
                    "llm.providers.second.temperature=0.35",
                    "llm.providers.second.max.tokens=9000",
                    "llm.providers.second.connect.timeout.millis=1111",
                    "llm.providers.second.read.timeout.millis=2222",
                    "llm.stages.chattester_intention.max.tokens=700",
                    "llm.stages.chattester_intention.temperature=0",
                )
            )
        )
        self.assertEqual("second", config["provider"])
        self.assertEqual("https://second.invalid/v1/chat/completions", config["api_url"])
        self.assertEqual("second-model", config["model"])
        self.assertEqual("second-secret", config["api_key"])
        self.assertEqual(0.35, config["temperature"])
        self.assertEqual(9000, config["max_tokens"])
        self.assertEqual(1234, config["max_input_tokens"])
        self.assertEqual(2345, config["max_input_chars"])
        self.assertEqual(1111, config["connect_timeout_millis"])
        self.assertEqual(2222, config["read_timeout_millis"])
        self.assertEqual(3, config["thread_count"])
        self.assertFalse(config["skip_existing_compiled"])
        self.assertTrue(config["force_regenerate"])
        self.assertEqual(700, resolve_max_tokens(config, "chattester_intention"))
        self.assertEqual(9000, resolve_max_tokens(config, "unconfigured_stage"))
        self.assertEqual(0.0, resolve_temperature(config, "chattester_intention"))
        self.assertEqual(0.35, resolve_temperature(config, "unconfigured_stage"))

    def test_explicit_environment_override_matches_reference_loader(self):
        original = os.environ.get("LLM_MODEL")
        os.environ["LLM_MODEL"] = "unexpected-environment-model"
        try:
            config = self._load(
                "\n".join(
                    (
                        "llm.provider=local",
                        "llm.providers.local.api.url=http://localhost/v1/chat/completions",
                        "llm.providers.local.model=properties-model",
                        "llm.providers.local.api.key=local-secret",
                    )
                )
            )
        finally:
            if original is None:
                os.environ.pop("LLM_MODEL", None)
            else:
                os.environ["LLM_MODEL"] = original
        self.assertEqual("unexpected-environment-model", config["model"])

    def test_baseline_local_stage_values_override_common_and_inherit_missing_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common = root / "common.properties"
            local = root / "local.properties"
            common.write_text(
                "\n".join(
                    (
                        "llm.provider=local",
                        "llm.temperature=0.35",
                        "llm.max.tokens=16000",
                        "llm.providers.local.api.url=http://localhost/v1/chat/completions",
                        "llm.providers.local.model=model",
                        "llm.providers.local.api.key=secret",
                    )
                ),
                encoding="utf-8",
            )
            local.write_text(
                "llm.stages.example_generation.max.tokens=700\n",
                encoding="utf-8",
            )
            config = load_config(
                common,
                stage="example_generation",
                local_config_path=local,
            )
        self.assertEqual(700, resolve_max_tokens(config, "example_generation"))
        self.assertEqual(0.35, resolve_temperature(config, "example_generation"))
        self.assertEqual(str(common.resolve()), config["global_config_path"])
        self.assertEqual(str(local.resolve()), config["local_config_path"])
        self.assertEqual(2, len(config["config_paths"]))

    def test_baseline_local_stage_values_override_common_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common = root / "common.properties"
            local = root / "local.properties"
            common.write_text(
                "\n".join(
                    (
                        "llm.provider=local",
                        "llm.providers.local.api.url=http://localhost/v1/chat/completions",
                        "llm.providers.local.model=model",
                        "llm.providers.local.api.key=secret",
                    )
                ),
                encoding="utf-8",
            )
            local.write_text(
                "\n".join(
                    (
                        "llm.stages.example_generation.max.tokens=700",
                        "llm.stages.example_generation.temperature=0.1",
                    )
                ),
                encoding="utf-8",
            )
            with mock.patch.dict(
                os.environ,
                {"LLM_MAX_TOKENS": "999", "LLM_TEMPERATURE": "0.9"},
            ):
                config = load_config(
                    common,
                    stage="example_generation",
                    local_config_path=local,
                )
        self.assertEqual(700, resolve_max_tokens(config, "example_generation"))
        self.assertEqual(0.1, resolve_temperature(config, "example_generation"))

    def test_baseline_local_file_rejects_common_or_secret_settings(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common = root / "common.properties"
            local = root / "local.properties"
            common.write_text(
                "\n".join(
                    (
                        "llm.provider=local",
                        "llm.providers.local.api.url=http://localhost/v1/chat/completions",
                        "llm.providers.local.model=model",
                        "llm.providers.local.api.key=secret",
                    )
                ),
                encoding="utf-8",
            )
            local.write_text("llm.api.key=must-not-be-local\n", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "only llm.stages"):
                load_config(common, local_config_path=local)

    def test_repository_stage_discovery_finds_the_owning_adapter(self):
        path = _discover_local_config("aster_initial")
        self.assertEqual(
            (BASELINES_DIR / "adapters" / "ASTER" / "llm.properties").resolve(),
            path,
        )

    def test_deepseek_uses_official_thinking_object(self):
        payload = {}
        _apply_thinking_config(
            payload,
            {"provider": "deepseek", "thinking_type": "disabled"},
        )
        self.assertEqual({"type": "disabled"}, payload["thinking"])
        self.assertNotIn("enable_thinking", payload)

    def test_oversize_input_is_truncated_before_transport(self):
        config = self._load(
            "\n".join(
                (
                    "llm.provider=local",
                    "llm.providers.local.api.url=http://localhost/v1/chat/completions",
                    "llm.providers.local.model=model",
                    "llm.providers.local.api.key=secret",
                    "llm.max.input.tokens=80",
                    "llm.max.input.chars=100",
                    "llm.budget.chars.per.token=1",
                    "llm.input.message.overhead.tokens=16",
                    "llm.oversize.prompt.policy=truncate",
                )
            )
        )
        system, user, budget = prepare_input(
            config, "chattester_initial", "system", "x" * 300
        )
        self.assertEqual("system", system)
        self.assertTrue(budget["truncated"])
        self.assertIn("INPUT_TRUNCATED_BY_GATEWAY", user)
        self.assertLessEqual(budget["sent_input_tokens_estimate"], 80)
        self.assertLessEqual(budget["sent_input_chars"], 100)

    def test_oversize_error_policy_fails_before_api(self):
        config = self._load(
            "\n".join(
                (
                    "llm.provider=local",
                    "llm.providers.local.api.url=http://localhost/v1/chat/completions",
                    "llm.providers.local.model=model",
                    "llm.providers.local.api.key=secret",
                    "llm.max.input.tokens=50",
                    "llm.max.input.chars=50",
                    "llm.oversize.prompt.policy=error",
                )
            )
        )
        with self.assertRaisesRegex(RuntimeError, "before API call"):
            prepare_input(config, "chattester_initial", "system", "x" * 300)


if __name__ == "__main__":
    unittest.main()
