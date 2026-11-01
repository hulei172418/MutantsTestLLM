# MutGen method mapping

| Paper step | Verified rule | Local stage/module | Adaptation |
|---|---|---|---|
| Code summarization | Remove comments; summarize purpose and input format in 1--3 sentences | `mutgen_summary`; `protocol.build_summary_prompt` | Llama-3.3 → DeepSeek-V4-Flash |
| Mutation feedback | id, mutated statement, status, operator; focus on live/uncovered | `build_mutation_feedback`, `jacoco_coverage`, `mutation_line_mapping` | PITest report → isolated MuJava snapshots plus exact-class JaCoCo lines |
| Test generation | summary + comment-free source + feedback; JUnit 4 | `mutgen_generation`; `build_generation_prompt` | Common DeepSeek gateway, YAML preserved |
| Execution filter | compile and execute each generated test on original program | common Java pipeline | Project-native JUnit classpath |
| Failure repair | duplicate method name: author mechanical `dedul_` rename; otherwise one LLM invocation for each compilation/assertion failure, then filter again | `author_duplicate_method_fix`, `mutgen_compile_repair`, `mutgen_assert_repair`; `build_repair_prompt` | Actual compiler/JUnit error preserved |
| Accumulation | merge passing new tests into prior working suite | `run_pipeline.py` round runner | Preserve test-to-feedback mapping |
| Iteration | reevaluate mutants and re-invoke generation; maximum four rounds | `run_pipeline.py` round runner | Separate MuJava execution ID and JaCoCo snapshot per round |
| Final comparison | common original validation and per-mutant execution | common mutation executor/scorer | Same End-to-End MS as other Baselines |

Model-visible mutation fields are restricted to `id`, `line`, `operator`, `status`, and `mutated_statement`. `equivalent`, graph paths, final kill answers, and unrelated Excel audit fields are forbidden. Killed mutants are retained in logs but omitted from the next generation prompt.

For each surviving mutant, `LIVE` versus `NO_COVERAGE` is decided from the
mapped current-source line and the accepted suite's exact-class JaCoCo data.
An unresolved mapping remains `LIVE` conservatively and is recorded for audit.

The paper says stop at convergence or an iteration limit, while the evaluation fixes the limit to four after a seven-round preliminary study. Because “significant improvement” has no executable threshold in the paper, Native-budget uses four rounds (or early stops only when no visible live/uncovered mutant remains); it does not invent a numeric convergence threshold.

Every Native-budget iteration uses `use_cache=False` for the local response
cache. This preserves the paper's “LLM is re-invoked” semantics. Cache Read and
Cache Write reported by the DeepSeek provider remain part of token accounting.
