# MuJava-RIP-Evidence: Excel-Driven LLM Mutation-Killing Test Generation Extension

## 1. Overview

This repository extends the original **MuJava** mutation testing framework into an Excel-driven experimental toolchain for **LLM-based mutation-killing test generation**.

The original MuJava framework provides Java mutation operators, mutant generation, mutant compilation, and mutation test execution. The original project is available at:

```text
https://www.albany.edu/faculty/offutt/mujava/
```

This extended version keeps MuJava as the mutation-testing backend, but adds a research pipeline for:

```text
1. generating and organizing mutants;
2. reading mutant metadata from Excel;
3. constructing structured output.json evidence for each mutant;
4. compressing output.json into PromptEvidence;
5. generating JUnit4 tests with an OpenAI-compatible LLM;
6. compiling generated tests and repairing compilation failures;
7. executing generated tests against original and mutant programs;
8. collecting target-level and suite-level mutation-killing results;
9. exporting statistics, failure evidence, and artifact-completeness reports.
```

The key design of this extension is that **Excel is the central experiment index**. In most scripts, the `main` function reads an Excel file, parses each row into a mutant/test-generation task, and then executes subsequent steps row by row or group by group.

Overall workflow:

```text
Excel mutant metadata
  ↓
Locate original program and mutant program
  ↓
Generate graph/output.json structured evidence
  ↓
Compress output.json into PromptEvidence
  ↓
Generate and compile JUnit4 tests with LLM
  ↓
Execute tests on original and mutant versions
  ↓
Collect target-level kill and suite-level mutation score
  ↓
Merge CSV/JSON reports for paper experiments
```

## 2. Relationship to the Original MuJava

The original MuJava is used as the basic mutation testing infrastructure. In this project, its role is mainly to:

```text
generate Java mutants;
compile original and mutant classes;
organize original/mutant directories;
load original and mutant versions during execution;
compare original and mutant test behaviors.
```

The added part of this repository focuses on the LLM-based workflow:

```text
MuJava backend
  + Excel-driven task management
  + structured evidence construction
  + LLM test generation
  + compilation repair
  + multi-process mutant execution
  + experiment result aggregation
```

Thus, the original MuJava answers how mutants are generated and executed, while this extension answers how LLMs can be guided by structured evidence to generate tests that kill specific target mutants.

## 3. Excel as the Central Input

Most batch scripts in this repository are driven by an Excel file. The Excel file is not only a data table, but also the central mapping among:

```text
mutant identity;
source and result paths;
output.json evidence path;
generated test class name;
target-level evaluation target;
suite-level evaluation group;
merged statistical result.
```

A typical Excel file contains the following columns:

| Column | Field | Meaning |
|---:|---|---|
| 0 | `operator` / `mutantName` | Mutant name, e.g., `AOIS_1` |
| 1 | `lineNo` | Mutation source line |
| 2 | `methodSignature` | Target method signature |
| 3 | `className` | Class name |
| 4 | `classNameF` | Source file class name |
| 5 | `mutationStatement` | Mutation statement or diff |
| 6 | `packageName` / `targetClassName` | MuJava target class name |
| 7 | `projectName` | Project name |
| 8 | `file_path` | Mutant file path used to infer the result module |
| 9 | `original_graph_path` | Original-side source/graph path |
| 10 | `mutant_graph_path` | Mutant-side source/graph path |
| 11 | `is_killed` | Optional reference label |

The main identity key is:

```text
projectName + targetClassName + methodSignature + mutantName
```

This key is used consistently in evidence construction, LLM test generation, test execution, and result merging.

Many entry classes currently read the Excel path in one of the following ways:

```text
1. command-line argument;
2. JVM system property;
3. hard-coded default path in main for local experiments.
```

For public release or replication, it is recommended to pass the Excel path through command-line arguments or JVM properties rather than modifying the source code.

## 4. Main Modules

### 4.1 Mutant Generation

This part is based on the MuJava infrastructure.

Representative classes:

```text
mujava.cmd.MutantsGenerator
mujava.AllMutantsGenerator
mujava.TraditionalMutantsGenerator
mujava.TraditionalMutantsGeneratorCLI
mujava.MutationSystem
```

