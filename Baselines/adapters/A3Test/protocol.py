"""A3Test-DS single-generation prompt and deterministic signature verifier."""

from __future__ import print_function

import re


BASELINE = "A3Test-DS-Adapted"
UPSTREAM_COMMIT = "14fa2c4a5bf44bf51201f293e8dd599033652027"
GENERATION_STAGE = "a3test_generation"

SYSTEM_PROMPT = """You are an assertion-augmented Java unit-test generator.
Translate one focal context into the single best complete JUnit 4 test class.
Use meaningful assertions that check the focal method's expected behavior.
Return one Java source file and no alternative candidates."""


def build_generation_prompt(context, test_class_name):
    return """Generate exactly one best test case for the following A3Test focal context.

The source fields preserve the paper's fm+fc+c+m+f order: focal method, focal
class name, constructor signatures, other public method signatures, and public
fields.

```text
{translation_source}
```

Requirements:
- Output one complete Java 8/JUnit 4 class in one fenced java block.
- Package: `{package_name}`.
- Public class name: `{test_class_name}`.
- Generate exactly one `@Test public void test...()` method, corresponding to
  one best A3Test inference candidate, not a suite of alternatives.
- Instantiate and invoke the focal method `{method_name}`.
- Include meaningful assertions derived from the focal method behavior.
- Include all required imports. Do not use JUnit Jupiter.
- Do not modify production code and do not use placeholders.
- Mockito may be used only when it is already on the project classpath.
""".format(
        translation_source=context["translation_source"],
        package_name=context.get("package_name") or "",
        test_class_name=test_class_name,
        method_name=context["method_name"],
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


def _author_balance_method(source, opening):
    end = _brace_end(source, opening)
    if end is not None:
        return source[:end], False
    last_statement = source.rfind(";", opening)
    if last_statement < 0:
        raise ValueError("A3Test output is unbalanced and has no complete statement")
    # Mirrors the author post-processing rule: keep through the final complete
    # statement and append a closing method brace. It does not invent expressions.
    return source[: last_statement + 1].rstrip() + "\n}", True


def _normalize_import(value):
    value = str(value or "").strip()
    return value if value.endswith(";") else value + ";"


def verify_and_wrap(content, context, test_class_name):
    raw = extract_java(content)
    raw = raw.replace("org.junit.jupiter.api.Test", "org.junit.Test")
    raw = raw.replace("org.junit.jupiter.api.Assertions", "org.junit.Assert")
    imports = set(re.findall(r"(?m)^\s*import\s+[^;]+;", raw))
    imports.update(_normalize_import(value) for value in context.get("imports") or [])
    imports.add("import org.junit.Test;")
    imports.add("import static org.junit.Assert.*;")
    imports = {value for value in imports if "org.junit.jupiter" not in value}

    method_pattern = re.compile(
        r"(?m)(?P<annotation>@(?:org\.junit\.)?Test(?:\([^\n]*\))?\s*)?"
        r"(?P<visibility>public|protected|private)?\s*"
        r"(?P<static>static\s+)?(?P<return_type>[A-Za-z_$][\w$<>,.?\[\]]*\s+)?"
        r"(?P<name>[A-Za-z_$][\w$]*)\s*\(\s*\)\s*"
        r"(?P<throws>throws\s+[A-Za-z_$][\w$.,\s]*\s*)?\{"
    )
    matches = list(method_pattern.finditer(raw))
    annotated = [match for match in matches if match.group("annotation")]
    candidates = annotated or [match for match in matches if match.group("return_type")]
    if not candidates:
        raise ValueError("A3Test output contains no zero-argument test method")
    if len(annotated) > 1:
        raise ValueError("A3Test single-iteration output contains multiple @Test methods")
    selected = candidates[0]
    opening = raw.find("{", selected.start(), selected.end())
    method_source, parenthesis_repaired = _author_balance_method(raw[selected.start() :], opening - selected.start())
    local_opening = method_source.find("{")
    body = method_source[local_opening + 1 : -1].strip()
    original_name = selected.group("name")
    verified_name = original_name if original_name.startswith("test") else "test" + original_name
    throws_clause = str(selected.group("throws") or "").strip()
    method = "    @Test\n    public void {0}(){1} {{\n{2}\n    }}".format(
        verified_name,
        " " + throws_clause if throws_clause else "",
        "\n".join("        " + line if line.strip() else line for line in body.splitlines()),
    )
    lines = []
    package_name = context.get("package_name") or ""
    if package_name:
        lines.extend(["package {0};".format(package_name), ""])
    lines.extend(sorted(imports))
    lines.extend(["", "public class {0} {{".format(test_class_name), method, "}", ""])
    source = "\n".join(lines)
    assertion_pattern = re.compile(
        r"\b(?:Assert\.)?assert(?:ArrayEquals|Equals|False|NotEquals|NotNull|NotSame|Null|Same|That|Throws|True)\s*\("
    )
    assertion_count = len(assertion_pattern.findall(source))
    vacuous_count = len(re.findall(r"\bassertTrue\s*\(\s*true\s*\)", source))
    audit = {
        "schema_version": 1,
        "author_rule_source": "postProcessingScript.py",
        "annotation_added": not bool(selected.group("annotation")),
        "visibility_rewritten_to_public": selected.group("visibility") != "public",
        "void_added": str(selected.group("return_type") or "").strip() != "void",
        "original_method_name": original_name,
        "verified_method_name": verified_name,
        "throws_clause_preserved": throws_clause,
        "test_prefix_added": verified_name != original_name,
        "parenthesis_repaired": parenthesis_repaired,
        "assertion_count": assertion_count,
        "vacuous_assertion_count": vacuous_count,
        "vacuous_assertions_removed": 0,
        "policy": "author deterministic verification only; no LLM repair",
    }
    return source, audit
