"""Run ChatTester's intention and initial-test stages for a prepared target."""

from __future__ import print_function

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path

from llm import (
    ChatTesterLlmAdapter,
    INITIAL_TEST_SYSTEM_PROMPT,
    INTENTION_SYSTEM_PROMPT,
)
from deepseek_gateway import generate, validate_live_config
from prepare_run import (
    DEFAULT_MANIFEST,
    _strip_java_comments,
    default_manual_run_dir,
    prepare,
)


ADAPTER_DIR = Path(__file__).resolve().parent
COMMON_DIR = ADAPTER_DIR.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))
BASELINES_DIR = ADAPTER_DIR.parents[1]
RUNS_DIR = BASELINES_DIR / "runs"

from failure_classification import is_model_provider_failure  # noqa: E402


def latest_prepared_manual_run():
    candidates = []
    if RUNS_DIR.is_dir():
        for path in RUNS_DIR.glob("*_chattester_manual_*"):
            state_path = path / "state.json"
            if not path.is_dir() or not state_path.is_file():
                continue
            try:
                state = _read_json(state_path)
            except (OSError, ValueError):
                continue
            if state.get("state") == "PREPARED_API_NOT_CALLED":
                candidates.append(path)
    if not candidates:
        raise RuntimeError(
            "No prepared manual run found. Run prepare_run.py first."
        )
    return sorted(candidates, key=lambda value: value.name)[-1]


def _local_iso_millis():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _write_state(path, value):
    value = dict(value)
    value["updated_at"] = _local_iso_millis()
    _write_json(path, value)


def resolve_or_prepare_run(run_dir=None, manifest=DEFAULT_MANIFEST):
    """Resolve an explicit/prepared run or create a millisecond run by default."""
    if run_dir is not None:
        candidate = Path(run_dir).resolve()
        if not candidate.exists():
            prepare(manifest, candidate)
        return candidate
    try:
        return latest_prepared_manual_run()
    except RuntimeError:
        candidate = default_manual_run_dir()
        prepare(manifest, candidate)
        return candidate


def _write_json(path, value):
    Path(path).write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def _read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _require_api_environment():
    return validate_live_config()


def _method_context(context):
    signatures = "\n".join(context.get("all_method_signatures", []))
    return _strip_java_comments(
        "{0}\n{1}\n}}".format(context["class_declaration"], signatures)
    )


def _initial_prompt(context, focal_context, intention):
    imports = list(context.get("imports", []))
    imports.extend(["import org.junit.Test;", "import static org.junit.Assert.*;"])
    import_text = "\n".join(dict.fromkeys(imports))
    return (
        "# Import information\n{0}\n\n"
        "# Focal Method Context\n{1}\n\n"
        "# Method intention\n{2}\n\n{3}\n\n"
        "# Instruction\nPlease generate a test method for the \"{4}\" according "
        "to the given Import information, Focal Method Context and Method "
        "intention (it is crucial). Ensure that the generated test method is "
        "compilable and does not use private or undefined methods. Return the "
        "generated code within a Java code fence."
    ).format(
        import_text,
        _method_context(context),
        intention,
        focal_context,
        context["method_signature"],
    )


def _safe_identifier(value):
    normalized = re.sub(r"[^A-Za-z0-9_$]", "_", value)
    if not normalized or normalized[0].isdigit():
        normalized = "T_" + normalized
    return normalized


def render_test_source(context, generated_method, generated_imports):
    package_line = ""
    if context.get("package_name"):
        package_line = "package {0};\n\n".format(context["package_name"])

    imports = ["import org.junit.Test;", "import static org.junit.Assert.*;"]
    imports.extend(context.get("imports", []))
    imports.extend(
        line.strip()
        for line in generated_imports.splitlines()
        if line.strip().startswith("import ")
    )
    imports = list(dict.fromkeys(imports))
    class_name = _safe_identifier(
        context["simple_class_name"] + "_ChatTesterTest"
    )
    source = (
        package_line
        + "\n".join(imports)
        + "\n\npublic class "
        + class_name
        + " {\n"
        + generated_method.strip()
        + "\n}\n"
    )
    return class_name, source


