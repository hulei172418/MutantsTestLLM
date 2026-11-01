"""Baseline-owned OpenAI-compatible LLM gateway.

This module is independent of MuTestLLM. Common provider/transport settings
come from ``Baselines/configs/llm.properties`` and environment overrides;
paper-method stage settings come from the one owning adapter's
``llm.properties``. Secrets are used only for the Authorization header and are
never returned or written to run artifacts.
"""

from __future__ import print_function

import hashlib
import http.client
import json
import math
import os
import re
import socket
import time
import urllib.parse
from pathlib import Path

from token_accounting import normalize_usage


BASELINES_DIR = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG_PATH = BASELINES_DIR / "configs" / "llm.properties"
ADAPTERS_DIR = BASELINES_DIR / "adapters"
LOCAL_CONFIG_NAME = "llm.properties"


def _load_properties(path=DEFAULT_CONFIG_PATH):
    path = Path(path)
    if not path.is_file():
        raise RuntimeError("Missing Baselines LLM configuration: {0}".format(path))
    properties = {}
    pending = ""
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = pending + raw_line.strip()
        if line.endswith("\\"):
            pending = line[:-1]
            continue
        pending = ""
        if not line or line.startswith(("#", "!")):
            continue
        equals = line.find("=")
        colon = line.find(":")
        separators = [index for index in (equals, colon) if index >= 0]
        if separators:
            index = min(separators)
            key, value = line[:index], line[index + 1 :]
        else:
            parts = line.split(None, 1)
            key, value = parts[0], parts[1] if len(parts) > 1 else ""
        properties[key.strip()] = value.strip()
    return properties


def _validate_local_properties(path, properties):
    invalid = sorted(
        key for key in properties if not key.startswith("llm.stages.")
    )
    if invalid:
        raise RuntimeError(
            "Baseline-local LLM configuration may contain only llm.stages.* "
            "keys; move common/provider/secret settings to configs/llm.properties: "
            "{0}: {1}".format(path, ", ".join(invalid))
        )


def _discover_local_config(stage):
    """Find the one adapter-local properties file that owns ``stage``."""
    stage = str(stage or "").strip()
    if not stage:
        return None
    key_prefix = "llm.stages.{0}.".format(stage)
    candidates = []
    for path in sorted(ADAPTERS_DIR.glob("*/" + LOCAL_CONFIG_NAME)):
        properties = _load_properties(path)
        _validate_local_properties(path, properties)
        if any(key.startswith(key_prefix) for key in properties):
            candidates.append(path.resolve())
    if len(candidates) > 1:
        raise RuntimeError(
            "LLM stage is configured by multiple baseline-local files: "
            "{0}: {1}".format(stage, ", ".join(str(path) for path in candidates))
        )
    return candidates[0] if candidates else None


def _first_non_blank(*values):
    for value in values:
        if value is not None and str(value).strip():
            return str(value).strip()
    return ""


def _provider_environment_name(provider):
    return "".join(
        character if character.isalnum() else "_" for character in provider
    ).upper()


def _required_value(name, value):
    value = str(value or "").strip()
    if not value:
        raise RuntimeError("Missing required Baselines LLM configuration: " + name)
    return value


def _int_value(name, value):
    value = str(value).strip()
    try:
        parsed = int(value)
    except ValueError:
        raise RuntimeError("Invalid integer configuration {0}: {1}".format(name, value))
    if parsed <= 0:
        raise RuntimeError("Configuration {0} must be positive".format(name))
    return parsed


def _float_value(name, value):
    value = str(value).strip()
    try:
        return float(value)
    except ValueError:
        raise RuntimeError("Invalid numeric configuration {0}: {1}".format(name, value))


def _bool_value(name, value):
    normalized = str(value).strip().lower()
    if normalized in ("true", "1", "yes", "on"):
        return True
    if normalized in ("false", "0", "no", "off"):
        return False
    raise RuntimeError("Invalid boolean configuration {0}: {1}".format(name, value))


