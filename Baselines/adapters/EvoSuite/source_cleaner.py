"""Non-destructive cleanup for Java tests emitted by EvoSuite.

The historical ``EvoSuiteCleaner`` removed EvoSuite runtime references and
then recursively deleted statements depending on those references.  That can
make a test compile without the runtime, but it can also change the generated
test's behaviour and mutation-killing ability.  The Baselines adapter instead
keeps runtime/scaffolding sources and performs only source-compatible cleanup.
"""

from __future__ import print_function

import difflib
import hashlib
import re
from pathlib import Path


_JUNIT5_REPLACEMENTS = (
    ("org.junit.jupiter.api.Assertions", "org.junit.Assert"),
    ("org.junit.jupiter.api.Assumptions", "org.junit.Assume"),
    ("org.junit.jupiter.api.Test", "org.junit.Test"),
    ("org.junit.jupiter.api.BeforeEach", "org.junit.Before"),
    ("org.junit.jupiter.api.AfterEach", "org.junit.After"),
    ("org.junit.jupiter.api.BeforeAll", "org.junit.BeforeClass"),
    ("org.junit.jupiter.api.AfterAll", "org.junit.AfterClass"),
    ("org.junit.jupiter.api.Disabled", "org.junit.Ignore"),
)

_ANNOTATION_REPLACEMENTS = (
    (r"@(?:org\.junit\.jupiter\.api\.)?BeforeEach\b", "@Before"),
    (r"@(?:org\.junit\.jupiter\.api\.)?AfterEach\b", "@After"),
    (r"@(?:org\.junit\.jupiter\.api\.)?BeforeAll\b", "@BeforeClass"),
    (r"@(?:org\.junit\.jupiter\.api\.)?AfterAll\b", "@AfterClass"),
    (r"@(?:org\.junit\.jupiter\.api\.)?Disabled\b", "@Ignore"),
    (r"@org\.junit\.jupiter\.api\.Test\b", "@Test"),
)

_MESSAGE_LAST_ARITY = {
    "assertTrue": {2},
    "assertFalse": {2},
    "assertNull": {2},
    "assertNotNull": {2},
    "assertSame": {3},
    "assertNotSame": {3},
    "assertNotEquals": {3},
    "assertThrows": {3},
}

_ASSERTION_NAMES = set(_MESSAGE_LAST_ARITY).union(
    {"assertEquals", "assertArrayEquals"}
)