def run(run_dir):
    _require_api_environment()
    run_dir = Path(run_dir).resolve()
    state_path = run_dir / "state.json"
    state = _read_json(state_path)
    if state.get("state") != "PREPARED_API_NOT_CALLED":
        raise RuntimeError("Run is not ready for initial generation: {0}".format(state))

    context = _read_json(run_dir / "target_context.json")
    focal_context = (run_dir / "parsed" / "focal_method_context.txt").read_text(
        encoding="utf-8"
    ).strip()
    intention_user = (
        run_dir / "prompts" / "chattester_intention_0.user.txt"
    ).read_text(encoding="utf-8")

    _write_state(
        state_path,
        {"state": "CALLING_INTENTION", "api_called": True,
         "next_stage": "chattester_intention"},
    )
    try:
        intention_response = generate(
            baseline="ChatTester-DS-Adapted",
            stage="chattester_intention",
            system_prompt=INTENTION_SYSTEM_PROMPT,
            user_prompt=intention_user,
            run_dir=run_dir,
            max_tokens=0,
            response_file=run_dir / "responses" / "chattester_intention_0.json",
        )
        intention = " ".join(intention_response["content"].split())
        if not intention:
            raise RuntimeError("Intention response is empty")
        (run_dir / "parsed" / "chattester_intention_0.txt").write_text(
            intention + "\n", encoding="utf-8"
        )

        initial_user = _initial_prompt(context, focal_context, intention)
        (run_dir / "prompts" / "chattester_initial_0.system.txt").write_text(
            INITIAL_TEST_SYSTEM_PROMPT + "\n", encoding="utf-8"
        )
        (run_dir / "prompts" / "chattester_initial_0.user.txt").write_text(
            initial_user + "\n", encoding="utf-8"
        )
        _write_state(
            state_path,
            {"state": "CALLING_INITIAL_TEST", "api_called": True,
             "next_stage": "chattester_initial"},
        )
        initial_response = generate(
            baseline="ChatTester-DS-Adapted",
            stage="chattester_initial",
            system_prompt=INITIAL_TEST_SYSTEM_PROMPT,
            user_prompt=initial_user,
            run_dir=run_dir,
            max_tokens=0,
            response_file=run_dir / "responses" / "chattester_initial_0.json",
        )
        method, generated_imports = ChatTesterLlmAdapter.return_code(
            initial_response["content"]
        )
        if not method:
            raise RuntimeError("Initial response contains no extractable test method")
        class_name, source = render_test_source(context, method, generated_imports)
        test_file = run_dir / "raw_tests" / (class_name + ".java")
        test_file.write_text(source, encoding="utf-8")
        _write_state(
            state_path,
            {"state": "GENERATED_NOT_COMPILED", "api_called": True,
             "next_stage": "compile_original", "test_file": str(test_file)},
        )
        return {
            "run_dir": str(run_dir),
            "state": "GENERATED_NOT_COMPILED",
            "test_file": str(test_file),
        }
    except Exception as error:
        failure_state = (
            "MODEL_PROVIDER_FAILED"
            if is_model_provider_failure(error)
            else "API_OR_PARSE_FAILED"
        )
        _write_state(
            state_path,
            {"state": failure_state, "api_called": True,
             "error_type": type(error).__name__, "error_message": str(error)},
        )
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--run-dir",
        default=None,
        help=(
            "default: latest prepared manual run, or automatically create a "
            "millisecond manual run"
        ),
    )
    parser.add_argument(
        "--manifest",
        default=str(DEFAULT_MANIFEST),
        help="used only when a run must be prepared automatically",
    )
    args = parser.parse_args()
    run_dir = resolve_or_prepare_run(args.run_dir, args.manifest)
    result = run(run_dir)
    try:
        common_dir = ADAPTER_DIR.parent / "common"
        if str(common_dir) not in sys.path:
            sys.path.insert(0, str(common_dir))
        from summarize_token_usage import summarize_run

        result["token_summary"] = summarize_run(run_dir)
    except Exception as error:
        result["token_summary_error"] = "{0}: {1}".format(
            type(error).__name__, error
        )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
