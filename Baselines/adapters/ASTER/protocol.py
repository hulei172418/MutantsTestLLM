"""ASTER Java prompt templates and deterministic output sanitization."""

from __future__ import print_function

import re


BASELINE = "ASTER-Java-DS-Reimplementation"
UPSTREAM_COMMIT = "a6d8ab7b6ee43926821f490cb9e1844f35e7e88d"
INITIAL_STAGE = "aster_initial"
REPAIR_STAGE = "aster_repair"
COVERAGE_STAGE = "aster_coverage"

INITIAL_SYSTEM_PROMPT = """You are a Java unit-test generator following ASTER's static-analysis-guided prompt. Complete the supplied test skeleton with meaningful, natural tests. Return one complete Java 8/JUnit 4 source file and no prose."""
REPAIR_SYSTEM_PROMPT = """You repair an ASTER-generated Java test using the exact compiler or JUnit feedback. Return the complete corrected Java 8/JUnit 4 class only. Never modify production code."""
COVERAGE_SYSTEM_PROMPT = """You augment an executable ASTER Java test suite to cover the supplied uncovered focal-method lines. Preserve passing tests and return one complete Java 8/JUnit 4 class only."""


def _section(values):
    return "\n".join(str(value).strip() for value in values or [] if str(value).strip()) or "<none>"


def _mock_skeleton(context):
    fields = context.get("mock_fields") or []
    if not fields:
        return "// No user-specified mockable API type matched this focal method."
    declarations = ["private {0} mocked{1};".format(item["type"], item["type"]) for item in fields]
    setup = ["mocked{0} = org.mockito.Mockito.mock({0}.class);".format(item["type"]) for item in fields]
    return "\n".join(declarations + ["@org.junit.Before public void setup() {"] + ["    " + value for value in setup] + ["}"])


def build_initial_prompt(context, test_class_name):
    return """Generate unit test cases for the following Java focal method using the ASTER prompt structure.

## Relevant constructors
{constructors}

## Relevant accessor methods
Setters for receiver/parameter state:
{setters}

Getters for receiver/return-value assertions:
{getters}

## Focal class and method
```java
{class_declaration}
{focal_method}
}}
```

## Non-private to private call chains
{private_calls}

## ASTER test skeleton
```java
{package_line}
import org.junit.Test;
import static org.junit.Assert.*;
public class {test_class_name} {{
{mock_skeleton}
    @Test public void test{method_name}() throws Exception {{
        // Fill inputs, invocation sequence, and meaningful assertions.
    }}
}}
```

Return the completed source file. Use meaningful test/variable names and assertions. Generate tests for `{method_signature}` only. Do not use JUnit Jupiter. Do not use Mockito unless the supplied skeleton contains mock fields. Do not expose or infer mutation information.
""".format(
        constructors=_section(context.get("relevant_constructors")),
        setters=_section(context.get("setter_signatures")),
        getters=_section(context.get("getter_signatures")),
        class_declaration=context.get("class_declaration") or "",
        focal_method=context.get("focal_method") or "",
        private_calls=_section(context.get("private_call_chains")),
        package_line=("package {0};".format(context.get("package_name")) if context.get("package_name") else ""),
        test_class_name=test_class_name,
        mock_skeleton=_mock_skeleton(context),
        method_name=context["method_name"],
        method_signature=context["method_signature"],
    )


def build_repair_prompt(context, source, error_line, error_message, test_class_name):
    return """The above ASTER test case has the following error.

Previous complete test context:
```java
{source}
```

Erroneous line or location:
```text
{error_line}
```

Compiler/JUnit error message:
```text
{error_message}
```

Focal method `{method_signature}`:
```java
{focal_method}
```

Fix the error using the information above and regenerate the complete test output. Keep package `{package_name}` and public class `{test_class_name}`. Return one full Java 8/JUnit 4 source file only.
""".format(
        source=source, error_line=error_line or "<unknown>", error_message=error_message,
        method_signature=context["method_signature"], focal_method=context["focal_method"],
        package_name=context.get("package_name") or "", test_class_name=test_class_name,
    )


