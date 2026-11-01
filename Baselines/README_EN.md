# Baseline Reproduction and Unified Evaluation

This workspace reproduces seven LLM-based unit-test generation methods and two traditional generators, EvoSuite and Randoop. Tests produced by every method are evaluated against the same mapped MuJava mutants.

This file is the top-level runbook. For paper-specific stages, prompts, stop conditions, and known deviations, also read the corresponding `adapters/<Baseline>/README.md`.

## 1. Implemented methods

| Baseline | Family | Current implementation | Batch entry point |
|---|---|---|---|
| [ChatTester](adapters/ChatTester/README.md) | LLM | Intention analysis, test generation, and paper-defined compile-feedback repair | `adapters/ChatTester/run_batch.py` |
| [MutGen](adapters/MutGen/README.md) | LLM + mutation feedback | Method summary, generation, compile/assertion repair, and mutation guidance | `adapters/MutGen/run_batch.py` |
| [MuTAP](adapters/MuTAP/README.md) | LLM + mutation feedback | Initial generation, syntax repair, survivor augmentation, and minimization | `adapters/MuTAP/run_batch.py` |
| [HITS](adapters/HITS/README.md) | LLM + method slicing | Semantic slicing, per-slice generation/repair, and suite merging | `adapters/HITS/run_batch.py` |
| [A3Test](adapters/A3Test/README.md) | LLM | Assertion-augmented knowledge and one-shot complete test generation; DeepSeek adaptation | `adapters/A3Test/run_batch.py` |
| [ASTER](adapters/ASTER/README.md) | LLM + static analysis | Initial generation, compile/runtime remediation, and uncovered-line augmentation; independent Java reimplementation | `adapters/ASTER/run_batch.py` |
| [IntUT](adapters/IntUT/README.md) | LLM + intention/RAG | PAINT-style intentions, project RAG, and five-candidate selection; independent reimplementation | `adapters/IntUT/run_batch.py` |
| [EvoSuite](adapters/EvoSuite/README.md) | Search-based | EvoSuite 1.0.6 with a fixed seed, search budget, and auditable source normalization | `adapters/EvoSuite/run_batch.py` |
| [Randoop](adapters/Randoop/README.md) | Feedback-directed random | Randoop 4.3.4 `gentests`, using RegressionTest suites | `adapters/Randoop/run_batch.py` |

All LLM methods use the DeepSeek-compatible endpoint configured in `configs/llm.properties`. EvoSuite and Randoop never call an LLM, so their LLM call and token counts are always zero.

## 2. Mandatory directory boundaries

### 2.1 Baselines directory layout

The tree below expands only stable directories and primary entry points. Large run outputs, complete author repositories, and method-generated artifacts are intentionally abbreviated.

```text
Baselines/
├─ .vscode/
│  ├─ launch.json                    VS Code/F5 debug and batch launch configurations
│  └─ settings.json                  Workspace Python and related settings
├─ adapters/
│  ├─ common/                        Infrastructure shared by every method
│  │  ├─ java-tools/                 Isolated JUnit worker and Java helpers
│  │  ├─ build_target_manifest.py    Excel -> JSONL manifest conversion
│  │  ├─ llm_gateway.py              DeepSeek/OpenAI-compatible gateway
│  │  ├─ java_test_pipeline.py       Java test compilation and original validation
│  │  ├─ mutant_execution.py         Isolated MuJava mutant execution
│  │  └─ summarize_*.py              Token, efficiency, and mutation summaries
│  ├─ ChatTester/                    ChatTester reproduction implementation
│  ├─ MutGen/                        MutGen reproduction implementation
│  ├─ MuTAP/                         MuTAP reproduction implementation
│  ├─ HITS/                          HITS reproduction implementation
│  ├─ A3Test/                        A3Test reproduction implementation
│  ├─ ASTER/                         ASTER reproduction implementation
│  ├─ IntUT/                         IntUT reproduction implementation
│  ├─ EvoSuite/                      EvoSuite 1.0.6 control and local configuration
│  └─ Randoop/                       Randoop 4.3.4 control and local configuration
├─ configs/
│  ├─ llm.properties                 Local LLM settings and secret; never commit
│  ├─ llm.properties.example         Common LLM configuration template
│  ├─ java.properties                Local Java/mutation settings
│  └─ java.properties.example        Common Java configuration template
├─ data/
│  ├─ mutant_total.xlsx              Original mutant-association workbook
│  ├─ target_manifest.jsonl          Full target manifest
│  ├─ target_manifest.*.jsonl        Smoke/project-subset manifests
│  ├─ *.summary.json                 Manifest statistics and audit summaries
│  └─ README.md                      Data fields and preprocessing notes
├─ ReferencePapers/
│  ├─ *.pdf                          Papers and local reference material
│  ├─ baseline_reference_table.md    Paper, code, and artifact link table
│  └─ baseline_reproduction_implementation_plan.md
│                                      Unified reproduction plan and experiment protocol
├─ runs/
│  └─ <run_id>/                      Calls, tests, logs, and summaries for one experiment
├─ sources/
│  └─ <Baseline>/                    Read-only original author code/artifact
├─ README.md                         Chinese top-level runbook
├─ README_EN.md                      English top-level runbook
├─ 注意事项.txt                       Local execution notes
└─ .gitignore                        Secret, cache, and run-artifact ignore rules
```

