"""ChatTester bridge to the Baselines-owned DeepSeek gateway."""

from __future__ import print_function

import argparse
import json
import sys
import uuid
from datetime import datetime
from pathlib import Path


ADAPTER_DIR = Path(__file__).resolve().parent
BASELINES_DIR = ADAPTER_DIR.parents[1]
WORKSPACE_DIR = BASELINES_DIR.parent
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from llm_gateway import (
    DEFAULT_CONFIG_PATH,
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


def offline_check():
    """Validate the Baselines-local bridge without returning credentials."""
    if not COMMON_DIR.is_dir():
        raise RuntimeError("Missing Baselines common module: {0}".format(COMMON_DIR))
    config = load_config(require_api_key=False, stage="chattester_initial")
    return {
        "success": True,
        "mode": "offline_check",
        "gateway": str(COMMON_DIR / "llm_gateway.py"),
        "config_path": str(DEFAULT_CONFIG_PATH),
        "local_config_path": config["local_config_path"],
        "config_exists": DEFAULT_CONFIG_PATH.is_file(),
        "provider": config["provider"],
        "configured_model": config["model"],
        "max_input_tokens": config["max_input_tokens"],
        "max_input_chars": config["max_input_chars"],
        "oversize_prompt_policy": config["oversize_prompt_policy"],
        "api_called": False,
        "mutestllm_accessed": False,
    }


def validate_live_config():
    """Resolve the Baselines configuration without returning the API key."""
    config = load_config(stage="chattester_initial")
    return {
        "config_path": config["config_path"],
        "global_config_path": config["global_config_path"],
        "local_config_path": config["local_config_path"],
        "configuration_precedence": config["configuration_precedence"],
        "provider": config["provider"],
        "api_url": config["api_url"],
        "model": config["model"],
        "temperature": config["temperature"],
        "max_tokens": config["max_tokens"],
        "max_input_tokens": config["max_input_tokens"],
        "max_input_chars": config["max_input_chars"],
        "oversize_prompt_policy": config["oversize_prompt_policy"],
        "max_attempts": config["max_attempts"],
        "connect_timeout_millis": config["connect_timeout_millis"],
        "read_timeout_millis": config["read_timeout_millis"],
        "thinking_type": config["thinking_type"],
        "stage_max_tokens": config["stage_max_tokens"],
        "stage_max_input_tokens": config["stage_max_input_tokens"],
        "stage_max_input_chars": config["stage_max_input_chars"],
        "stage_temperatures": config["stage_temperatures"],
        "thread_count": config["thread_count"],
        "skip_existing_compiled": config["skip_existing_compiled"],
        "force_regenerate": config["force_regenerate"],
    }


def generate(
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
):
    """Invoke the Baselines common client and return its response dictionary."""
    run_dir = Path(run_dir).resolve()
    call_id = "{0}_{1}".format(
        datetime.utcnow().strftime("%Y%m%dT%H%M%S%fZ"),
        uuid.uuid4().hex[:10],
    )
    call_dir = run_dir / "calls" / call_id
    call_dir.mkdir(parents=True, exist_ok=False)
    request_file = call_dir / "request.json"
    response_path = call_dir / "response.json"
    response_alias = Path(response_file).resolve() if response_file is not None else None
    if response_alias is not None:
        response_alias.parent.mkdir(parents=True, exist_ok=True)
    run_metadata = json.loads((run_dir / "metadata.json").read_text(encoding="utf-8"))
    source_records = load_source_records(run_dir)
    config = load_config(stage=stage)
    resolved_max_tokens = resolve_max_tokens(config, stage, max_tokens)
    resolved_temperature = resolve_temperature(config, stage)
    resolved_use_cache = (
        not config["force_regenerate"] if use_cache is None else bool(use_cache)
    )
    request = {
        "baseline": baseline,
        "stage": stage,
        "system_prompt": system_prompt,
        "user_prompt": user_prompt,
        "max_tokens": resolved_max_tokens,
        "temperature": resolved_temperature,
        "use_cache": resolved_use_cache,
        "cache_policy_source": (
            "llm.force.regenerate" if use_cache is None else "call_override"
        ),
        "cache_dir": str(run_dir / "llm_api_cache"),
        "global_config_path": config["global_config_path"],
        "local_config_path": config["local_config_path"],
        "configuration_precedence": config["configuration_precedence"],
    }
    request_file.write_text(
        json.dumps(request, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    source_path = Path(run_metadata["source_path"]).resolve()
    method_group_id = "|".join(
        (
            run_metadata["project"],
            run_metadata["class_name"],
            run_metadata["method_signature"],
        )
    )
    call_metadata = {
        "schema_version": 1,
        "run_id": run_dir.name,
        "call_id": call_id,
        "created_at": datetime.now().astimezone().isoformat(timespec="milliseconds"),
        "baseline": baseline,
        "implementation_identity": run_metadata["baseline"],
        "stage": stage,
        "round": int(round_number),
        "method_group_id": method_group_id,
        "source_path": str(source_path),
        "source_sha256": sha256_file(source_path),
        "prompt_sha256": sha256_text((system_prompt or "") + "\n" + (user_prompt or "")),
        "original_prompt_sha256": sha256_text(
            (system_prompt or "") + "\n" + (user_prompt or "")
        ),
        "system_prompt_file": "",
        "user_prompt_file": "",
        "request_file": str(request_file),
        "response_file": str(response_path),
        "status": "PREPARED",
        "api_key_logged": False,
    }
    write_call_file(call_dir, call_metadata)
    try:
        sent_system_prompt, sent_user_prompt, input_budget = prepare_input(
            config, stage, system_prompt, user_prompt
        )
        request.update(
            {
                "system_prompt": sent_system_prompt,
                "user_prompt": sent_user_prompt,
                "input_budget": input_budget,
                "original_system_prompt_sha256": sha256_text(system_prompt or ""),
                "original_user_prompt_sha256": sha256_text(user_prompt or ""),
            }
        )
        request_file.write_text(
            json.dumps(request, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        call_metadata.update(
            {
                "prompt_sha256": sha256_text(
                    sent_system_prompt + "\n" + sent_user_prompt
                ),
                "input_budget": input_budget,
            }
        )
        write_call_file(call_dir, call_metadata)
        response = call_llm(
            baseline=baseline,
            stage=stage,
            system_prompt=sent_system_prompt,
            user_prompt=sent_user_prompt,
            cache_dir=run_dir / "llm_api_cache",
            max_tokens=resolved_max_tokens,
            use_cache=resolved_use_cache,
        )
        # Preserve whether this logical call truncated the original prompt;
        # call_llm's second safety check sees the already prepared prompt.
        response["input_budget"] = input_budget
        response_path.write_text(
            json.dumps(response, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        if response_alias is not None:
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
                "input_budget": response.get("input_budget") or {},
                "thinking_type": response.get("thinking_type", ""),
                "cache_hit": bool(response.get("cache_hit")),
                "attempts": int(response.get("attempts", 0)),
                "elapsed_millis": int(response.get("elapsed_millis", 0)),
                "finish_reason": response.get("finish_reason", ""),
                "usage": response.get("usage") or {},
                "token_usage": response.get("token_usage") or {},
            }
        )
        write_call_file(call_dir, call_metadata)
        append_mutant_trace(
            run_dir,
            call_metadata,
            source_records,
            visible_to_model=visible_to_model,
        )
    except Exception as error:
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
        if response_alias is not None:
            response_alias.write_text(
                json.dumps(failure, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
        call_metadata.update(
            {
                "status": "ERROR",
                "error_type": type(error).__name__,
                "error_message": str(error),
                "token_usage": normalize_usage({}, False),
            }
        )
        write_call_file(call_dir, call_metadata)
        append_mutant_trace(
            run_dir,
            call_metadata,
            source_records,
            visible_to_model=visible_to_model,
        )
        raise
    return response


def _read_utf8(path):
    return Path(path).read_text(encoding="utf-8")


def _parse_args(argv):
    parser = argparse.ArgumentParser(
        description="ChatTester adapter for the Baselines DeepSeek client"
    )
    parser.add_argument(
        "--check", action="store_true", help="validate wiring without calling the API"
    )
    parser.add_argument("--baseline", default="ChatTester-DS-Adapted")
    parser.add_argument("--stage")
    parser.add_argument("--system-prompt-file")
    parser.add_argument("--user-prompt-file")
    parser.add_argument("--run-dir")
    parser.add_argument("--response-file")
    parser.add_argument("--max-tokens", type=int, default=0)
    cache_group = parser.add_mutually_exclusive_group()
    cache_group.add_argument(
        "--use-cache", dest="use_cache", action="store_true",
        help="explicitly allow cache reuse for this call",
    )
    cache_group.add_argument(
        "--no-cache", dest="use_cache", action="store_false",
        help="force a fresh API response for this call",
    )
    parser.set_defaults(use_cache=None)
    return parser.parse_args(argv)


def main(argv=None):
    args = _parse_args(argv)
    if args.check:
        print(json.dumps(offline_check(), ensure_ascii=False, indent=2))
        return 0
    required = {
        "--stage": args.stage,
        "--system-prompt-file": args.system_prompt_file,
        "--user-prompt-file": args.user_prompt_file,
        "--run-dir": args.run_dir,
    }
    missing = [name for name, value in required.items() if not value]
    if missing:
        raise SystemExit("Missing required arguments: " + ", ".join(missing))
    result = generate(
        baseline=args.baseline,
        stage=args.stage,
        system_prompt=_read_utf8(args.system_prompt_file),
        user_prompt=_read_utf8(args.user_prompt_file),
        run_dir=args.run_dir,
        max_tokens=args.max_tokens,
        use_cache=args.use_cache,
        response_file=args.response_file,
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print("ERROR: {0}".format(error), file=sys.stderr)
        sys.exit(1)
