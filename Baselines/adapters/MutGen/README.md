# MutGen-DS-Adapted

This is the second LLM Baseline. The immutable author package is in `Baselines/sources/MutGen`; all reproduction code lives here.

## Frozen protocol

For each deduplicated focal method:

1. summarize the original source in 1--3 sentences;
2. remove comments for the generation view;
3. construct feedback for unique MuJava mutants using only id, line, operator, status, and mutated statement;
4. call DeepSeek-V4-Flash at temperature 0 to generate additional JUnit 4 test methods;
5. compile and run each new test on the original program;
6. give each failing test at most one compile/assert repair call and filter again;
7. merge passing tests, run a new isolated MuJava round, and repeat for at most four rounds;
8. run the common final evaluator and report End-to-End MS plus diagnostic Executable MS.

Initial feedback marks mutants without test evidence as `NO_COVERAGE`. Later rounds show only `LIVE` and `NO_COVERAGE`; killed mutants are never re-prompted. Audit-only `equivalent` labels are never visible to the model.

The frozen author code has one special repair branch: a Java compiler error of
the form `method ... is already defined` is repaired mechanically by prefixing
the method name with `dedul_`; it does not consume an LLM call. All other
compilation/assertion failures receive at most one repair call. Native MutGen
calls explicitly disable the local response cache so each of the four paper
iterations really invokes the model; provider-side Cache Read/Write tokens are
still recorded normally.

The raw model response is always preserved. If DeepSeek loses indentation on a
line inside the fixed YAML `test_code` or `new_imports` literal block, the
adapter restores only the YAML envelope indentation without changing the Java
line or using an LLM repair call. Each round records this decision in
`rounds/NNN/response_parse.json`.

The frozen author router uses `max_tokens=4096`, but the main cross-baseline
comparison explicitly adapts every LLM stage to the shared 16000-token output
ceiling recorded in this directory's `llm.properties`. Provider credentials and
network controls remain in `configs/llm.properties`. This is
`UNIFIED_OUTPUT_BUDGET_ADAPTATION`, not a
claim that the author used 16000. The 4096-token result is retained only as a
Native-budget sensitivity run. If even the unified structured response ends
with `finish_reason=length`, the target becomes `MODEL_OUTPUT_TRUNCATED`; the
adapter never invents missing YAML/Java.

## Manual execution

Prepare one method without calling the API:

```powershell
python -B adapters/MutGen/prepare_run.py `
  --manifest runs/<existing-batch>/manifests/001.jsonl `
  --run-dir runs/<new-mutgen-run-id>
```

In VS Code, `MutGen 1. Prepare Manual Target` fixes the manifest to
`data/target_manifest.first10.jsonl` and prompts only for a new run directory.
Then `MutGen 2. Run Prepared Target End-to-End` prompts for that same run
directory. A run directory is never a manifest file.

Run summary, four generation/validation/mutation-feedback rounds, publish the
final test suite, and execute all mapped MuJava mutants:

```powershell
python -B adapters/MutGen/run_pipeline.py `
  --run-dir runs/<new-mutgen-run-id> `
  --mutant-workers 4
```

Run the first N unique methods concurrently and automatically produce the
common end-to-end mutation summary:

```powershell
python -B adapters/MutGen/run_batch.py `
  --manifest data/target_manifest.first10.jsonl `
  --limit 5 `
  --batch-dir runs/<new-mutgen-batch-id> `
  --workers 5 `
  --mutant-workers 4
