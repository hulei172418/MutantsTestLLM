# Baselines common module

This directory owns every capability shared by baseline reproductions:

It must not contain a Baseline-specific generator, prompt protocol, native-tool
command builder, test-family selector, or failure classifier. In particular,
EvoSuite and Randoop keep their complete method implementations in their own
adapter directories and only call the generic facilities listed below.

- `llm_gateway.py`: DeepSeek-V4-Flash configuration, HTTP calls, retry, cache,
  response parsing, and audit metadata;
- `manifest_paths.py`: the canonical workbook/full-manifest paths imported by
  every baseline adapter;
- `build_target_manifest.py`: the only Excel-to-JSONL implementation; it
  converts the common mutant workbook to the frozen full target list;
- `trace_logging.py`: uniform `call_id`, call metadata, and one-row-per-mutant
  association logs containing the original twelve Excel fields;
- `token_accounting.py`: provider-neutral uncached input, cache read, cache
  write, output, reasoning, total, and cache-hit-rate normalization;
- `summarize_token_usage.py`: per-call backfill plus run/stage token reports;
- `summarize_efficiency.py`: paper-aligned preprocessing/generation/total time,
  API attempts, native repair rounds, tokens, cache, and cost reports;
- `java_test_pipeline.py`: shared JUnit 4 compilation and original-program run;
- `mutant_execution.py`: original-once, fresh-JVM-per-mutant execution and
  KILLED/LIVE/infrastructure-error reporting;
- `jacoco_coverage.py`: exact-class JaCoCo line coverage for an accepted,
  original-passing suite;
- `mutation_line_mapping.py`: auditable mapping from MuJava pretty-printed
  mutant lines to the current project source line;
- `summarize_mutation_batch.py`: cross-target end-to-end mutation scoring that
  includes mutants not executed because the generated test was invalid;
- `java-tools`: standalone JavaParser and per-test JUnit worker utilities,
  compiled with their own Maven POM.

Shared/provider settings live in `Baselines/configs/llm.properties`. This file
owns provider selection, URL, model, API key, network/retry/cache controls,
input limits, concurrency and accounting prices. Each LLM baseline owns only
its `llm.stages.*` values in `adapters/<Baseline>/llm.properties`; these local
files must never contain credentials or provider settings.

The gateway discovers the unique local file containing the requested stage and
merges it automatically. Fixed precedence is code default < common properties
< baseline-local stage properties < environment or explicit call override.
There is deliberately no local/global mode flag: a missing local key naturally
falls back to common and then to the code default. Duplicate ownership of one
stage or any non-`llm.stages.*` key in a local file is a hard error.

High-level callers may pass an explicit cache choice. If they do not,
`llm.force.regenerate=false` enables prompt-hash cache reuse and
`llm.force.regenerate=true` forces a fresh API request. This mirrors the
reference generator's `generateOrReuse` versus `generateFresh` choice. The
parsed runtime settings also expose `llm.threads` and
`llm.skip.existing.compiled` for later batch and compile phases.

Create the shared secret-bearing file from
`Baselines/configs/llm.properties.example` when needed. The real common file is
ignored because it may contain a credential; adapter-local stage files are
secret-free, versioned experiment configuration.

## Isolation rule

`MuTestLLM` is read-only reference material. Common baseline code must not:

- modify or add files below `MuTestLLM`;
- run Maven or other compilers with `MuTestLLM` as the working directory;
- load `MuTestLLM/target/classes` or its generated classpath;
- read credentials from `MuTestLLM/src/main/resources/llm.properties`;
- write baseline caches, tests, logs, or reports outside `Baselines`.

Build the common Java utilities only here:

```powershell
cd Baselines/adapters/common/java-tools
mvn -DskipTests package
mvn dependency:build-classpath `
  "-Dmdep.includeScope=runtime" `
  "-Dmdep.outputFile=target/runtime-classpath.txt"
```

Keep this local configuration protected because it may contain a credential.
The gateway uses the key only in the HTTP Authorization header; it must never
be copied to request, response, call metadata, cache, trace, or report files.

Every logical model call must write `calls/<call_id>/call.json`, request and
response artifacts, plus one `call_mutant_trace.jsonl` row for each mutant in
the grouped focal method. Grouping never permits loss of the original Excel
records.

Every LLM baseline must follow [TOKEN_ACCOUNTING.md](TOKEN_ACCOUNTING.md).
Token reports are written to `token_usage_calls.jsonl` and
`token_usage_summary.json` in each run directory. Local response-cache hits do
not repeat the historical provider usage contained in their cached response.

Every LLM baseline must also follow
[EFFICIENCY_METRICS.md](EFFICIENCY_METRICS.md). A logical model call, a
provider HTTP retry attempt, and a baseline-native repair round are three
different counters. Final batch mutation summarization automatically writes
`efficiency_summary.json` at both target and batch level. Optional token-price
properties produce a clearly labelled estimate only; missing provider billing
data remains `null` rather than being guessed.

## Isolated MuJava execution

Only a suite in state `ORIGINAL_PASSED` may enter mutation evaluation. The
common executor reruns that immutable `final/classes` suite on the original
program once, groups `source_records.jsonl` by normalized `file_path`, and then
launches one new JVM for every unique mutant class. Every original source row
is retained and linked to that execution; exact duplicate artifact rows do not
inflate the mutation-score denominator. For a mutant JVM, the row's
`file_path` class is copied to an isolated overlay and that overlay is the first
classpath entry. Mutants are never swapped inside an already-running JVM.

