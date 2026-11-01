"""Build the static focal/dependency context required by HITS.

The immutable ChatUniTest implementation obtains this information from its
project parser.  This adapter uses the already-built Baselines JavaParser focal
context and a conservative source resolver for invoked same-project methods.
Unresolved dependencies remain explicit instead of being invented.
"""

from __future__ import print_function

import re
from pathlib import Path

from protocol import _brace_end


CONTROL_WORDS = {
    "if", "for", "while", "switch", "catch", "return", "throw", "new",
    "super", "this", "synchronized", "assert",
}


def _invoked_names(method_source):
    names = []
    for match in re.finditer(r"\b([A-Za-z_$][\w$]*)\s*\(", method_source or ""):
        name = match.group(1)
        if name not in CONTROL_WORDS and name not in names:
            names.append(name)
    return names


def _with_leading_javadoc(source, start):
    prefix = source[:start]
    end = len(prefix.rstrip())
    if end < 2 or prefix[max(0, end - 2) : end] != "*/":
        return start
    opening = prefix.rfind("/**", 0, end)
    if opening < 0:
        return start
    between = prefix[opening:end]
    return opening if "*/" in between else start


def _extract_methods(source, names):
    values = []
    seen = set()
    for name in names:
        pattern = re.compile(
            r"(?m)^\s*(?:@[\w.]+(?:\([^\n]*\))?\s*)*"
            r"(?:(?:public|protected|private|static|final|abstract|synchronized|native|default)\s+)*"
            r"[A-Za-z_$][\w$<>,.?\[\] ]*\s+"
            + re.escape(name)
            + r"\s*\([^;{}]*\)\s*(?:throws\s+[^\{]+)?\{"
        )
        for match in pattern.finditer(source):
            opening = source.find("{", match.start(), match.end())
            end = _brace_end(source, opening)
            if end is None:
                continue
            start = _with_leading_javadoc(source, match.start())
            code = source[start:end].strip()
            identity = (name, code)
            if identity not in seen:
                seen.add(identity)
                values.append({"identity": name, "source": code})
    return values


def _source_package(source):
    match = re.search(r"(?m)^\s*package\s+([\w.]+)\s*;", source)
    return match.group(1) if match else ""


def _source_class_declaration(source, simple_name):
    match = re.search(
        r"(?m)^\s*(?:(?:public|protected|private|abstract|final|static|strictfp)\s+)*"
        r"(?:class|interface|enum)\s+"
        + re.escape(simple_name)
        + r"\b[^\{]*\{",
        source,
    )
    return match.group(0).strip() if match else "class {0} {{".format(simple_name)


def _locate_imported_source(project_root, fqn):
    simple = fqn.rsplit(".", 1)[-1]
    expected_package = fqn.rsplit(".", 1)[0] if "." in fqn else ""
    for candidate in Path(project_root).rglob(simple + ".java"):
        try:
            text = candidate.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if _source_package(text) == expected_package:
            return candidate, text
    return None, None


def _class_summary(source, simple_name):
    declaration = re.search(
        r"(?m)^\s*(?:public\s+)?(?:abstract\s+|final\s+)?(?:class|interface|enum)\s+"
        + re.escape(simple_name)
        + r"[^\{]*\{",
        source,
    )
    declaration_text = declaration.group(0).strip() if declaration else simple_name
    fields = re.findall(
        r"(?m)^\s*(?:public|protected)\s+(?:static\s+|final\s+)*"
        r"[A-Za-z_$][\w$<>,.?\[\] ]+\s+[A-Za-z_$][\w$]*\s*(?:=[^;]*)?;",
        source,
    )
    methods = re.findall(
        r"(?m)^\s*(?:public|protected)\s+(?:static\s+|final\s+|abstract\s+|synchronized\s+)*"
        r"[A-Za-z_$][\w$<>,.?\[\] ]+\s+[A-Za-z_$][\w$]*\s*\([^;{}]*\)"
        r"\s*(?:throws\s+[^\{;]+)?[;\{]",
        source,
    )
    parts = [declaration_text]
    parts.extend(value.strip() for value in fields)
    parts.extend(value.strip().rstrip("{").strip() + ";" for value in methods)
    return "\n".join(parts)


def build_hits_context(target_context, project_root):
    source_path = Path(target_context["source_path"]).resolve()
    source = source_path.read_text(encoding="utf-8", errors="replace")
    focal_method = target_context.get("method_body") or ""
    focal_name = target_context.get("method_name")
    invoked = [name for name in _invoked_names(focal_method) if name != focal_name]
    same_class = _extract_methods(source, invoked)

    imports = {}
    for raw in target_context.get("imports") or []:
        match = re.match(r"\s*import\s+(?:static\s+)?([\w.]+)(?:\.\*)?\s*;", raw)
        if not match or raw.strip().startswith("import static") or raw.strip().endswith(".*;"):
            continue
        fqn = match.group(1)
        imports[fqn.rsplit(".", 1)[-1]] = fqn

    dependent_classes = []
    dependent_methods = list(same_class)
    unresolved = []
    for simple_name, fqn in sorted(imports.items()):
        if not re.search(r"\b{0}\b".format(re.escape(simple_name)), focal_method):
            continue
        if fqn.startswith(("java.", "javax.", "org.junit.", "org.mockito.")):
            unresolved.append(fqn + " (library/JDK source intentionally not searched)")
            continue
        path, dependency_source = _locate_imported_source(project_root, fqn)
        if dependency_source is None:
            unresolved.append(fqn)
            continue
        dependent_classes.append(
            {
                "class_name": fqn,
                "source_path": str(path.resolve()),
                "summary": _class_summary(dependency_source, simple_name),
            }
        )
        for method in _extract_methods(dependency_source, invoked):
            method["identity"] = fqn + "#" + method["identity"]
            dependent_methods.append(method)

    focal_parts = []
    package_name = target_context.get("package_name") or ""
    if package_name:
        focal_parts.append("package {0};".format(package_name))
    focal_parts.extend(target_context.get("imports") or [])
    focal_parts.append(
        _source_class_declaration(source, target_context["simple_class_name"])
    )
    focal_parts.extend(target_context.get("fields") or [])
    focal_parts.extend(target_context.get("constructors") or [])
    method_signatures = target_context.get("all_method_signatures") or []
    if method_signatures:
        focal_parts.append(
            "/* Available method declarations:\n{0}\n*/".format(
                "\n".join(str(value) + ";" for value in method_signatures)
            )
        )
    focal_parts.append(focal_method)
    focal_parts.append("}")
    return {
        "schema_version": 1,
        "class_name": target_context["class_name"],
        "simple_class_name": target_context["simple_class_name"],
        "method_name": target_context["method_name"],
        "method_signature": target_context["method_signature"],
        "package_name": package_name,
        "focal_context": "\n".join(value for value in focal_parts if str(value).strip()),
        "invoked_method_names": invoked,
        "dependent_classes": dependent_classes,
        "dependent_methods": dependent_methods,
        "unresolved_dependencies": unresolved,
        "context_policy": (
            "ChatUniTest-style focal fields/constructors/method plus source-resolved "
            "invoked same-project method bodies and Javadocs"
        ),
    }
