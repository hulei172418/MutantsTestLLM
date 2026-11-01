"""Paper-priority HITS prompts, response parsing, and Java suite assembly."""

from __future__ import print_function

import html
import json
import re


BASELINE = "HITS-ChatUniTest-DS"
CHATUNITEST_COMMIT = "06d164aed21ec4cea29b8ac8661de395bdf50c41"
CORE_COMMIT = "513056beadebee0571c0615e3b13bc84bce65179"
PLUGIN_COMMIT = "38f152ce9186bc42f2b08b73a07dce8af54b1378"
MAX_REPAIR_ROUNDS = 10
MAX_SLICE_FORMAT_RETRIES = 3

SLICING_SYSTEM_PROMPT = """You are a professional Java programmer and tester.
Decompose one focal method into semantic problem-solving slices. Follow the
requested JSON schema exactly. Replicate source statements verbatim in each
slice and do not invent program behavior."""

TEST_SYSTEM_PROMPT = """You are a professional Java 8 programmer and tester.
Generate a complete, executable JUnit 4 test class for the requested semantic
slice. Cover every feasible line and branch in that slice. Return the complete
Java source in one fenced java block and no incomplete placeholders."""

REPAIR_SYSTEM_PROMPT = """You are a professional Java 8 test repairer.
Diagnose the supplied compiler or JUnit execution report and return one complete
fixed JUnit 4 test class. Do not modify production code. Preserve valid tests
and return the full Java source in one fenced java block."""


def _dependency_text(context):
    parts = []
    for item in context.get("dependent_classes") or []:
        parts.append(
            "Dependent class {0}:\n```java\n{1}\n```".format(
                item.get("class_name") or "unknown", item.get("summary") or ""
            )
        )
    for item in context.get("dependent_methods") or []:
        parts.append(
            "Invoked method {0}:\n```java\n{1}\n```".format(
                item.get("identity") or "unknown", item.get("source") or ""
            )
        )
    return "\n\n".join(parts) or "No resolvable external dependency source was found."


def build_slicing_prompt(context):
    return """Please decompose the method under test into semantic slices.

## Focal method and class context
```java
{focal}
```

## Resolved dependencies
{dependencies}

Work step by step:
1. Summarize the focal method.
2. List non-local input parameters, object/class fields, and invoked methods
   that must be configured or mocked. Do not list local variables.
3. Decompose the method according to its problem-solving steps. Each slice must
   describe one subtask and reproduce its corresponding original statements.
   Hierarchical slices must be flattened in execution order.

One-shot structural example: initialization, input normalization, and result
rendering should be three slices when they are distinct semantic steps.

Return exactly one JSON object with this schema:
```json
{{
  "summarization": "...",
  "invoked_outside_vars": ["name: type and role"],
  "invoked_outside_methods": ["signature: role"],
  "steps": [
    {{"desp": "semantic subtask", "code": "verbatim original statements"}}
  ]
}}
```
At least one non-empty step is required. Do not add keys outside this schema.
""".format(focal=context["focal_context"], dependencies=_dependency_text(context))


def build_slice_test_prompt(context, slice_info, test_class_name, slice_number):
    return """Generate one complete JUnit 4 test class for semantic slice #{number}.

## Focal method and class context
```java
{focal}
```

## Resolved dependencies
{dependencies}

## Slice to cover
Description: {description}
```java
{code}
```

Follow the HITS chain-of-thought plan internally:
1. Identify every variable and method used by this slice.
2. Enumerate feasible condition combinations and branches in the slice.
3. For each condition, infer parameters, object state, mocks, and the execution
   prefix needed to reach the slice.
4. Generate tests that cover all feasible lines and branches of the slice.

Requirements:
- Return a complete Java 8/JUnit 4 source file in one fenced java block.
- Use package `{package_name}` and public class `{test_class_name}`.
- Use `org.junit.Test` and `org.junit.Assert`; do not use JUnit Jupiter.
- Tests must compile and pass on the original program without timeout.
- Reflection may be used for inaccessible members; Mockito may be used only if
  it already exists on the evaluated project's classpath.
- Do not alter production code and do not include placeholders.
""".format(
        number=slice_number,
        focal=context["focal_context"],
        dependencies=_dependency_text(context),
        description=slice_info["desp"],
        code=slice_info["code"],
        package_name=context.get("package_name") or "",
        test_class_name=test_class_name,
    )


def build_repair_prompt(context, slice_info, source, error_report, test_class_name):
    return """Fix this non-executable HITS slice test.

Focal class: {class_name}
Focal method: {method_signature}
Slice description: {description}
Slice code:
```java
{slice_code}
```

Broken test:
```java
{source}
```

Compiler/JUnit report:
```text
{error}
```

Work internally in this order: locate failing statements; explain the cause;
select a correction; produce the complete corrected test. Return only the full
Java 8/JUnit 4 source in one fenced java block, using public class
`{test_class_name}` and package `{package_name}`. Do not modify production code.
""".format(
        class_name=context["class_name"],
        method_signature=context["method_signature"],
        description=slice_info["desp"],
        slice_code=slice_info["code"],
        source=source,
        error=error_report,
        test_class_name=test_class_name,
        package_name=context.get("package_name") or "",
    )


