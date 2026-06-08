# RIP-Evidence Output JSON Generation Toolchain

## 1. Overview

This repository provides the evidence construction toolchain used by **RIP-Evidence**, an LLM-based mutation-killing test generation approach. Given Java mutant metadata, the toolchain generates a structured `output.json` file for each target mutant. The generated JSON contains mutation semantics, RIP-related evidence, entry-lifted invocation evidence, dependency context, receiver construction guidance, branch reachability hints, and observable/assertion plans. These evidence items are later compressed into `PromptEvidence` and used to guide LLM-based JUnit test generation.

The toolchain is designed for method-level Java mutants. For each mutant, it distinguishes between the **real mutation method A**, which is used to construct mutation-point RIP/Jimple/CFG/DFG evidence, and the **callable test entry B**, which generated tests should invoke when A is private, inaccessible, or reachable only through an indirect call chain. The batch entry point is `OutputJsonBatchRunner`, which reads mutant metadata from an Excel file and invokes worker processes to generate `graph/output.json` for each mutant.

## 2. Main Features

- **Batch evidence generation.** `OutputJsonBatchRunner` reads mutant rows from an Excel file, constructs `MutationConfig` objects, schedules tasks, and writes a `summary.tsv` file recording the execution status of each row.
- **Worker-based isolated analysis.** `OutputJsonWorker` receives encoded `MutationConfig` objects, resolves the callable test entry, invokes `RipParser`, and reports per-row results using `ROW_RESULT`.
- **Mutation-point RIP evidence construction.** `RipParser` builds common mutation information, original-side evidence, and mutant-side evidence, and writes the final structured JSON to `<mutantDir>/graph/output.json`.
- **AST diff and Jimple alignment.** `DiffWithLineRanges` computes method-level AST differences between original and mutant programs; `AstToJimpleBridge` maps affected source ranges to Jimple units and enumerates bounded paths through affected statements.
- **Entry-lifted evidence.** `EntryLiftedRipBuilder` constructs evidence for the callable test entry B, including invocation plans, receiver construction, branch reachability, observable behavior, and assertion plans.
- **Dependency and compilation context.** `DependencyContextBuilder` builds prompt-facing information needed for compilable JUnit generation, including package information, imports, callable signatures, constructors, field types, public APIs, and compilation guardrails.

## 3. Repository Structure

```text
src/main/java/
├── org/
│   ├── OutputJsonBatchRunner.java      # Batch entry point for output.json generation
│   ├── OutputJsonWorker.java           # Worker process for one or more mutant rows
│   ├── MutationConfigCodec.java        # Serialization/deserialization of MutationConfig
│   └── DataGenerator.java              # Earlier single-process generator and entry report writer
│
├── org/model/
│   ├── MutationConfig.java             # Central configuration object for each mutant
│   ├── Bundle.java                     # JSON evidence container for original/mutant sides
│   ├── CFG.java                        # CFG-related evidence schema
│   ├── DFG.java                        # DFG-related evidence schema
│   └── Info.java                       # Path-level CFG/DFG evidence item
│
├── org/astjimple/
│   ├── DiffWithLineRanges.java         # GumTree-based AST diff and line range extraction
│   ├── AstToJimpleBridge.java          # Soot/Jimple analysis and affected path extraction
│   ├── MethodContent.java              # Source-level method extraction and signature matching
│   ├── SourceOwnerResolver.java        # Retargeting nested concrete mutation owners
│   └── ChangeRange.java                # Source-line change range representation
│
├── org/rip/
│   ├── RipParser.java                  # Core JSON evidence assembler
│   ├── RipExtractor.java               # CFG/DFG/RIP helper analysis
│   ├── MethodEntryResolver.java        # Resolves mutation method A and callable test entry B
│   ├── ReceiverResolver.java           # Resolves receiver construction and observable plans
│   ├── DependencyContextBuilder.java   # Builds dependency and compilation context
│   ├── EntryLiftedRipBuilder.java      # Builds entry-lifted RIP evidence
│   └── PromptSignatureFormatter.java   # Converts internal signatures to prompt-facing forms
│
├── org/graph/
│   ├── ASTVisualizer.java              # Optional AST visualization
│   ├── CFGVisualizer.java              # Optional CFG visualization
│   └── DFGVisualizer.java              # Optional DFG visualization
│
└── org/utils/
    ├── ExcelUtils.java                 # Excel reader
    ├── MethodSignature.java            # Soot-style signature matching
    ├── PathSanitizer.java              # Windows path normalization
    ├── DotToImageConverter.java        # Optional DOT-to-image conversion
    └── MapUtils.java                   # Small map construction utility
```

