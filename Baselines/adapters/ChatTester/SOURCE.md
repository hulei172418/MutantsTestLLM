# ChatTester source record

## Upstream

- Repository: `https://github.com/FudanSELab/ChatTester.git`
- Branch: `main`
- Pinned commit: `af8ea1c643a401317c54ee7936d829851d512109`
- Retrieved: 2026-10-28
- Local bare repository: `Baselines/sources/ChatTester/repository.git`
- Selected source export: `Baselines/sources/ChatTester`

No top-level license file was found at the pinned commit. Before redistributing
upstream source or a derived package, confirm the authors' licensing terms. This
does not block local research inspection, but it must be reported as an artifact
limitation.

## Why the repository is stored as bare + selected export

The upstream tree contains generated experiment files whose names embed Java
method signatures, for example `method(Class<?> value).java`. Such names cannot
be checked out on Windows because `?`, `<`, and `>` are invalid NTFS filename
characters.

Both normal checkout and non-cone sparse checkout were attempted and failed when
Git for Windows traversed those paths. The official Git objects are therefore
preserved in `repository.git`, while the required files were exported from the
pinned commit using an explicit whitelist and a one-command-only
`core.protectNTFS=false` override. Invalid generated artifacts were not extracted.

Exported material includes:

- `ReadMe.md`;
- initial and iterative generation scripts under `ExperimentCode`;
- compile/test feedback logic under `ExperimentCode/Deal`;
- `Java_Analyzer` source, Maven project, bundled dependency, and author-committed
  class files;
- coverage scripts and generated coverage data under `CoverageCal`;
- representative experiment metadata under `RepoData`.

These author files are retained even when the adapted runtime does not execute
them, so the paper artifact remains inspectable. The pinned bare repository is
still the authoritative complete Git object store; invalid NTFS filenames are
the only reason a normal full checkout is not present.

## Verified environment

- Local JDK: Oracle JDK 8u202
- Local Maven: 3.8.1
- Upstream README requirement: Python >= 3.7 and JDK 8u131
- Historical compatibility check: `Java_Analyzer` compiled successfully with
  JDK 8u202 and Maven 3.8.1 on 2026-10-28.
- The generated `Java_Analyzer/target` was removed after verification. `sources`
  is now a read-only snapshot and must not be compiled in place.
- Executable context extraction lives in
  `Baselines/adapters/common/java-tools`; any future ChatTester-only Java code
  must be copied into `Baselines/adapters/ChatTester` before modification/build.

The JDK patch version differs from the paper artifact (8u202 versus 8u131) and
must be recorded in the experiment metadata.

## Important upstream behaviors

The official scripts currently:

- hard-code `gpt-3.5-turbo` and an old OpenAI-compatible endpoint;
- contain API-key placeholders directly in Python source;
- import local Hugging Face/CodeLlama dependencies even when the remote model is used;
- hard-code a macOS JDK path;
- assume three upstream experiment repositories under `Repos`;
- delete and recreate result directories;
- run `git add`, `git commit`, `git restore .`, and `git clean -fd` in target projects;
- name generated files with complete method signatures, which is not Windows-safe;
- parse Maven/Surefire output with path- and locale-sensitive string rules.

The official scripts must not be run unmodified against the MutantsTestLLM
workspace. The adapter will use isolated project copies, run-specific output
directories, safe hashed filenames, and the shared DeepSeek-V4-Flash gateway.

## Implementation identity

The experiment implementation is named `ChatTester-DS-Adapted` because it keeps
ChatTester's intention, initial generation, compilation/execution feedback, and
iterative refinement strategy while replacing the model backend and project
plumbing.

