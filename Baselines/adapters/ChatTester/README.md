# ChatTester-DS-Adapted runbook

This adapter preserves ChatTester's method-intention, initial-test, and
compile-repair design while routing every model request through
`Baselines/adapters/common/llm_gateway.py`. For the formal experiment, the
published paper is the normative source: execution failures are measured but
are not sent back to the LLM for repair. The source snapshot under
`Baselines/sources/ChatTester` is never modified or executed in-place.

`MuTestLLM` is read-only reference material. This adapter neither imports its
classes nor writes or compiles anything in that subtree.

## Current smoke target

```text
project: commons-csv-1.2
class:   org.apache.commons.csv.Token
method:  void_reset()
mutants: SDL_1, SDL_2, SDL_3
```

The bounded manifest is `Baselines/data/target_manifest.smoke.jsonl`. Its
summary records both workbook and manifest SHA-256 values.

## Offline checks

Run from the workspace root:

```powershell
python -m unittest discover -s Baselines/adapters/ChatTester -p "test_*.py" -v
python Baselines/adapters/ChatTester/deepseek_gateway.py --check
```

Prepare a new immutable run directory without calling the API:

```powershell
python Baselines/adapters/ChatTester/prepare_run.py `
  --manifest Baselines/data/target_manifest.smoke.jsonl `
  --run-dir Baselines/runs/<new_run_id>
```

The expected state is `PREPARED_API_NOT_CALLED` with `api_called=false`.
Preparation also writes `source_records.jsonl`, containing every mutant row
associated with the deduplicated method and all twelve original Excel fields.

When VS Code opens the `Baselines` folder, `prepare_run.py` can be run without
arguments. It defaults to `data/target_manifest.jsonl` and creates
`runs/yyyyMMddHHmmssSSS_chattester_manual_001`. Running
`generate_initial_test.py` without arguments selects the newest manual run whose
state is `PREPARED_API_NOT_CALLED`; if none exists, it prepares a new
millisecond run automatically. After successful generation it also writes the
run's token summary. Therefore, once `data/target_manifest.jsonl` exists, this
single command is sufficient:

```powershell
python -B adapters/ChatTester/generate_initial_test.py
```

## First live generation

Configure the Baselines-owned file `Baselines/configs/llm.properties`. For
example, selecting `deepseek` causes the gateway to use the three values under
`llm.providers.deepseek`:

```properties
llm.provider=deepseek
llm.providers.deepseek.api.url=<OpenAI-compatible chat-completions URL>
llm.providers.deepseek.model=deepseek-v4-flash
llm.providers.deepseek.api.key=<secret>
llm.providers.deepseek.thinking.type=disabled
llm.max.input.tokens=16000
llm.max.input.chars=30000
llm.budget.chars.per.token=3.0
llm.input.message.overhead.tokens=16
llm.oversize.prompt.policy=truncate
llm.force.regenerate=false
```

ChatTester-specific stage settings are versioned separately in
`adapters/ChatTester/llm.properties`:

```properties
llm.stages.chattester_intention.temperature=0
llm.stages.chattester_initial.temperature=0
llm.stages.chattester_compile_repair.temperature=0
llm.stages.chattester_runtime_repair.temperature=0

llm.stages.chattester_intention.max.tokens=16000
llm.stages.chattester_initial.max.tokens=16000
llm.stages.chattester_compile_repair.max.tokens=16000
llm.stages.chattester_runtime_repair.max.tokens=16000
```

The 16000-token ceiling is the shared main-comparison output budget, not a
claim about ChatTester's original GPT-3.5 limit. Intention remains short because
of its prompt contract. Original-budget results are separate sensitivity runs.

The common file plus the automatically discovered adapter-local file are
sufficient for normal runs. As in the reference Java loader,
an explicitly set `LLM_*` or provider-specific `LLM_<PROVIDER>_*` environment
variable has higher priority for a one-process override. Protect the properties
file locally; the key is never copied into run artifacts.
The repository-safe common starting point is
`Baselines/configs/llm.properties.example`; only the real common
`configs/llm.properties` is secret-bearing and ignored.

