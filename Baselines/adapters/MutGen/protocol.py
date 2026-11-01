"""Paper-priority protocol helpers for MutGen-DS-Adapted.

This module is deliberately offline: it constructs the exact information
views that a later runner may send through the shared DeepSeek gateway.  It
never reads or modifies the immutable author repository.
"""

from __future__ import print_function

import hashlib
import json
from pathlib import Path


BASELINE = "MutGen-DS-Adapted"
MAX_GENERATION_ROUNDS = 4
VISIBLE_MUTANT_STATUSES = frozenset(("LIVE", "NO_COVERAGE"))

SUMMARY_SYSTEM_PROMPT = (
    "You are a senior Java software engineer. Summarize code precisely and "
    "do not generate tests."
)

GENERATION_SYSTEM_PROMPT = (
    "You are a Senior Software Engineer specializing in Software Testing. "
    "Generate additional JUnit 4 test functions that kill more mutants in "
    "the mutation feedback."
)

COMPILE_REPAIR_SYSTEM_PROMPT = (
    "You are processing a Java JUnit 4 compilation failure. Correct only the "
    "failed test according to the compiler error."
)

ASSERT_REPAIR_SYSTEM_PROMPT = (
    "You are processing a Java JUnit 4 assertion or execution failure. "
    "Correct only the failed test according to the actual error."
)


def build_summary_prompt(original_source):
    """Return the paper's 1--3 sentence purpose/input-format request."""
    return (
        "Summarize the following Java source code in 1-3 sentences, including "
        "its purpose and input format (if applicable).\n```java\n{0}\n```"
    ).format(original_source)


def physical_key(record):
    path = str(record.get("file_path") or "").replace("\\", "/").lower()
    return path or "|".join(
        str(record.get(name) or "")
        for name in ("project", "class", "method", "operator", "line")
    )


def _mutated_statement(record):
    """Prefer the actual mutated source line over workbook shorthand."""
    source_path = str(record.get("mutated_source_path") or "").strip()
    line_number = record.get("line") or record.get("mutation_line")
    if source_path and line_number:
        try:
            lines = Path(source_path).read_text(
                encoding="utf-8", errors="replace"
            ).splitlines()
            index = int(line_number) - 1
            if 0 <= index < len(lines) and lines[index].strip():
                return lines[index].strip()
        except (OSError, TypeError, ValueError):
            pass
    return record.get("mutation_statement") or ""


def _feedback_id(key):
    return "m_" + hashlib.sha256(key.encode("utf-8")).hexdigest()[:12]


def build_mutation_feedback(source_records, status_by_file=None):
    """Create the only mutant fields allowed to reach the MutGen model.

    Duplicate Excel rows that point at one physical mutant are collapsed.
    Audit-only labels (especially ``equivalent``) and graph paths are omitted.
    Killed mutants are not fed into the next generation round.
    """
    status_by_file = status_by_file or {}
    unique = {}
    for record in source_records:
        key = physical_key(record)
        unique.setdefault(key, record)

    feedback = []
    for key in sorted(unique):
        record = unique[key]
        status = str(status_by_file.get(key, "NO_COVERAGE")).upper()
        if status not in VISIBLE_MUTANT_STATUSES:
            continue
        feedback.append(
            {
                "id": _feedback_id(key),
                "line": record.get("line"),
                "operator": record.get("operator"),
                "status": status,
                "mutated_statement": _mutated_statement(record),
            }
        )
    return feedback


def build_generation_prompt(summary, source_without_comments, feedback):
    """Render MutGen's summary + source + mutation-feedback prompt."""
    return """# Unit Test Generator for Java using JUnit 4

## Source code description
{summary}

## Source File (comments removed)
```java
{source}
```

## Mutation Feedback
Analyze the live or uncovered mutants and generate diverse tests that can kill them.
{feedback}

## Requirements
1. Return additional, complete JUnit 4 test methods only; do not return a test class.
2. Each test must assert one behavior and include @Test.
3. Do not copy the focal method implementation into the test.
4. Include required imports separately.
5. Return YAML only, using this schema:

```yaml
new_tests:
  - test_behavior: "..."
    test_name: "..."
    test_code: |
      @Test
      public void testName() {{ ... }}
    new_imports_: |
      import ...;
```
""".format(
        summary=summary.strip(),
        source=source_without_comments.strip(),
        feedback=json.dumps(feedback, ensure_ascii=False, indent=2),
    )


def build_repair_prompt(test_code, error_message, failure_type):
    failure_type = str(failure_type).upper()
    if failure_type not in ("COMPILATION", "ASSERTION"):
        raise ValueError("failure_type must be COMPILATION or ASSERTION")
    return """# MutGen single-test repair

Failure type: {failure_type}

## Failed test
```java
{test_code}
```

## Actual error
{error_message}

Correct only this failed test according to the error. Preserve JUnit 4 and its
intended behavior; do not add unrelated tests. Return YAML only:

```yaml
new_tests:
  - test_code: |
      ...
    new_imports_: |
      ...
```
""".format(
        failure_type=failure_type,
        test_code=test_code.strip(),
        error_message=str(error_message).strip(),
    )
