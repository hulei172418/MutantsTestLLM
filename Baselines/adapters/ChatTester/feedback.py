"""Faithful ChatTester feedback counters and prompt construction."""

from __future__ import print_function

import re

from llm import ChatTesterLlmAdapter


def should_stop(non_improving_repairs, runtime_repairs, total_repairs):
    """Pinned upstream expression; keep strict greater-than semantics."""
    return (
        int(non_improving_repairs) > 3
        or int(runtime_repairs) > 5
        or int(total_repairs) > 30
    )


def advance_feedback_counters(
    repair_tag,
    compiled,
    passed,
    compile_error_count,
    error_numbers,
    non_improving,
    iter_compile,
    iter_test,
):
    """Mirror the counter updates in pinned ``IteratePred`` lines 243-305.

    ``repair_tag`` is persistent upstream state. In particular, once the
    author loop enters ``testRepair``, a later compilation failure does not
    switch that tag back to ``compileRepair``.
    """
    error_numbers = list(error_numbers)
    restore_initial = repair_tag == "testRepair" and not compiled
    if restore_initial:
        iter_test += 1

    if repair_tag == "compileRepair":
        if not compiled:
            iter_compile += 1
            error_numbers.append(int(compile_error_count))
            if (
                len(error_numbers) >= 2
                and error_numbers[-1] >= error_numbers[-2]
            ):
                non_improving += 1
        elif not passed:
            repair_tag = "testRepair"

    if repair_tag == "testRepair" and compiled and not passed:
        iter_test += 1

    return {
        "repair_tag": repair_tag,
        "error_numbers": error_numbers,
        "non_improving": non_improving,
        "iter_compile": iter_compile,
        "iter_test": iter_test,
        "restore_initial": restore_initial,
    }


def extract_error_line(output, generated_filename=""):
    patterns = []
    if generated_filename:
        patterns.append(
            re.compile(re.escape(generated_filename) + r":(\d+)(?::\d+)?")
        )
        patterns.append(
            re.compile(re.escape(generated_filename) + r"\[(\d+)(?:,\d+)?\]")
        )
    patterns.extend((re.compile(r"\.java:(\d+)"), re.compile(r"\.java\[(\d+)")))
    for pattern in patterns:
        match = pattern.search(output or "")
        if match:
            return int(match.group(1))
    return 0


def first_error_message(output):
    lines = [line.strip() for line in (output or "").splitlines() if line.strip()]
    for line in lines:
        if " error:" in line.lower() or line.lower().startswith("error:"):
            return line
    for line in lines:
        if "failure" in line.lower() or "exception" in line.lower():
            return line
    return lines[0] if lines else "Unknown test failure"


def _test_method(source):
    method, _ = ChatTesterLlmAdapter.return_code(source)
    return method or source


def _mark_compile_error(source, error_line, error_message):
    lines = source.splitlines()
    if error_line < 1 or error_line > len(lines):
        return _test_method(source), False
    indentation = re.match(r"\s*", lines[error_line - 1]).group(0)
    lines.insert(
        error_line - 1,
        indentation + "<Buggy Line>: " + str(error_message),
    )
    return _test_method("\n".join(lines)), True


def build_compile_prompt(
    source,
    compile_output,
    focal_method,
    method_intention="",
    regenerate=False,
    class_information="",
):
    error_line = extract_error_line(compile_output)
    error_message = first_error_message(compile_output)
    buggy_method, marked = _mark_compile_error(
        source, error_line, error_message
    )
    if regenerate or not marked:
        return (
            class_information
            + "\n\n# Focal Method\n"
            + focal_method
            + "\n\n# Method Intention\n"
            + method_intention
            + "\n\n# Instruction\nPlease generate a complete and compilable "
            "test method for the `Focal Method` based on the `Method Intention` "
            "and Class Information. \nThe generated test method coverage for the "
            "focal method is as comprehensive as possible, and the generated code "
            "should be enclosed within ``` ```."
        )
    return (
        class_information
        + "\n\n# Test Method\n"
        + buggy_method
        + "\n\n# Instruction\nThe test method has a bug error (marked "
        "<Buggy Line>). \n Please repair the buggy code line and return the "
        "complete and compilable test method after repair. \nThe generated code "
        "should be enclosed within ``` ```."
    )


def build_runtime_prompt(
    source,
    execution_output,
    focal_method,
    method_intention,
):
    error_line = extract_error_line(execution_output)
    error_message = first_error_message(execution_output)
    lines = source.splitlines()
    if 1 <= error_line <= len(lines):
        lines[error_line - 1] = (
            re.match(r"\s*", lines[error_line - 1]).group(0)
            + "[Generate an assertion statement here]"
        )
    dealed_code = _test_method("\n".join(lines))
    if "[Generate an assertion statement here]" not in dealed_code:
        return (
            "# Focal Method\n"
            + focal_method
            + "\n\n# Method Intention\n"
            + method_intention
            + "\n\n# Instruction\nPlease generate a complete and compilable test "
            "method for the `Focal Method` based on the `Method Intention`. "
            "\nThe generated test method coverage for the focal method is as "
            "comprehensive as possible, and the generated code should be enclosed "
            "within ``` ```"
        )
    return (
        "# Focal method (Cannot be modified)\n"
        + focal_method
        + "\n\n# Method Intention\n"
        + method_intention
        + "\n\n# Test Method\n"
        + dealed_code
        + '\n\n# Instruction\nThe test method throw an error " '
        + error_message
        + ' ".\nPlease analyze the code logic and method intention of the Focal '
        "method, then generate a correct assertion statement in the test method. "
        "Return the complete and compilable test method for the Focal method.\n"
        "The generated code should be enclosed within ``` ```."
    )