```

Within one method, summary, generation, repair, validation and mutation
feedback remain sequential. `--workers` only parallelizes independent methods;
`--mutant-workers` parallelizes fresh-JVM mutant executions.

## Batch mutation semantics

`--limit 5` selects five unique `(project, class, method)` groups, not five
mutant rows. Every selected group retains all of its workbook rows and all
unique physical mutant artifacts. Each method gets an independent generated
suite; that suite is revalidated on the original program and executed against
every mutant mapped to that method.

This is a method-first selection policy, not a row-prefix policy. The batch
writes the exact policy, per-method counts, total source rows, and total unique
physical mutants to `batch_metadata.json` before any model call, and prints the
totals to the console.

The current first-five manifest contains 844 source rows and 737 unique
physical mutants, distributed as 7, 215, 68, 100, and 347. MutGen may execute
each mutant in four intermediate feedback rounds and once again in the final
evaluation. Intermediate executions are feedback snapshots only. Batch scoring
reads each target's published final `mutant_execution/latest.json`, so a mutant
is counted exactly once in the final aggregate.

The batch report is a mutant-level micro aggregate:

- `Executable MS = total KILLED / (total KILLED + total LIVE)`;
- `End-to-End MS = total KILLED / (KILLED + LIVE + invalid-test-not-executed)`.

It is not the arithmetic mean of five per-method scores. Prepare/tool failures
materialize an auditable infrastructure target and are excluded from score
denominators. A declared target missing its state or source records makes the
formal summary fail closed instead of silently shrinking the denominator.

## Current status (2026-10-30)

- Official source and commit frozen.
- Paper/artifact conflict audit complete.
- Prompt and mutation-feedback protocol implemented in `protocol.py`.
- Leakage, deduplication, four-round, generation, repair, YAML parsing, suite rendering, and author duplicate-name contracts have offline tests.
- DeepSeek stage configuration is isolated in this adapter's `llm.properties`.
- `prepare_run.py`, `run_pipeline.py`, and concurrent `run_batch.py` are implemented.
- The common mutation executor accepts explicit original-passing round snapshots without overwriting final `latest.json`.
- `20260318_mutgen_smoke_002` completed the strict single-target flow: 25 accepted tests, four uncached API generation rounds, 7 unique mutants, 5 KILLED and 2 LIVE (71.43%), with no mutation infrastructure errors.
- Shared JaCoCo line coverage and MuJava-to-current-source line mapping are integrated into every feedback round.
- `20260318_mutgen_smoke_004` completed the coverage-aware single-target flow: 25 accepted tests, 7 unique mutants, 5 KILLED, 2 LIVE, 0 NO_COVERAGE, and Executable MS 71.43%. The MuJava pretty-printed line 93 maps to current source line 177, which JaCoCo covered in all four rounds.
- `20260318_mutgen_batch_unified16k_001` has been recalculated from retained
  evidence: 844 source rows, 737 unique physical mutants, 99 KILLED, 8 LIVE,
  and 630 invalid-test mutants. End-to-End MS is 13.43%, Executable MS is
  92.52%, and `formal_score_valid=true`. A legacy wrapper recorded target 002
  as `PIPELINE_FAILED` after reading a missing `accepted_test_count`; its 42
  validation attempts and empty `accepted_tests.json` deterministically recover
  `NO_EXECUTABLE_CANDIDATE` without rewriting the original state file.
- The native-4096 sensitivity batch `20260318_mutgen_batch_full5_001` is also
  formally recalculable: 5 KILLED, 2 LIVE, 730 invalid model-output mutants,
  End-to-End MS 0.68%, and Executable MS 71.43%. It is not the unified-16K
  comparison result.

`20260318_mutgen_smoke_001` is a superseded technical smoke: it incorrectly
used LLM repair for duplicate names and allowed local response-cache reuse. It
must never enter experimental results. The `_002` smoke validates the corrected
protocol but remains a one-method smoke, not a pilot or formal comparison.
`20260318_mutgen_smoke_003` is also excluded: the model returned valid fenced
YAML, but the former fence parser rejected it before compilation. The parser
now prefers the complete non-empty YAML fence and has a regression test; `_004`
is the first coverage-aware successful smoke.

For post-round feedback, a surviving mutant is `NO_COVERAGE` only when its
mapped current-source line is absent from JaCoCo's covered-line set. A covered
survivor is `LIVE`. If line mapping cannot be proven, the adapter fails
conservatively to `LIVE` rather than fabricating `NO_COVERAGE`. Coverage is
collected from the exact original `.class` whose JaCoCo class ID matches the
execution data.

## Efficiency, calls, and repair accounting

MutGen writes the shared target- and batch-level `efficiency_summary.json`.
Only `mutgen_compile_repair` and `mutgen_assert_repair` count as LLM repair
rounds. `mutgen_summary` and `mutgen_generation` remain generation/feedback
calls, while deterministic duplicate-name repair and YAML envelope indentation
repair consume neither an API call nor an LLM repair round. Provider retry
attempts are reported separately as API Calls.

The final one-click batch refreshes the report after its terminal metadata is
written. Existing results can be recalculated without invoking the model or
rerunning mutants:

```powershell
python -B adapters/common/summarize_mutation_batch.py `
  --batch-dir runs/<batch_id>
```

The report follows `adapters/common/EFFICIENCY_METRICS.md`; averages use the
five (or configured N) unique focal methods selected by the batch, not the 844
source rows or their physical mutant count.