def load_config(
    config_path=DEFAULT_CONFIG_PATH,
    require_api_key=True,
    stage=None,
    local_config_path=None,
):
    """Load common settings, then overlay one baseline-local stage file.

    Effective lookup is explicit call > baseline-local stage > common
    environment/properties > code default. No mode flag is needed: absent local
    keys naturally fall back to the common layer.
    """
    config_path = Path(config_path).resolve()
    properties = _load_properties(config_path)
    if local_config_path is None:
        local_path = _discover_local_config(stage)
    else:
        local_path = Path(local_config_path).resolve()
        if not local_path.is_file():
            raise RuntimeError(
                "Missing baseline-local LLM configuration: {0}".format(local_path)
            )
    if local_path is not None:
        local_properties = _load_properties(local_path)
        _validate_local_properties(local_path, local_properties)
        properties.update(local_properties)
    provider = _first_non_blank(
        os.environ.get("LLM_PROVIDER"), properties.get("llm.provider"), "deepseek"
    )
    provider = _required_value("llm.provider", provider)
    provider_prefix = "llm.providers.{0}.".format(provider)
    provider_environment = "LLM_{0}_".format(_provider_environment_name(provider))
    api_key = _first_non_blank(
        os.environ.get("LLM_API_KEY"),
        os.environ.get(provider_environment + "API_KEY"),
        properties.get(provider_prefix + "api.key"),
        properties.get("llm.api.key"),
    )
    if require_api_key:
        api_key = _required_value(provider_prefix + "api.key", api_key)

    stage_max_tokens = {}
    stage_max_input_tokens = {}
    stage_max_input_chars = {}
    stage_temperatures = {}
    stage_prefix = "llm.stages."
    for key, value in properties.items():
        if key.startswith(stage_prefix) and key.endswith(".max.input.tokens"):
            stage = key[len(stage_prefix) : -len(".max.input.tokens")].strip()
            if stage:
                stage_max_input_tokens[stage] = _int_value(key, value)
        elif key.startswith(stage_prefix) and key.endswith(".max.input.chars"):
            stage = key[len(stage_prefix) : -len(".max.input.chars")].strip()
            if stage:
                stage_max_input_chars[stage] = _int_value(key, value)
        elif key.startswith(stage_prefix) and key.endswith(".max.tokens"):
            stage = key[len(stage_prefix) : -len(".max.tokens")].strip()
            if stage:
                stage_max_tokens[stage] = _int_value(key, value)
        elif key.startswith(stage_prefix) and key.endswith(".temperature"):
            stage = key[len(stage_prefix) : -len(".temperature")].strip()
            if stage:
                stage_temperatures[stage] = _float_value(key, value)

    max_input_tokens = _int_value(
        provider_prefix + "max.input.tokens / llm.max.input.tokens",
        _first_non_blank(
            os.environ.get("LLM_MAX_INPUT_TOKENS"),
            os.environ.get(provider_environment + "MAX_INPUT_TOKENS"),
            properties.get(provider_prefix + "max.input.tokens"),
            properties.get("llm.max.input.tokens"),
            properties.get("llm.initial.max.input.tokens"),
            "16000",
        ),
    )
    chars_per_token = _float_value(
        "llm.budget.chars.per.token",
        _first_non_blank(properties.get("llm.budget.chars.per.token"), "3.0"),
    )
    if chars_per_token <= 0:
        raise RuntimeError("Configuration llm.budget.chars.per.token must be positive")
    max_input_chars = _int_value(
        provider_prefix + "max.input.chars / llm.max.input.chars",
        _first_non_blank(
            os.environ.get("LLM_MAX_INPUT_CHARS"),
            os.environ.get(provider_environment + "MAX_INPUT_CHARS"),
            properties.get(provider_prefix + "max.input.chars"),
            properties.get("llm.max.input.chars"),
            properties.get("llm.initial.max.input.chars"),
            str(int(max_input_tokens * chars_per_token)),
        ),
    )
    oversize_policy = _first_non_blank(
        os.environ.get("LLM_OVERSIZE_PROMPT_POLICY"),
        properties.get("llm.oversize.prompt.policy"),
        "error",
    ).lower()
    if oversize_policy not in ("error", "truncate"):
        raise RuntimeError(
            "llm.oversize.prompt.policy must be error or truncate: {0}".format(
                oversize_policy
            )
        )

    return {
        "config_path": str(config_path),
        "global_config_path": str(config_path),
        "local_config_path": str(local_path) if local_path is not None else "",
        "config_paths": [str(config_path)] + (
            [str(local_path)] if local_path is not None else []
        ),
        "configuration_precedence": (
            "code_default<common_properties<common_environment"
            "<baseline_local_stage<explicit_call"
        ),
        "provider": provider,
        "api_url": _required_value(
            provider_prefix + "api.url",
            _first_non_blank(
                os.environ.get("LLM_API_URL"),
                os.environ.get(provider_environment + "API_URL"),
                properties.get(provider_prefix + "api.url"),
                properties.get("llm.api.url"),
            ),
        ),
        "api_key": api_key,
        "model": _required_value(
            provider_prefix + "model",
            _first_non_blank(
                os.environ.get("LLM_MODEL"),
                os.environ.get(provider_environment + "MODEL"),
                properties.get(provider_prefix + "model"),
                properties.get("llm.model"),
            ),
        ),
        "temperature": _float_value(
            provider_prefix + "temperature / llm.temperature",
            _first_non_blank(
                os.environ.get("LLM_TEMPERATURE"),
                os.environ.get(provider_environment + "TEMPERATURE"),
                properties.get(provider_prefix + "temperature"),
                properties.get("llm.temperature"),
                "0.2",
            ),
        ),
        "max_tokens": _int_value(
            provider_prefix + "max.tokens / llm.max.tokens",
            _first_non_blank(
                os.environ.get("LLM_MAX_TOKENS"),
                os.environ.get(provider_environment + "MAX_TOKENS"),
                properties.get(provider_prefix + "max.tokens"),
                properties.get("llm.max.tokens"),
                "12000",
            ),
        ),
        "max_input_tokens": max_input_tokens,
        "max_input_chars": max_input_chars,
        "chars_per_token": chars_per_token,
        "input_message_overhead_tokens": _int_value(
            "llm.input.message.overhead.tokens",
            _first_non_blank(
                properties.get("llm.input.message.overhead.tokens"), "16"
            ),
        ),
        "oversize_prompt_policy": oversize_policy,
        "connect_timeout_millis": _int_value(
            provider_prefix + "connect.timeout.millis / llm.connect.timeout.millis",
            _first_non_blank(
                os.environ.get("LLM_CONNECT_TIMEOUT_MILLIS"),
                os.environ.get(provider_environment + "CONNECT_TIMEOUT_MILLIS"),
                properties.get(provider_prefix + "connect.timeout.millis"),
                properties.get("llm.connect.timeout.millis"),
                "30000",
            ),
        ),
        "read_timeout_millis": _int_value(
            provider_prefix + "read.timeout.millis / llm.read.timeout.millis",
            _first_non_blank(
                os.environ.get("LLM_READ_TIMEOUT_MILLIS"),
                os.environ.get(provider_environment + "READ_TIMEOUT_MILLIS"),
                properties.get(provider_prefix + "read.timeout.millis"),
                properties.get("llm.read.timeout.millis"),
                "120000",
            ),
        ),
        "max_attempts": _int_value(
            provider_prefix + "api.max.attempts / llm.api.max.attempts",
            _first_non_blank(
                os.environ.get("LLM_API_MAX_ATTEMPTS"),
                os.environ.get(provider_environment + "API_MAX_ATTEMPTS"),
                properties.get(provider_prefix + "api.max.attempts"),
                properties.get("llm.api.max.attempts"),
                "2",
            ),
        ),
        "retry_initial_delay_millis": _int_value(
            provider_prefix + "api.retry.initial.delay.millis / llm.api.retry.initial.delay.millis",
            _first_non_blank(
                properties.get(provider_prefix + "api.retry.initial.delay.millis"),
                properties.get("llm.api.retry.initial.delay.millis"),
                "1000",
            ),
        ),
        "retry_max_delay_millis": _int_value(
            provider_prefix + "api.retry.max.delay.millis / llm.api.retry.max.delay.millis",
            _first_non_blank(
                properties.get(provider_prefix + "api.retry.max.delay.millis"),
                properties.get("llm.api.retry.max.delay.millis"),
                "10000",
            ),
        ),
        "thinking_type": _first_non_blank(
            os.environ.get("LLM_THINKING_TYPE"),
            os.environ.get(provider_environment + "THINKING_TYPE"),
            properties.get(provider_prefix + "thinking.type"),
            properties.get("llm.thinking.type"),
        ),
        "stage_max_tokens": stage_max_tokens,
        "stage_max_input_tokens": stage_max_input_tokens,
        "stage_max_input_chars": stage_max_input_chars,
        "stage_temperatures": stage_temperatures,
        "thread_count": _int_value(
            "llm.threads",
            _first_non_blank(
                os.environ.get("LLM_THREADS"),
                properties.get("llm.threads"),
                "1",
            ),
        ),
        "skip_existing_compiled": _bool_value(
            "llm.skip.existing.compiled",
            _first_non_blank(
                os.environ.get("LLM_SKIP_EXISTING_COMPILED"),
                properties.get("llm.skip.existing.compiled"),
                "true",
            ),
        ),
        "force_regenerate": _bool_value(
            "llm.force.regenerate",
            _first_non_blank(
                os.environ.get("LLM_FORCE_REGENERATE"),
                properties.get("llm.force.regenerate"),
                "false",
            ),
        ),
    }