The pinned ChatTester sources do not define an input-token ceiling. The adapted
gateway therefore treats the values above as an `API_REQUIRED_ADAPTATION` for
every configured stage. A paper-aligned formal run activates intention, initial
generation, and compilation repair only; runtime repair is artifact-only. A
stage can override the common input limits with
`llm.stages.<stage>.max.input.tokens` and `.max.input.chars`. Before cache lookup
or HTTP transport, the gateway estimates input tokens as
`ceil(UTF-8 bytes / chars-per-token) + message-overhead`. With policy `truncate`,
only the middle of the user prompt is removed, preserving its beginning and
instructions at the end; with policy `error`, the call fails before the API.
Every request/call log records the original and sent sizes and whether
truncation occurred. Provider-reported usage remains the authoritative actual
token count.

For DeepSeek, the common gateway maps `thinking.type` to the official Chat
Completions request field `thinking: {"type": "enabled|disabled"}`. Test-code
generation uses `disabled` so the output budget is available to the Java test
instead of being exhausted by hidden reasoning tokens.

Then run:

```powershell
python Baselines/adapters/ChatTester/generate_initial_test.py `
  --run-dir Baselines/runs/<prepared_run_id>
```

The Baselines common gateway sends the provider URL, model, temperature,
thinking switch, input/output budgets, timeout, and retry values resolved from that
file. A successful initial call ends at `GENERATED_NOT_COMPILED` and writes the
intention, raw responses, prompts, and a same-package JUnit 4 source file. Run
the feedback pipeline next; it places test artifacts in the subject project's
dedicated `baselines/ChatTester` subtree automatically. Do not copy a generated
test into the project's real source or test directories.

## Bounded batch generation

`run_batch.py` performs preparation plus intention and initial generation. The
separate `run_feedback_batch.py` phase compiles the generated test, applies the
paper-authorized compilation feedback loop, and then runs JUnit on the original
program for execution-correctness assessment. Keeping the phases separate
allows inspection and safe resumption without repeating successful model
calls.

`run_feedback_pipeline.py` uses the paper-priority compile-only protocol. Any
historical run containing a `chattester_runtime_repair` call is an exploratory
artifact-mode run and must not be mixed with paper-aligned formal results.

To generate one test suite for each of the first ten deduplicated methods, run
from the `Baselines` directory:

```powershell
python -B adapters/ChatTester/run_batch.py `
  --manifest data/target_manifest.first10.jsonl `
  --limit 10 `
  --workers 8
```

`--workers 0` (the default) reads `llm.threads` from
`configs/llm.properties`. Workers parallelize independent focal methods only;
intention, generation, compilation feedback, and repair calls inside one method
retain the paper-defined order. The feedback batch has the same override:

```powershell
python -B adapters/ChatTester/run_feedback_batch.py `
  --batch-dir runs/<batch_id> `
  --workers 8
```

Deduplication uses `project + class_name + method_signature`. Every selected
method gets an isolated directory under
`runs/<timestamp>_chattester_batch_001/targets/<number>`. The batch root holds
`batch_metadata.json`, `batch_results.jsonl`, and `batch_summary.json`; each
target retains all prompts, responses, call logs, mutant traces, token reports,
and its generated Java test. A target failure is recorded and does not stop the
remaining targets.

Selected 1-based positions can be retried without overwriting the first batch:

```powershell
python -B adapters/ChatTester/run_batch.py `
  --manifest data/target_manifest.first10.jsonl `
  --limit 10 `
  --indices 2,3,4
```

To recompute token reports for an existing batch without calling the model:

```powershell
python -B adapters/ChatTester/run_batch.py `
  --summarize-existing runs/<batch_id>
```

To compile, apply compilation-error repair, and execute every generated target
in that batch:

```powershell
python -B adapters/ChatTester/run_feedback_batch.py `
  --batch-dir runs/<batch_id>
```

Use `--indices 1,3,10` for selected targets. Completed targets are idempotently
recognized from `state.json` and `project_artifacts_final.json`, so they are not
sent to the model again. For one target, run:

```powershell
python -B adapters/ChatTester/run_feedback_pipeline.py `
  --run-dir runs/<batch_id>/targets/<target>
```

## Paper-priority correctness and feedback protocol

The formal reproduction follows the published paper before the author artifact.
The primary evidence is the paper's experimental procedure (Section 3.4) and
iterative refiner description (Section 5.2):
<https://arxiv.org/pdf/2305.04207>.
The paper separates correctness from sufficiency:

| Dimension | Paper criterion | Role in this reproduction |
|---|---|---|
| Syntactic correctness | the generated test passes an AST syntax checker such as JavaParser | auxiliary native metric |
| Compilation correctness | the generated test compiles successfully | ChatTester refinement target and `CompR` |
| Execution correctness | the generated test passes when executed on the original program | `ExecR` and mutation-evaluation admission |
| Statement coverage (SC) | JaCoCo statement coverage on the focal method | auxiliary native sufficiency metric |
| Branch coverage (BC) | JaCoCo branch coverage on the focal method | auxiliary native sufficiency metric |
| Assertions | number of assertions in the generated test | auxiliary native sufficiency metric |

The paper uses **statement coverage**, not a separately defined line-coverage
criterion. SC and BC are evaluation metrics; they do not trigger another model
call and are not stopping conditions for generation or repair. Coverage is
reported for executable/passing tests because non-executable tests have zero
coverage and would bias the comparison. At project level, the paper aggregates
SC and BC over the entire project.

The paper-aligned method flow is:

```text
intention call
  -> initial test call
  -> compile generated test
     -> compilation failure: construct feedback from compiler errors and
        relevant project context, call the LLM, and compile again
     -> compilation success: terminate the LLM refinement loop
  -> execute the compiled test on the original program for assessment
     -> pass: eligible for common mutation evaluation
     -> fail/timeout: record ORIGINAL_FAILED/TIMEOUT and do not run mutants
```

The iterative refiner is compilation-only. The paper explicitly excludes
execution-error repair because an execution failure cannot be reliably
attributed to an incorrect generated test rather than an incorrect focal
method. A refinement is valid only when it reduces the compilation-error count.
If the count does not decrease, it is an invalid refinement; the paper sets the
maximum-invalid-refinement parameter to three and terminates after the
accumulated count exceeds that maximum. Any other maximum-iteration value not
explicitly fixed by the paper remains
`UNRESOLVED` and must not be invented for a formal run.

### Paper/artifact discrepancy

The pinned author artifact contains additional `testRepair` logic, including
Surefire execution feedback and the stop expression `iter > 3 or IterTest > 5
or TotalIter > 30`. This conflicts with the published paper's explicit
compilation-only refiner. Under the project's source-priority rule, that branch
is classified as `AUTHOR_CODE` artifact behavior, while the formal experiment
uses the `PAPER` behavior. If artifact mode is retained for diagnosis, its runs
must use a distinct experiment label and cannot be mixed with formal results.
The preserved evidence is
`sources/ChatTester/ExperimentCode/ChatGptTester.py`; it remains read-only.

Current implementation status:

- `run_feedback_pipeline.py` implements the paper-priority compile-only path;
- `chattester_runtime_repair` is not invoked by a paper-aligned formal run;
- the adapter does not yet collect JaCoCo SC/BC, so coverage reporting is still
  pending common coverage infrastructure;
- mutation execution is implemented separately and must never become feedback
  to ChatTester.

The shared compiler/executor is
`adapters/common/java_test_pipeline.py`; project-artifact mapping is in
`adapters/common/project_artifacts.py`. They are common infrastructure. The
ChatTester-only feedback prompts and state machine remain in
`adapters/ChatTester/feedback.py` and
`adapters/ChatTester/run_feedback_pipeline.py`.

Generated sources, classes, and reports are isolated at:

```text
<project_root>/baselines/ChatTester/runs/<experiment_run_id>/<method_id>/
├── rounds/<nnn>/src/<package-path>/*.java
├── rounds/<nnn>/classes/<package-path>/*.class
├── rounds/<nnn>/report/{compile,execution,result}.json-or-log
└── final/{src,classes,report}/
```

The target run keeps `project_artifacts.json` and
`project_artifacts_final.json` so every model call and mutant row can be traced
to those files. Compiler/runtime design was read from MuTestLLM, but all
implementation remains under Baselines and no MuTestLLM file is imported,
compiled, or changed.

Each live stage automatically creates `calls/<call_id>/call.json` and appends
one `call_mutant_trace.jsonl` record per associated mutant. ChatTester keeps
`visible_to_model=false`: mutation paths and statements are logged for
traceability but are not inserted into its prompts.

