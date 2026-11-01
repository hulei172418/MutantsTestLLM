# ASTER source evidence

- Paper: *ASTER: Natural and Multi-language Unit Test Generation with LLMs*
  (arXiv:2409.03093; ICSE-SEIP 2025).
- Public artifact: <https://github.com/aster-test-generation/aster>
- Fixed artifact commit: `a6d8ab7b6ee43926821f490cb9e1844f35e7e88d`.
- Local immutable evidence: `Baselines/sources/ASTER`.

The public repository contains generated Java/Python tests, figures, the
naturalness evaluator, and a modified CodaMOSA copy. It does **not** contain the
complete ASTER Java generation engine. `codamosa/` is the Python baseline used
by the paper and must not be represented as ASTER's Java implementation.

The prompt evidence is the paper and `figures/prompts.png`. Generated Java
artifacts preserve separate `ASTER_*_Test_{Passing,Failing}.java` and
`ASTER_*_Coverage_Test_{Passing,Failing}.java` files. This supports an initial
generation phase followed by a coverage-augmentation phase, but does not reveal
the original numeric repair limit or coverage threshold.

Do not build, execute, or edit the source snapshot. All reimplementation work
belongs to `Baselines/adapters/ASTER`.