## 4. Input Format

The batch runner expects an Excel file whose first sheet contains mutant metadata. The first row is treated as a header, and data rows start from row index 1.

| Column index | Field | Meaning |
|---:|---|---|
| 0 | `operator` | Mutation operator and mutant identifier |
| 1 | `lineNo` | Source line number of the mutation |
| 2 | `methodName` | Internal method signature of the real mutation method A |
| 3 | `className` | Class containing the mutant |
| 4 | `classNameF` | File-level or source-level class name |
| 5 | `mutationStatement` | Syntactic mutation statement or diff |
| 6 | `packageName` | Java package name |
| 7 | `projectName` | Project identifier |
| 8 | `sourcePath` | Path to the mutant source file or mutant directory |

Each row is converted into a `MutationConfig`. The target output path for a row is:

```text
<config.filepath>/graph/output.json
```

## 5. Output Files

For each mutant, the expected evidence artifact is:

```text
<mutantDir>/graph/output.json
```

A batch run also creates a run directory, typically under:

```text
logs/output_json_parallel/<excel-name>_<timestamp>/
```

The run directory contains:

| File | Description |
|---|---|
| `summary.tsv` | Per-row execution summary, including row index, exit code, duration, operator, class, method, mutant path, output path, and log file |
| `output_json_batch.log` | Combined worker log |
| `pids.txt` | Parent and worker process IDs |
| `STOP` | Optional stop signal file; creating this file requests graceful termination |

## 6. Execution Modes

### 6.1 Single Mode

`single` mode processes all tasks sequentially in the parent JVM. It is recommended for debugging.

```bash
java -cp <classpath> org.OutputJsonBatchRunner \
  --excel <mutant-metadata.xlsx> \
  --mode single \
  --rewrite true
```

### 6.2 Thread Mode

`thread` mode uses a Java thread pool. Since Soot maintains global singleton state, the core Soot analysis is serialized inside `OutputJsonWorker`. This mode is safe but may not provide substantial speedup.

```bash
java -cp <classpath> org.OutputJsonBatchRunner \
  --excel <mutant-metadata.xlsx> \
  --mode thread \
  --workers 8 \
  --rewrite true
```

### 6.3 Process Mode

`process` mode is recommended for large-scale generation. It starts multiple persistent worker JVMs. Each worker receives a chunk of mutant rows through standard input and processes them sequentially. This design amortizes JVM and Soot initialization cost while preserving process-level isolation across workers.

```bash
java -cp <classpath> org.OutputJsonBatchRunner \
  --excel <mutant-metadata.xlsx> \
  --mode process \
  --workers 8 \
  --rewrite true
```

## 7. Recommended Reproduction Command

```bash
java -Xmx16g -cp <full-project-classpath> org.OutputJsonBatchRunner \
  --excel data/mutants.xlsx \
  --mode process \
  --workers 8 \
  --rewrite true \
  --rowStart 1 \
  --rowEnd -1
```

When running from an IDE, the same configuration can be specified by editing the direct-run constants in `OutputJsonBatchRunner.java`, including `DIRECT_EXCEL_FILE`, `DIRECT_MODE`, `DIRECT_WORKERS`, `DIRECT_REWRITE`, `DIRECT_ROW_START`, and `DIRECT_ROW_END`.

## 8. Execution Workflow

```text
Excel mutant metadata
  ↓
OutputJsonBatchRunner.loadTasks()
  ↓
MutationConfig for each mutant
  ↓
single/thread/process scheduling
  ↓
OutputJsonWorker.runOne()
  ↓
MethodEntryResolver.resolve()
  ↓
ReceiverResolver.enrich()
  ↓
RipParser.analyzePairToJson()
  ├─ buildCommonInfo()
  ├─ buildOriginSide()
  ├─ buildMutantSide()
  └─ assembleOutput()
  ↓
<mutantDir>/graph/output.json
```

