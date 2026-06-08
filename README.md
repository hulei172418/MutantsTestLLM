# Multi-Source Evidence-Guided LLMs for Mutation Unit Test Generation

<img src="./figs/Fig2.png" alt="framework" width="1200">

This repository contains the replication package for our work on **LLM-based mutation-killing unit test generation**. The proposed approach, **RIP-Evidence**, constructs multi-source program evidence for each target mutant and guides large language models to generate JUnit tests that can reach the mutation point, trigger the state difference, propagate the difference to observable behavior, and compile in the real project environment.

Different from coverage-oriented test generation or mutation-feedback-only prompting, RIP-Evidence organizes mutation semantics, Reach/Infect/Propagate evidence, callable entry lifting, receiver construction, public API constraints, observable behavior, assertion plans, and compilation constraints into a compact `PromptEvidence` representation. The generated tests are then compiled, repaired using classified compiler feedback, and executed against the original and mutant versions.

---

**_Contact_**: Feel free to contact Lei Hu (hulei172418@gmail.com) if you have any questions about the code or replication package.

---

## 1. Overview

The workflow of this repository is Excel-driven. In most scripts, the `main` function reads a mutant metadata Excel file, parses each row as a target mutant or evaluation task, and then performs evidence construction, LLM test generation, compilation repair, execution, and result collection.

```text
Java subject projects
  ↓
MuJava mutant generation
  ↓
Mutant metadata Excel
  ↓
Structured evidence construction: graph/output.json
  ↓
PromptEvidence compression and post-processing
  ↓
LLM-based JUnit4 test generation
  ↓
javac compilation and targeted repair
  ↓
Original/mutant execution
  ↓
Target-level kill and suite-level mutation score
  ↓
CSV/JSON result aggregation
```

This package contains two major code components:

```text
evidence/
  Constructs graph/output.json for each mutant.

mutest/
  Extends MuJava with LLM test generation, compilation repair,
  mutation execution, and experimental result collection.
```

The original MuJava mutation testing system is used as the mutation-testing backend. Its original project page is:

```text
https://www.albany.edu/faculty/offutt/mujava/
```

This repository extends MuJava with Excel-driven task management, RIP-guided evidence extraction, LLM test generation, and large-scale experimental statistics.

---

## 2. Environment

The following environment was used in our experiments.

- Platform: Windows/Linux compatible; large-scale experiments were executed with Java projects managed through Maven-style classpaths.
- Java: JDK 8 is recommended for MuJava and JUnit4 compatibility.
- Build system: Maven is recommended for resolving project dependencies.
- Unit testing: JUnit 4.12 + Hamcrest.
- Static analysis: Soot, JavaParser, GumTree.
- Data processing: Apache POI, Jackson.
- Optional visualization: Graphviz.
- LLM access: OpenAI-compatible Chat Completion API.
- Baseline tools: Randoop and EvoSuite are used only for comparison experiments.

The project also uses the following configuration files:

```text
mujava.config
mujavaCLI.config
libraries.json
llm.properties
```

`mujava.config` defines MuJava paths, subject project paths, result directories, class directories, and Maven repository paths.  
`libraries.json` records project-specific dependencies.  
`llm.properties` configures the LLM provider, model name, API URL, API key, runtime budget, and repair settings.

---

## 3. Dataset

### (1) Subject programs and mutants

We evaluated RIP-Evidence on 12 Java projects, including 11 open-source Java projects and one benchmark suite. The dataset contains 125,157 generated mutants. After filtering equivalent mutants, 106,130 non-equivalent mutants are used as target mutants for test generation.

<img src="./figs/Table2.png" alt="subject projects" width="700">

**Table 2** summarizes the subject projects, including LoC, packages, classes, methods, generated mutants, and equivalent mutants.

<img src="./figs/Table3.png" alt="mutation operators" width="700">

**Table 3** summarizes the 19 traditional mutation operators used in the study. They cover arithmetic, logical, conditional, relational, variable-level, and statement-level mutations.

### (2) Mutant metadata Excel

Most scripts are driven by a mutant metadata Excel file. The Excel file is the central index connecting mutants, source paths, generated evidence, generated tests, and execution reports.

A typical Excel file contains the following fields:

| Column | Field | Meaning |
|---:|---|---|
| 0 | `operator` / `mutantName` | Mutant name, such as `AOIS_1` |
| 1 | `lineNo` | Mutation source line |
| 2 | `methodSignature` | Target method signature |
| 3 | `className` | Target class name |
| 4 | `classNameF` | Source-file class name |
| 5 | `mutationStatement` | Mutation statement or source-level diff |
| 6 | `packageName` / `targetClassName` | Package or MuJava target class |
| 7 | `projectName` | Subject project name |
| 8 | `file_path` | Mutant file path or result-module path |
| 9 | `original_graph_path` | Original-side source/graph path |
| 10 | `mutant_graph_path` | Mutant-side source/graph path |
| 11 | `is_killed` | Optional historical label or reference result |

The main identity key is:

```text
projectName + targetClassName + methodSignature + mutantName
```

This key is used consistently by evidence generation, LLM generation, execution, result merging, and artifact checking.

### (3) How to access the dataset

The processed dataset and experimental metadata are released separately. Please place the Excel metadata file and subject projects under the paths specified by `mujava.config` before running the pipeline.

---

## 4. Method and Implementation

### (1) Multi-source program evidence

For each target mutant, RIP-Evidence constructs structured evidence from the original program, the mutant, and the project context.

The evidence contains:

| Evidence type | Role |
|---|---|
| Mutation semantic evidence | Locates the mutation anchor and describes what changed |
| Reach evidence | Helps generate inputs and calls that reach the mutation point |
| Infect evidence | Explains whether the mutation may introduce internal state differences |
| Propagate evidence | Tracks whether the difference can affect return values, exceptions, object states, or external behavior |
| Entry-lifting evidence | Separates the real mutated method from the callable test entry |
| Invocation construction evidence | Provides receiver construction, setup templates, invocation templates, and example arguments |
| API constraint evidence | Prevents API hallucination and calls to unavailable methods |
| Assertion constraint evidence | Guides mutation-sensitive assertions and avoids weak assertions |
| Type and dependency evidence | Reduces type errors, missing imports, and unhandled exceptions |
| Skip-signal evidence | Avoids ineffective LLM calls when no legal entry or observable behavior exists |

### (2) Real mutated method A and callable test entry B

A key implementation feature is the separation between:

```text
A = real mutated method
B = callable test entry
```

Method A contains the mutation and is used to build mutation-side RIP/CFG/DFG evidence.  
Method B is the method that a generated JUnit test should call. If A is private, protected, package-private, abstract, or only reachable through another method, the toolchain searches for a callable entry B and records the B→A call chain.

This design reduces illegal calls, inaccessible method invocations, and tests that fail to reach the mutation point.

### (3) PromptEvidence

The full `output.json` can be too large for direct prompting. RIP-Evidence therefore compresses it into `PromptEvidence`.

Main sections include:

```text
mutation
entry
executableTestPlan
invocation
assertions
mutationEvidence
mutationGraphEvidence
entryEvidence
entryGraphEvidence
publicApiEvidence
observablePlan
```

The budget-aware compressor prioritizes mutation semantics, callable entry, invocation plan, receiver construction, public API constraints, observable behavior, assertion plan, and key RIP summaries. Redundant paths, long Jimple fragments, low-relevance APIs, and repeated graph evidence are truncated first.

### (4) LLM generation and compilation repair

The LLM generation pipeline follows this loop:

```text
Read output.json
  ↓
Build PromptEvidence
  ↓
Build initial generation prompt
  ↓
Call LLM
  ↓
Extract Java test code
  ↓
Compile with javac
  ↓
If compilation fails:
    classify the javac error
    build targeted repair prompt
    call LLM again
  ↓
Output compiled JUnit test or failure report
```

Compiler errors are classified into categories such as missing import, constructor mismatch, method not found, private access, abstract instantiation, incomplete test stub, invalid override, void-value misuse, invented API, truncated output, and empty LLM content. The repair prompt is then specialized according to the error type.

---

## 5. Repository Structure

A recommended structure is shown below.

```text
RIP-Evidence/
├── EvidenceParse/
│   ├── src/
│   ├── README.md
│   └── scripts/
│
├── MuTestLLM/
│   ├── src/
│   ├── README.md
│   ├── mujava.config
│   ├── mujavaCLI.config
│   ├── libraries.json
│   └── llm.properties.example
│
├── data/
│   ├── mutant_metadata_example.xlsx
│   └── README.md
│
├── figs/
│   ├── Fig2.png
│   ├── Fig4.png
│   ├── Fig5.png
│   ├── Fig6.png
│   ├── Table2.png
│   ├── Table3.png
│   ├── Table4.png
│   ├── Table5.png
│   └── ...
│
└── README.md
```