Main responsibility:

```text
Java source files
  ↓
MuJava traditional mutation operators
  ↓
compiled original and mutant versions
  ↓
result/<targetClassName>/traditional_mutants/
```

Typical output:

```text
<project>/result/<targetClassName>/
  ├── original/
  └── traditional_mutants/
      ├── method_list.txt
      └── <methodSignature>/
          ├── AOIS_1/
          ├── ROR_2/
          └── ...
```

### 4.2 Structured Evidence Generation

This part generates `graph/output.json` for each mutant described in the Excel file.

Representative classes:

```text
OutputJsonBatchRunner
OutputJsonWorker
RipParser
MethodEntryResolver
ReceiverResolver
DependencyContextBuilder
EntryLiftedRipBuilder
DiffWithLineRanges
AstToJimpleBridge
```

Main responsibility:

```text
Excel row
  ↓
MutationConfig
  ↓
locate original/mutant source and class files
  ↓
compute AST diff and Jimple affected units
  ↓
construct RIP/CFG/DFG evidence
  ↓
resolve mutation method A and callable test entry B
  ↓
write <mutantDir>/graph/output.json
```

Key design:

```text
A = real mutation method
B = callable test entry
```

Method A is where the mutation is located. Method B is the method that the generated test should call. This is necessary because the real mutation method may be private, protected, package-private, abstract, or only reachable through another public method.

Typical `output.json` structure:

```text
output.json
  ├── DependencyContext
  ├── EntryLiftedRIP
  ├── origin
  └── mutated
```

### 4.3 PromptEvidence Compression

The full `output.json` is compressed into compact prompt-facing evidence before being sent to the LLM.

Representative classes:

```text
PromptEvidence
EvidencePostProcessor
EvidenceUtils
EvidenceAwarePromptBudgeter
PromptBudgetPolicy
PromptBudgetProfile
GeneratorDefaults
```

Main compact sections:

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

This layer keeps high-priority evidence such as callable entry, receiver construction, public API constraints, observable behavior, and required assertions, while compressing lower-priority graph/path evidence when the prompt exceeds the budget.

### 4.4 LLM Test Generation and Compilation Repair

This part generates one JUnit4 test class for each target mutant.

Representative classes:

```text
LLMTestGeneratorBatch
LLMTestGenerator
InitialPromptBuilder
RepairPromptBuilder
GeneratedCodeExtractor
GeneratedTestCompiler
CompileErrorClassifier
CompilerDiagnosisAppender
LlmClient
CachedLlmClient
LlmResponseCache
GenerationReportWriter
Request
Result
TaskTiming
ModelConfigLoader
LlmRuntimeConfigLoader
```

Closed loop:

```text
Excel row
  ↓
Request
  ↓
read output.json
  ↓
PromptEvidence.fromFullOutput()
  ↓
InitialPromptBuilder
  ↓
LLM call
  ↓
GeneratedCodeExtractor
  ↓
write llm/src/<package>/<TestName>.java
  ↓
GeneratedTestCompiler
  ↓
if javac fails:
      classify compile error
      build repair prompt
      call LLM again
  ↓
write generation report
```

Typical output:

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
      └── <TestName>__generation_summary.txt
```

### 4.5 Test Execution and Mutation-Kill Evaluation

This part executes generated tests against original and mutant versions.

Representative classes:

```text
LLMTestExecutor
TestRunner9_MultiProcess_batched
MutantWorker8_MultiProcess
MuJavaRuntimeSupport8
OriginalLoader8
JMutationLoader8
TestResult
LocalJarFinder
```

Evaluation logic:

```text
run test on original program
  ↓
run test on mutant program
  ↓
compare outcomes
  ↓
different behavior → KILLED
same behavior      → LIVE
```

The extension reports two levels of results:

```text
target-level kill:
  whether the generated test kills its corresponding target mutant.

suite-level mutation score:
  whether the generated tests, as a test suite, kill other mutants in the same evaluation scope.
