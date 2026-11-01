"""Build ASTER's Java static-analysis context without changing production code."""

from __future__ import print_function

import re
from pathlib import Path


CONTROL_WORDS = {
    "if", "for", "while", "switch", "catch", "return", "throw", "new",
    "super", "this", "synchronized", "assert",
}


def _signature(source):
    text = str(source or "").strip()
    opening = text.find("{")
    text = (text[:opening] if opening >= 0 else text).strip()
    text = re.sub(r"/\*[\s\S]*?\*/|//[^\r\n]*", "", text)
    return " ".join(text.split())


def _type_name(value):
    text = re.sub(r"@[\w.]+(?:\([^)]*\))?\s*", "", str(value or ""))
    text = re.sub(r"\b(?:final|volatile|transient)\b", "", text)
    text = re.sub(r"<[^<>]*>", "", text)
    text = text.replace("...", "").replace("[]", "").strip()
    return text.split(".")[-1].strip()


def _method_types(signature):
    text = _signature(signature)
    before = text.split("(", 1)[0].strip()
    tokens = before.split()
    return_type = tokens[-2] if len(tokens) >= 2 else ""
    parameters = text.split("(", 1)[1].rsplit(")", 1)[0] if "(" in text else ""
    parameter_types = []
    for item in parameters.split(","):
        parts = item.strip().split()
        if len(parts) >= 2:
            parameter_types.append(_type_name(" ".join(parts[:-1])))
    return _type_name(return_type), [value for value in parameter_types if value]


def _source_package(source):
    match = re.search(r"(?m)^\s*package\s+([\w.]+)\s*;", source)
    return match.group(1) if match else ""


def _class_declaration(source, simple_name):
    match = re.search(
        r"(?m)^\s*(?:(?:public|protected|private|abstract|final|static|strictfp)\s+)*"
        r"(?:class|interface|enum)\s+" + re.escape(simple_name) + r"\b[^\{]*\{",
        source,
    )
    return match.group(0).strip() if match else "public class {0} {{".format(simple_name)


def _imports_map(imports):
    values = {}
    for raw in imports or []:
        match = re.match(r"\s*import\s+(?!static)([\w.]+)\s*;", str(raw))
        if match:
            fqn = match.group(1)
            values[fqn.rsplit(".", 1)[-1]] = fqn
    return values


def _source_roots(project_root, source_path):
    """Return bounded Java source roots without traversing experiment output."""
    project_root = Path(project_root).resolve()
    source_path = Path(source_path).resolve()
    candidates = [source_path.parent]
    for parent in source_path.parents:
        if (
            parent.name == "java"
            and parent.parent.name == "main"
            and parent.parent.parent.name == "src"
        ):
            candidates.insert(0, parent)
            break
        if parent.name == "src":
            candidates.append(parent)
            break
    candidates.append(project_root / "src" / "main" / "java")
    try:
        children = list(project_root.iterdir())
    except OSError:
        children = []
    candidates.extend(
        child / "src" / "main" / "java" for child in children if child.is_dir()
    )
    roots = []
    seen = set()
    for candidate in candidates:
        try:
            resolved = candidate.resolve()
        except OSError:
            continue
        key = str(resolved).lower()
        if key not in seen and resolved.is_dir():
            seen.add(key)
            roots.append(resolved)
    return roots


def _find_source(source_roots, simple_name, imports, package_name):
    if not simple_name or simple_name in {
        "void", "boolean", "byte", "short", "int", "long", "float", "double",
        "char", "String", "Object", "Class",
    }:
        return None
    expected = imports.get(simple_name)
    for source_root in source_roots:
        try:
            candidates = source_root.rglob(simple_name + ".java")
            for candidate in candidates:
                try:
                    source = candidate.read_text(encoding="utf-8", errors="replace")
                except OSError:
                    continue
                candidate_package = _source_package(source)
                if expected and expected == candidate_package + "." + simple_name:
                    return candidate, source
                if not expected and candidate_package == package_name:
                    return candidate, source
        except OSError:
            continue
    return None


def _declarations(source, class_name):
    constructors = re.findall(
        r"(?m)^\s*(?:public|protected|private)?\s*" + re.escape(class_name)
        + r"\s*\([^;{}]*\)\s*(?:throws\s+[^\{]+)?\{",
        source,
    )
    methods = re.findall(
        r"(?m)^\s*(?:(?:public|protected|private|static|final|abstract|synchronized|default)\s+)+"
        r"[A-Za-z_$][\w$<>,.?\[\] ]*\s+[A-Za-z_$][\w$]*\s*\([^;{}]*\)"
        r"\s*(?:throws\s+[^\{;]+)?[\{;]",
        source,
    )
    return ([_signature(value) for value in constructors], [_signature(value) for value in methods])


def _accessors(methods, prefixes):
    result = []
    for method in methods:
        name_match = re.search(r"\b([A-Za-z_$][\w$]*)\s*\(", method)
        if name_match and name_match.group(1).startswith(prefixes):
            result.append(method)
    return result


def _invoked_names(source):
    values = []
    for name in re.findall(r"\b([A-Za-z_$][\w$]*)\s*\(", source or ""):
        if name not in CONTROL_WORDS and name not in values:
            values.append(name)
    return values


def _private_call_chains(class_source, focal_name, focal_body):
    private_names = set(re.findall(
        r"(?m)^\s*private\s+(?:static\s+|final\s+|synchronized\s+)*"
        r"[A-Za-z_$][\w$<>,.?\[\] ]*\s+([A-Za-z_$][\w$]*)\s*\(",
        class_source,
    ))
    direct = [name for name in _invoked_names(focal_body) if name in private_names]
    return ["{0} -> {1}".format(focal_name, name) for name in direct]