---

## 6. Experiment Replication

Please set up the environment following Section 2 and configure `mujava.config`, `libraries.json`, and `llm.properties`.

### Step 1. Generate MuJava mutants

Use the MuJava mutant-generation entry to generate traditional method-level mutants.

```bash
java -cp <classpath> mujava.cmd.MutantsGenerator <args>
```

or use the CLI wrapper:

```bash
java -cp <classpath> mujava.TraditionalMutantsGeneratorCLI <args>
```

Expected output:

```text
<project>/result/<targetClassName>/traditional_mutants/
```

After mutant generation, export or build the mutant metadata Excel file.

---

### Step 2. Generate structured `output.json` evidence

Run the evidence generation tool with the Excel file.

```bash
java -Xmx16g -cp <classpath> org.OutputJsonBatchRunner \
  --excel <mutant_metadata.xlsx> \
  --mode process \
  --workers 8 \
  --rewrite true \
  --rowStart 1 \
  --rowEnd -1
```

For debugging a small range:

```bash
java -Xmx16g -cp <classpath> org.OutputJsonBatchRunner \
  --excel <mutant_metadata.xlsx> \
  --mode single \
  --rewrite true \
  --rowStart 1 \
  --rowEnd 100
```

Expected output:

```text
<mutantDir>/graph/output.json
```

Batch logs:

```text
logs/output_json_parallel/<excel-name>_<timestamp>/
├── summary.tsv
├── output_json_batch.log
├── pids.txt
└── STOP
```

---

### Step 3. Generate and compile LLM tests

Run the LLM test generation entry in `mutest`.

```bash
java -Xmx16g \
  -Dpath.to.mujava.config=<mujava.config> \
  -Dllm.provider=deepseek \
  -Dllm.threads=4 \
  -Dllm.force.regenerate=false \
  -cp <classpath> \
  mujava.testgenerator.LLMTestGeneratorBatch <mutant_metadata.xlsx>
```

Some local versions specify the Excel path directly inside the `main` function. For public replication, we recommend passing the Excel file through command-line arguments or JVM properties.

Expected output:

```text
<resultModuleHome>/llm/
├── src/
├── classes/
└── report/
    ├── llm_generation_results.jsonl
    ├── <TestName>__compact_evidence.json
    ├── <TestName>__prompt.txt
    ├── <TestName>__response_0.json
    ├── <TestName>__compile_error_0.txt
    ├── <TestName>__repair_1_prompt.txt
    └── <TestName>__generation_summary.txt
```

---

### Step 4. Execute generated tests against mutants

Run the LLM test executor.

```bash
java -Xmx16g -cp <classpath> mujava.testgenerator.LLMTestExecutor \
  <mutant_metadata.xlsx> \
  5000
```

Arguments:

```text
arg0 = Excel metadata file
arg1 = timeout in milliseconds
```

Useful JVM options:

```bash
-Dllm.executor.granularity=method
-Dllm.executor.quickPrimaryOnly=false
-Dllm.executor.startRow=1
-Dllm.executor.endRow=10000
```

Expected output:

```text
<resultModuleHome>/llm/execution-report/<runId>/
├── llm_target_kill_results.json
├── llm_target_kill_results.csv
├── llm_suite_kill_results.json
├── all_method_summary.csv
├── all_method_summary.json
├── all_class_summary.csv
└── llm_execution_summary.txt
```

---

### Step 5. Merge LLM kill results

```bash
java -Xmx16g -cp <classpath> mujava.testgenerator.LlmMutantKillDetailCollector \
  <ProgramsRoot> \
  <mutant_metadata.xlsx> \
  <output_csv>
```

Example:

```bash
java -Xmx16g -cp <classpath> mujava.testgenerator.LlmMutantKillDetailCollector \
  E:/PHD/testJava/Programs \
  E:/PHD/testJava/MutantParse/data/mutant_statistic_total_graph_llm.xlsx \
  E:/PHD/testJava/MutantParse/data/llm_mutant_kill_detail_merged.csv
```

Expected output:

```text
llm_mutant_kill_detail_merged.csv
```

---

### Step 6. Optional: run comparison baselines

Randoop and EvoSuite are used only as comparison baselines. They are not part of the proposed RIP-Evidence method.

For EvoSuite:

