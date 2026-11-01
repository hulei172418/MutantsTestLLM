"""Provider-neutral token accounting for every LLM baseline.

The normalized counters represent token usage caused by the current logical
call. A local response-cache hit performs no provider request, so its current
call counters are zero; usage embedded in the cached response is retained under
``source_response_token_usage`` and must not be counted again.
"""

from __future__ import print_function


SCHEMA_VERSION = 1


def _path_value(mapping, path):
    value = mapping
    for part in path:
        if not isinstance(value, dict) or part not in value:
            return None, False
        value = value[part]
    return value, True


def _first_number(mapping, paths, default=0):
    for path in paths:
        value, present = _path_value(mapping, path)
        if present and value is not None:
            try:
                return max(0, int(value)), True
            except (TypeError, ValueError):
                continue
    return max(0, int(default)), False


def extract_provider_usage(raw_usage):
    """Map common OpenAI/DeepSeek/Anthropic-compatible usage fields."""
    raw_usage = raw_usage if isinstance(raw_usage, dict) else {}
    cache_read, cache_read_reported = _first_number(
        raw_usage,
        (
            ("prompt_cache_hit_tokens",),
            ("cache_read_input_tokens",),
            ("cache_read_tokens",),
            ("prompt_tokens_details", "cached_tokens"),
            ("input_tokens_details", "cached_tokens"),
            ("input_tokens_details", "cache_read_tokens"),
        ),
    )
    uncached, uncached_reported = _first_number(
        raw_usage,
        (
            ("prompt_cache_miss_tokens",),
            ("uncached_input_tokens",),
            ("input_tokens_details", "uncached_tokens"),
        ),
    )
    input_tokens, input_reported = _first_number(
        raw_usage, (("prompt_tokens",), ("input_tokens",))
    )
    if not uncached_reported:
        uncached = max(0, input_tokens - cache_read)
    if not input_reported:
        input_tokens = cache_read + uncached

    cache_write, cache_write_reported = _first_number(
        raw_usage,
        (
            ("cache_creation_input_tokens",),
            ("cache_write_input_tokens",),
            ("cache_write_tokens",),
            ("prompt_tokens_details", "cache_write_tokens"),
            ("input_tokens_details", "cache_write_tokens"),
        ),
    )
    output_tokens, _ = _first_number(
        raw_usage, (("completion_tokens",), ("output_tokens",))
    )
    reasoning_tokens, reasoning_reported = _first_number(
        raw_usage,
        (
            ("completion_tokens_details", "reasoning_tokens"),
            ("output_tokens_details", "reasoning_tokens"),
            ("reasoning_tokens",),
        ),
    )
    total_tokens, total_reported = _first_number(raw_usage, (("total_tokens",),))
    if not total_reported:
        total_tokens = input_tokens + output_tokens

    denominator = cache_read + uncached
    hit_rate = float(cache_read) / denominator if denominator else 0.0
    return {
        "provider_usage_reported": bool(raw_usage),
        "input_tokens": input_tokens,
        "uncached_input_tokens": uncached,
        "cache_read_input_tokens": cache_read,
        "cache_write_input_tokens": cache_write,
        "output_tokens": output_tokens,
        "reasoning_output_tokens": reasoning_tokens,
        "total_tokens": total_tokens,
        "cache_hit_rate_denominator_tokens": denominator,
        "cache_hit_rate": hit_rate,
        "cache_read_tokens_reported": cache_read_reported,
        "uncached_input_tokens_reported": uncached_reported,
        "cache_write_tokens_reported": cache_write_reported,
        "reasoning_output_tokens_reported": reasoning_reported,
        "raw_usage_fields": sorted(raw_usage.keys()),
    }


def normalize_usage(raw_usage, local_response_cache_hit=False):
    """Return current-call accounting without double-counting local cache hits."""
    source = extract_provider_usage(raw_usage)
    if local_response_cache_hit:
        current = extract_provider_usage({})
        current["source_response_token_usage"] = source
    else:
        current = source
    current.update(
        {
            "schema_version": SCHEMA_VERSION,
            "local_response_cache_hit": bool(local_response_cache_hit),
            "api_request_performed": not bool(local_response_cache_hit),
        }
    )
    return current


def aggregate_token_usage(records):
    """Aggregate normalized call records using the required hit-rate formula."""
    counters = {
        "call_count": 0,
        "api_request_call_count": 0,
        "local_response_cache_hit_call_count": 0,
        "input_tokens": 0,
        "uncached_input_tokens": 0,
        "cache_read_input_tokens": 0,
        "cache_write_input_tokens": 0,
        "output_tokens": 0,
        "reasoning_output_tokens": 0,
        "total_tokens": 0,
        "cache_write_reported_call_count": 0,
    }
    for record in records:
        record = record or {}
        counters["call_count"] += 1
        counters["api_request_call_count"] += int(
            bool(record.get("api_request_performed"))
        )
        counters["local_response_cache_hit_call_count"] += int(
            bool(record.get("local_response_cache_hit"))
        )
        counters["cache_write_reported_call_count"] += int(
            bool(record.get("cache_write_tokens_reported"))
        )
        for name in (
            "input_tokens",
            "uncached_input_tokens",
            "cache_read_input_tokens",
            "cache_write_input_tokens",
            "output_tokens",
            "reasoning_output_tokens",
            "total_tokens",
        ):
            counters[name] += int(record.get(name) or 0)
    denominator = (
        counters["cache_read_input_tokens"] + counters["uncached_input_tokens"]
    )
    counters["cache_hit_rate_denominator_tokens"] = denominator
    counters["cache_hit_rate"] = (
        float(counters["cache_read_input_tokens"]) / denominator
        if denominator
        else 0.0
    )
    return counters
