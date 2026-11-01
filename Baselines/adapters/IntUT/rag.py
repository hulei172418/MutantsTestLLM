"""Build IntUT-style project-local unit-test and mock examples."""

from __future__ import print_function

import re
from pathlib import Path


def _tokens(source):
    return set(value.lower() for value in re.findall(r"[A-Za-z_$][\w$]*", str(source or "")))


def _jaccard(left, right):
    union = left.union(right)
    return float(len(left.intersection(right))) / len(union) if union else 0.0


def _test_methods(source):
    values = []
    for marker in re.finditer(r"@(?:org\.junit\.)?Test\b", source):
        opening = source.find("{", marker.end())
        if opening < 0:
            continue
        depth = 0
        end = None
        for index in range(opening, len(source)):
            if source[index] == "{": depth += 1
            elif source[index] == "}":
                depth -= 1
                if depth == 0:
                    end = index + 1
                    break
        if end:
            values.append(source[marker.start():end])
    return values


def _java_methods(source):
    """Return best-effort method bodies for building historical focal-test pairs."""
    values = []
    pattern = re.compile(
        r"(?m)^\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\s+)+"
        r"[A-Za-z_$][\w$<>,.?\[\] ]*\s+([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*(?:throws\s+[^\{]+)?\{"
    )
    for match in pattern.finditer(source):
        opening = source.find("{", match.start())
        depth = 0
        end = None
        for index in range(opening, len(source)):
            if source[index] == "{": depth += 1
            elif source[index] == "}":
                depth -= 1
                if depth == 0:
                    end = index + 1
                    break
        if end:
            values.append({"name": match.group(1), "code": source[match.start():end]})
    return values


def _excluded(path, project_root):
    parts = set(part.lower() for part in path.relative_to(project_root).parts[:-1])
    return bool(parts.intersection({".git", "target", "build", "dist", "result", "baselines", "node_modules"}))


def _invoked_names(source):
    controls = {"if", "for", "while", "switch", "catch", "return", "throw", "new", "assert"}
    return set(name for name in re.findall(r"\b([A-Za-z_$][\w$]*)\s*\(", source or "") if name not in controls)


def build_rag(project_root, target_context, settings):
    project_root = Path(project_root).resolve()
    focal_tokens = _tokens(target_context.get("method_body"))
    focal_name = target_context["method_name"]
    test_candidates = []
    mock_examples = []
    historical_mock_calls = []
    production_by_name = {}
    test_methods = []
    for path in project_root.rglob("*.java"):
        normalized = str(path).replace("\\", "/").lower()
        if _excluded(path, project_root):
            continue
        try:
            source = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        is_test = "/test/" in normalized or path.stem.lower().endswith(("test", "tests"))
        if is_test:
            test_methods.extend({"path": str(path), "code": method} for method in _test_methods(source))
        else:
            for method in _java_methods(source):
                # The paper retrieves historical pairs. Excluding the exact focal
                # body prevents its existing tests from becoming answer leakage.
                if " ".join(method["code"].split()) == " ".join(str(target_context.get("method_body") or "").split()):
                    continue
                production_by_name.setdefault(method["name"], []).append({"path": str(path), "code": method["code"]})
    focal_calls = _invoked_names(target_context.get("method_body") or "")
    for test in test_methods:
        invoked = _invoked_names(test["code"])
        pair_scores = []
        for name in invoked:
            for historical_focal in production_by_name.get(name, []):
                pair_scores.append((_jaccard(focal_tokens, _tokens(historical_focal["code"])), historical_focal))
        if pair_scores:
            score, historical = max(pair_scores, key=lambda item: item[0])
            if score >= float(settings["rag_jaccard_threshold"]):
                test_candidates.append({"path": test["path"], "historical_focal_path": historical["path"], "jaccard": score, "code": test["code"]})
        if focal_calls.intersection(invoked) and re.search(r"\b(?:mock|when|doReturn|doThrow|mockStatic)\s*\(", test["code"]):
            mock_examples.append(test)
            historical_mock_calls.extend(re.findall(r"\bwhen\s*\(\s*(?:[A-Za-z_$][\w$]*\.)*([A-Za-z_$][\w$]*)\s*\(", test["code"]))
    test_candidates.sort(key=lambda item: (-item["jaccard"], item["path"]))
    test_candidates = test_candidates[:int(settings["rag_max_test_examples"])]
    mock_examples = mock_examples[:int(settings["rag_max_mock_examples"])]
    return {
        "schema_version": 1,
        "policy": "dynamic_target_project_RAG; Jaccard threshold 0.8; no cross-project examples",
        "test_examples": test_candidates,
        "mock_examples": mock_examples,
        "historical_mock_calls": sorted(set(historical_mock_calls)),
        "limitations": "Public IntUT focal-test index builder is unavailable; historical focal-test pairs are reconstructed by matching test invocations to same-name production methods.",
    }

