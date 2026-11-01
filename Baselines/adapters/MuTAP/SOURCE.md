# MuTAP Source Pin

- Paper: *Effective Test Generation Using Pre-trained Large Language Models and Mutation Testing*, arXiv 2308.16557 / Information and Software Technology 171 (2024) 107468.
- Official repository: <https://github.com/ExpertiseModel/MuTAP>
- Pinned commit: `57c38c0c13df2c4d2b5714491e2c67a6d0463928` (commit date: 2025-03-07).
- Local read-only snapshot: `Baselines/sources/MuTAP`.
- GitHub codeload ZIP SHA-256 for the pinned commit: `0FDA94E5D8D1882981B0C25E540D3DD36B2CEC967DDF95C66144F4D9D5424211`.

Keep `sources/MuTAP` identical to the author artifact: do not install dependencies, compile, or modify it. Java/JUnit/MuJava adaptations belong only in `adapters/MuTAP`; common compilation and per-mutant isolated execution belong only in `adapters/common`.

## Verified Artifact Differences

- Paper algorithm: select one unused surviving mutant at a time, augment the tests, and rerun mutation testing; stop when all mutants are killed or no unused survivor remains.
- Pinned commit's Codex `augmented_prompt.py`: iterates over the initial survivor list to generate multiple files, which `Merge_all_mut.py` merges afterward; survivors are not recalculated inside the loop.
- The README states a maximum of 10 syntax attempts, while the pinned commit's `generate_test_oracle.py` actually passes `rep=3` to `check_test_oracle_synx`. Under the master plan's paper-first rule, the primary adapter uses the paper/README limit of 10 and exposes 3 only as an artifact-mode sensitivity setting, never mixed into the primary result.