def resolve_max_tokens(config, stage, requested=0):
    """Resolve output budget: explicit call override, stage property, global."""
    requested = int(requested or 0)
    if requested > 0:
        return requested
    return config["stage_max_tokens"].get(stage, config["max_tokens"])


def resolve_temperature(config, stage):
    """Resolve a paper-specific stage temperature before the global fallback."""
    return config.get("stage_temperatures", {}).get(stage, config["temperature"])


def resolve_input_limits(config, stage):
    """Resolve the Baselines-owned input ceiling for one logical stage."""
    return (
        config.get("stage_max_input_tokens", {}).get(
            stage, config["max_input_tokens"]
        ),
        config.get("stage_max_input_chars", {}).get(
            stage, config["max_input_chars"]
        ),
    )


def estimate_input_tokens(config, system_prompt, user_prompt):
    """Conservative dependency-free estimate used only for preflight limits.

    Provider-reported usage remains authoritative after a request. Counting
    UTF-8 bytes is safer for Chinese than dividing Unicode code points by the
    configured character/token ratio.
    """
    byte_count = len((system_prompt or "").encode("utf-8")) + len(
        (user_prompt or "").encode("utf-8")
    )
    return int(math.ceil(byte_count / config["chars_per_token"])) + int(
        config["input_message_overhead_tokens"]
    )


