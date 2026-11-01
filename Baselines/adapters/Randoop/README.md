# Randoop 4.3.4 Control Baseline

This adapter follows Randoop's official 4.3.4 `gentests` protocol and serves as a traditional feedback-directed random-testing control group independent of `MuTestLLM`.

Directory boundary: `run_batch.py` is only a command entry point. Randoop's `gentests` command, Regression/Error filtering, suite aggregation, failure classification, and method-level batch processing are all implemented in this directory's `pipeline.py`. `common` contains no Randoop method implementation; it only provides general Java compilation, original-program validation, mutant execution, and summarization.

Official references: [Randoop 4.3.4 manual](https://randoop.github.io/randoop/manual/) and [GitHub releases](https://github.com/randoop/randoop/releases/tag/v4.3.4).

## Required Artifact

Formal execution must use `lib/randoop-all-4.3.4.jar` from the official distribution (SHA-256 `BC134DCA4A0DF20A4787148923A2F6F4D0ECF8D40D7BBA16B906E8CF0BDF8BAF`).

The repository's existing `lib/randoop-4.3.4.jar` is the core JAR (SHA-256 `C4A6DD0CDCB8C0B12A007002F53C2F38F0C92DBCF8C5F00C614A09B4E1414115`). Launching it alone lacks transitive dependencies such as `org.plumelib.options`; this must not be treated as generation failure and cannot be used in formal experiments. The adapter explicitly rejects configurations without the all-in-one JAR before generation.

## Fixed Protocol

- Inputs select the first N unique methods, and generation is deduplicated by `project + class`;
- The default is 60 seconds per class with random seed 42, matching EvoSuite's wall-clock budget;
- Raw RegressionTest and ErrorTest files are preserved, but formal original-program/mutant evaluation uses only RegressionTest;
- DeepSeek is not invoked, no LLM repair is performed, and token/API call counts are both zero;
- Each selected method uses the same class test suite to execute all of its physical mutants;
- The common multi-file compiler, original-program validator, and process-isolated MuJava executor are used.

## VS Code/F5

Open the `Baselines` folder and select `Randoop A. Full Batch End-to-End (5 Methods)`. Equivalent command:

```powershell
python -B adapters/Randoop/run_batch.py `
  --manifest data/target_manifest.first10.jsonl `
  --limit 5 `
  --batch-dir runs/20260318_randoop_batch_manual_001 `
  --workers 1 `
  --mutant-workers 4 `
  --seed 42
```

Configuration is stored in this directory's `randoop.properties`. The output structure, scores, and timing statistics are fully symmetric with EvoSuite. `raw_tests/` preserves Randoop's original output, while `clean_tests/` currently performs only a byte-for-byte copy. Randoop does not use EvoSuite's runtime/scaffolding cleaning rules.
