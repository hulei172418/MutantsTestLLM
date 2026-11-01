# MuTAP-Java-DS-Adapted

This adapter faithfully preserves MuTAP's core loop for the current Java projects: zero-shot initial tests, syntax repair, original-program behavior correction, mutation testing, prompt augmentation with one surviving mutant at a time, accumulated tests, rerunning, and greedy oracle minimization. All model calls are uniformly replaced with DeepSeek-V4-Flash.

The shared provider, URL, model, API key, network retry, and cache-reading settings come from `../../configs/llm.properties`. MuTAP-specific initial-generation, syntax-repair, and survivor-augmentation budgets/temperatures come from the `llm.properties` file in this directory. The common gateway automatically merges the two configuration layers by stage; no mode switch is used.

## Directory Boundaries

- `sources/MuTAP`: pinned author commit; read-only and never cross-modified.
- `adapters/MuTAP`: MuTAP-specific protocol and execution entry points.
- `adapters/common`: API, compilation, JUnit, and MuJava executors shared by ChatTester, MutGen, and MuTAP.
- `runs`: prompts, responses, tokens, rounds, and results.
- `baselines/MuTAP/runs/...` in the target project: generated `.java`, compiled `.class`, and isolated mutant-execution artifacts.

## Formal Protocol

1. Generate one complete JUnit 4 class for a deduplicated focal method.
2. Allow at most 10 syntax/compilation repairs. Each call must be triggered by a real failure; the call count is not fixed in advance.
3. Assertions that fail on the original program do not enter mutant evaluation, and runtime feedback is not sent to the LLM.
4. Execute the intermediate suite against all unique physical MuJava mutants.
5. Randomly select one currently LIVE, unused mutant (default seed 42), and provide only that mutant's source to the model.
6. Accumulate each new test after it passes on the original program, then immediately rerun all mutants.
7. Stop when all mutants are killed or no unused LIVE mutant remains. Infrastructure failures must not be disguised as LIVE.
8. Greedily minimize tests by their independent killed sets, then publish `final` and apply unified scoring.

For complete sources, differences, and execution instructions, see [SOURCE.md](SOURCE.md), [METHOD_MAPPING.md](METHOD_MAPPING.md), [ENVIRONMENT.md](ENVIRONMENT.md), and [REPRODUCE.md](REPRODUCE.md).

Both VS Code step-by-step mode and one-click batch mode select the first five deduplicated focal methods by default and retain every source record and unique physical mutant associated with each method. Step-by-step mode runs `prepare_batch.py` followed by `run_prepared_batch.py`; one-click mode runs `run_batch.py`.

## Efficiency, Call, and Repair Statistics

MuTAP reuses the common `summarize_efficiency.py`. Only `mutap_syntax_repair` counts as a native LLM repair round. `mutap_initial` is initial generation, and `mutap_survivor_augmentation` is mutation-feedback augmentation; neither may be miscounted as repair to inflate `r_avg`. Provider HTTP retries are reported separately as API Calls and also do not count as repair rounds.

Both step-by-step and one-click five-method modes ultimately generate target-level and batch-level `efficiency_summary.json` files containing `t_pre`, `t_gen`, `r_avg`, `t_total`, `t_avg`, call/retry counts, token/cache statistics, compilation/execution counts, and cost availability. Manual recomputation neither invokes the model nor reruns mutants:

```powershell
python -B adapters/common/summarize_efficiency.py `
  --batch-dir runs/<batch_id>
```

The denominator for averages is the number of deduplicated focal-method targets, not Excel rows or mutants. See `adapters/common/EFFICIENCY_METRICS.md` for the complete rules.