def _truncate_middle_chars(value, limit):
    value = value or ""
    if len(value) <= limit:
        return value
    marker = "\n...[INPUT_TRUNCATED_BY_GATEWAY]...\n"
    if limit <= len(marker):
        return value[:limit]
    remaining = limit - len(marker)
    head = remaining // 2
    tail = remaining - head
    return value[:head] + marker + value[-tail:]


def _utf8_prefix(value, limit):
    return value.encode("utf-8")[: max(0, limit)].decode("utf-8", errors="ignore")


def _utf8_suffix(value, limit):
    return value.encode("utf-8")[-max(0, limit) :].decode("utf-8", errors="ignore")


def _truncate_middle_bytes(value, limit):
    value = value or ""
    encoded = value.encode("utf-8")
    if len(encoded) <= limit:
        return value
    marker = "\n...[INPUT_TRUNCATED_BY_GATEWAY]...\n"
    marker_bytes = len(marker.encode("utf-8"))
    if limit <= marker_bytes:
        return _utf8_prefix(value, limit)
    remaining = limit - marker_bytes
    head = remaining // 2
    tail = remaining - head
    return _utf8_prefix(value, head) + marker + _utf8_suffix(value, tail)


def prepare_input(config, stage, system_prompt, user_prompt):
    """Enforce configured input limits before cache lookup or HTTP transport."""
    system_prompt = system_prompt or ""
    user_prompt = user_prompt or ""
    max_tokens, max_chars = resolve_input_limits(config, stage)
    original_chars = len(system_prompt) + len(user_prompt)
    original_estimate = estimate_input_tokens(config, system_prompt, user_prompt)
    oversized = original_chars > max_chars or original_estimate > max_tokens
    truncated = False
    if oversized:
        if config["oversize_prompt_policy"] == "error":
            raise RuntimeError(
                "LLM input exceeds configured limit before API call: "
                "stage={0}, estimated_tokens={1}/{2}, chars={3}/{4}".format(
                    stage, original_estimate, max_tokens, original_chars, max_chars
                )
            )
        allowed_user_chars = max_chars - len(system_prompt)
        token_content_bytes = int(
            math.floor(
                (max_tokens - config["input_message_overhead_tokens"])
                * config["chars_per_token"]
            )
        )
        allowed_user_bytes = token_content_bytes - len(system_prompt.encode("utf-8"))
        if allowed_user_chars <= 0 or allowed_user_bytes <= 0:
            raise RuntimeError(
                "System prompt alone exceeds configured LLM input limit: "
                "stage={0}, max_input_tokens={1}, max_input_chars={2}".format(
                    stage, max_tokens, max_chars
                )
            )
        user_prompt = _truncate_middle_chars(user_prompt, allowed_user_chars)
        user_prompt = _truncate_middle_bytes(user_prompt, allowed_user_bytes)
        truncated = True

    sent_chars = len(system_prompt) + len(user_prompt)
    sent_estimate = estimate_input_tokens(config, system_prompt, user_prompt)
    if sent_chars > max_chars or sent_estimate > max_tokens:
        raise RuntimeError(
            "Unable to fit LLM input within configured limit: "
            "stage={0}, estimated_tokens={1}/{2}, chars={3}/{4}".format(
                stage, sent_estimate, max_tokens, sent_chars, max_chars
            )
        )
    metadata = {
        "max_input_tokens": max_tokens,
        "max_input_chars": max_chars,
        "oversize_prompt_policy": config["oversize_prompt_policy"],
        "token_estimator": "ceil(utf8_bytes/chars_per_token)+message_overhead",
        "chars_per_token": config["chars_per_token"],
        "message_overhead_tokens": config["input_message_overhead_tokens"],
        "original_input_tokens_estimate": original_estimate,
        "sent_input_tokens_estimate": sent_estimate,
        "original_input_chars": original_chars,
        "sent_input_chars": sent_chars,
        "truncated": truncated,
    }
    return system_prompt, user_prompt, metadata


