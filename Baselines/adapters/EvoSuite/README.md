# EvoSuite 1.0.6 Control Baseline

This adapter is a traditional search-based control group independent of `MuTestLLM`. It references the workflows in `EvoSuiteTestGenerator.java`, `EvoSuiteCleaner.java`, and `EvoSuiteMutantKillDetailCollector.java`, but it neither imports nor modifies compiled artifacts in the `MuTestLLM` directory and does not depend on them.

Directory boundary: `run_batch.py` is only a command entry point. EvoSuite command construction, class-level deduplicated generation, runtime/scaffolding retention, failure classification, and method-level batch processing are all implemented in this directory's `pipeline.py`. `common` contains no EvoSuite method implementation; it only provides general Java compilation, original-program validation, mutant execution, and summarization.

## Fixed Protocol

- Generator: `lib/evosuite-master-1.0.6.jar`;
- SHA-256: `82DB121F31253925BB0FD5B55BD88FF16D7DB70D6A6CBA9C8511B52BC0C9BB88`;
- Inputs select the first N unique `project + class + method_signature` combinations;
- EvoSuite search is deduplicated by `project + class`, so each class is generated only once;
- Tests generated for a class are associated separately with each selected method and execute all physical mutants for that method;
- Default search budget is 60 seconds per class, with `max_size=20` and random seed 42;
- DeepSeek is not invoked, no LLM repair is performed, and token/API call counts are both zero;
- The common isolated MuJava executor is entered only after original-program validation passes;
- Mutation scores, timing, and execution counts are calculated by the common summarizer.

Formal tests retain the EvoSuite runtime and scaffolding. `raw_tests/` preserves the generator's original bytes. `clean_tests/` is non-destructively normalized by this directory's `source_cleaner.py` and serves as the formal compilation input. The cleaner removes the UTF-8 BOM and, when common JUnit 5 APIs are detected, converts imports, lifecycle annotations, and assertion message-argument order to JUnit 4. Input/output hashes, transformations, and remaining JUnit 5 symbols for each file are recorded in `cleaning_audit.json`; textual differences are recorded in `cleaning.diff`.

The old `EvoSuiteCleaner.java` strategy of deleting scaffolding, deleting all EvoSuite runtime statements, and recursively deleting subsequent statements along variable-dependency chains is not the formal default because it can remove test inputs or assertions and change mutant-killing ability. The current design retains runtime/scaffolding through the formal classpath, improving compilability while preserving original test semantics. Cleaning occurs only after EvoSuite has generated Java files and cannot bypass EvoSuite's own target-class exclusion rules.

EvoSuite 1.0.6's built-in `excluded.classes` contains `org.apache.tools.ant.`. Therefore, `org.apache.tools.ant.taskdefs.optional.junit.JUnitTest` is rejected before search because of the Ant package prefix, not because its class name contains `junit`, and not because of a collision between the cleaner and target-project files.

### commons-jxpath-1.3 Five-Method Validation

To avoid Ant packages natively excluded by EvoSuite 1.0.6 and rerun the validated five-method jxpath batch, select `EvoSuite B. commons-jxpath-1.3 (5 Methods)` in VS Code or run:

```powershell
python -B adapters/EvoSuite/run_batch.py `
  --manifest data/target_manifest.commons-jxpath-1.3.first5.jsonl `
  --limit 5 `
  --batch-dir runs/<new>_evosuite_jxpath5_001 `
  --workers 1 `
  --mutant-workers 4 `
  --seed 42
```

The 2026-03-21 validation batch `runs/20260318_evosuite_jxpath5_001` selected five unique methods, three unique classes, 549 original association records, and 346 unique physical mutants. All five test targets compiled and passed on the original program. The result was 282 killed, 64 live, and 0 invalid/error; both End-to-End MS and Executable MS were `282 / 346 = 81.50%`. This batch was rerun under the old “separate startup from JUnit, but share one behavioral limit across the whole suite” protocol: isolated JVM startup, classpath lookup, and non-initializing test-class loading used a 60-second infrastructure limit. The 231 `KILLED_TIMEOUT` results from the five-second sensitivity experiment were confirmed not to include JVM loading time, but all came from `SimplePathInterpreter_ESTest`, which has 29 tests, and clustered between 5.025 and 5.153 seconds. The original-program baseline for that suite was approximately 3.23–3.35 seconds, demonstrating a clear effect from four-worker concurrency and an overly tight threshold. The above `282 / 346 = 81.50%` is retained only as a five-second sensitivity result.