```

Typical output:

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

### 4.6 Result Collection and Failure Analysis

This part is used after generation and execution to produce paper-level statistics and diagnose failures.

Representative classes:

```text
LlmMutantKillDetailCollector
LlmArtifactTableCounter
JsonlCompiledFalseToExcel
MissingCompiledClassExcelExporter
MissingFailureEvidenceCollector
LLMCompiledTestRestorer
```

Typical outputs:

```text
llm_mutant_kill_detail_merged.csv
llm_artifact_statistics.csv
compiled_false.xlsx
missing_llm_class_rows.xlsx
grouped_minimal_failure_evidence_for_llm.json
```

### 4.7 EvoSuite Baseline

EvoSuite is used only as a comparison baseline in the experiments. It is not part of the proposed LLM-evidence pipeline.

Related scripts are kept for baseline generation, cleaning, execution, and result collection:

```text
EvoSuiteTestGenerator
EvoSuiteCleaner
EvoSuiteMutantKillDetailCollector
```

The final collected baseline result is typically:

```text
evosuite_mutant_kill_detail_merged.csv
```

## 5. Configuration

### 5.1 MuJava Configuration

Common configuration files:

```text
mujava.config
mujavaCLI.config
libraries.json
```

The MuJava config path can be passed through:

```bash
-Dmujava.config.path=/path/to/mujava.config
```

### 5.2 LLM Model Configuration

The LLM configuration is loaded in the following order:

```text
environment variable
  → JVM system property
  → llm.properties
  → default value
```

Example `llm.properties`:

```properties
llm.provider=deepseek

llm.providers.deepseek.api.url=https://api.deepseek.com/chat/completions
llm.providers.deepseek.api.key=YOUR_API_KEY
llm.providers.deepseek.model=deepseek-chat
llm.providers.deepseek.temperature=0.2
llm.providers.deepseek.max.tokens=16000
llm.providers.deepseek.connect.timeout.millis=30000
llm.providers.deepseek.read.timeout.millis=120000
```

### 5.3 Runtime Configuration

Example runtime options:

```properties
llm.threads=4
llm.api.max.attempts=2
llm.skip.existing.compiled=true
llm.force.regenerate=false

llm.dynamic.budget.enabled=true
llm.oversize.prompt.policy=truncate

llm.initial.max.input.tokens=16000
llm.initial.max.input.chars=30000
llm.initial.max.tokens=3500

llm.repair1.input.ratio=1.80
llm.repair1.output.ratio=1.45
llm.repair2.input.ratio=2.60
llm.repair2.output.ratio=2.30

llm.hard.input.ratio=3.00
llm.hard.output.ratio=3.00
```

## 6. How to Use

### Step 1. Generate Mutants

Generate MuJava traditional mutants.

```bash
java -cp <classpath> mujava.cmd.MutantsGenerator <args>
```

or use the CLI wrapper if your local experiment uses CLI configuration:

```bash
java -cp <classpath> mujava.TraditionalMutantsGeneratorCLI <args>
```

Expected output:

```text
<project>/result/<targetClassName>/traditional_mutants/
```

After this step, prepare the Excel metadata file. Later steps are driven by this Excel file.

### Step 2. Generate `output.json` Evidence from Excel

Recommended large-scale mode:

```bash
java -Xmx16g -cp <classpath> org.OutputJsonBatchRunner \
  --excel <mutant_metadata.xlsx> \
  --mode process \
  --workers 8 \
  --rewrite true \
  --rowStart 1 \
  --rowEnd -1
```

Debug mode:

```bash
java -Xmx16g -cp <classpath> org.OutputJsonBatchRunner \
  --excel <mutant_metadata.xlsx> \
  --mode single \
  --rewrite true
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

### Step 3. Generate and Compile LLM Tests from Excel

Run the LLM test generation batch entry.

```bash
java -Xmx16g -cp <classpath> mujava.testgenerator.LLMTestGeneratorBatch
```

In many local experiment versions, the Excel path is configured inside the `main` function. For cleaner reuse, it is recommended to pass it as an argument or JVM property, for example:

```bash
java -Xmx16g \
  -Dllm.provider=deepseek \
  -Dllm.threads=4 \
  -Dllm.force.regenerate=false \
  -cp <classpath> \
  mujava.testgenerator.LLMTestGeneratorBatch <mutant_metadata.xlsx>
```

Expected outputs:

