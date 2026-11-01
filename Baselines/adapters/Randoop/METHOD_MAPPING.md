# Randoop Method Mapping

| Official `gentests` step | Local implementation | Evidence |
|---|---|---|
| Specify the target class and project classpath | `pipeline.generate_class` | `generation.json.command` |
| Fix `--time-limit` and `--randomseed` | `randoop.properties`, CLI | `generation.json` |
| Generate RegressionTest/ErrorTest | Randoop 4.3.4 all-in-one JAR | `class_generations/*/raw_tests/` |
| Include RegressionTest in formal evaluation | `_copy_and_select_sources` | `cleaning_audit.json.formal_source_files` |
| Retain ErrorTest only for audit | `_copy_and_select_sources` | `raw_tests/` |
| Multi-source compilation and original-program validation | `JavaTestPipeline.compile_and_run_suite` | Project-local `rounds/` and `final/` |
| Execute all physical mutants at method level | `mutant_execution.execute_mutants` | `mutant_execution/` |
| Summarize MS, time, counts, and zero tokens | Common summary modules | `mutation_batch_summary.json`, `efficiency_summary.json` |

Randoop neither uses the EvoSuite Cleaner nor invokes DeepSeek. Missing transitive dependencies in the core JAR constitute a tool-artifact error; the formal entry point accepts only the official `randoop-all-4.3.4.jar`.
