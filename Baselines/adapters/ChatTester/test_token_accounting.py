import sys
import unittest
from pathlib import Path


COMMON_DIR = Path(__file__).resolve().parent.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from token_accounting import aggregate_token_usage, normalize_usage


class TokenAccountingTest(unittest.TestCase):
    def test_deepseek_hit_and_miss_fields(self):
        usage = normalize_usage(
            {
                "prompt_tokens": 100,
                "completion_tokens": 20,
                "total_tokens": 120,
                "prompt_cache_hit_tokens": 40,
                "prompt_cache_miss_tokens": 60,
                "completion_tokens_details": {"reasoning_tokens": 7},
            }
        )
        self.assertEqual(40, usage["cache_read_input_tokens"])
        self.assertEqual(60, usage["uncached_input_tokens"])
        self.assertEqual(0.4, usage["cache_hit_rate"])
        self.assertFalse(usage["cache_write_tokens_reported"])
        self.assertEqual(7, usage["reasoning_output_tokens"])

    def test_cache_write_and_openai_nested_cached_tokens(self):
        usage = normalize_usage(
            {
                "input_tokens": 120,
                "output_tokens": 10,
                "input_tokens_details": {
                    "cached_tokens": 80,
                    "cache_write_tokens": 30,
                },
            }
        )
        self.assertEqual(80, usage["cache_read_input_tokens"])
        self.assertEqual(40, usage["uncached_input_tokens"])
        self.assertEqual(30, usage["cache_write_input_tokens"])
        self.assertTrue(usage["cache_write_tokens_reported"])

    def test_local_response_cache_does_not_double_count(self):
        usage = normalize_usage(
            {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120},
            local_response_cache_hit=True,
        )
        self.assertEqual(0, usage["total_tokens"])
        self.assertFalse(usage["api_request_performed"])
        self.assertEqual(120, usage["source_response_token_usage"]["total_tokens"])

    def test_aggregate_hit_rate_uses_required_formula(self):
        totals = aggregate_token_usage(
            [
                normalize_usage(
                    {
                        "prompt_tokens": 100,
                        "completion_tokens": 10,
                        "prompt_cache_hit_tokens": 25,
                        "prompt_cache_miss_tokens": 75,
                    }
                ),
                normalize_usage(
                    {
                        "prompt_tokens": 100,
                        "completion_tokens": 10,
                        "prompt_cache_hit_tokens": 75,
                        "prompt_cache_miss_tokens": 25,
                    }
                ),
            ]
        )
        self.assertEqual(100, totals["cache_read_input_tokens"])
        self.assertEqual(100, totals["uncached_input_tokens"])
        self.assertEqual(0.5, totals["cache_hit_rate"])


if __name__ == "__main__":
    unittest.main()
