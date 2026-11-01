# IntUT DeepSeek independent reimplementation

This adapter reproduces the observable Java workflow described in *Test
Intention Guided LLM-based Unit Test Generation* (ICSE 2025):

```text
PAINT-style branch analysis -> explicit test intentions
  -> target-project RAG examples and historical mock evidence
  -> five candidates for each intention
  -> original-program candidate validation/selection
  -> common isolated MuJava evaluation
```

No verifiable public IntUT implementation, CFG engine, trained CodeBERT mock
classifier, or model weights were found. The formal identity is therefore
`IntUT-DS-Reimplementation`, not an official-source rerun. See
`METHOD_MAPPING.md` and `SOURCE.md` before changing the protocol.

## Fidelity rules

- Program context follows Figure 6: focal class/method, fields, method
  signatures, project examples, test prefix/existing tests, and one explicit
  test intention.
- Each intention contains input constraints, mock behavior, hint
  specification, and expected result.
- Project RAG uses the paper's Jaccard threshold `0.8` and never retrieves from
  another evaluated project.
- The paper reports five candidates per request. Because the shared
  OpenAI-compatible gateway has no portable multi-sample `n` contract, the
  adapter performs five independent DeepSeek calls and records this API
  adaptation.
- The original IDE lets a developer select a candidate. The automated
  comparison uses the first candidate that compiles and passes on the original
  program; it never uses mutant results for selection.
- There is no LLM repair or coverage-feedback stage. Section VII-E explicitly
  leaves recovery mechanisms to future work. Invalid candidates remain failed
  candidates instead of being repaired.
- After candidate selection, JaCoCo records focal-method line and branch
  counters as paper auxiliary metrics. These counters are reporting-only and
  are never visible to the LLM or candidate selector.

The unavailable bytecode CFG/SSM-DFS engine is approximated by auditable
lexical branch nesting: nested conditions are retained and parallel condition
combinations are pruned. The unavailable CodeBERT classifier is replaced only
with target-project historical mock evidence. These are disclosed
reimplementation limitations, not claimed original behavior.

## VS Code execution

Open `Baselines` as the workspace.

1. Select `IntUT 1. Prepare Manual Target`, press F5, and enter a new run dir.
2. Inspect `paint_intentions.json`, `intut_rag.json`, and `metadata.json`.
3. Select `IntUT 2. Run Prepared Target End-to-End`, press F5, and reuse the
   exact same run dir.

For the standard experiment select `IntUT A. Full Batch End-to-End (5
Methods)`. It selects the first five unique focal methods and retains all
physical mutants associated with those methods.

```powershell
python -B adapters/IntUT/prepare_run.py `
  --manifest data/target_manifest.first10.jsonl `
  --run-dir runs/<new_intut_run>

python -B adapters/IntUT/run_pipeline.py `
  --run-dir runs/<new_intut_run> `
  --mutant-workers 4

python -B adapters/IntUT/run_batch.py `
  --manifest data/target_manifest.first10.jsonl `
  --limit 5 `
  --batch-dir runs/<new_intut_batch> `
  --workers 5 `
  --mutant-workers 4
```

## Scoring and artifacts

Only an original-passing candidate suite enters mutant execution. Mutants
whose method has no executable suite are `NOT_EXECUTED_INVALID_TEST` and remain
in the End-to-End MS denominator. Executable MS is diagnostic only.

Every run retains PAINT intentions, RAG evidence, prompts, all five raw and
normalized candidates, javac/JUnit reports, selected suite, project-local
`.java`/`.class` files, token/cache/time logs, and per-mutant results.

Provider/network retries are common infrastructure resilience, not additional
IntUT candidates or repair rounds. The shared gateway uses the values in
`configs/llm.properties`; it retries transient transport, rate-limit, and 5xx
failures with bounded exponential backoff. A final `MODEL_PROVIDER_FAILED` or
unexpected `PIPELINE_FAILED` invalidates the whole batch and is excluded from
both MS denominators. In contrast, candidates that receive valid model
responses but fail compile/original validation remain IntUT invalid tests and
their mutants stay in the End-to-End MS denominator.
The `intut_generation` output ceiling and reimplementation temperature are in
this directory's `llm.properties`, not in the common provider file.

## Validated runs

- `20260318_intut_prepare_smoke_002`: one straight-line intention, five
  candidates, one selected original-passing test, 5 KILLED / 2 LIVE.
- `20260318_intut_batch5_001`: five methods and 737 unique physical mutants;
  409 KILLED / 160 LIVE / 168 invalid-test mutants, End-to-End MS 55.50% and
  Executable MS 71.88%. The batch made 295 generation calls and zero repair
  calls. Across the three original-passing focal methods, reporting-only micro
  line coverage is 74/77 (96.10%) and branch coverage is 37/43 (86.05%); the
  two invalid targets are explicitly excluded from that auxiliary denominator.
  It is a one-run five-method protocol validation, not a full repeated experiment.
- `20261030_intut_vscode_batch5_002`: independent VS Code rerun after the
  provider-failure audit; 410 KILLED / 159 LIVE / 168 invalid-test mutants,
  End-to-End MS 55.63% and Executable MS 72.06%. All 295 logical calls used
  exactly 295 provider requests (no retry was needed), zero repair calls, and
  the batch is formally valid. Total input/output/all tokens are
  779040/390975/1170015; wall clock is 1404.340 seconds. Reporting-only focal
  line and branch coverage remain 74/77 and 37/43.

