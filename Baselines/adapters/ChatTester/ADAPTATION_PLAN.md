# ChatTester-DS-Adapted implementation plan

## Preserved method stages

1. Build focal-method context from class declaration, fields, constructors,
   signatures, imports, and related methods.
2. Generate a concise method intention.
3. Generate an initial JUnit 4 test using the context and intention.
4. Compile and run the test on the original program.
5. Parse compilation or runtime feedback.
6. Ask the model to repair the test for a bounded number of rounds.
7. Run the accepted suite on the MuJava mutants belonging to the focal method.

The no-intention path is retained only as an ablation.

## Unified model bridge

ChatTester must not call `openai.ChatCompletion.create` directly. Its Python
adapter writes an auditable request JSON and invokes:

```text
Baselines/adapters/common/llm_gateway.py
```

The gateway is owned and built inside Baselines. Common transport settings and
ChatTester stage settings are layered automatically:

```text
Baselines/configs/llm.properties
        + adapters/ChatTester/llm.properties
        -> OpenAI-compatible HTTP -> retry/cache/result metadata
```

The common file owns provider/URL/model/key/network/cache/concurrency. The local
file owns only `llm.stages.chattester_*` settings. Missing local keys fall back
to common settings and then code defaults; there is no local/global mode flag.

The common client supports a stage-specific system prompt. The cache key
includes baseline, stage, system prompt, user prompt, model profile, temperature,
and output budget.

The high-level adapter follows the reference batch generator's separation:
configuration is loaded once for policy decisions, a logical call chooses
reuse or fresh generation, and the low-level client owns HTTP/retry/cache.
An explicit call option overrides cache behavior; otherwise
`llm.force.regenerate` controls it.

Example request schema:

```json
{
  "baseline": "ChatTester-DS-Adapted",
  "stage": "chattester_intention",
  "system_prompt": "Return one concise method intention as JSON.",
  "user_prompt": "<focal method context>",
  "max_tokens": 1000,
  "use_cache": true,
  "cache_dir": "<run-dir>/llm_api_cache"
}
```

The response file contains the provider/model metadata, parsed content, raw API
response, cache status, retry count, elapsed time, and finish reason. It never
contains the API key.

## Stage mapping

| Local stage | Upstream behavior | Required output |
|---|---|---|
| `chattester_intention` | `Unit.intention_unit` | intention JSON/text |
| `chattester_initial` | `Unit.method_pred_unit` | complete JUnit 4 source |
| `chattester_compile_repair` | repair branch of `method_pred_unit` | complete JUnit 4 source |
| `chattester_runtime_repair` | iterative refiner feedback | complete JUnit 4 source |

The native protocol does not impose adapter-invented limits. Upstream performs
one initial intention call and one initial-generation call, compiles and runs
the test, and only then enters feedback. Each repair iteration makes one repair
call followed by compilation and execution. Runtime feedback, and compile
feedback after `re_generate_Tag` is enabled, also makes an additional intention
call. Every logical call is logged and counted.

The pinned upstream stop expression is exactly `iter > 3 or IterTest > 5 or
TotalIter > 30`. Its initial values, increment positions, and strict `>`
semantics must be ported and boundary-tested; it must not be simplified into an
invented “two compile repairs / one runtime repair” policy. Equal-budget
truncation, if studied, is a separate labeled experiment and never changes the
native run.

Upstream explicitly uses `temperature=0` for intention, initial generation, and
repair calls. The Baselines gateway therefore uses stage-specific temperature
0 for all four ChatTester stages. Output-token limits are not specified in the
upstream OpenAI calls; any DeepSeek transport ceiling is an adaptation delta,
not a paper parameter.

## Configuration provenance

| Setting | Effective value | Source classification |
|---|---|---|
| stage order and conditional intention calls | pinned `InitialPhrase_Experiment.py` / `ChatGptTester.py` flow | `AUTHOR_CODE` |
| system/user prompt templates | pinned author templates, with only path-safe rendering changes | `AUTHOR_CODE` |
| four ChatTester stage temperatures | `0` | `AUTHOR_CODE` |
| stop expression | `iter > 3 or IterTest > 5 or TotalIter > 30` with persistent `repairTag` | `AUTHOR_CODE` |
| model/provider | configured DeepSeek-V4-Flash profile | `API_REQUIRED_ADAPTATION` |
| output ceilings | all ChatTester stages `16000` | `API_REQUIRED_ADAPTATION`; upstream does not specify them; equal DeepSeek comparison budget |
| input ceiling | `llm.max.input.tokens=16000`, `llm.max.input.chars=30000`; optional stage override | `API_REQUIRED_ADAPTATION`; upstream does not specify one |
| oversize input | `llm.oversize.prompt.policy=truncate`; preserve prompt head and tail, record both sizes | `API_REQUIRED_ADAPTATION`; configuration-controlled |
| connect/read timeout and retry count | from `configs/llm.properties` | `API_REQUIRED_ADAPTATION`; not paper parameters |
| isolated direct `javac` + `JUnitCore` backend | shared project classpath, JUnit 4 | `EVALUATION_COMMON`; replaces upstream in-tree Maven writes for safety/isolation |
| local Java runtime | JDK 8u202 | environment delta from the reported/author JDK 8u131, not a tuned method setting |