With the same tests, mutants, classpath, and four-worker configuration, the ten-second execution ID `20260318_junit10s_001` produced 118 killed, 228 live, and 0 timeout/error; both Executable MS and End-to-End MS were `118 / 346 = 34.10%`. The 118 killed results included 114 ordinary JUnit failures and four `KILLED_RESOURCE` outcomes. All four resource kills were `StackOverflowError` caused by mutant-program recursion and represent genuine mutant behavior. Under ten seconds, the original 231 five-second timeouts became 63 ordinary kills, four resource kills, and 164 live mutants, proving that the old high score mainly resulted from an overly tight threshold and concurrent scheduling. This ten-second execution still used the old “ten seconds shared by the whole suite” protocol; after enabling the per-test-method protocol, it remains only a historical diagnostic. Formal comparisons must be rerun with the current executor.

The TarEntry smoke suite at `runs/20260318_evosuite_smoke_tarentry_001` is larger: 94 test methods and 380 assertions, with every test in the old generated file carrying `@Test(timeout = 4000)`. The old whole-process five-second protocol produced 212 timeout kills and three live mutants. Execution ID `20260318_tarentry_junit10s_001`, using a shared ten-second whole-suite limit, produced one killed, 214 live, and zero timeouts, with MS=`1/215=0.47%`. Startup took only 128–369 ms, while the JUnit suite took 4.372–5.942 seconds; the old 98.60% result was a false positive caused by suite size and the concurrency boundary. That rerun did not reach the shared ten-second suite boundary and can remain as a diagnostic, but formal results still must be republished under the current per-test protocol.

The current formal protocol keeps one isolated JVM per mutant. Original-program validation executes every test. Mutant execution stops immediately at the first assertion failure, exception, resource error, or per-test timeout; only a `LIVE` mutant executes the entire suite. Each `@Test` independently receives four seconds, and `TEST_STARTED` resets the watchdog, so the whole suite no longer shares four seconds. `evosuite.test.timeout.seconds=4` is also passed to EvoSuite's native `timeout` parameter. `raw_tests/` remains byte-for-byte unchanged; formal `clean_tests/` auditably normalizes generated annotations to `@Test(timeout = 4000)`, with changes recorded in `cleaning.diff` and `cleaning_audit.json`.

## VS Code/F5

Open the `Baselines` folder, select `EvoSuite A. Full Batch End-to-End (5 Methods)`, and enter a new directory that does not exist. Equivalent command:

```powershell
python -B adapters/EvoSuite/run_batch.py `
  --manifest data/target_manifest.first10.jsonl `
  --limit 5 `
  --batch-dir runs/20260318_evosuite_batch_manual_001 `
  --workers 1 `
  --mutant-workers 4 `
  --seed 42
```

Parameters are configured in this directory's `evosuite.properties`. A formal multi-seed experiment must run each preregistered seed in a separate new batch directory; results from different seeds must not be written to the same directory.

## Results

- `class_generations/`: per-class deduplicated commands, logs, raw tests, and reports;
- `targets/<NNN>/`: method-level original-program compilation/execution and mutant execution;
- `mutation_batch_summary.json`: End-to-End MS and Executable MS;
- `efficiency_summary.json`: search, compilation, original-program, and mutant-execution times and counts;
- `batch_summary.json`: batch overview.

A missing JAR or classpath, or an inability to launch, is an infrastructure problem. Explicit target rejection by EvoSuite is a native generator limitation. Like a generated test that cannot compile or pass on the original program, such a target is recorded as an invalid baseline target, and its associated mutants enter the End-to-End MS denominator.
