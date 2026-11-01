# HITS-ChatUniTest-DS

This adapter reproduces HITS's “semantic method slicing—per-slice test generation—compilation/runtime feedback repair—test-suite merging” workflow, then evaluates the same MuJava mutants with the common executor. The LLM backend is uniformly replaced with `deepseek-v4-flash`.

## Reproduction Identity and Boundaries

- Paper: *HITS: High-coverage LLM-based Unit Test Generation via Method Slicing* (ASE 2024; the conference title also uses SlicePromptTest4J).
- The original authors' anonymous artifact is currently unavailable, so the original repository cannot be verified.
- Readable code evidence comes from ChatUniTest's public third-party implementation; see `../../sources/HITS/README.md` for the pinned commit.
- Therefore, the method name is fixed as `HITS-ChatUniTest-DS`; this is not a rerun of the official original code.
- `sources/HITS` is read-only. All adaptation changes are in this directory, and `MuTestLLM` is never modified.

The shared provider, URL, model, API key, network retry, and cache-reading settings come from `../../configs/llm.properties`. HITS-specific slicing, per-slice generation, and repair budgets/temperatures come from the `llm.properties` file in this directory. The common gateway automatically merges both configuration layers by stage; no mode switch is used.

## Actual Workflow

1. Select deduplicated focal methods by `project + class_name + method_signature`, while retaining every source record and physical mutant associated with each method.
2. JavaParser extracts the focal method, class fields/constructors/method declarations, called method bodies in the same class, and resolvable explicit project dependencies. Mutation statements, mutant paths, and equivalent labels are not included in prompts.
3. `hits_slicing` asks the model to output slicing JSON containing `summary`, external variables/methods, and `steps[{desp, code}]`.
4. `hits_slice_test` generates one complete test class for each slice.
5. Each slice test is immediately compiled and executed on the original program. On failure, real compilation or JVM/JUnit feedback is passed to `hits_test_repair`. The paper permits at most 10 repair rounds per slice.
6. Only slice tests that pass on the original program are merged into a common JUnit 4 Suite, which is compiled and executed again.
7. After the merged suite passes, the common isolated executor runs every physical mutant associated with the method. The primary metric is fixed as `End-to-End MS = killed / (killed + live + not_executed_invalid_test)`. `Executable MS = killed / (killed + live)` is only a diagnostic metric for the executed subset and must not replace the overall mutation score.

Recorded differences between the paper and the current evaluation environment: the paper/ChatUniTest prompts target JUnit 5, whereas the unified evaluator uses JUnit 4, so deterministic normalization is applied after model output. The paper says to gradually increase top-p when the slice format is invalid but provides no numeric schedule; the current implementation follows fixed ChatUniTest behavior, retries at most three additional times, keeps temperature=0, and does not invent a top-p schedule. ChatUniTest defaults to five repair rounds, while the paper explicitly specifies ten, so the paper's value of ten is used.

## Manual VS Code Execution

Open the `Baselines` folder in VS Code.

Run a single target in two steps:

1. Press F5 and select `HITS 1. Prepare Manual Target`; enter a new run directory that does not yet exist.
2. Press F5 and select `HITS 2. Run Prepared Target End-to-End`; enter exactly the same directory.

For a batch experiment on five deduplicated methods, press F5 and select `HITS A. Full Batch End-to-End (5 Methods)`. It retains all mutant records associated with those five methods, generates tests concurrently, and executes mutants concurrently for each suite that passes original-program validation.

Equivalent command-line form:

```powershell
python -B adapters/HITS/prepare_run.py `
  --manifest data/target_manifest.first10.jsonl `
  --run-dir runs/20260318_hits_manual_001

python -B adapters/HITS/run_pipeline.py `
  --run-dir runs/20260318_hits_manual_001 `
  --mutant-workers 4

python -B adapters/HITS/run_batch.py `
  --manifest data/target_manifest.first10.jsonl `
  --limit 5 `
  --batch-dir runs/20260318_hits_batch_001 `
  --workers 5 `
  --mutant-workers 4
```

`--run-dir`/`--batch-dir` must point to a new directory. The preparation and execution stages must reuse the same single-target directory. For a formal full experiment, change the manifest to `data/target_manifest.jsonl`.

## Outputs

- `hits_context.json`: static context without mutation information.
- `slices/slice.json`: model-returned slices that passed schema validation.
- `slices/<ordinal>/attempt_*.java` and `result.json`: per-slice generation and repair traces.
- `prompts/`, `responses/`, `calls/`, and `call_mutant_trace.jsonl`: API, token, cache, and source-record audit data.
- `raw_tests/merged_final.java`: merged test suite.
- `project_artifacts_final.json`: final `.java`/`.class` artifact locations; actual artifacts reside under `baselines/HITS/runs/...` in the target project.
- `mutant_execution/<execution_id>/`: isolated mutant-execution results.
- `hits_result.json`: single-target summary.
- `batch_summary.json`, `mutation_batch_summary.json`, and `efficiency_summary.json` in the batch directory: success rate, mutation score, calls, repairs, tokens, and timing statistics.

## Verification

```powershell
python -B -m unittest discover -s adapters/HITS -p "test_*.py"
```

The real-API smoke run on 2026-10-29 was used only to verify the pipeline: one method, three slices, all three slices passing, and 43 test methods after merging. Among seven physical mutants, killed=5, live=2, and invalid=0, so `End-to-End MS` and `Executable MS` both happened to be `5/7=71.43%`. This is not a formal comparative result.

The five-method validation batch from the same day, `runs/20260318_hits_batch5_001`, retained 844 association records and 737 unique physical mutants for the first five deduplicated methods. Three targets generated valid suites; two failed with `MODEL_OUTPUT_TRUNCATED` and `NO_EXECUTABLE_SLICE_TEST`, respectively. The final counts were killed=391, live=63, and invalid=283. The primary metric was `End-to-End MS=391/737=53.05%`, and the diagnostic metric was `Executable MS=391/454=86.12%`. The batch completed with `formal_score_valid=true`, but it remains a single five-method validation and does not replace independent repetitions or a Full experiment.