The direct compiler/executor must preserve real compiler and JUnit feedback, but
it is not presented as the author's Maven command. Both the command array and
resolved classpath are persisted per round so this delta remains auditable. An
additional native-Maven validation may be run in an isolated project copy, but
must not silently replace this common backend or write into the source project.

## Required safety changes

- Never run upstream `git clean -fd`, `git restore`, or automatic commits in a
  user project.
- Work from a run-specific isolated copy or a prepared read-only source plus
  separate build directory.
- Replace method-signature filenames with a stable SHA-256-based identifier and
  keep the original signature in `metadata.json`.
- Do not delete a previous run directory; create a new run ID.
- Use structured process exit codes instead of searching only for `BUILD SUCCESS`.
- Force English Maven/Javac output when the upstream error parser is used.
- Store raw and normalized tests separately.
- Store generated `.java` and `.class` files only under
  `<project_root>/baselines/ChatTester/runs/<experiment_run_id>/<method_id>/`.
  Use round-specific `src`, `classes`, and `report` directories so stale classes
  cannot make a later repair appear compilable. Never write into project
  `src`, `target`, `build`, or existing test directories.
- Do not provide mutant diff, RIP evidence, or MutantsTestLLM tests to ChatTester.

## Implemented through 2026-10-29

1. `deepseek_gateway.py` invokes the Baselines-owned `llm_gateway.py`, stores
   exchanges per run, and never accepts an API key as an argument.
2. `llm.py` replaces the three direct OpenAI behaviors with the
   stage names `chattester_intention`, `chattester_initial`,
   `chattester_compile_repair`, and `chattester_runtime_repair`.
3. The original code parser behavior is retained in a safer fenced-Java parser;
   three offline unit tests cover imports, JUnit 4 annotations, method bodies,
   and same-package test shells.
4. `python deepseek_gateway.py --check` validates the Baselines-local module
   without reading model credentials or calling the API; it reports
   `mutestllm_accessed=false`.
5. ChatTester's upstream `Java_Analyzer` passed a historical compatibility
   check, after which its generated `target` was removed. The executable common
   `java-tools` module builds under `Baselines/adapters/common`; `sources` is not
   compiled in place.
6. `build_target_manifest.py` produced a three-mutant smoke manifest for
   `commons-csv-1.2|org.apache.commons.csv.Token|void_reset()`; all original and
   mutated source paths resolve.
7. The shared `TargetContextCli` extracts fields, constructors, focal method,
   imports, and accessible signatures with JavaParser. `prepare_run.py` creates
   a complete intention-stage prompt and stops at the auditable
   `PREPARED_API_NOT_CALLED` boundary.
8. The Baselines common gateway uses the profile selected by `llm.provider` and
   takes its URL, model, API key, and optional provider overrides directly from
   `Baselines/configs/llm.properties`; it automatically overlays ChatTester's
   stage controls from `Baselines/adapters/ChatTester/llm.properties`.
9. `generate_initial_test.py` is ready to execute the intention and initial-test
   calls, archive their responses, and render a same-package JUnit 4 class. It
   refuses to start when the selected profile lacks URL/model/key values. The
   API key is never persisted in run artifacts.
10. On 2026-10-28, run `20260318_chattester_trace_schema_v2` completed a real
    intention plus initial-generation smoke. Both calls succeeded on the first
    attempt with model ID `deepseek-v4-flash`; the generated
    `Token_ChatTesterTest.java` reached `GENERATED_NOT_COMPILED`. Six
    call-to-mutant trace rows were written for the two calls and three mutants,
    with no missing original workbook fields and no API-key match in run files.
11. `adapters/common/project_artifacts.py` maps each target run to the isolated
    project-local Baseline tree. `java_test_pipeline.py` independently resolves
    the project classpath, compiles with `javac`, executes JUnit 4, and publishes
    only a passing round to `final`.
12. `run_feedback_pipeline.py` implements compilation feedback, runtime
    feedback, conditional intention regeneration, and the pinned strict stop
    expression. `run_feedback_batch.py` applies the same loop to a batch and is
    safe to resume completed targets without another model request.
13. A live Ant target demonstrated the full path: the initial test failed with
    a wrong package name, one `chattester_compile_repair` call corrected it, and
    the repaired test compiled and passed JUnit. This is an implementation
    smoke, not a formal result, because its initial generation came from the
    earlier non-native-temperature batch.
14. The common classpath builder now excludes legacy JUnit 3 jars whenever a
    JUnit 4 jar exists, preventing mixed-version class shadowing observed in the
    historical Ant smoke. The historical log is retained unchanged.
15. The common gateway now enforces the configured input-token and input-char
    ceilings before cache lookup or HTTP. It records the estimator, limits,
    original/sent sizes, and truncation status in request, response, and call
    metadata. Provider usage remains authoritative for post-call accounting.

The upstream code does not perform an LLM coverage-refinement stage: it repairs
compiler and Surefire runtime failures, then calculates JaCoCo coverage. Coverage
therefore remains an evaluation result rather than a prompt-feedback input.

## Next implementation tasks

1. Add the common MuJava mutant executor and link every execution row back to
   the twelve original workbook fields.
2. Exercise the accepted suite against its associated mutants only after the
   original program passes, then aggregate mutation score and failure classes.
3. Run a fresh formal ChatTester pilot in which initial and repair stages all
   use the pinned author temperature and the corrected JUnit 4-only classpath.