```bash
java -Xmx16g -cp <classpath> mujava.testgenerator.EvoSuiteTestGenerator
java -Xmx16g -cp <classpath> mujava.testgenerator.EvoSuiteCleaner
java -Xmx16g -cp <classpath> mujava.testgenerator.EvoSuiteMutantKillDetailCollector \
  <ProgramsRoot> \
  <mutant_metadata.xlsx> \
  <output_csv>
```

Expected output:

```text
evosuite_mutant_kill_detail_merged.csv
```

---

### Step 7. Optional: artifact checking and failure diagnosis

Count generated artifacts:

```bash
java -Xmx16g -cp <classpath> mujava.testgenerator.LlmArtifactTableCounter \
  <mutant_metadata.xlsx>
```

Export `compiled=false` records:

```bash
java -Xmx16g -cp <classpath> mujava.testgenerator.JsonlCompiledFalseToExcel
```

Export rows with missing compiled `.class` files:

```bash
java -Xmx16g -cp <classpath> mujava.testgenerator.MissingCompiledClassExcelExporter \
  <mutant_metadata.xlsx> \
  missing_llm_class_rows.xlsx
```

Collect grouped failure evidence:

```bash
java -Xmx16g -cp <classpath> mujava.testgenerator.MissingFailureEvidenceCollector \
  missing_llm_class_rows.xlsx \
  grouped_minimal_failure_evidence_for_llm.json
```

Restore historically compiled tests without calling the LLM again:

```bash
java -Xmx16g -cp <classpath> mujava.testgenerator.LLMCompiledTestRestorer \
  <mutant_metadata.xlsx>
```

---

## 7. Experimental Results and Analysis

### 1) How effective is RIP-Evidence at project level?

<img src="./figs/Table4.png" alt="project-level results" width="700">

**Table 4** reports project-level test generation results of RIP-Evidence. Overall, RIP-Evidence achieves a compile rate of 98.55%, an execution rate of 97.08%, a suite-level mutation score of 84.22%, and a target kill rate of 70.89%.

<img src="./figs/Table5.png" alt="baseline comparison" width="700">

**Table 5** compares RIP-Evidence with traditional automatic test generation, basic LLM-based generation, static-analysis-enhanced LLM generation, and mutation-feedback-enhanced LLM generation. RIP-Evidence outperforms all comparable LLM-based baselines in CompR, ExecR, MS, and TKR.

---

### 2) How stable is the mutation-killing ability across operators?

<img src="./figs/Fig4.png" alt="operator-wise comparison" width="1000">

**Figure 4** compares operator-wise mutation scores between RIP-Evidence and the best baseline. RIP-Evidence improves over the best baseline on 18 out of 19 mutation operators and ties on the remaining operator.

---

### 3) What are the efficiency and repair costs?

<img src="./figs/Table6.png" alt="time cost" width="700">

**Table 6** reports the time cost and repair overhead. RIP-Evidence has a lower average generation cost than all LLM-based baselines. Its evidence construction and PromptEvidence organization overhead is small, while the average number of repair rounds is substantially reduced.

---

### 4) Does RIP-Evidence work across different LLM backbones?

<img src="./figs/Table7.png" alt="LLM effectiveness" width="700">

**Table 7** shows that RIP-Evidence remains effective across different LLM backbones, including StarCoder, Code Llama, Qwen, DeepSeek, and ChatGPT-family models.

<img src="./figs/Table8.png" alt="LLM efficiency" width="700">

**Table 8** reports efficiency and repair overhead across LLM backbones. Stronger models usually require fewer repair rounds, while evidence construction and PromptEvidence organization remain stable.

---

## 8. Discussion

### 1) Overall impact of multi-source evidence

<img src="./figs/Fig5.png" alt="evidence impact" width="1000">

**Figure 5** compares LLM-based test generation with and without multi-source evidence. The results show that structured evidence improves compile rate, execution rate, and mutation score for most models.

---

### 2) Contribution of evidence components

<img src="./figs/Table9.png" alt="ablation table" width="700">

**Table 9** reports the ablation results of major evidence components. Removing entry lifting, compilation evidence, or RIP evidence causes clear performance drops.

<img src="./figs/Fig6.png" alt="field-level ablation" width="1000">

**Figure 6** shows the fine-grained impact of evidence fields on mutation score, highlighting the roles of callable entry, public API constraints, and CFG/DFG evidence.

---

### 3) Token budgeting and evidence compression

<img src="./figs/Table10.png" alt="token budget" width="700">

