"""Prompt and output protocol for IntUT-DS-Reimplementation."""

from __future__ import print_function

import re


BASELINE = "IntUT-DS-Reimplementation"
STAGE = "intut_generation"

SYSTEM_PROMPT = """You are completing one Java unit test from an explicit IntUT test intention. Follow the supplied program context, retrieved project examples, existing tests, and intention exactly. Use Java 8 and JUnit 4. Do not add mutation-testing logic. Return one complete Java source file and no prose."""


def build_prompt(context, rag, intention, test_class_name, existing_source=""):
    examples = "\n\n".join(item["code"] for item in rag.get("test_examples") or []) or "// No project test passed the paper's Jaccard threshold."
    mocks = "\n\n".join(item["code"] for item in rag.get("mock_examples") or []) or "// No project-local mock example was retrieved."
    fields = "\n".join(context.get("fields") or []) or "// no fields extracted"
    methods = "\n".join(context.get("all_method_signatures") or []) or "// no dependency signatures extracted"
    imports = "\n".join(context.get("imports") or [])
    prefix = existing_source.strip() if existing_source else """{package}
import org.junit.Test;
import static org.junit.Assert.*;

public class {test_class} {{
    @Test
    public void test_{method}() throws Exception {{
""".format(
        package=("package " + context["package_name"] + ";") if context.get("package_name") else "",
        test_class=test_class_name, method=context["method_name"],
    )
    return """--- 1. Program context ---
// Focal class file path: {source_path}
{imports}
public class {class_name} {{
{fields}
{methods}
{focal_method}
}}

--- 2. Example code ---
// possible project test code
{examples}

// project-local mock examples
{mocks}

--- 3. Test code prefix / existing accepted test cases ---
{prefix}

--- 4. Test intention ---
/**
{intention}
*/

Complete this intention. Return the entire source using package `{package_name}` and public class `{test_class}`. Preserve every existing accepted test when a complete class is supplied above. Return Java only.
""".format(
        source_path=context.get("source_path") or "", imports=imports,
        class_name=context["simple_class_name"], fields=fields, methods=methods,
        focal_method=context.get("method_body") or "", examples=examples, mocks=mocks,
        prefix=prefix, intention=intention["test_intention"],
        package_name=context.get("package_name") or "", test_class=test_class_name,
    )


def extract_java(content):
    text = str(content or "").strip()
    fenced = re.findall(r"```(?:java)?\s*([\s\S]*?)```", text, re.IGNORECASE)
    return (max(fenced, key=len) if fenced else text).strip()


def _brace_end(source, opening):
    depth = 0
    quote = None
    escaped = False
    for index in range(opening, len(source)):
        char = source[index]
        if quote:
            if escaped: escaped = False
            elif char == "\\": escaped = True
            elif char == quote: quote = None
        elif char in ('"', "'"): quote = char
        elif char == "{": depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0: return index + 1
    return None


def _insert_imports(source, values):
    missing = [value for value in values if value not in source]
    if not missing: return source
    package = re.search(r"(?m)^\s*package\s+[^;]+;\s*", source)
    at = package.end() if package else 0
    return source[:at] + "\n" + "\n".join(missing) + "\n" + source[at:]


def normalize_test_source(content, package_name, test_class_name):
    source = extract_java(content)
    source = source.replace("org.junit.jupiter.api.Test", "org.junit.Test")
    source = source.replace("org.junit.jupiter.api.Assertions", "org.junit.Assert")
    package_match = re.search(r"(?m)^\s*package\s+[^;]+;", source)
    desired = "package {0};".format(package_name) if package_name else ""
    if package_match: source = source[:package_match.start()] + desired + source[package_match.end():]
    elif desired: source = desired + "\n\n" + source
    class_match = re.search(r"\bpublic\s+(?:final\s+)?class\s+([A-Za-z_$][\w$]*)", source)
    if not class_match: raise ValueError("IntUT candidate has no public test class")
    source = re.sub(r"\b{0}\b".format(re.escape(class_match.group(1))), test_class_name, source)
    source = _insert_imports(source, ("import org.junit.Test;", "import static org.junit.Assert.*;"))
    if not re.search(r"@(?:org\.junit\.)?Test\b", source): raise ValueError("IntUT candidate has no JUnit test")
    opening = source.find("{", class_match.start())
    closing = _brace_end(source, opening)
    if closing is None: raise ValueError("IntUT candidate has unbalanced class braces")
    return source[:closing].strip() + "\n"


def test_count(source):
    return len(re.findall(r"@(?:org\.junit\.)?Test\b", source or ""))

