# ASTER Java DeepSeek reimplementation

This adapter reproduces ASTER's Java pipeline for the shared mutation-testing
experiment:

```text
static focal context
  -> initial test generation
  -> sanitize / compile / run original program
  -> compiler or runtime feedback repair when needed
  -> JaCoCo focal-line analysis
  -> uncovered-line coverage augmentation when needed
  -> final original-program validation
  -> isolated execution against every mapped MuJava mutant
```

The public ASTER repository does not release its complete generation engine.
Therefore the formal method name is `ASTER-Java-DS-Reimplementation`, not
"official ASTER code rerun". See `SOURCE.md` and `METHOD_MAPPING.md` before
changing any stage.

## Inputs and information isolation

Targets come from `data/target_manifest.jsonl` and are deduplicated by
`project + class_name + method_signature`. One generated suite is shared by all
physical mutants mapped to that focal method. Initial, repair, and coverage
prompts never receive mutation operator, statement, graph path, equivalent
label, mutant source, or kill result.

Static context contains the focal method/class, relevant constructors,
setters/getters, direct private call chains, and only explicitly configured
mockable API types. Unknown dependencies remain absent instead of being
invented.

## Run manually in VS Code

Open `Baselines` as the VS Code workspace.

1. Select `ASTER 1. Prepare Manual Target`, press F5, and enter a new directory.
2. Inspect `aster_context.json`, `metadata.json`, and the initial prompt.
3. Select `ASTER 2. Run Prepared Target End-to-End`, press F5, and enter exactly
   the same directory.

For the standard first-five-method experiment select
`ASTER A. Full Batch End-to-End (5 Methods)`. It retains all source rows and all
unique physical mutants associated with those five methods.

Command-line equivalents:

```powershell
python -B adapters/ASTER/prepare_run.py `
  --manifest data/target_manifest.first10.jsonl `
  --run-dir runs/<new_aster_run>

python -B adapters/ASTER/run_pipeline.py `
  --run-dir runs/<new_aster_run> `
  --mutant-workers 4

python -B adapters/ASTER/run_batch.py `
  --manifest data/target_manifest.first10.jsonl `
  --limit 5 `
  --batch-dir runs/<new_aster_batch> `
  --workers 5 `
  --mutant-workers 4
```

## Configuration and stopping rules

DeepSeek endpoint/model/credential/network settings are common in
`configs/llm.properties`. The `aster_initial`, `aster_repair`, and
`aster_coverage` token/temperature values are isolated in this directory's
`llm.properties`. Undisclosed ASTER loop bounds remain in `aster.properties`
and are copied into run metadata.

- Repair stops immediately after a candidate compiles and passes on the
  original program, or after the configured bound.
- Coverage augmentation starts only from an original-passing suite and stops at
  the target focal-line ratio, when no measurable focal lines remain uncovered,
  or at the configured bound.
- If an augmentation candidate cannot be repaired, the last passing suite is
  retained; a failing candidate never replaces it.
- Only the final original-passing suite is published and used for mutation
  evaluation.

The main score is End-to-End MS, whose denominator includes mutants linked to
invalid generated tests. Executable MS remains diagnostic.

## Outputs

Each target records prompts, raw responses, every Java validation attempt,
compiler/JUnit logs, focal coverage, final project-local `.java`/`.class`
artifacts, per-mutant results, Token/cache accounting, timings, call counts, and
repair counts. Batch roots add `mutation_batch_summary.json` and
`efficiency_summary.json`.

## Validated runs

- `20260318_aster_smoke_001`: one target, 5 KILLED / 2 LIVE.
- `20260318_aster_batch5_002`: five targets, 304 KILLED / 50 LIVE /
  383 invalid-test mutants; End-to-End MS 41.25%, Executable MS 85.88%.

`20260318_aster_batch5_001` is explicitly invalidated because the first focal
line mapper missed a multiline `throws` declaration. It is audit material only.