def _field_type(field):
    text = re.sub(r"/\*[\s\S]*?\*/|//[^\r\n]*", "", str(field or ""))
    match = re.search(
        r"(?:public|protected|private)?\s*(?:static\s+|final\s+|volatile\s+|transient\s+)*"
        r"([A-Za-z_$][\w$<>,.?\[\]]*)\s+[A-Za-z_$][\w$]*",
        text,
    )
    return _type_name(match.group(1)) if match else ""


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


def _line_range(source, method_body, method_name):
    normalized = str(method_body or "").replace("\r\n", "\n")
    full = str(source or "").replace("\r\n", "\n")
    at = full.find(normalized)
    if at < 0:
        declaration = normalized.split("{", 1)[0].strip()
        at = full.find(declaration)
    if at < 0:
        declaration_match = re.search(
            r"(?m)^\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default)\s+)+"
            r"[^;{}\n]*\b" + re.escape(method_name) + r"\s*\(",
            full,
        )
        if not declaration_match:
            return []
        at = declaration_match.start()
        opening = full.find("{", declaration_match.end())
        end = _brace_end(full, opening) if opening >= 0 else None
        if end is None:
            return []
        start_line = full.count("\n", 0, at) + 1
        end_line = full.count("\n", 0, end) + 1
        return list(range(start_line, end_line + 1))
    start = full.count("\n", 0, at) + 1
    count = max(1, normalized.count("\n") + 1)
    return list(range(start, start + count))


def build_aster_context(target_context, project_root, settings):
    source_path = Path(target_context["source_path"]).resolve()
    source_roots = _source_roots(project_root, source_path)
    class_source = source_path.read_text(encoding="utf-8", errors="replace")
    imports = _imports_map(target_context.get("imports"))
    package_name = target_context.get("package_name") or ""
    return_type, parameter_types = _method_types(
        target_context.get("method_declaration") or target_context.get("method_body")
    )
    constructors = [_signature(value) for value in target_context.get("constructors") or []]
    focal_methods = [_signature(value) for value in target_context.get("all_method_signatures") or []]
    setters = _accessors(focal_methods, ("set",))
    getters = _accessors(focal_methods, ("get", "is"))

    application_types = []
    queue = [(value, 0) for value in parameter_types + ([return_type] if return_type else [])]
    seen = set()
    max_depth = int(settings.get("max_transitive_constructor_depth", 2))
    max_types = int(settings.get("max_context_types", 20))
    while queue and len(application_types) < max_types:
        simple_name, depth = queue.pop(0)
        if simple_name in seen:
            continue
        seen.add(simple_name)
        found = _find_source(source_roots, simple_name, imports, package_name)
        if not found:
            continue
        path, dependency_source = found
        dependency_constructors, dependency_methods = _declarations(dependency_source, simple_name)
        item = {
            "type": simple_name,
            "source_path": str(path.resolve()),
            "constructors": dependency_constructors,
            "setters": _accessors(dependency_methods, ("set",)),
            "getters": _accessors(dependency_methods, ("get", "is")),
        }
        application_types.append(item)
        constructors.extend(dependency_constructors)
        if simple_name in parameter_types:
            setters.extend(item["setters"])
        if simple_name == return_type:
            getters.extend(item["getters"])
        if depth < max_depth:
            for constructor in dependency_constructors:
                _, nested = _method_types(constructor)
                queue.extend((value, depth + 1) for value in nested)

    prefixes = tuple(value.strip() for value in str(settings.get("mockable_api_prefixes") or "").split(",") if value.strip())
    mock_fields = []
    if prefixes:
        for field in target_context.get("fields") or []:
            field_type = _field_type(field)
            fqn = imports.get(field_type, (package_name + "." + field_type).strip("."))
            if fqn.startswith(prefixes):
                mock_fields.append({"type": field_type, "fqn": fqn, "declaration": str(field).strip()})

    focal_method = target_context.get("method_body") or ""
    method_lines = _line_range(class_source, focal_method, target_context["method_name"])
    source_lines = class_source.replace("\r\n", "\n").splitlines()
    return {
        "schema_version": 1,
        "class_name": target_context["class_name"],
        "simple_class_name": target_context["simple_class_name"],
        "method_name": target_context["method_name"],
        "method_signature": target_context["method_signature"],
        "package_name": package_name,
        "imports": list(target_context.get("imports") or []),
        "class_declaration": _class_declaration(class_source, target_context["simple_class_name"]),
        "focal_method": focal_method,
        "focal_method_lines": method_lines,
        "focal_method_numbered_source": "\n".join(
            "{0}: {1}".format(number, source_lines[number - 1])
            for number in method_lines if 0 < number <= len(source_lines)
        ),
        "relevant_constructors": list(dict.fromkeys(value for value in constructors if value)),
        "setter_signatures": list(dict.fromkeys(setters)),
        "getter_signatures": list(dict.fromkeys(getters)),
        "private_call_chains": _private_call_chains(class_source, target_context["method_name"], focal_method),
        "application_types": application_types,
        "mockable_api_prefixes": list(prefixes),
        "mock_fields": mock_fields,
        "context_policy": "ASTER paper sections 3.1: focal method/class, transitive relevant constructors, setters, getters, private call chains, and user-specified mockable fields",
    }
