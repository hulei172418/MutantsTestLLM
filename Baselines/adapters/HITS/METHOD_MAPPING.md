# HITS Paper–Upstream Implementation–Adapter Mapping

| HITS method element | Paper/public implementation evidence | This adapter | Fidelity note |
|---|---|---|---|
| Static context | The paper requires the focal method, dependent class field/method declarations, called method bodies, and Javadocs; the ChatUniTest project parser provides similar information | `context.py` | Conservatively parses project source; unresolved dependencies are explicitly recorded rather than guessed |
| Semantic method slicing | The paper first generates structured method slices through CoT; ChatUniTest `HITSRunner` invokes `hits_gen_slice.ftl` | `protocol.build_slicing_prompt`, `run_pipeline._generate_slices` | `hits_slicing` outputs strict JSON; format failures allow at most three additional attempts |
| Per-slice test generation | The paper requires one test file per slice; ChatUniTest invokes test generation for each step | `build_slice_test_prompt`, `_validate_slice` | Each slice independently invokes `hits_slice_test` and retains separate artifacts |
| Compilation and execution validation | The paper executes tests in the JVM and repairs non-executable tests; the ChatUniTest Validator both compiles and runs them | Common `JavaTestPipeline.compile_and_run` | Both compilation failure and original-program execution failure can trigger native repair |
| Self-debugging repair | The paper feeds a broken test and JVM report back to the model for up to 10 rounds; ChatUniTest defaults to 5 | `hits_test_repair`, `MAX_REPAIR_ROUNDS=10` | The paper takes precedence, so each slice permits up to 10 rounds |
| Merge executable tests | The paper collects executable tests from all slices into a suite | `merge_slice_suites` | Only slices that pass on the original program are merged; the whole suite is compiled and executed again |
| Rule-based postprocessing | The paper mentions rule processing for braces, useless validation, imports, and related issues | `normalize_test_source` | Only deterministic syntax/framework normalization is performed; no extra LLM is used |
| Coverage | The paper reports line/branch coverage | Common JaCoCo collector | Common coverage reports are persisted; coverage neither affects suite validity nor triggers extra generation |
| Model | Original paper: `gpt-3.5-turbo-0125` | Common gateway: `deepseek-v4-flash` | Required substitution under the project's unified-model premise; all calls and tokens are traceable |
| Test framework | The paper and public prompts use JUnit 5 | Output is normalized to JUnit 4 | Required for consistency with MutantsTestLLM and the common mutant executor; reported as an adaptation |
| Mutant evaluation | HITS primarily evaluates coverage and does not use this project's MuJava set during generation | Common `mutant_execution.py` | Performed only after final original-program validation; mutation information never enters HITS prompts |

## Parts That Cannot Be Claimed as a Complete Reproduction

1. The original authors' anonymous artifact is unavailable, so the original SlicePromptTest4J cannot be verified file by file.
2. The paper only states that top-p is gradually increased when slice formatting fails and does not publish an exact schedule; this implementation does not invent values.
3. The current implementation relies on evidence from ChatUniTest's public third-party implementation and adapts both the LLM and JUnit version to the unified experimental protocol.

Therefore, the paper should refer to this implementation as `HITS-ChatUniTest-DS` or “a HITS adaptation based on ChatUniTest's public implementation,” not as “running the official HITS repository.”