Top-level responsibilities:

| Directory | Purpose | Rule |
|---|---|---|
| `.vscode` | F5 launch configurations and workspace settings | Adapt the local Python path when needed; keep argument semantics aligned with the CLI |
| `adapters/common` | Manifest, LLM gateway, token/logging, compilation, JUnit, coverage, mutant execution, and summaries | Contains only logic reusable by multiple baselines |
| `adapters/<Baseline>` | One method's prompts, protocol, context construction, generation/repair loop, and entry points | Editable; must not contain another method's private behavior |
| `configs` | Cross-method runtime configuration | Real secrets stay only in local `llm.properties`; paper-specific settings stay with the adapter |
| `data` | Source workbook and frozen manifests | Formal runs consume frozen JSONL; inputs must not be rewritten during execution |
| `ReferencePapers` | Papers, link table, and master implementation plan | Documentation and protocol only; no run artifacts |
| `sources/<Baseline>` | Author repository, pinned snapshot, or original artifact | Read-only; never build, install, clean, or generate files in place |
| `runs/<run_id>` | Auditable record of one Baselines experiment | Always create a new directory; never overwrite an old run |
| `<project_root>/baselines` | Subject-linked Java/classes and compile/JUnit/mutant artifacts | Never write into the subject's real source or build directories |

A method adapter typically contains `README.md`, `METHOD_MAPPING.md`, local properties, `protocol.py`/`context.py`, `prepare_run.py`, `run_pipeline.py`, `run_batch.py`, and `test_*.py`. Paper workflows differ, so not every adapter must contain every filename. Use Section 1 and the method-specific README as the authoritative entry-point index.

### 2.2 Read/write boundary

```text
sources/<Baseline>    Read-only author repository or artifact
adapters/<Baseline>   Editable reproduction implementation
adapters/common       Infrastructure shared by all methods
configs               Common LLM and Java configuration
data                  Workbook and frozen manifests
runs/<run_id>         Prompts, responses, logs, and summaries for one run
<project>/baselines   Generated Java/classes and compile/JUnit/mutant artifacts
MuTestLLM              Read-only reference; reproduction code must not be written here
```

Non-negotiable rules:

- never edit, format, build, or clean author files under `sources`;
- never modify `MuTestLLM`; existing code there may only be inspected as a reference;
- method-specific behavior belongs in `adapters/<Baseline>`;
- shared manifest, LLM, compilation, JUnit, and mutation logic belongs in `adapters/common`;
- every experiment uses a new `runs/<run_id>` and never overwrites an old run;
- generated Java and `.class` files live in an isolated subject-project subtree, never in the real `src`, `target`, or existing test directories.

Subject-project artifacts are stored under:

```text
<project_root>/baselines/<Baseline>/runs/<experiment_run_id>/<method_id>/
```