def build_coverage_prompt(context, source, missed_lines, test_class_name):
    missed = set(int(value) for value in missed_lines)
    snippets = []
    for raw in str(context.get("focal_method_numbered_source") or "").splitlines():
        try:
            number = int(raw.split(":", 1)[0])
        except (TypeError, ValueError):
            continue
        if number in missed:
            snippets.append(raw)
    return """In the above method `{method_signature}`, the current passing test cases cannot cover the lines below.

Current passing test class:
```java
{source}
```

Uncovered focal-method lines:
```text
{missed_lines}
```

Uncovered source statements:
```java
{snippets}
```

Add natural, meaningful test cases that exercise these lines. Preserve the existing passing tests and regenerate the complete source using package `{package_name}` and public class `{test_class_name}`. Return one full Java 8/JUnit 4 source file only.
""".format(
        method_signature=context["method_signature"], source=source,
        missed_lines=", ".join(str(value) for value in sorted(missed)),
        snippets="\n".join(snippets) or "<source mapping unavailable>",
        package_name=context.get("package_name") or "", test_class_name=test_class_name,
    )


def extract_java(content):
    text = str(content or "").strip()
    fenced = re.findall(r"```(?:java)?\s*([\s\S]*?)```", text, re.IGNORECASE)
    return (max(fenced, key=len) if fenced else text).strip()


def _brace_end(source, opening):
    depth = 0
    quote = None
    escaped = False
    line_comment = False
    block_comment = False
    index = opening
    while index < len(source):
        char = source[index]
        nxt = source[index + 1] if index + 1 < len(source) else ""
        if line_comment:
            if char in "\r\n": line_comment = False
        elif block_comment:
            if char == "*" and nxt == "/": block_comment = False; index += 1
        elif quote:
            if escaped: escaped = False
            elif char == "\\": escaped = True
            elif char == quote: quote = None
        elif char == "/" and nxt == "/": line_comment = True; index += 1
        elif char == "/" and nxt == "*": block_comment = True; index += 1
        elif char in ('"', "'"): quote = char
        elif char == "{": depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0: return index + 1
        index += 1
    return None


def _insert_imports(source, values):
    missing = [value for value in values if value not in source]
    if not missing:
        return source
    package = re.search(r"(?m)^\s*package\s+[^;]+;\s*", source)
    at = package.end() if package else 0
    return source[:at] + "\n" + "\n".join(missing) + "\n" + source[at:]


def normalize_test_source(content, package_name, test_class_name):
    source = extract_java(content)
    source = source.replace("org.junit.jupiter.api.Test", "org.junit.Test")
    source = source.replace("org.junit.jupiter.api.Assertions", "org.junit.Assert")
    source = source.replace("org.junit.jupiter.api.BeforeEach", "org.junit.Before")
    source = source.replace("org.junit.jupiter.api.AfterEach", "org.junit.After")
    source = source.replace("@BeforeEach", "@Before").replace("@AfterEach", "@After")
    package_match = re.search(r"(?m)^\s*package\s+[^;]+;", source)
    desired = "package {0};".format(package_name) if package_name else ""
    if package_match:
        source = source[:package_match.start()] + desired + source[package_match.end():]
    elif desired:
        source = desired + "\n\n" + source
    class_match = re.search(r"\bpublic\s+(?:final\s+)?class\s+([A-Za-z_$][\w$]*)", source)
    if not class_match:
        raise ValueError("ASTER response has no public test class")
    old_name = class_match.group(1)
    source = re.sub(r"\b{0}\b".format(re.escape(old_name)), test_class_name, source)
    source = _insert_imports(source, ("import org.junit.Test;", "import static org.junit.Assert.*;"))
    if not re.search(r"@(?:org\.junit\.)?Test\b", source):
        raise ValueError("ASTER response contains no JUnit test method")
    opening = source.find("{", class_match.start())
    closing = _brace_end(source, opening)
    if closing is None:
        missing = source.count("{") - source.count("}")
        if missing > 0:
            source = source.rstrip() + "\n" + ("}\n" * missing)
            closing = _brace_end(source, opening)
    if closing is None:
        raise ValueError("ASTER response has unbalanced test-class braces")
    return source[:closing].strip() + "\n"


def test_count(source):
    return len(re.findall(r"@(?:org\.junit\.)?Test\b", source or ""))
