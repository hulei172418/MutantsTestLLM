# RIP-Evidence Output JSON 证据生成工具链

## 1. 工程概述

本仓库提供 **RIP-Evidence** 方法中的证据构造工具链。给定 Java 变异体元数据后，工具链会为每个目标变异体生成结构化的 `output.json` 文件。该 JSON 文件包含变异语义、RIP 相关证据、入口提升调用证据、依赖上下文、接收者构造信息、分支可达提示以及可观察行为和断言计划。这些证据后续会被压缩为 `PromptEvidence`，用于引导大语言模型生成 JUnit 测试用例。

该工具链主要面向方法级 Java 变异体。对于每个目标变异体，工具链显式区分 **真实变异方法 A** 和 **测试可调用入口 B**。其中，A 是真正包含变异点的方法，用于构造变异点相关的 RIP/Jimple/CFG/DFG 证据；B 是生成的测试代码应当调用的合法入口，当 A 是私有方法、不可访问方法或只能通过间接调用链触达时，测试应通过 B 间接触达 A。批量入口为 `OutputJsonBatchRunner`，该类从 Excel 文件读取变异体元数据，并调用 worker 进程为每个变异体生成 `graph/output.json`。

## 2. 主要功能

- **批量证据生成。** `OutputJsonBatchRunner` 从 Excel 文件读取变异体行，构造 `MutationConfig`，调度任务，并生成记录每行执行状态的 `summary.tsv`。
- **基于 Worker 的隔离分析。** `OutputJsonWorker` 接收编码后的 `MutationConfig`，解析测试可调用入口，调用 `RipParser`，并通过 `ROW_RESULT` 输出每行执行结果。
- **变异点 RIP 证据构造。** `RipParser` 构建公共变异信息、原程序侧证据和变异体侧证据，并将最终 JSON 写入 `<mutantDir>/graph/output.json`。
- **AST 差异与 Jimple 对齐。** `DiffWithLineRanges` 计算原程序与变异体之间的方法级 AST 差异；`AstToJimpleBridge` 将受影响源码行映射到 Jimple 语句，并枚举经过受影响语句的有界路径。
- **入口提升证据构造。** `EntryLiftedRipBuilder` 为测试可调用入口 B 构造证据，包括调用计划、接收者构造、分支可达性、可观察行为和断言计划。
- **依赖与编译上下文构造。** `DependencyContextBuilder` 构造面向 Prompt 的依赖信息，包括包名、导入、方法签名、构造函数、字段类型、公开 API 以及编译防护规则等。

## 3. 工程结构

```text
src/main/java/
├── org/
│   ├── OutputJsonBatchRunner.java      # output.json 批量生成入口
│   ├── OutputJsonWorker.java           # 处理一个或多个变异体行的 worker
│   ├── MutationConfigCodec.java        # MutationConfig 序列化与反序列化
│   └── DataGenerator.java              # 早期单进程生成器和入口解析报告工具
│
├── org/model/
│   ├── MutationConfig.java             # 每个变异体的核心配置对象
│   ├── Bundle.java                     # 原程序/变异体两侧 JSON 证据容器
│   ├── CFG.java                        # CFG 相关证据结构
│   ├── DFG.java                        # DFG 相关证据结构
│   └── Info.java                       # 路径级 CFG/DFG 证据项
│
├── org/astjimple/
│   ├── DiffWithLineRanges.java         # 基于 GumTree 的 AST 差异与行号映射
│   ├── AstToJimpleBridge.java          # Soot/Jimple 分析与受影响路径提取
│   ├── MethodContent.java              # 源码级方法抽取与签名匹配
│   ├── SourceOwnerResolver.java        # 嵌套具体类变异 owner 重定位
│   └── ChangeRange.java                # 源码变更行范围表示
│
├── org/rip/
│   ├── RipParser.java                  # 核心 JSON 证据组装器
│   ├── RipExtractor.java               # CFG/DFG/RIP 辅助分析
│   ├── MethodEntryResolver.java        # 解析真实变异方法 A 与测试可调用入口 B
│   ├── ReceiverResolver.java           # 解析接收者构造与可观察计划
│   ├── DependencyContextBuilder.java   # 构造依赖与编译上下文
│   ├── EntryLiftedRipBuilder.java      # 构造入口提升 RIP 证据
│   └── PromptSignatureFormatter.java   # 内部签名到 Prompt 友好签名的转换
│
├── org/graph/
│   ├── ASTVisualizer.java              # 可选 AST 可视化
│   ├── CFGVisualizer.java              # 可选 CFG 可视化
│   └── DFGVisualizer.java              # 可选 DFG 可视化
│
└── org/utils/
    ├── ExcelUtils.java                 # Excel 读取工具
    ├── MethodSignature.java            # Soot 风格签名匹配
    ├── PathSanitizer.java              # Windows 路径规范化
    ├── DotToImageConverter.java        # 可选 DOT 到图片转换
    └── MapUtils.java                   # 小型 Map 构造工具
```

