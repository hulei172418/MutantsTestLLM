"""Shared, auditable LLM call wrapper for all Baseline adapters."""

from __future__ import print_function

import json
import re
import time
import uuid
from datetime import datetime
from pathlib import Path

from llm_gateway import (
    call_llm,
    load_config,
    prepare_input,
    resolve_max_tokens,
    resolve_temperature,
)
from token_accounting import normalize_usage
from trace_logging import (
    append_mutant_trace,
    load_source_records,
    sha256_file,
    sha256_text,
    write_call_file,
)


def generate_traced(
    baseline,
    stage,
    system_prompt,
    user_prompt,
    run_dir,
    max_tokens=0,
    use_cache=None,
    response_file=None,
    round_number=0,
    visible_to_model=False,
    visible_file_paths=None,
):
    logical_call_started = time.perf_counter()
    run_dir = Path(run_dir).resolve()
    call_id = "{0}_{1}".format(
        datetime.utcnow().strftime("%Y%m%dT%H%M%S%fZ"), uuid.uuid4().hex[:10]
    )
    call_dir = run_dir / "calls" / call_id
    call_dir.mkdir(parents=True, exist_ok=False)
    request_path = call_dir / "request.json"
    response_path = call_dir / "response.json"
    response_alias = Path(response_file).resolve() if response_file else None
    if response_alias:
        response_alias.parent.mkdir(parents=True, exist_ok=True)

    metadata = json.loads((run_dir / "metadata.json").read_text(encoding="utf-8"))
    source_records = load_source_records(run_dir)
    config = load_config(stage=stage)
    resolved_max_tokens = resolve_max_tokens(config, stage, max_tokens)
    resolved_temperature = resolve_temperature(config, stage)
    use_cache = not config["force_regenerate"] if use_cache is None else bool(use_cache)
    source_path = Path(metadata["source_path"]).resolve()
    method_group_id = "|".join(
        (metadata["project"], metadata["class_name"], metadata["method_signature"])
    )
    call_metadata = {
        "schema_version": 1,
        "run_id": run_dir.name,
        "call_id": call_id,
        "created_at": datetime.now().astimezone().isoformat(timespec="milliseconds"),
        "baseline": baseline,
        "implementation_identity": metadata["baseline"],
        "stage": stage,
        "round": int(round_number),
        "method_group_id": method_group_id,
        "source_path": str(source_path),
        "source_sha256": sha256_file(source_path),
        "prompt_sha256": sha256_text((system_prompt or "") + "\n" + (user_prompt or "")),
        "request_file": str(request_path),
        "response_file": str(response_path),
        "status": "PREPARED",
        "api_key_logged": False,
    }
    request = {
        "baseline": baseline,
        "stage": stage,
        "system_prompt": system_prompt,
        "user_prompt": user_prompt,
        "max_tokens": resolved_max_tokens,
        "temperature": resolved_temperature,
        "use_cache": use_cache,
        "cache_dir": str(run_dir / "llm_api_cache"),
        "global_config_path": config["global_config_path"],
        "local_config_path": config["local_config_path"],
        "configuration_precedence": config["configuration_precedence"],
    }
    request_path.write_text(
        json.dumps(request, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    write_call_file(call_dir, call_metadata)
    try:
        sent_system, sent_user, input_budget = prepare_input(
            config, stage, system_prompt, user_prompt
        )
        request.update(
            {
                "system_prompt": sent_system,
                "user_prompt": sent_user,
                "input_budget": input_budget,
                "original_system_prompt_sha256": sha256_text(system_prompt or ""),
                "original_user_prompt_sha256": sha256_text(user_prompt or ""),
            }
        )
        request_path.write_text(
            json.dumps(request, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        response = call_llm(
            baseline=baseline,
            stage=stage,
            system_prompt=sent_system,
            user_prompt=sent_user,
            cache_dir=run_dir / "llm_api_cache",
            max_tokens=resolved_max_tokens,
            use_cache=use_cache,
        )
        response["input_budget"] = input_budget
        response_path.write_text(
            json.dumps(response, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        if response_alias:
            response_alias.write_text(
                json.dumps(response, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
        call_metadata.update(
            {
                "status": "CACHE_HIT" if response.get("cache_hit") else "SUCCESS",
                "provider": response.get("provider", ""),
                "api_url": response.get("api_url", ""),
                "model": response.get("model", ""),
                "temperature": response.get("temperature"),
                "max_tokens": response.get("max_tokens"),
                "global_config_path": response.get("global_config_path", ""),
                "local_config_path": response.get("local_config_path", ""),
                "input_budget": input_budget,
                "thinking_type": response.get("thinking_type", ""),
                "cache_hit": bool(response.get("cache_hit")),
                "attempts": int(response.get("attempts", 0)),
                "elapsed_millis": int(response.get("elapsed_millis", 0)),
                "finish_reason": response.get("finish_reason", ""),
                "usage": response.get("usage") or {},
                "token_usage": response.get("token_usage") or {},
                "logical_call_elapsed_millis": int(
                    (time.perf_counter() - logical_call_started) * 1000
                ),
            }
        )
        write_call_file(call_dir, call_metadata)
    except Exception as error:
        attempt_match = re.search(
            r"after\s+(\d+)\s+attempt", str(error), re.IGNORECASE
        )
        failed_attempts = int(attempt_match.group(1)) if attempt_match else 0
        failure = {
            "baseline": baseline,
            "stage": stage,
            "success": False,
            "error_type": type(error).__name__,
            "error_message": str(error),
        }
        response_path.write_text(
            json.dumps(failure, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        if response_alias:
            response_alias.write_text(
                json.dumps(failure, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
        call_metadata.update(
            {
                "status": "ERROR",
                "error_type": type(error).__name__,
                "error_message": str(error),
                "attempts": failed_attempts,
                "elapsed_millis": int(
                    (time.perf_counter() - logical_call_started) * 1000
                ),
                "logical_call_elapsed_millis": int(
                    (time.perf_counter() - logical_call_started) * 1000
                ),
                "token_usage": normalize_usage({}, False),
            }
        )
        write_call_file(call_dir, call_metadata)
        append_mutant_trace(
            run_dir,
            call_metadata,
            source_records,
            visible_to_model=visible_to_model,
            visible_file_paths=visible_file_paths,
        )
        raise
    append_mutant_trace(
        run_dir,
        call_metadata,
        source_records,
        visible_to_model=visible_to_model,
        visible_file_paths=visible_file_paths,
    )
    return response