```text
<resultModuleHome>/llm/src/
<resultModuleHome>/llm/classes/
<resultModuleHome>/llm/report/llm_generation_results.jsonl
```

### Step 4. Execute LLM Tests from Excel

Run the LLM test executor:

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

Useful options:

```bash
-Dllm.executor.granularity=method
-Dllm.executor.quickPrimaryOnly=false
-Dllm.executor.startRow=1
-Dllm.executor.endRow=10000
```

Expected output:

```text
<resultModuleHome>/llm/execution-report/<runId>/
```

### Step 5. Merge LLM Results

```bash
java -Xmx16g -cp <classpath> mujava.testgenerator.LlmMutantKillDetailCollector \
  <ProgramsRoot> \
  <mutant_metadata.xlsx> \
  <output_csv>
```

Expected output:

```text
llm_mutant_kill_detail_merged.csv
```

### Step 6. Optional: Run EvoSuite Baseline

EvoSuite is only used as a comparison baseline. Run it only when baseline results are needed.

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

### Step 7. Optional: Artifact Check and Failure Diagnosis

```bash
java -Xmx16g -cp <classpath> mujava.testgenerator.LlmArtifactTableCounter \
  <mutant_metadata.xlsx>
```

```bash
java -Xmx16g -cp <classpath> mujava.testgenerator.JsonlCompiledFalseToExcel
```

```bash
java -Xmx16g -cp <classpath> mujava.testgenerator.MissingCompiledClassExcelExporter \
  <mutant_metadata.xlsx> \
  missing_llm_class_rows.xlsx
```

```bash
java -Xmx16g -cp <classpath> mujava.testgenerator.MissingFailureEvidenceCollector \
  missing_llm_class_rows.xlsx \
  grouped_minimal_failure_evidence_for_llm.json
```

## 7. Recommended End-to-End Pipeline

```text
1. Generate MuJava traditional mutants.
2. Build or export the mutant metadata Excel file.
3. Run OutputJsonBatchRunner with the Excel file to generate graph/output.json.
4. Run LLMTestGeneratorBatch with the same Excel file to generate and compile JUnit4 tests.
5. Run LLMTestExecutor with the same Excel file to evaluate target-level and suite-level killing.
6. Run LlmMutantKillDetailCollector to merge LLM results.
7. Optionally run EvoSuite as the comparison baseline.
8. Run artifact and failure-analysis tools if missing outputs or failed compilations need diagnosis.
9. Use merged CSV/JSON files for paper tables and figures.
```

## 8. Reproducibility Checklist

Before running the full pipeline, check:

```text
1. Java projects have been compiled.
2. MuJava paths and Maven repository paths are correctly configured.
3. Mutants have been generated under traditional_mutants.
4. The Excel metadata file contains all required columns.
5. Each Excel row can be resolved to valid original/mutant paths.
6. output.json has been generated for each target mutant.
7. llm.properties contains a valid provider, API URL, API key, and model.
8. JUnit 4.12 and Hamcrest are available.
9. Project classpath can be resolved.
10. llm/src and llm/classes are writable.
11. Worker JVM memory is sufficient.
12. skip/regenerate behavior is configured correctly.
```

## 9. Main Outputs for Paper Experiments

```text
llm_generation_results.jsonl
llm_target_kill_results.csv
llm_suite_kill_results.json
all_method_summary.csv
all_class_summary.csv
llm_mutant_kill_detail_merged.csv
evosuite_mutant_kill_detail_merged.csv
llm_artifact_statistics.csv
compiled_false.xlsx
missing_llm_class_rows.xlsx
grouped_minimal_failure_evidence_for_llm.json
```

## 10. Notes

- The original MuJava part is retained as the mutation testing backend.
- The proposed extension is centered on Excel-driven task parsing, `output.json`, and `PromptEvidence`.
- EvoSuite is only a comparison baseline and is not part of the proposed LLM-evidence method.
- Target-level kill and suite-level mutation score are different metrics and should be reported separately.
- For large-scale runs, use process mode for evidence generation and multi-process execution for mutation evaluation.
- For debugging, inspect `compact_evidence.json`, prompt, LLM response, compile error, and generation summary files.
