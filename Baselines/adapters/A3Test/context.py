"""Build the focal context used by the A3Test generation translation task."""

from __future__ import print_function

import re


def _signature(source):
    text = str(source or "").strip()
    opening = text.find("{")
    return (text[:opening] if opening >= 0 else text).strip()


def _public_fields(fields):
    return [
        str(value).strip()
        for value in fields or []
        if re.search(r"(?m)^\s*public\b", str(value or ""))
    ]


def build_a3test_context(target_context):
    focal_name = str(target_context.get("method_name") or "")
    constructors = [
        value for value in (_signature(item) for item in target_context.get("constructors") or [])
        if value
    ]
    methods = []
    for value in target_context.get("public_method_signatures") or []:
        text = str(value).strip()
        if text and not re.search(r"\b{0}\s*\(".format(re.escape(focal_name)), text):
            methods.append(text)
    public_fields = _public_fields(target_context.get("fields"))
    parts = [
        "[FOCAL_METHOD]\n{0}".format(target_context.get("method_body") or ""),
        "[FOCAL_CLASS]\n{0}".format(target_context.get("class_name") or ""),
        "[CONSTRUCTOR_SIGNATURES]\n{0}".format("\n".join(constructors) or "<none>"),
        "[OTHER_PUBLIC_METHOD_SIGNATURES]\n{0}".format("\n".join(methods) or "<none>"),
        "[PUBLIC_FIELDS]\n{0}".format("\n".join(public_fields) or "<none>"),
    ]
    return {
        "schema_version": 1,
        "class_name": target_context["class_name"],
        "simple_class_name": target_context["simple_class_name"],
        "method_name": target_context["method_name"],
        "method_signature": target_context["method_signature"],
        "package_name": target_context.get("package_name") or "",
        "imports": list(target_context.get("imports") or []),
        "focal_method": target_context.get("method_body") or "",
        "constructor_signatures": constructors,
        "other_public_method_signatures": methods,
        "public_fields": public_fields,
        "translation_source": "\n\n".join(parts),
        "context_encoding": "fm+fc+c+m+f",
    }
