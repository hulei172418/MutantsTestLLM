"""Evidence-backed protocol helpers for the Java MuTAP adaptation."""

from __future__ import print_function


BASELINE = "MuTAP-DS-Adapted"
UPSTREAM_COMMIT = "57c38c0c13df2c4d2b5714491e2c67a6d0463928"
MAX_SYNTAX_REPAIR_ROUNDS = 10
DEFAULT_SURVIVOR_SEED = 42

SYSTEM_PROMPT = (
    "You are a Java coding assistant. Always answer with Java JUnit 4 code. "
    "Do not use JUnit 5, mocks, reflection, or reimplement the program under test."
)


def build_initial_prompt(java_context, test_class_name):
    """Adapt the paper's zero-shot `Generate test cases for ...` prompt."""
    return """Generate test cases for the following Java code.
```java
{source}
```

Return one complete compilable JUnit 4 test class named `{test_class}`. Every
test method must have `@Test` and an observable assertion. Return Java code
only.
""".format(source=java_context.strip(), test_class=test_class_name)


def build_syntax_repair_prompt(java_source, compiler_output, test_class_name):
    """Java form of MuTAP's syntax-error correction prompt."""
    return """Fix the syntax or compilation errors in the following Java JUnit 4 code.
Keep the test intent and class name `{test_class}` unchanged. Return the entire
corrected Java test class and nothing else.

```java
{source}
```

Compiler output:
{error}
""".format(
        test_class=test_class_name,
        source=java_source.strip(),
        error=str(compiler_output or "").strip(),
    )


def build_augmentation_prompt(initial_prompt, current_suite, mutant_source):
    """Build one paper-defined augmentation turn for one surviving mutant."""
    return """{initial_prompt}

The previous test class was:
```java
{suite}
```

The previous test class cannot detect the fault in the following code:
```java
{mutant}
```

Provide one or more new JUnit 4 `@Test` methods that detect the fault in the
prior code. Do not return a class declaration and do not repeat existing test
methods. Return Java code only.
""".format(
        initial_prompt=initial_prompt.strip(),
        suite=current_suite.strip(),
        mutant=mutant_source.strip(),
    )

