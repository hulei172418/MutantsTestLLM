# EvoSuite Method Mapping

| Protocol step | Local implementation | Evidence |
|---|---|---|
| Select the first N unique methods and retain all mutants | `target_context.load_method_groups` | `batch_metadata.json` |
| Deduplicate by project and class | `pipeline.run_batch` | `class_generations/` |
| EvoSuite whole-suite generation | `pipeline.generate_class` | `generation.json`, `generator.log` |
| Fix search budget, suite size, and seed | `evosuite.properties`, CLI | `generation.json.command` |
| Preserve raw and non-destructive clean audit snapshots | `source_cleaner.clean_tree` | `raw_tests/`, `clean_tests/`, `cleaning_audit.json`, `cleaning.diff` |
| Multi-file compilation and original-program execution | `JavaTestPipeline.compile_and_run_suite` | Project-local `rounds/` and `final/` |
| Execute all physical mutants at method level | `mutant_execution.execute_mutants` | `mutant_execution/` |
| Summarize MS, time, counts, and zero tokens | Common summary modules | `mutation_batch_summary.json`, `efficiency_summary.json` |

This implementation does not copy the old `EvoSuiteMutantKillDetailCollector`'s second mutant-kill logic. The old Java files are used only to understand generation, cleaning, and execution boundaries. Formal classification fully reuses the common executor used by every baseline.