Each worker uses a three-scope timeout protocol. JVM/process startup, classpath
lookup, and non-initializing test-class loading use the independent
`java.mutant.startup.timeout.seconds` infrastructure ceiling. The worker then
writes `junit-execution-started.json` and publishes an atomic
`junit-test-progress.json` event for every `TEST_STARTED`, `TEST_FINISHED`, and
`TEST_IGNORED`. Every `TEST_STARTED` resets a fresh
`java.mutant.test.timeout.seconds` deadline for that one test method. A class
with 94 tests therefore receives up to 4 seconds per test, not 4 seconds for
the whole class. `@BeforeClass`/`@AfterClass` and gaps with no active test use
the separate `java.mutant.lifecycle.timeout.seconds` infrastructure ceiling.
Fresh-JVM startup is never charged to a test method. Startup and lifecycle
timeouts are infrastructure errors and never kill a mutant.
The worker result records every test ID with `started_epoch_ms`,
`finished_epoch_ms`, `duration_nanos`, and `duration_ms`. Mutant rows also
contain started/run counts plus sum/max/average test-method duration.

An original-passing JUnit test that fails only under a mutant yields `KILLED`;
a specific original-passing test method that exceeds its own deadline only
under a mutant yields `KILLED_TIMEOUT`, with its test ID recorded.
Original-program validation always runs the complete suite. Mutant execution
is fail-fast: the first assertion failure, exception, resource error, or mapped
per-test timeout is already sufficient to kill that mutant, so no later test is
executed. A `LIVE` mutant must still exhaust the complete suite. Every result
records `fail_fast`, `fail_fast_stop_requested`, and `stopped_early` so this
optimization remains auditable and cannot silently alter scoring semantics.
`ClassNotFoundException`,
`NoClassDefFoundError`, invalid/no-test discovery, loader errors, missing files,
and worker failures are infrastructure errors and are excluded from the
mutation-score denominator. `LIVE` means the same suite still passes.

The global defaults are a 60-second infrastructure-startup ceiling, 4 seconds
for each individual JUnit test method, and a 60-second no-active-test lifecycle
ceiling. These settings apply to ChatTester, MutGen, MuTAP, HITS, A3Test,
ASTER, IntUT, EvoSuite, and Randoop because every adapter delegates mutation
evaluation to this module. Baseline-native generation/search limits are a
different clock: EvoSuite `search_budget` and Randoop `time-limit` are guarded
by a separate outer process ceiling of at least budget plus 120 seconds.

Batch comparison uses two explicitly different metrics:

- primary `End-to-End MS = KILLED / (KILLED + LIVE +
  NOT_EXECUTED_INVALID_TEST)`;
- diagnostic `Executable MS = KILLED / (KILLED + LIVE)`.

`NOT_EXECUTED_INVALID_TEST` is counted per unique physical mutant when its
generated target test fails generation, compilation, original-program
execution, or the paper-defined repair limit. It is not mislabeled as `LIVE`,
but it enters the primary denominator because the baseline failed to supply an
executable test. Evaluation-infrastructure failures remain excluded from both
denominators and are reported separately. `equivalent` is audit metadata and
never creates an adjusted score. If
duplicate rows for the same class disagree on that label,
`equivalent_label_conflicts` is incremented.

Project-local reports use a short `mx/<millisecond-id>` path because Java 8 on
Windows still encounters `MAX_PATH`; `index.json` and the result rows preserve
the full semantic identity. Baselines run directories retain pointers and
summaries. All twelve workbook fields are copied to every mutant result row.

Mutation workers are bounded by `java.mutant.workers` in
`Baselines/configs/java.properties`; `--workers N` overrides it for one run.
The original baseline executes once before the pool. Parallelism only
orchestrates separate JVMs, so no mutant shares a class loader or overlay.

Mutation-guided methods may evaluate an intermediate original-passing test
snapshot without publishing it as the final suite:

```powershell
python -B adapters/common/mutant_execution.py `
  --run-dir runs/<target> `
  --artifact-result <project-round>/report/result.json `
  --execution-id mutgen-r01 `
  --no-publish-latest
```

The explicit snapshot must have both `compile.compiled=true` and
`execution.passed=true`. ChatTester continues to use the published-final
default path; therefore this extension does not change its execution protocol.

## Coverage-aware mutation feedback

Mutation-guided Baselines may call `jacoco_coverage.py` after an intermediate
suite passes on the original program. The collector starts a separate JVM with
the JaCoCo agent, restricts instrumentation to the focal class, and analyzes
the exact original `.class`. `class_id_match=false`, a missing execution file,
or a failing original suite is an infrastructure failure rather than coverage
evidence. The agent version/path is configured by `java.jacoco.version` and
optional `java.jacoco.agent.jar` in `configs/java.properties`.

MuJava's workbook line can refer to its pretty-printed mutant source and must
not be compared directly with current-project JaCoCo lines. The shared mapper
first confines matching to the focal method, reverses the recorded mutation
when possible, and otherwise requires a unique original-fragment match.
Unresolved mappings remain explicit. For a surviving mutant only:

- proven mapped line covered: `LIVE`;
- proven mapped line not covered: `NO_COVERAGE`;
- unresolved mapping: conservatively `LIVE`.

These labels are feedback semantics for methods such as MutGen; the common
final mutation score continues to use actual KILLED/LIVE JVM outcomes.

After all `ORIGINAL_PASSED` targets have run mutation execution, summarize the
whole batch, including invalid targets, with:

```powershell
python -B adapters/common/summarize_mutation_batch.py `
  --batch-dir runs/<batch_id>
```

The command writes `mutation_batch_summary.json` and
`mutation_batch_results.jsonl` in the batch directory.

Formal scoring is fail-closed: if any `ORIGINAL_PASSED` target has no usable
mutation result, the command raises an error instead of silently shrinking the
denominator. `--allow-incomplete` may be used only for troubleshooting; its
output has `formal_score_valid: false` and is not a publishable score.