## 3. Environment

The current setup uses:

- Windows PowerShell;
- Python 3.8;
- JDK 8, or a JDK compatible with the subject project;
- the subject project's native Maven/Ant tooling;
- VS Code Python Debugger for F5 workflows.

On another machine, adjust the configured path first, or use `python` directly from the command line.

Build the shared Java tools once:

```powershell
./adapters/common/java-tools/build.ps1
```

The isolated JUnit worker and result collector are built only under `adapters/common/java-tools/target`.

## 4. Configuration

### 4.1 Common LLM configuration

Create the local file from the template:

```powershell
Copy-Item configs/llm.properties.example configs/llm.properties
```

At minimum, configure:

```properties
llm.provider=deepseek
llm.providers.deepseek.api.url=<OPENAI_COMPATIBLE_CHAT_COMPLETIONS_URL>
llm.providers.deepseek.model=deepseek-v4-flash
llm.providers.deepseek.api.key=<SECRET>
llm.max.tokens=16000
llm.max.input.tokens=16000
llm.threads=8
```

The real key belongs only in the ignored `configs/llm.properties`; never put it in documentation, prompts, logs, or commits.

Paper-specific temperatures and output budgets live in:

```text
adapters/<Baseline>/llm.properties
```

Configuration precedence is fixed:

```text
code default
< configs/llm.properties
< common environment override
< adapters/<Baseline>/llm.properties
< explicit call argument
```

### 4.2 Java and mutation configuration

Copy the template and set the local Maven repository:

```powershell
Copy-Item configs/java.properties.example configs/java.properties
```

The current formal protocol is:

```properties
java.mutant.startup.timeout.seconds=60
java.mutant.test.timeout.seconds=4
java.mutant.lifecycle.timeout.seconds=60
java.mutant.workers=4
```

Timing semantics:

- JVM startup, classpath lookup, and test-class loading use a separate 60-second infrastructure ceiling;
- every `@Test` receives its own four-second behavioral deadline starting at `TEST_STARTED`;
- original-program validation always exhausts the complete suite;
- mutant execution stops after the first assertion failure, exception, resource error, or mapped per-test timeout;
- a `LIVE` mutant must exhaust the complete suite;
- worker count changes throughput only, never timing boundaries or scoring semantics.

### 4.3 Traditional generator configuration

- EvoSuite: `adapters/EvoSuite/evosuite.properties`;
- Randoop: `adapters/Randoop/randoop.properties`.

Search time, seed, and generation workers are native generator parameters and are separate from LLM token budgets.

## 5. Build the target manifest from Excel

The default input is:

```text
data/mutant_total.xlsx
```

Choose `1. Build Full Target Manifest` in VS Code and press F5, or run:

```powershell
python -B adapters/common/build_target_manifest.py
```

Default outputs:

```text
data/target_manifest.jsonl
data/target_manifest.jsonl.summary.json
```

The manifest retains the original workbook fields and source-record mapping.

Common manifests:

| File | Purpose |
|---|---|
| `target_manifest.jsonl` | Full formal dataset |
| `target_manifest.first10.jsonl` | Five-method VS Code smoke/F5 input |
| `target_manifest.commons-jxpath-1.3.first5.jsonl` | First five unique commons-jxpath methods |
| `target_manifest.smoke.jsonl` | Minimal infrastructure check |

## 6. VS Code/F5 quick start

Open this directory itself in VS Code:

```text
<current-project-path>\Baselines
```

### 6.1 One-command five-method batches

Select a launch configuration, enter a brand-new output directory, and press F5:

| Baseline | F5 configuration |
|---|---|
| MutGen | `MutGen A. Full Batch End-to-End` |
| MuTAP | `MuTAP A. Full Batch End-to-End` |
| HITS | `HITS A. Full Batch End-to-End (5 Methods)` |
| A3Test | `A3Test A. Full Batch End-to-End (5 Methods)` |
| ASTER | `ASTER A. Full Batch End-to-End (5 Methods)` |
| IntUT | `IntUT A. Full Batch End-to-End (5 Methods)` |
| EvoSuite | `EvoSuite A. Full Batch End-to-End (5 Methods)` |
| EvoSuite/commons-jxpath | `EvoSuite B. commons-jxpath-1.3 (5 Methods)` |
| Randoop | `Randoop A. Full Batch End-to-End (5 Methods)` |