def _cache_key(config, baseline, stage, system_prompt, user_prompt, max_tokens):
    identity = json.dumps(
        {
            "provider": config["provider"],
            "api_url": config["api_url"],
            "model": config["model"],
            "temperature": config["temperature"],
            "thinking_type": config["thinking_type"],
            "baseline": baseline,
            "stage": stage,
            "system_prompt": system_prompt,
            "user_prompt": user_prompt,
            "max_tokens": max_tokens,
        },
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(identity.encode("utf-8")).hexdigest()


def _extract_content(raw_response):
    choices = raw_response.get("choices") or []
    if not choices:
        return ""
    choice = choices[0] or {}
    message = choice.get("message") or {}
    return message.get("content") or choice.get("text") or ""


def _finish_reason(raw_response):
    choices = raw_response.get("choices") or []
    return (choices[0] or {}).get("finish_reason", "") if choices else ""


def _post_json(config, encoded):
    """POST JSON while applying connect and read timeouts independently."""
    parsed = urllib.parse.urlsplit(config["api_url"])
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise RuntimeError("Unsupported LLM API URL: {0}".format(config["api_url"]))
    connection_class = (
        http.client.HTTPSConnection
        if parsed.scheme == "https"
        else http.client.HTTPConnection
    )
    connection = connection_class(
        parsed.hostname,
        parsed.port,
        timeout=config["connect_timeout_millis"] / 1000.0,
    )
    path = parsed.path or "/"
    if parsed.query:
        path += "?" + parsed.query
    try:
        connection.request(
            "POST",
            path,
            body=encoded,
            headers={
                "Authorization": "Bearer " + config["api_key"],
                "Content-Type": "application/json; charset=UTF-8",
            },
        )
        if connection.sock is not None:
            connection.sock.settimeout(config["read_timeout_millis"] / 1000.0)
        response = connection.getresponse()
        body = response.read().decode("utf-8", errors="replace")
        if response.status < 200 or response.status >= 300:
            raise RuntimeError(
                "LLM HTTP error {0}: {1}".format(response.status, body)
            )
        decoded = json.loads(body)
        if not isinstance(decoded, dict):
            raise ValueError("LLM response JSON root must be an object")
        return decoded
    finally:
        connection.close()


def call_llm(
    baseline,
    stage,
    system_prompt,
    user_prompt,
    cache_dir,
    max_tokens=0,
    use_cache=None,
):
    config = load_config(stage=stage)
    config = dict(config)
    config["temperature"] = resolve_temperature(config, stage)
    system_prompt, user_prompt, input_budget = prepare_input(
        config, stage, system_prompt, user_prompt
    )
    if use_cache is None:
        use_cache = not config["force_regenerate"]
    else:
        use_cache = bool(use_cache)
    output_tokens = resolve_max_tokens(config, stage, max_tokens)
    cache_dir = Path(cache_dir).resolve()
    cache_key = _cache_key(
        config, baseline, stage, system_prompt, user_prompt, output_tokens
    )
    cache_file = cache_dir / (cache_key + ".json")
    if use_cache and cache_file.is_file():
        raw_response = json.loads(cache_file.read_text(encoding="utf-8"))
        return _result(
            config,
            baseline,
            stage,
            output_tokens,
            raw_response,
            True,
            0,
            0,
            input_budget,
        )

    payload = {
        "model": config["model"],
        "temperature": config["temperature"],
        "max_tokens": output_tokens,
        "messages": [
            {"role": "system", "content": system_prompt or ""},
            {"role": "user", "content": user_prompt or ""},
        ],
    }
    _apply_thinking_config(payload, config)

    encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    last_error = None
    raw_response = None
    started = time.time()
    for attempt in range(1, config["max_attempts"] + 1):
        try:
            raw_response = _post_json(config, encoded)
        except (
            ValueError,
            OSError,
            socket.timeout,
            http.client.HTTPException,
            RuntimeError,
        ) as error:
            last_error = error
            if not _is_retryable_provider_error(error):
                raise RuntimeError(
                    "LLM API failed after {0} attempt(s) (non-retryable): {1}".format(
                        attempt, error
                    )
                ) from error
            if attempt < config["max_attempts"]:
                time.sleep(_retry_delay_seconds(config, attempt))
        else:
            break
    if raw_response is None:
        raise RuntimeError(
            "LLM API failed after {0} attempt(s): {1}".format(
                config["max_attempts"], last_error
            )
        )
    # Local cache persistence and response normalization are deliberately
    # outside the provider retry loop. Repeating a billed API request cannot
    # repair a local filesystem or adapter error.
    if use_cache:
        cache_dir.mkdir(parents=True, exist_ok=True)
        temporary = cache_file.with_suffix(".tmp")
        temporary.write_text(
            json.dumps(raw_response, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        os.replace(str(temporary), str(cache_file))
    elapsed = int((time.time() - started) * 1000)
    return _result(
        config,
        baseline,
        stage,
        output_tokens,
        raw_response,
        False,
        attempt,
        elapsed,
        input_budget,
    )


def _retry_delay_seconds(config, failed_attempt):
    initial = int(config["retry_initial_delay_millis"])
    maximum = int(config["retry_max_delay_millis"])
    delay_millis = min(maximum, initial * (2 ** max(0, int(failed_attempt) - 1)))
    return delay_millis / 1000.0


def _is_retryable_provider_error(error):
    """Return whether a provider failure may succeed without changing the request.

    Network/transport failures, malformed transient responses, rate limits and
    server-side failures are retried.  Other HTTP 4xx responses and local
    configuration errors fail immediately because repeating the same request
    cannot repair them.
    """
    if isinstance(error, (OSError, socket.timeout, http.client.HTTPException, ValueError)):
        return True
    match = re.search(r"LLM HTTP error\s+(\d{3})\s*:", str(error))
    if not match:
        return False
    status = int(match.group(1))
    return status in {408, 409, 425, 429} or 500 <= status <= 599


def _apply_thinking_config(payload, config):
    """Apply the configured thinking switch using the provider's wire format."""
    thinking = config["thinking_type"].lower()
    if thinking in ("disabled", "false", "off", "none"):
        enabled = False
    elif thinking in ("enabled", "true", "on"):
        enabled = True
    else:
        return

    if config["provider"].lower() == "deepseek":
        payload["thinking"] = {"type": "enabled" if enabled else "disabled"}
    else:
        payload["enable_thinking"] = enabled


def _result(
    config,
    baseline,
    stage,
    max_tokens,
    raw_response,
    cache_hit,
    attempts,
    elapsed,
    input_budget,
):
    raw_usage = raw_response.get("usage") or {}
    return {
        "baseline": baseline,
        "stage": stage,
        "config_path": config["config_path"],
        "global_config_path": config["global_config_path"],
        "local_config_path": config["local_config_path"],
        "config_paths": config["config_paths"],
        "provider": config["provider"],
        "model": config["model"],
        "api_url": config["api_url"],
        "temperature": config["temperature"],
        "max_tokens": max_tokens,
        "input_budget": input_budget,
        "thinking_type": config["thinking_type"],
        "success": True,
        "cache_hit": cache_hit,
        "attempts": attempts,
        "elapsed_millis": elapsed,
        "finish_reason": _finish_reason(raw_response),
        "content": _extract_content(raw_response),
        "usage": raw_usage,
        "token_usage": normalize_usage(raw_usage, cache_hit),
        "raw_response": raw_response,
    }
