# MuTAP Method Mapping

| Original method stage | Local implementation | Source/decision |
|---|---|---|
| Zero-shot initial prompt | `protocol.build_initial_prompt` / `mutap_initial` | PAPER + AUTHOR_CODE; adapted from Python to Java/JUnit 4 |
| Syntax fixer | `run_pipeline._compile_with_syntax_repair` / `mutap_syntax_repair` | PAPER takes precedence; at most 10 attempts using real `javac` feedback |
| IntendedBehaviorFixer | `run_pipeline._semantic_filter` | JAVA_REQUIRED_ADAPTATION; no LLM call, and test methods that fail on the original program are deterministically removed |
| Mutation testing | `common/mutant_execution.py` | EVALUATION_COMMON; the same physical MuJava mutant set, with an independent JVM |
| Select survivor | `run_pipeline.run_pipeline` | PAPER; seeded random selection, one at a time, without replacement |
| Augmented prompt | `build_augmentation_prompt` / `mutap_survivor_augmentation` | PAPER; exposes the complete source of only the current surviving mutant |
| Accumulate and rerun | `render_methods` + intermediate `execute_mutants` | PAPER; recalculates survivors after every accepted addition |
| Oracle minimization | `_minimize` | PAPER; sorts by per-test kill count and retains a test only when it expands the killed set |
| Final score | Common batch summarizer | EVALUATION_COMMON; End-to-End MS is primary and Executable MS is diagnostic |

## Required Java Adaptation

The author implementation only supports single-line Python `assert` statements, allowing it to directly execute expressions and replace the oracle with PUT output. General Java/JUnit cannot safely recover an evaluated expression from an arbitrary assertion. Therefore, the current adapter validates each test method on the original program, retains passing methods, and removes failing methods. It neither sends runtime errors to the LLM nor fabricates expected values. This difference must be marked as `JAVA_REQUIRED_ADAPTATION` in experimental reports.