## 9. Evidence Construction Details

### 9.1 Mutation Method A and Callable Entry B

The toolchain explicitly separates the real mutation method A from the callable test entry B. A is the method that contains the mutation and is used to build mutation-point RIP, Jimple, CFG, and DFG evidence. B is the method that generated JUnit tests should invoke. If A is private or otherwise inaccessible, `MethodEntryResolver` searches for a legal caller B; if no such caller exists, it falls back to reflection or marks the target as unsuitable for normal test generation.

### 9.2 Common Mutation Information

`RipParser.buildCommonInfo()` records the mutation operator, mutation diff, domain assumptions, observable sinks, and Jimple changes. It invokes `DiffWithLineRanges.diffWithLineRangesForMethod()` to compute method-level AST differences between original and mutant source files.

### 9.3 Original and Mutant Side RIP Evidence

`RipParser.buildOriginSide()` and `buildMutantSide()` extract method text, load the corresponding Soot method body, identify affected Jimple units, enumerate bounded paths through affected units, and construct path-level CFG/DFG evidence. For each path, the generated evidence may include dominator information, path predicates, control dependencies, definitions, uses toward observable outputs, kill sets, heap accesses, potential exceptions, and alias-related information.

### 9.4 Dependency Context

`DependencyContextBuilder` builds prompt-facing dependency context for compilable test generation. It distinguishes the detailed context for callable entry B from the lightweight context for mutation method A. The context includes package and import information, method signatures, receiver construction, public APIs, constructors, referenced types, internal calls, observable plans, branch reachability plans, and compilation guardrails.

### 9.5 Entry-Lifted RIP Evidence

`EntryLiftedRipBuilder` constructs entry-side evidence showing how tests can reach mutation method A through callable entry B. If B and A are the same method, it focuses on mutation-affected units. If B differs from A, it focuses on call sites from B to A and enumerates paths through those call sites. It also records the entry relation, invocation plan, receiver metadata, suggested test values, public API constraints, observable plan, branch reachability plan, and assertion plan.

## 10. Dependencies

| Dependency | Purpose |
|---|---|
| Soot | Bytecode/Jimple loading, CFG construction, dominator and post-dominator analysis |
| JavaParser | Source parsing, callable extraction, receiver and dependency context analysis |
| GumTree | AST differencing between original and mutant methods |
| Jackson | JSON serialization |
| Apache POI | Excel metadata reading |
| Graphviz | Optional DOT-to-image conversion |
| JUnit 4 | Target test generation context |

## 11. Notes on Path Handling

The toolchain includes `PathSanitizer` to normalize Windows paths, especially extended-length paths such as `\\?\C:\...`. This avoids failures caused by inconsistent handling of Windows long-path prefixes in `java.nio.file.Paths`.

## 12. Exit Codes

| Code | Meaning |
|---:|---|
| 0 | Successful generation |
| 2 | Analysis failure for the row |
| 64 | Invalid command-line usage or missing Excel path |
| 130 | Graceful stop requested |

Each worker prints a `ROW_RESULT` line containing row index, exit code, duration, and mutant path. The parent process parses these lines and records them in `summary.tsv`.

## 13. Reproducibility Checklist

Before running the toolchain, ensure that:

- The project classes for both original and mutant programs have been compiled.
- The Excel file contains the required mutant metadata columns.
- The classpath includes Soot, JavaParser, GumTree, Jackson, Apache POI, JUnit, and project dependencies.
- The `sourcePath` column points to a valid mutant source file or mutant directory.
- The directory layout contains the corresponding `original` directory and mutant operator directories.
- The worker JVM has sufficient memory for Soot analysis.
- Existing `graph/output.json` files are removed or `--rewrite true` is used when regeneration is required.

## 14. Expected Artifact

For each successfully processed mutant, the expected artifact is:

```text
<mutantDir>/graph/output.json
```

This JSON file is consumed by the downstream LLM test generation pipeline. It provides both mutation-side semantic evidence and test-entry-side engineering evidence, thereby supporting generation of tests that are reachable, infecting, propagating, observable, and compilable.