Cache selection is flexible at the high-level boundary: omit an override to
follow `llm.force.regenerate`, pass `--use-cache` to allow reuse explicitly, or
pass `--no-cache` to force a fresh response for a direct gateway invocation.

Normalize and aggregate token usage after a run with:

```powershell
python Baselines/adapters/common/summarize_token_usage.py `
  --run-dir Baselines/runs/<run_id>
```

The result files are `token_usage_calls.jsonl` and
`token_usage_summary.json`. ChatTester follows the shared contract in
`Baselines/adapters/common/TOKEN_ACCOUNTING.md`; this includes uncached input,
provider cache read/write, output/reasoning tokens, and the required cache hit
rate formula.

## Separate mutation-evaluation phase

The paper-native LLM repair loop stops when the test compiles (or its
paper-defined repair limit is reached). The common evaluation layer then runs
the compiled test on the original program. Only a test that passes that run is
published as `ORIGINAL_PASSED` and admitted to mutation evaluation. This
admission filter is `EVALUATION_COMMON`; it must not be confused with an
additional ChatTester runtime-repair stage.

Mutation evaluation is a separate command and never calls the LLM or repairs a
test according to mutant outcomes:

```powershell
python -B adapters/ChatTester/run_mutant_execution.py `
  --run-dir runs/<batch_id>/targets/<target> `
  --workers 4
```

`--workers 0` reads `java.mutant.workers` from `configs/java.properties`.
The original program still runs once before the pool; every mutant worker
launches a separate JVM with its own overlay and report directory.

Omitting `--run-dir` currently selects the verified Ant `Date.setMillis(long)`
smoke target. Use `--max-mutants N` only for an execution smoke; omit it for the
complete mapped set. The command reads every row from `source_records.jsonl`,
including duplicate operator names with different `file_path` values. Exact
duplicate `file_path` rows share one physical execution but each original row
still appears in `mutant-results.jsonl` and CSV.

Detailed original/per-mutant JUnit JSON and logs are stored below the subject
project's method artifact at `mx/<millisecond-id>/`. The target run stores
`mutant_execution/<id>/summary.json` and `mutant_execution/latest.json` as
traceable pointers. This phase uses `adapters/common/mutant_execution.py` and
the common `JUnitResultWorker`; ChatTester-specific code is only the entrypoint.

After mutation execution finishes for every `ORIGINAL_PASSED` target, summarize
the complete batch:

```powershell
python -B adapters/common/summarize_mutation_batch.py `
  --batch-dir runs/<batch_id>
```

The formal command refuses to score when an `ORIGINAL_PASSED` target is still
missing mutation results. `--allow-incomplete` is diagnostic only and produces
`formal_score_valid: false`.

The primary comparison score is:

```text
End-to-End MS
= KILLED / (KILLED + LIVE + NOT_EXECUTED_INVALID_TEST)
```

Mutants mapped to generation failures, compilation/repair-limit failures,
original-program failures, or original-program timeouts are recorded as
`NOT_EXECUTED_*` and lower this primary score. They are not falsely labeled
`LIVE`. `Executable MS = KILLED / (KILLED + LIVE)` is retained as a diagnostic
for the subset that actually reached mutation execution. Evaluation
infrastructure errors are reported separately and excluded from both
denominators.

## Efficiency, calls, and repair accounting

ChatTester uses the common `summarize_efficiency.py` report. Its native repair
counter includes `chattester_compile_repair` only in the paper-priority formal
mode. `chattester_intention` and `chattester_initial` are generation calls;
provider HTTP retries are API attempts rather than repair rounds. The preserved
`chattester_runtime_repair` stage may be counted only for separately labelled
artifact-mode runs and is never mixed into formal paper-priority results.

After all targets have been evaluated, `summarize_mutation_batch.py` writes
`efficiency_summary.json` automatically. For an existing batch it can also be
recomputed without any API or Java execution:

```powershell
python -B adapters/common/summarize_efficiency.py `
  --batch-dir runs/<batch_id>
```

The report contains `t_pre`, `t_gen`, `r_avg`, `t_total`, `t_avg`, logical
calls, provider request attempts, Token/cache counters, compilation/execution
counts, and provider-billed or explicitly estimated cost. Its average unit is
one deduplicated focal-method target, not one workbook row or mutant.
