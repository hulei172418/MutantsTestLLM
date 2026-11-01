"""Paper-guided PAINT/SSM-DFS approximation for the IntUT reimplementation.

The original bytecode CFG builder and CodeBERT mock-classifier weights are not
public.  This module keeps their observable contract (one condition chain and
one explicit intention per branch) while recording every approximation.
"""

from __future__ import print_function

import re


CONTROL = {"if", "for", "while", "switch", "catch", "return", "throw", "new"}


def _strip_comments(source):
    return re.sub(r"/\*[\s\S]*?\*/|//[^\r\n]*", "", str(source or ""))


def _balanced(source, opening, left="(", right=")"):
    depth = 0
    quote = None
    escaped = False
    for index in range(opening, len(source)):
        char = source[index]
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
        elif char in ('"', "'"):
            quote = char
        elif char == left:
            depth += 1
        elif char == right:
            depth -= 1
            if depth == 0:
                return source[opening + 1:index], index
    return "", -1


def _parameter_names(declaration):
    if "(" not in declaration:
        return []
    values, _ = _balanced(declaration, declaration.find("("))
    result = []
    for raw in values.split(","):
        item = re.sub(r"@[\w.]+(?:\([^)]*\))?", "", raw).strip()
        tokens = item.replace("...", " ").split()
        if tokens:
            result.append(tokens[-1].replace("[]", ""))
    return result


def _calls(expression):
    result = []
    for qualifier, name in re.findall(r"(?:(\b[A-Za-z_$][\w$\.]*)\s*\.)?\s*([A-Za-z_$][\w$]*)\s*\(", expression):
        if name not in CONTROL:
            value = qualifier + "." + name if qualifier else name
            if value not in result:
                result.append(value)
    return result


def _branch_sites(source):
    """Extract branch sites and lexical nesting without pretending to be a CFG."""
    clean = _strip_comments(source)
    sites = []
    for match in re.finditer(r"\b(if|while|for|switch|catch)\s*\(", clean):
        kind = match.group(1)
        expression, closing = _balanced(clean, clean.find("(", match.start()))
        if closing < 0:
            continue
        depth = clean.count("{", 0, match.start()) - clean.count("}", 0, match.start())
        line = clean.count("\n", 0, match.start()) + 1
        sites.append({
            "kind": kind,
            "condition": " ".join(expression.split()),
            "line": line,
            "brace_depth": depth,
            "offset": match.start(),
            "end": closing + 1,
        })
    for match in re.finditer(r"\bcase\s+([^:]+):|\bdefault\s*:", clean):
        depth = clean.count("{", 0, match.start()) - clean.count("}", 0, match.start())
        sites.append({
            "kind": "case", "condition": "case " + (match.group(1).strip() if match.group(1) else "default"),
            "line": clean.count("\n", 0, match.start()) + 1,
            "brace_depth": depth, "offset": match.start(), "end": match.end(),
        })
    return sorted(sites, key=lambda item: item["offset"])


def _outer_conditions(sites, index):
    current = sites[index]
    parents = []
    for prior in sites[:index]:
        if prior["kind"] not in ("if", "switch"):
            continue
        if prior["brace_depth"] < current["brace_depth"]:
            while parents and parents[-1]["brace_depth"] >= prior["brace_depth"]:
                parents.pop()
            parents.append(prior)
    return parents


def _mock_required(call, historical_mock_calls):
    simple = call.rsplit(".", 1)[-1]
    return any(simple == value.rsplit(".", 1)[-1] for value in historical_mock_calls)


def _expected_hint(source, site):
    tail = source[site["end"]:site["end"] + 600]
    found = re.search(r"\breturn\s+([^;]+);", tail)
    if found:
        return "Assert the observable result corresponding to return expression: {0}".format(found.group(1).strip())
    if site["kind"] == "catch":
        return "Assert the observable behavior of the exception-handling branch"
    return "Assert the focal method's observable result or state for this branch"