def _json_object(text):
    text = str(text or "").strip()
    fenced = re.findall(r"```(?:json)?\s*([\s\S]*?)```", text, re.IGNORECASE)
    candidates = list(fenced)
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        candidates.append(text[start : end + 1])
    candidates.append(text)
    for candidate in candidates:
        try:
            value = json.loads(candidate.strip())
            if isinstance(value, dict):
                return value
        except (TypeError, ValueError):
            continue
    raise ValueError("HITS slicing response has no valid JSON object")


def parse_slices(content):
    value = _json_object(content)
    raw_steps = value.get("steps")
    if not isinstance(raw_steps, list) or not raw_steps:
        raise ValueError("HITS slicing response contains no steps")
    steps = []
    for index, raw in enumerate(raw_steps, 1):
        if not isinstance(raw, dict):
            raise ValueError("HITS slice {0} is not an object".format(index))
        description = str(raw.get("desp") or raw.get("description") or "").strip()
        code = html.unescape(str(raw.get("code") or "")).strip()
        if not description or not code:
            raise ValueError("HITS slice {0} lacks desp/code".format(index))
        steps.append({"index": index, "desp": description, "code": code})
    return {
        "summarization": str(value.get("summarization") or "").strip(),
        "invoked_outside_vars": list(value.get("invoked_outside_vars") or []),
        "invoked_outside_methods": list(value.get("invoked_outside_methods") or []),
        "steps": steps,
    }


def extract_java(content):
    text = str(content or "").strip()
    fenced = re.findall(r"```(?:java)?\s*([\s\S]*?)```", text, re.IGNORECASE)
    if fenced:
        return max(fenced, key=len).strip()
    return text


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
            if char in "\r\n":
                line_comment = False
        elif block_comment:
            if char == "*" and nxt == "/":
                block_comment = False
                index += 1
        elif quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
        elif char == "/" and nxt == "/":
            line_comment = True
            index += 1
        elif char == "/" and nxt == "*":
            block_comment = True
            index += 1
        elif char in ('"', "'"):
            quote = char
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index + 1
        index += 1
    return None


def _insert_imports(source, imports):
    missing = [value for value in imports if value not in source]
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
    desired_package = "package {0};".format(package_name) if package_name else ""
    if package_match:
        source = source[: package_match.start()] + desired_package + source[package_match.end() :]
    elif desired_package:
        source = desired_package + "\n\n" + source
    class_match = re.search(
        r"\bpublic\s+(?:final\s+)?class\s+([A-Za-z_$][\w$]*)", source
    )
    if not class_match:
        raise ValueError("HITS test response has no public class")
    old_name = class_match.group(1)
    source = re.sub(r"\b{0}\b".format(re.escape(old_name)), test_class_name, source)
    source = _insert_imports(
        source,
        ("import org.junit.Test;", "import static org.junit.Assert.*;"),
    )
    if not re.search(r"@(?:org\.junit\.)?Test\b", source):
        raise ValueError("HITS test response contains no JUnit test method")
    opening = source.find("{", class_match.start())
    if opening < 0:
        raise ValueError("HITS test class has no body")
    closing = _brace_end(source, opening)
    if closing is None:
        missing = source.count("{") - source.count("}")
        if missing > 0:
            source = source.rstrip() + "\n" + ("}\n" * missing)
            closing = _brace_end(source, opening)
    if closing is None:
        raise ValueError("HITS test class has unbalanced braces")
    return source.strip() + "\n"


def _class_parts(source):
    package = re.search(r"(?m)^\s*package\s+([^;]+);", source)
    imports = re.findall(r"(?m)^\s*import\s+[^;]+;", source)
    match = re.search(r"\bpublic\s+(?:final\s+)?class\s+([A-Za-z_$][\w$]*)", source)
    if not match:
        raise ValueError("Cannot merge HITS source without public class")
    opening = source.find("{", match.end())
    closing = _brace_end(source, opening)
    if closing is None:
        raise ValueError("Cannot merge HITS source with unbalanced class")
    return {
        "package": package.group(1) if package else "",
        "imports": [value.strip() for value in imports],
        "name": match.group(1),
        "body": source[opening + 1 : closing - 1].strip(),
    }


def merge_slice_suites(sources, package_name, suite_class_name):
    if not sources:
        raise ValueError("HITS has no executable slice suites to merge")
    imports = {
        "import org.junit.runner.RunWith;",
        "import org.junit.runners.Suite;",
        "import org.junit.Test;",
        "import static org.junit.Assert.*;",
    }
    nested = []
    nested_names = []
    for index, source in enumerate(sources, 1):
        parts = _class_parts(source)
        imports.update(parts["imports"])
        name = "Slice{0:03d}".format(index)
        body = re.sub(r"\b{0}\b".format(re.escape(parts["name"])), name, parts["body"])
        nested_names.append(suite_class_name + "." + name + ".class")
        nested.append("    public static class {0} {{\n{1}\n    }}".format(name, _indent(body, 8)))
    lines = []
    if package_name:
        lines.extend(["package {0};".format(package_name), ""])
    lines.extend(sorted(imports))
    lines.extend(
        [
            "",
            "@RunWith(Suite.class)",
            "@Suite.SuiteClasses({{{0}}})".format(", ".join(nested_names)),
            "public class {0} {{".format(suite_class_name),
            "\n\n".join(nested),
            "}",
            "",
        ]
    )
    return "\n".join(lines)


def _indent(text, spaces):
    prefix = " " * spaces
    return "\n".join(prefix + line if line.strip() else line for line in text.splitlines())
