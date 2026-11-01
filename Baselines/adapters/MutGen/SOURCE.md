# MutGen source identity

- Paper: [Mutation-Guided Unit Test Generation with a Large Language Model](https://arxiv.org/abs/2506.02954), arXiv v8, 2026-04-15.
- Official replication package: [Amocy-Wang/MUTGEN](https://github.com/Amocy-Wang/MUTGEN).
- Frozen local source: `Baselines/sources/MutGen`.
- Frozen commit: `69dd37ddfab16b8c5efd22117dc3d213ab05418d`.
- Local implementation identity: `MutGen-DS-Adapted`.

`sources/MutGen` is immutable evidence. Do not edit it, install dependencies into it, build in it, or run scripts that rewrite its files. All implementation and runtime output belong under `adapters/MutGen`, `runs`, or the subject project's `baselines/MutGen` directory.

## Verified author artifacts

- `HumanEval-Java_fromscratch/iterrun.sh`: fixes the experiment to four iterations.
- `HumanEval-Java_fromscratch/runeach.sh`: prepares prior tests, runs mutation reporting, calls Mutahunter, compiles, executes PITest, and carries tests into the next iteration.
- `scripts/extractAndremoveComments.py`: calls the LLM for a 1--3 sentence summary, removes comments, and rewrites an installed prompt template.
- `scripts/noplan_parsePITestReport.py`: extracts `SURVIVED` and `NO_COVERAGE` PITest entries and asks the LLM for mutated code, but its invocation is commented out in the frozen `runeach.sh`.
- `mutahunter/core/unit_test_gen.py`: generates tests, validates them, and attempts at most one repair for each failed test.
- `mutahunter/core/templates/test_generation/`: JUnit generation and compile/assert repair templates.

## Paper/artifact conflicts

The paper defines mutation feedback as mutant id, mutated statement, status, and operator; it also says generation is from scratch. The frozen shell artifact requires an existing `_ESTest.java`, carries tests between rounds, comments out the mutation-report injection call, and mutates both the target source and installed `site-packages` prompt templates. Therefore the shell script is not executed as the formal implementation.

The adapter follows the paper's semantic protocol and uses the author artifact only as evidence for prompt structure, JUnit 4, one repair attempt, temperature 0, and four rounds. PITest is replaced by the common MuJava snapshot interface so MutGen operates on the same mutants as MutantsTestLLM.