## 4. 输入格式

批量入口读取 Excel 文件第 0 个 sheet。第一行为表头，数据行从行索引 1 开始。每行会被转换为一个 `MutationConfig`。

| 列索引 | 字段 | 含义 |
|---:|---|---|
| 0 | `operator` | 变异算子及变异体编号 |
| 1 | `lineNo` | 变异所在源码行号 |
| 2 | `methodName` | 真实变异方法 A 的内部签名 |
| 3 | `className` | 变异体所在类 |
| 4 | `classNameF` | 文件级或源码级类名 |
| 5 | `mutationStatement` | 变异语句或差异描述 |
| 6 | `packageName` | Java 包名 |
| 7 | `projectName` | 项目名称 |
| 8 | `sourcePath` | 变异体源码文件或变异体目录路径 |

每行的目标输出路径为：

```text
<config.filepath>/graph/output.json
```

## 5. 输出文件

对于每个变异体，核心输出文件为：

```text
<mutantDir>/graph/output.json
```

批量运行时还会创建运行目录，通常位于：

```text
logs/output_json_parallel/<excel-name>_<timestamp>/
```

该目录包含：

| 文件 | 说明 |
|---|---|
| `summary.tsv` | 每行任务的执行汇总，包括行号、退出码、耗时、算子、类名、方法名、变异体路径、输出路径和日志文件 |
| `output_json_batch.log` | 所有 worker 的合并日志 |
| `pids.txt` | 父进程和 worker 进程 ID |
| `STOP` | 可选停止信号文件；创建该文件可请求优雅停止 |

## 6. 执行模式

### 6.1 Single 模式

`single` 模式在父 JVM 中顺序处理所有任务，适合调试。

```bash
java -cp <classpath> org.OutputJsonBatchRunner \
  --excel <mutant-metadata.xlsx> \
  --mode single \
  --rewrite true
```

### 6.2 Thread 模式

`thread` 模式使用 Java 线程池。由于 Soot 使用全局单例状态，`OutputJsonWorker` 内部会对核心 Soot 分析加锁，因此该模式是安全的，但不一定能显著加速。

```bash
java -cp <classpath> org.OutputJsonBatchRunner \
  --excel <mutant-metadata.xlsx> \
  --mode thread \
  --workers 8 \
  --rewrite true
```

### 6.3 Process 模式

`process` 模式是大规模生成时推荐使用的模式。该模式会启动多个持久 worker JVM，每个 worker 通过标准输入接收一组变异体行，并在同一 worker 内顺序处理。这样可以摊薄 JVM 和 Soot 初始化成本，同时保留进程级隔离。

```bash
java -cp <classpath> org.OutputJsonBatchRunner \
  --excel <mutant-metadata.xlsx> \
  --mode process \
  --workers 8 \
  --rewrite true
```

## 7. 推荐复现实验命令

```bash
java -Xmx16g -cp <full-project-classpath> org.OutputJsonBatchRunner \
  --excel data/mutants.xlsx \
  --mode process \
  --workers 8 \
  --rewrite true \
  --rowStart 1 \
  --rowEnd -1
```

如果从 IDE 中直接运行，也可以修改 `OutputJsonBatchRunner.java` 中的直接运行配置，例如 `DIRECT_EXCEL_FILE`、`DIRECT_MODE`、`DIRECT_WORKERS`、`DIRECT_REWRITE`、`DIRECT_ROW_START` 和 `DIRECT_ROW_END`。

## 8. 执行流程

