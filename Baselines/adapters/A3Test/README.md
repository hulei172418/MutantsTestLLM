# A3Test-DS-Adapted

This adapter migrates A3Test's “assertion-augmented knowledge + single-best complete-test generation + deterministic signature verification” workflow to the shared DeepSeek-V4-Flash backend. It is not a rerun of the original PLBART checkpoint.

The shared provider, URL, model, API key, network retry, and cache-reading settings come from `../../configs/llm.properties`. A3Test-specific `a3test_generation` budget and temperature settings come from the `llm.properties` file in this directory. The common gateway automatically merges the two configuration layers by stage; no mode switch is used.

## Faithful Workflow

1. Select deduplicated focal methods by `project + class_name + method_signature`, while retaining all associated mutants.
2. Construct the paper's `fm+fc+c+m+f` context: focal method, focal class, constructor signatures, signatures of other public methods, and public fields.
3. Invoke `a3test_generation` exactly once per method to generate one best complete JUnit 4 test with meaningful assertions.
4. Following the intent of the author's `postProcessingScript.py`, deterministically add `@Test`, `public void`, the `test` prefix, and repairable closing braces; save a verification audit. This step is not a model call.
5. Compile and execute the test only once. Because the original method has no feedback repair, a failure is recorded directly as an invalid test.
6. After the test passes on the original program, collect JaCoCo line/branch coverage and execute every associated physical mutant individually.
7. The primary metric is `End-to-End MS = killed / (killed + live + invalid)`; Executable MS is diagnostic only.

## VS Code

After opening `Baselines`:

1. `A3Test 1. Prepare Manual Target`
2. `A3Test 2. Run Prepared Target End-to-End`

The concurrent five-method entry point is `A3Test A. Full Batch End-to-End (5 Methods)`.

```powershell
python -B adapters/A3Test/prepare_run.py `
  --manifest data/target_manifest.first10.jsonl `
  --run-dir runs/20260318_a3test_manual_001

python -B adapters/A3Test/run_pipeline.py `
  --run-dir runs/20260318_a3test_manual_001 `
  --mutant-workers 4

python -B adapters/A3Test/run_batch.py `
  --manifest data/target_manifest.first10.jsonl `
  --limit 5 `
  --batch-dir runs/20260318_a3test_batch_001 `
  --workers 5 `
  --mutant-workers 4
```

## Outputs

- `a3test_context.json`: the `fm+fc+c+m+f` context.
- `raw_tests/model_output.txt`: raw DeepSeek output.
- `raw_tests/verified.java` and `parsed/verification.json`: deterministic correction result and audit.
- `calls/` and `call_mutant_trace.jsonl`: calls, tokens, cache data, and source-record mapping.
- `a3test_result.json`: single-target result.
- `mutation_batch_summary.json` and `efficiency_summary.json` in the batch directory: unified scoring and efficiency statistics.

## Offline Verification

```powershell
python -B -m unittest discover -s adapters/A3Test -p "test_*.py"
```

## 2026-10-29 Validation Results

The single-method smoke run `runs/20260318_a3test_prepare_smoke_001` invoked `a3test_generation` only once, with 919 input tokens, 95 output tokens, and 1,014 total tokens. It generated a test with valid assertions, required no signature or brace correction, and passed compilation and original-program execution. Across seven physical mutants, killed=5, live=2, and invalid=0; both End-to-End MS and Executable MS were 71.43%.

After fixing the verifier, the valid five-method batch is `runs/20260318_a3test_batch5_003`: 844 association records and 737 unique physical mutants. Two targets passed; the other three ended as `COMPILE_FAILED`, `ORIGINAL_FAILED`, and `VERIFICATION_FAILED`, respectively. The final result was killed=294, live=60, invalid=383, `End-to-End MS=39.89%`, and `Executable MS=83.05%`. There were five generation calls and zero repair calls; uncached input=919, Cache Read=6,011, total input=6,930, output=2,852, total tokens=9,782, and cache hit rate=86.74%.

`runs/20260318_a3test_batch5_001` and `_002` were invalidated because the adapter failed to recognize and preserve Java `throws` clauses, respectively. Both directories retain their raw records and contain `INVALIDATED.md`; they must not be used as experimental results.