def _sha256_text(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _scan_closing_parenthesis(source, opening):
    depth = 0
    state = "code"
    index = opening
    while index < len(source):
        char = source[index]
        nxt = source[index + 1] if index + 1 < len(source) else ""
        if state == "code":
            if char == '"':
                state = "string"
            elif char == "'":
                state = "char"
            elif char == "/" and nxt == "/":
                state = "line_comment"
                index += 1
            elif char == "/" and nxt == "*":
                state = "block_comment"
                index += 1
            elif char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    return index
        elif state in ("string", "char"):
            if char == "\\":
                index += 1
            elif (state == "string" and char == '"') or (
                state == "char" and char == "'"
            ):
                state = "code"
        elif state == "line_comment":
            if char in "\r\n":
                state = "code"
        elif state == "block_comment" and char == "*" and nxt == "/":
            state = "code"
            index += 1
        index += 1
    return -1


def _split_arguments(value):
    arguments = []
    start = 0
    round_depth = square_depth = brace_depth = angle_depth = 0
    state = "code"
    index = 0
    while index < len(value):
        char = value[index]
        nxt = value[index + 1] if index + 1 < len(value) else ""
        if state == "code":
            if char == '"':
                state = "string"
            elif char == "'":
                state = "char"
            elif char == "/" and nxt == "/":
                state = "line_comment"
                index += 1
            elif char == "/" and nxt == "*":
                state = "block_comment"
                index += 1
            elif char == "(":
                round_depth += 1
            elif char == ")":
                round_depth -= 1
            elif char == "[":
                square_depth += 1
            elif char == "]":
                square_depth -= 1
            elif char == "{":
                brace_depth += 1
            elif char == "}":
                brace_depth -= 1
            elif char == "<":
                angle_depth += 1
            elif char == ">" and angle_depth:
                angle_depth -= 1
            elif (
                char == ","
                and round_depth == square_depth == brace_depth == angle_depth == 0
            ):
                arguments.append(value[start:index].strip())
                start = index + 1
        elif state in ("string", "char"):
            if char == "\\":
                index += 1
            elif (state == "string" and char == '"') or (
                state == "char" and char == "'"
            ):
                state = "code"
        elif state == "line_comment":
            if char in "\r\n":
                state = "code"
        elif state == "block_comment" and char == "*" and nxt == "/":
            state = "code"
            index += 1
        index += 1
    tail = value[start:].strip()
    if tail or value.strip():
        arguments.append(tail)
    return arguments


def _looks_like_message(argument):
    value = argument.strip()
    return (
        value.startswith('"')
        or value.startswith("String.format(")
        or value.startswith("() ->")
        or value.endswith("Message")
        or value.endswith("message")
    )


def _plain_message(argument):
    value = argument.strip()
    match = re.match(r"^\(\s*\)\s*->\s*(.+)$", value, re.DOTALL)
    return match.group(1).strip() if match else value


def _should_move_message(name, arguments):
    if len(arguments) in _MESSAGE_LAST_ARITY.get(name, set()):
        return True
    if name in ("assertEquals", "assertArrayEquals"):
        if len(arguments) >= 4:
            return True
        return len(arguments) == 3 and _looks_like_message(arguments[-1])
    return False


def _rewrite_junit5_assertion_arguments(source):
    pattern = re.compile(
        r"(?<![A-Za-z0-9_$])(?P<name>" + "|".join(sorted(_ASSERTION_NAMES)) + r")\s*\("
    )
    changes = 0
    cursor = 0
    pieces = []
    while True:
        match = pattern.search(source, cursor)
        if not match:
            pieces.append(source[cursor:])
            break
        opening = source.find("(", match.start("name") + len(match.group("name")))
        closing = _scan_closing_parenthesis(source, opening)
        if closing < 0:
            pieces.append(source[cursor:])
            break
        arguments = _split_arguments(source[opening + 1 : closing])
        pieces.append(source[cursor : opening + 1])
        if _should_move_message(match.group("name"), arguments):
            arguments = [_plain_message(arguments[-1])] + arguments[:-1]
            changes += 1
        pieces.append(", ".join(arguments))
        pieces.append(")")
        cursor = closing + 1
    return "".join(pieces), changes


def clean_source(source, test_timeout_millis=4000):
    """Return ``(cleaned_source, audit)`` without deleting test semantics."""
    original = source
    transformations = []
    if source.startswith("\ufeff"):
        source = source.lstrip("\ufeff")
        transformations.append("remove_utf8_bom")

    junit5_detected = "org.junit.jupiter" in source
    if junit5_detected:
        for old, new in _JUNIT5_REPLACEMENTS:
            if old in source:
                source = source.replace(old, new)
                transformations.append("replace_{0}_with_{1}".format(old, new))
        for pattern, replacement in _ANNOTATION_REPLACEMENTS:
            source, count = re.subn(pattern, replacement, source)
            if count:
                transformations.append(
                    "replace_annotation_{0}_count_{1}".format(replacement, count)
                )
        source, count = _rewrite_junit5_assertion_arguments(source)
        if count:
            transformations.append("move_junit5_messages_to_junit4_order_count_{0}".format(count))

        # JUnit 4 has no DisplayName annotation; it is descriptive metadata and
        # removing it does not alter the executable test body.
        source, count = re.subn(
            r"(?m)^\s*@(?:org\.junit\.jupiter\.api\.)?DisplayName\s*\([^\r\n]*\)\s*\r?\n",
            "",
            source,
        )
        if count:
            transformations.append("remove_junit5_display_name_count_{0}".format(count))
        source = re.sub(
            r"(?m)^\s*import\s+org\.junit\.jupiter\.api\.DisplayName\s*;\s*\r?\n",
            "",
            source,
        )

    # EvoSuite 1.0.6 normally emits @Test(timeout = 4000). Mutation
    # evaluation uses one isolated JVM per mutant but grants the configured
    # budget to every test method independently, so the formal source must not
    # retain a smaller generated annotation. Raw output remains byte-exact and
    # this policy rewrite is fully recorded in cleaning.diff/audit.
    timeout_pattern = re.compile(
        r"(@(?:org\.junit\.)?Test\s*\([^)]*?\btimeout\s*=\s*)\d+[lL]?"
    )
    timeout_change_count = [0]

    def replace_timeout(match):
        replacement = match.group(1) + str(int(test_timeout_millis))
        if replacement != match.group(0):
            timeout_change_count[0] += 1
        return replacement

    source = timeout_pattern.sub(replace_timeout, source)
    timeout_change_count = timeout_change_count[0]
    if timeout_change_count:
        transformations.append(
            "set_junit_test_timeout_millis_{0}_count_{1}".format(
                int(test_timeout_millis), timeout_change_count
            )
        )

    remaining_junit5 = sorted(set(re.findall(r"org\.junit\.jupiter\.[A-Za-z0-9_.$]+", source)))
    return source, {
        "input_sha256": _sha256_text(original),
        "output_sha256": _sha256_text(source),
        "changed": source != original,
        "junit5_detected": junit5_detected,
        "remaining_junit5_symbols": remaining_junit5,
        "transformations": transformations,
        "configured_test_timeout_millis": int(test_timeout_millis),
        "test_timeout_annotation_change_count": timeout_change_count,
        "semantic_statement_deletion": False,
        "evosuite_runtime_preserved": True,
        "evosuite_scaffolding_preserved": True,
    }


def clean_tree(raw_source, raw_target, clean_target, test_timeout_millis=4000):
    """Copy one EvoSuite source tree and create audited cleaned sources."""
    raw_source = Path(raw_source)
    raw_target = Path(raw_target)
    clean_target = Path(clean_target)
    selected = []
    files = []
    diffs = []
    for source in sorted(raw_source.rglob("*.java")):
        relative = source.relative_to(raw_source)
        raw_copy = raw_target / relative
        clean_copy = clean_target / relative
        raw_copy.parent.mkdir(parents=True, exist_ok=True)
        clean_copy.parent.mkdir(parents=True, exist_ok=True)
        original = source.read_text(encoding="utf-8")
        # Preserve a byte-for-byte raw artifact.  Decoding is used only for the
        # formal cleaned source below.
        raw_copy.write_bytes(source.read_bytes())
        cleaned, audit = clean_source(original, test_timeout_millis)
        clean_copy.write_text(cleaned, encoding="utf-8")
        audit["path"] = str(relative)
        files.append(audit)
        selected.append(clean_copy)
        if original != cleaned:
            diffs.extend(
                difflib.unified_diff(
                    original.splitlines(True),
                    cleaned.splitlines(True),
                    fromfile="raw_tests/" + str(relative),
                    tofile="clean_tests/" + str(relative),
                )
            )
    return selected, files, "".join(diffs)