These entry points perform the applicable generation, compilation, original-program validation, mutant execution, and batch summary stages.

### 6.2 ChatTester four-step workflow

ChatTester deliberately exposes four F5 stages:

1. `ChatTester A. Generate Batch (5 Methods)`;
2. `ChatTester B. Compile + Original Program`, using exactly the same batch directory;
3. run `ChatTester C. Mutants (One Passed Target)` once for every `ORIGINAL_PASSED` `targets/<nnn>` directory;
4. run `ChatTester D. End-to-End Mutation Summary` with the same batch directory.

### 6.3 Step-by-step debugging

MutGen, HITS, A3Test, ASTER, and IntUT provide `1. Prepare` and `2. Run Prepared`. MuTAP provides `1. Prepare Step-by-Step Batch` and `2. Run Prepared Batch` for five methods.

Rules:

- the Prepare `run-dir`/`batch-dir` must not already exist;
- Run Prepared must receive exactly the directory created by Prepare;
- never pass a run directory as `--manifest`; a manifest is a `.jsonl` file.

## 7. Command-line execution

Run every command from `Baselines`. Use a baseline name and millisecond timestamp in `<run_id>`.

### 7.1 ChatTester

```powershell
python -B adapters/ChatTester/run_batch.py `
  --manifest data/target_manifest.first10.jsonl `
  --limit 5 `
  --batch-dir runs/<run_id> `
  --workers 5

python -B adapters/ChatTester/run_feedback_batch.py `
  --batch-dir runs/<run_id> `
  --workers 5

# Run once for each ORIGINAL_PASSED targets/<nnn>
python -B adapters/ChatTester/run_mutant_execution.py `
  --run-dir runs/<run_id>/targets/001 `
  --workers 4

python -B adapters/common/summarize_mutation_batch.py `
  --batch-dir runs/<run_id>
```

### 7.2 Integrated LLM pipelines

Replace `<Baseline>` with `MutGen`, `HITS`, `A3Test`, `ASTER`, or `IntUT`:

```powershell
python -B adapters/<Baseline>/run_batch.py `
  --manifest data/target_manifest.first10.jsonl `
  --limit 5 `
  --batch-dir runs/<run_id> `
  --workers 5 `
  --mutant-workers 4
```

MuTAP additionally fixes the survivor seed:

```powershell
python -B adapters/MuTAP/run_batch.py `
  --manifest data/target_manifest.first10.jsonl `
  --limit 5 `
  --batch-dir runs/<run_id> `
  --workers 5 `
  --mutant-workers 4 `
  --survivor-seed 42
```

### 7.3 EvoSuite

```powershell
python -B adapters/EvoSuite/run_batch.py `
  --manifest data/target_manifest.commons-jxpath-1.3.first5.jsonl `
  --limit 5 `
  --batch-dir runs/<run_id> `
  --workers 1 `
  --mutant-workers 4 `
  --seed 42
```

### 7.4 Randoop

```powershell
python -B adapters/Randoop/run_batch.py `
  --manifest data/target_manifest.commons-jxpath-1.3.first5.jsonl `
  --limit 5 `
  --batch-dir runs/<run_id> `
  --workers 1 `
  --mutant-workers 4 `
  --seed 42
```

`--workers` controls independent target generation. `--mutant-workers` controls isolated mutant JVM concurrency. Traditional generators default to one generation worker to avoid CPU and memory contention between searches.

## 8. End-to-end workflow

```text
mutant_total.xlsx
  -> target_manifest.jsonl
  -> select N unique method targets
  -> execute the baseline-native generation protocol
  -> compile tests
  -> validate the complete suite on the original program
  -> execute the same suite against every mapped physical mutant
  -> fail-fast KILLED / KILLED_TIMEOUT / KILLED_RESOURCE classification
  -> summarize mutation scores, tokens, calls, repairs, and time
```