**Table 10** reports the effect of different PromptEvidence budgets. PE-16K provides a good trade-off between effectiveness and generation cost.

---

### 4) Failure modes

<img src="./figs/Table11.png" alt="failure modes" width="700">

**Table 11** categorizes failed test generation cases. Most remaining failures are related to incomplete abstract/interface test stubs, type/signature mismatch, illegal overriding, unresolved symbols, private access, and unhandled checked exceptions.

---

## 9. Implementation and Code Availability

The main implementation components are:

1. **Structured evidence construction.**  
   This component maps source-level mutation differences to IR-level CFG/DFG/RIP evidence, resolves callable test entries, builds dependency contexts, and writes `graph/output.json`.

2. **PromptEvidence construction and LLM test generation.**  
   This component compresses `output.json` into compact evidence, builds prompts, calls OpenAI-compatible LLMs, extracts generated Java tests, compiles them, and performs targeted repair.

3. **MuJava-based mutation execution and result collection.**  
   This component extends MuJava execution with multi-process isolation, target-level kill evaluation, suite-level mutation score computation, and CSV/JSON result collection.

4. **Baseline and analysis scripts.**  
   This component includes optional baseline execution and artifact/failure analysis tools.

---

## 10. Main Outputs

The following files are typically used for paper-level statistics:

```text
# Evidence
<mutantDir>/graph/output.json

# LLM generation
<resultModuleHome>/llm/report/llm_generation_results.jsonl
<resultModuleHome>/llm/src/
<resultModuleHome>/llm/classes/

# Execution
<resultModuleHome>/llm/execution-report/<runId>/llm_target_kill_results.csv
<resultModuleHome>/llm/execution-report/<runId>/llm_suite_kill_results.json
<resultModuleHome>/llm/execution-report/<runId>/all_method_summary.csv
<resultModuleHome>/llm/execution-report/<runId>/all_class_summary.csv

# Merged statistics
llm_mutant_kill_detail_merged.csv
evosuite_mutant_kill_detail_merged.csv
llm_artifact_statistics.csv
compiled_false.xlsx
missing_llm_class_rows.xlsx
grouped_minimal_failure_evidence_for_llm.json
```

---

## 11. Metrics

We report four main effectiveness metrics.

| Metric | Meaning |
|---|---|
| CompR | Percentage of target mutants whose generated tests compile successfully |
| ExecR | Percentage of target mutants whose generated tests execute successfully on the original program |
| MS | Suite-level mutation score over non-equivalent target mutants |
| TKR | Target kill rate: whether each generated test kills its corresponding target mutant |

In addition, we report:

| Metric | Meaning |
|---|---|
| `tEvidenceConstruct` | Evidence construction time |
| `tPromptEvidence` | PromptEvidence organization time |
| `tGeneration` | LLM generation time |
| `tavg` | Average processing time per target mutant |
| `Repairavg` | Average number of compilation repair rounds |

Note that MS and TKR measure different aspects. MS measures the overall mutation-killing ability of the generated test suite, while TKR measures one-to-one target mutant killing ability.

---

## 12. Notes

- The original MuJava system is retained as the mutation testing backend.
- The proposed method is centered on `output.json`, `PromptEvidence`, and evidence-guided generation.
- The Excel file is the central index for evidence construction, test generation, execution, and result merging.
- EvoSuite and Randoop are comparison baselines, not part of the proposed RIP-Evidence pipeline.
- For large-scale experiments, use process mode for evidence construction and multi-process execution for mutation evaluation.
- For debugging individual cases, inspect `compact_evidence.json`, prompt files, LLM response files, compiler error files, and generation summaries.

---

## 13. Citation

If you use this repository, please cite our paper and the original MuJava work.

```bibtex
@article{rip_evidence_llm_mutation_test_generation,
  title   = {Multi-Source Evidence-Guided Large Language Models for Mutation Unit Test Generation},
  author  = {Hu, Lei and Yao, Xiangjuan and Wei, Changqing},
  journal = {Under Review},
  year    = {2026}
}
```

Original MuJava:

```bibtex
@inproceedings{ma2006mujava,
  title     = {MuJava: A mutation system for Java},
  author    = {Ma, Yu-Seung and Offutt, Jeff and Kwon, Yong-Rae},
  booktitle = {Proceedings of the 28th International Conference on Software Engineering},
  pages     = {827--830},
  year      = {2006}
}
```