def _intention(case_name, chain, parameters, historical_mock_calls, expected):
    input_lines = []
    mock_lines = []
    for condition in chain:
        expression = condition["condition"]
        desired = condition["desired"]
        mentioned = [name for name in parameters if re.search(r"\b" + re.escape(name) + r"\b", expression)]
        if mentioned:
            input_lines.append("Set {0} so that `{1}` evaluates to {2}".format(", ".join(mentioned), expression, desired))
        for call in _calls(expression):
            if _mock_required(call, historical_mock_calls):
                mock_lines.append("Mock `{0}` so that `{1}` evaluates to {2}".format(call, expression, desired))
    if not input_lines:
        input_lines.append("Initialize every focal-method parameter consistently with the condition chain")
    if not mock_lines:
        mock_lines.append("No mock is required by project-history evidence; do not invent one")
    lines = ["Case name: " + case_name, "Input parameter(s):"]
    lines.extend("({0}) {1}".format(index + 1, value) for index, value in enumerate(input_lines))
    lines.append("Mock condition(s):")
    lines.extend("({0}) {1}".format(index + 1, value) for index, value in enumerate(mock_lines))
    lines.extend(["Hint specification:", "(1) Keep the test isolated, repeatable, self-validating, and focused on this branch", "Expected result: " + expected])
    return "\n".join(lines)


def build_intentions(target_context, rag, settings):
    source = target_context.get("method_body") or ""
    declaration = target_context.get("method_declaration") or source.split("{", 1)[0]
    parameters = _parameter_names(declaration)
    mock_calls = list(rag.get("historical_mock_calls") or [])
    sites = _branch_sites(source)
    intentions = []
    for index, site in enumerate(sites):
        parents = _outer_conditions(sites, index)
        parent_chain = [{"condition": value["condition"], "desired": "true", "line": value["line"]} for value in parents]
        desired_values = ["true", "false"] if site["kind"] in ("if", "while", "for") else ["reached"]
        for desired in desired_values:
            chain = parent_chain + [{"condition": site["condition"], "desired": desired, "line": site["line"]}]
            case_name = "{0}_{1}_{2}".format(target_context["method_name"], site["kind"], len(intentions) + 1)
            intentions.append({
                "id": "intention_{0:03d}".format(len(intentions) + 1),
                "target_kind": site["kind"], "target_line_in_extracted_method": site["line"],
                "condition_chain": chain,
                "test_intention": _intention(case_name, chain, parameters, mock_calls, _expected_hint(source, site)),
            })
    if not intentions:
        chain = [{"condition": "normal method completion", "desired": "reached", "line": 1}]
        intentions.append({
            "id": "intention_001", "target_kind": "straight_line", "target_line_in_extracted_method": 1,
            "condition_chain": chain,
            "test_intention": _intention(target_context["method_name"] + "_normal", chain, parameters, mock_calls, "Assert the focal method's observable result or state"),
        })
    limit = int(settings["max_intentions"])
    truncated = len(intentions) > limit
    return {
        "schema_version": 1,
        "analysis_identity": "PAINT_LEXICAL_CFG_APPROXIMATION_NO_PUBLIC_ENGINE",
        "parameters": parameters,
        "branch_site_count": len(sites),
        "intentions": intentions[:limit],
        "intention_count_before_safety_bound": len(intentions),
        "safety_bound_truncated": truncated,
        "mock_classifier_identity": "PROJECT_HISTORY_EVIDENCE_FALLBACK_NO_PUBLIC_CODEBERT_WEIGHTS",
        "limitations": [
            "The original bytecode CFG and SSM-DFS implementation is unavailable.",
            "The original CodeBERT mock-classifier weights and training data are unavailable.",
            "Lexical nesting preserves nested-condition chains and prunes parallel combinations, but is not bytecode-equivalent.",
        ],
    }