Generation must not see mutation statements, equivalence labels, or kill outcomes unless the paper's native method explicitly uses mutation feedback, as MuTAP and MutGen do in their defined stages.

## 9. Output locations

Batch directory:

```text
runs/<run_id>/
  batch_metadata.json
  batch_results.jsonl
  batch_summary.json
  mutation_batch_results.jsonl
  mutation_batch_summary.json
  efficiency_summary.json
  targets/<nnn>/
```

Important files:

| File | Contents |
|---|---|
| `mutation_batch_summary.json` | KILLED/LIVE/invalid counts, both scores, and formal validity |
| `mutation_batch_results.jsonl` | Per source record and physical-mutant execution details |
| `efficiency_summary.json` | Calls, repairs, compilation/execution counts, and stage times |
| `targets/<nnn>/token_usage_summary.json` | Per-target input, output, cache, and total tokens |
| `targets/<nnn>/state.json` | Final target state |
| `targets/<nnn>/calls/` | Sanitized model requests, responses, and call metadata |
| `targets/<nnn>/final/` or project artifact tree | Accepted Java tests, classes, and reports |

Project-local Java artifacts are under:

```text
<project_root>/baselines/<Baseline>/runs/<run_id>/<method_id>/
```

## 10. Scoring

Primary metric:

```text
End-to-End MS
= KILLED / (KILLED + LIVE + NOT_EXECUTED_INVALID_TEST)
```

Diagnostic metric:

```text
Executable MS
= KILLED / (KILLED + LIVE)
```

Interpretation:

- the killed total includes ordinary failures, mapped per-test timeouts, and resource-error kills;
- `NOT_EXECUTED_INVALID_TEST` means the method failed to produce a test that compiles and passes on the original program;
- infrastructure failures are reported separately and are never disguised as `LIVE`;
- `equivalent` is audit metadata and is not automatically removed from the formal denominator;
- denominators use unique physical mutants, so duplicate workbook associations do not inflate scores.

Only a batch with `formal_score_valid=true` is eligible for formal reporting.

## 11. Tokens, calls, and time

Every LLM method records:

- logical calls and actual provider requests;
- initial-generation calls and paper-permitted repair/augmentation calls;
- Uncached Input, Cache Read, Cache Write, Output, and Total Token counts;
- cache hit rate: `Cache Read / (Cache Read + Uncached Input)`;
- preprocessing, generation, compilation, original validation, mutation execution, and wall-clock time.

Network retries are infrastructure resilience. They do not increment logical paper calls or repair rounds, but actual provider-request counts remain available for auditing.

## 12. Verification

Run offline tests for one adapter:

```powershell
python -B -m unittest discover -s adapters/ChatTester -p "test_*.py"
python -B -m unittest discover -s adapters/common -p "test_*.py"
```

Replace the directory with another baseline as needed. Offline tests must not call the real LLM unless an explicit gateway integration check is selected.

## 13. Troubleshooting

### `PermissionError` points to a run directory

The run directory was probably passed as `--manifest`. A manifest must be a `.jsonl` file; `--run-dir` or `--batch-dir` is the output directory.

### A script appears idle

The full manifest is large. Workbook conversion and first-time dependency resolution may take time. Check CPU, disk activity, and log timestamps before starting a duplicate run.

### Can an old run be overwritten?

No. Always use a new directory. Old runs are audit evidence; a configuration or timeout-protocol change requires a new execution/run ID.

### Why stop after the first failing test?

Original-program validation still executes the entire suite. Fail-fast applies only to mutants: one originally passing test that now fails is sufficient to kill the mutant, and later tests cannot change the mutation score.

## 14. Further documentation

- [Master reproduction plan](ReferencePapers/baseline_reproduction_implementation_plan.md)
- [Paper and artifact link table](ReferencePapers/baseline_reference_table.md)
- [Shared infrastructure](adapters/common/README.md)
- [Data directory](data/README.md)
- Method-specific READMEs linked in Section 1.