```text
Excel 变异体元数据
  ↓
OutputJsonBatchRunner.loadTasks()
  ↓
每个变异体对应一个 MutationConfig
  ↓
single/thread/process 调度
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

## 9. 证据构造细节

### 9.1 真实变异方法 A 与测试可调用入口 B

工具链显式区分真实变异方法 A 和测试可调用入口 B。A 是包含变异点的方法，用于构造变异点相关的 RIP、Jimple、CFG 和 DFG 证据；B 是生成 JUnit 测试时应调用的方法。当 A 是私有方法或不可直接调用时，`MethodEntryResolver` 会寻找合法调用者 B；若找不到可访问入口，则使用反射回退或标记为不适合常规测试生成。

### 9.2 公共变异信息

`RipParser.buildCommonInfo()` 记录变异算子、变异差异、领域假设、可观察出口和 Jimple 变化。它调用 `DiffWithLineRanges.diffWithLineRangesForMethod()` 计算原程序与变异体源码之间的方法级 AST 差异。

### 9.3 原程序与变异体两侧 RIP 证据

`RipParser.buildOriginSide()` 和 `buildMutantSide()` 分别抽取方法源码、加载对应 Soot 方法体、识别受影响 Jimple 语句、枚举经过受影响语句的有界路径，并构造路径级 CFG/DFG 证据。每条路径上的证据可包括支配关系、路径谓词、控制依赖、变量定义、到可观察输出的使用链、覆盖性重定义、堆访问、潜在异常和别名相关信息。

### 9.4 依赖上下文

`DependencyContextBuilder` 构造面向 Prompt 的依赖上下文，用于支持可编译测试生成。它区分测试可调用入口 B 的详细上下文和真实变异方法 A 的轻量上下文。该上下文包括包名、导入、方法签名、接收者构造、公开 API、构造函数、引用类型、内部调用、可观察计划、分支可达计划和编译防护规则。

### 9.5 入口提升 RIP 证据

`EntryLiftedRipBuilder` 构造入口侧证据，用于说明测试如何通过可调用入口 B 触达真实变异方法 A。如果 B 与 A 是同一方法，则聚焦于变异受影响语句；如果 B 不同于 A，则聚焦于 B 中调用 A 的调用点，并枚举经过这些调用点的路径。该模块还记录入口关系、调用计划、接收者元数据、推荐测试值、公开 API 约束、可观察计划、分支可达计划和断言计划。

## 10. 依赖环境

| 依赖 | 用途 |
|---|---|
| Soot | 字节码/Jimple 加载、CFG 构造、支配和后支配分析 |
| JavaParser | 源码解析、可调用实体抽取、接收者和依赖上下文分析 |
| GumTree | 原程序与变异体方法之间的 AST 差异分析 |
| Jackson | JSON 序列化 |
| Apache POI | Excel 元数据读取 |
| Graphviz | 可选 DOT 到图片转换 |
| JUnit 4 | 测试生成上下文 |

## 11. 路径处理说明

工具链包含 `PathSanitizer`，用于规范化 Windows 路径，尤其是 `\\?\C:\...` 这类长路径前缀。这样可以避免部分 JDK 对 Windows 长路径前缀处理不一致导致的路径解析失败。

## 12. 退出码

| 退出码 | 含义 |
|---:|---|
| 0 | 生成成功 |
| 2 | 当前行分析失败 |
| 64 | 命令行参数错误或 Excel 路径缺失 |
| 130 | 收到优雅停止请求 |

每个 worker 会输出一行 `ROW_RESULT`，其中包含行号、退出码、耗时和变异体路径。父进程解析这些结果，并写入 `summary.tsv`。

## 13. 复现检查清单

运行工具链前应确认：

- 原程序和变异体程序均已完成编译。
- Excel 文件包含所需的变异体元数据列。
- classpath 中包含 Soot、JavaParser、GumTree、Jackson、Apache POI、JUnit 以及被测项目依赖。
- `sourcePath` 列指向有效的变异体源码文件或变异体目录。
- 项目目录中存在对应的 `original` 目录和变异算子目录。
- worker JVM 具有足够内存运行 Soot 分析。
- 若需要重新生成，删除已有 `graph/output.json` 或使用 `--rewrite true`。

## 14. 预期产物

对于每个成功处理的变异体，预期产物为：

```text
<mutantDir>/graph/output.json
```

该 JSON 文件供后续 LLM 测试生成流水线使用。它同时提供变异侧语义证据和测试入口侧工程证据，从而支持生成具备可达、可感染、可传播、可观察和可编译特性的变异体杀死测试。
