# Baseline 测试用例生成与 MutantsTestLLM 对比实施方案

> 版本：3.12（EvoSuite/Randoop 方法实现完全分目录，common 仅保留公共执行能力）  
> 更新日期：2026-03-02  
> 工作区：`E:\PHD\MutantsTestLLM`  
> 核心 Baseline：A3Test、ASTER、ChatTester、HITS、IntUT、MuTAP、MutGen  
> 统一大模型：DeepSeek-V4-Flash；API 模型标识 `deepseek-v4-flash`

## 1. 唯一目标

本项目不是以复现七篇论文中的原始实验数字为主要目标，而是：

> 在 MutantsTestLLM 使用的同一批 Java 项目、目标方法和变异体上，让七种 Baseline 实际生成 JUnit 测试，再通过统一的编译、执行和变异测试流水线，与 MutantsTestLLM 对比。

论文仓库的原始样例只用于 smoke check：验证代码能运行且对方法的理解没有明显偏差。正式结果来自当前项目上的测试生成实验，不要求重训原论文模型，也不把原论文数据集上的指标放入主结果表。

正式实验回答：

1. 各方法能否生成可编译的 JUnit 4 测试？
2. 测试能否在原程序上稳定通过？
3. 测试能否到达目标代码并杀死当前 MuJava 变异体？
4. 与 MutantsTestLLM 相比，CompR、ExecR、MS、TKR、覆盖率、时间、Token 和费用如何？
5. 在相同 DeepSeek-V4-Flash 后端下，不同上下文、提示策略和反馈机制带来多少差异？

### 1.1 所有 Baseline 强制执行的两层协议

“生成出能编译的测试”不是完成标准。七种 LLM Baseline 都必须依次经过以下两层，
才能进入与 MutantsTestLLM 的主比较。EvoSuite、Randoop 是纯搜索式特殊控制组，
本轮暂不改造其生成协议，也不纳入本节的 LLM 阶段约束：

```text
第一层：Baseline 原生测试生成协议
  → 解析/规范化
  → 编译
  → 在原程序上执行
  → 按该方法论文允许的反馈机制修复
  → 发布不可变 final 测试套件

第二层：公共评估协议
  → 原程序隔离复验
  → 同一 final 套件逐唯一 MuJava 变异制品执行
  → 统一 KILLED/LIVE/ERROR/TIMEOUT 判定
  → 统一 CompR、ExecR、MS、Token、时间和规模统计
```

第一层必须忠实于各方法本身：保留论文或固定作者代码中的 Prompt、阶段、真实调用
条件、反馈与停止规则。ChatTester 论文正文只允许根据编译错误进行 LLM 修复；编译成功
后停止修复，再在原程序上执行并统计 execution correctness。运行失败不得反馈给 LLM，
也不得进入变异评估。作者制品中的 `testRepair` 只能作为单独标识的 artifact-mode 行为，
不得混入论文优先的正式结果。没有 LLM 修复机制的方法不得为了提高通过率私加修复调用。
除 MuTAP、MutGen 等原方法明确使用变异反馈的方法外，生成阶段禁止看到变异语句、
等价标签、RIP 证据和击杀结果。

冲突裁决顺序固定为：先忠实论文正文/附录；再用固定 commit 的作者代码补齐论文未写
清楚的工程细节；二者都无法直接落地时，才采用本项目方案并标记
`API_REQUIRED_ADAPTATION` 或 `EVALUATION_COMMON`。唯一覆盖原模型选择的强制前提是：
七种 LLM Baseline 的所有模型调用均替换为 DeepSeek-V4-Flash。除模型客户端及其必要
接口字段外，不得借替换模型之名改变原方法的 Prompt、阶段、调用条件、反馈内容或停止
规则。EvoSuite/Randoop 不调用任何 LLM，因此不适用模型替换，也禁止为其增加 DeepSeek
生成或修复。

第一层的最低有效性门槛为：测试真实编译成功、在原程序上执行完成且通过、不超时，
并确认不是复用旧 `.class` 造成假成功。只编译成功但原程序断言失败、初始化失败、
运行异常或超时的测试不得进入第二层，也不能把原程序失败直接解释成“杀死变异体”。

目标生成按 `project + class_name + method_signature` 分组；同一方法按该 Baseline 原生
协议生成一套测试。评估时重新展开这个方法关联的全部原始记录，按规范化 `file_path`
识别唯一物理 `.class`，用同一套已通过测试逐个执行。相同 `operator` 但不同
`file_path` 是不同变异体；完全相同的 `file_path` 只执行一次，但所有 Excel 原始行都
必须保留并映射到该次结果，不能重复计入 MS 分母。

第二层统一复用 `Baselines/adapters/common/mutant_execution.py`，不得在七个 LLM adapter
中各自实现不同的击杀判定。各 Baseline adapter 只负责产出公共执行器要求的
`metadata.json`、`state.json=ORIGINAL_PASSED`、`source_records.jsonl` 和
`project_artifacts_final.json`。公共执行器先对原程序隔离复验一次，再为每个唯一变异
制品启动一个全新 JVM。非变异反馈方法只在生成结束后调用它做盲评；MuTAP、MutGen
只有在论文规定的 survivor-feedback 节点才可读取其结果并进入下一轮。

原论文自己的覆盖率、编译率、切片效果或分支指标用于验证“实现是否仍像原方法”；
主比较必须使用当前统一项目、统一 MuJava 集合和统一执行器，不能直接拿不同论文、
不同数据集或不同变异工具的原始数字互相比。

## 2. 两份方案的取舍

### 2.1 认可并保留

- 区分原作者代码、Artifact、第三方实现和论文驱动的独立重实现；
- 固定仓库 URL、commit、依赖、模型配置及本地补丁；
- 使用相同项目、目标集合、变异体和结果计算器；
- 保存每轮 prompt、原始 API response、生成测试、编译日志和变异执行结果；
- 区分方法/类级套件与目标变异体级生成，不能混淆 MS 和 TKR；
- HITS 标记为第三方实现，ASTER 和 IntUT 标记为独立重实现；
- MuTAP 必须实现 Java/JUnit/MuJava 适配版，不能用 Python 结果代替；
- 使用统一失败分类、smoke/pilot/full 准入和可审计结果格式；
- Randoop、EvoSuite 可作为传统控制组，但不属于七种 LLM Baseline 主线。

### 2.2 调整后保留

- 当前项目统一对比是主线；原论文示例仅作可选正确性检查。
- 统一模型由建议改为强制：所有通用大模型调用全部使用 DeepSeek-V4-Flash。
- 不机械要求各方法调用次数完全相同。HITS 切片、IntUT 意图和 MutGen 摘要是方法必要阶段，因此同时报告原生策略预算和等预算结果。
- A3Test 原始 PLBART 模型是论文实现的一部分。统一换成 DeepSeek 后，主实验命名为 `A3Test-DS-Adapted`，不称为严格原版 A3Test。
- HITS 可复用第三方切片流程，但必须替换其原有模型客户端并验证切片、逐片生成、合并与修复确实存在。

### 2.3 不采纳

- 不把完整复现原论文训练、原论文数据集和绝对数值作为主任务；
- 不要求完成 MuTAP 全量 Python 实验后才实现 Java 版；
- 不因论文原模型不可用而混用多个模型；主比较统一 DeepSeek-V4-Flash；
- 不向原本不使用变异信息的 Baseline 私自加入 RIP 证据或击杀答案；
- 不把 ASTER 的 CodaMOSA 代码当作完整 ASTER 生成器；
- 不把 ChatUniTest 的 HITS 表述为 HITS 原作者官方代码。
- 不根据实现方便或统一预算臆造论文方法的阶段、调用次数、修复轮数、Prompt、温度、
  Token 上限、超时、采样参数或停止条件；找不到依据的配置必须标为 `UNRESOLVED`，
  在完成论文/作者代码核对前不得进入正式实验。

## 3. 实现身份和难度

| Baseline | 代码入口 | 主实验实现名称 | 保留的核心机制 | 难度 |
|---|---|---|---|---|
| A3Test | [主仓库](https://github.com/awsm-research/A3Test)、[备用仓库](https://github.com/A3Test-TestCaseGeneration/A3Test) | `A3Test-DS-Adapted` | 测试骨架 + 断言增强 | 高 |
| ASTER | [官方 Artifact](https://github.com/aster-test-generation/aster) | `ASTER-Java-DS-Reimplementation` | 静态上下文 + 修复 + 覆盖增强 | 很高 |
| ChatTester | [官方 main](https://github.com/FudanSELab/ChatTester/tree/main) | `ChatTester-DS-Adapted` | 方法意图 + 初始生成 + 编译反馈；运行仅作正确性评估 | 中高 |
| HITS | [Maven 插件](https://github.com/ZJU-ACES-ISE/chatunitest-maven-plugin)、[core](https://github.com/ZJU-ACES-ISE/chatunitest-core) | `HITS-ChatUniTest-DS` | 方法切片 + 分片生成 + 合并修复 | 中高 |
| IntUT | [论文 DOI](https://doi.org/10.1109/ICSE55347.2025.00243) | `IntUT-DS-Reimplementation` | 分支分析 + 测试意图 + 生成 | 很高 |
| MuTAP | [官方仓库](https://github.com/ExpertiseModel/MuTAP) | `MuTAP-Java-DS-Adapted` | 初始生成 + 存活变异体反馈 | 中高 |
| MutGen | [作者复现包](https://github.com/Amocy-Wang/MUTGEN) | `MutGen-DS-Adapted` | 摘要 + 生成 + 变异反馈 + 修复 | 中高 |

这些名称必须进入日志和论文实验设置，不能把适配版写成完全不变的原论文实现。

## 4. DeepSeek-V4-Flash 统一 API

### 4.1 只读参考与独立公共模块

`MuTestLLM` 中原有 API 调用代码只允许作为只读参考，禁止在该子目录新增、修改、
编译或生成任何 Baseline 文件。已参考的模块包括：

```text
MuTestLLM/src/main/java/mujava/testgenerator/tools/
├── ModelConfig.java
├── ModelConfigLoader.java
├── LlmClient.java
├── CachedLlmClient.java
├── LlmResponseCache.java
├── LlmCallResult.java
└── GeneratedCodeExtractor.java
```

Baseline 的实际实现和构建全部位于：

```text
Baselines/adapters/common/
├── llm_gateway.py                 # 环境变量、HTTP、重试、缓存、原始响应
├── manifest_paths.py              # Excel/正式 manifest 的唯一公共路径定义
├── build_target_manifest.py       # 冻结目标清单
└── java-tools/
    ├── pom.xml                    # 只在 Baselines 内构建
    └── .../TargetContextCli.java  # JavaParser 上下文提取
```

统一调用链为：`Baselines/configs/llm.properties` +
`Baselines/adapters/<Baseline>/llm.properties` → `llm_gateway.py` → OpenAI-compatible
`/chat/completions` → Baseline 私有阶段解析器。公共 Java 工具只负责静态分析，
构建产物写入 `Baselines/adapters/common/java-tools/target`。

必须统一的行为：Bearer Key、OpenAI-compatible `messages`、model/temperature/max_tokens、连接与读取超时、有界重试、缓存、原始 HTTP JSON 留档、content 提取及 usage 统计。禁止七个仓库各自维护 API Key 和重试逻辑。

### 4.2 统一配置

Baseline 不读取或修改 `MuTestLLM/src/main/resources/llm.properties`。公共文件
`Baselines/configs/llm.properties` 只保存 provider、URL、模型、密钥、公共输入限制、
thinking、超时/重试、缓存、Token 计价和生成并发；每个 LLM Baseline 在自己的
`Baselines/adapters/<Baseline>/llm.properties` 中只保存其 `llm.stages.<stage>.*`
阶段预算与采样设置。EvoSuite/Randoop 不调用 LLM，不创建空的 `llm.properties`。

不设置“读取本地还是公共”的标记变量。请求携带 stage 后，公共网关自动发现唯一声明该
stage 的 adapter 本地文件，并按以下固定顺序合并：代码默认值 < 公共 properties <
公共环境变量覆盖 < Baseline 本地阶段 properties < 显式调用参数。换成查找顺序即：
显式调用 → Baseline 本地 → 公共环境/properties → 默认值。缺少本地键时自然回退公共值；
本地文件出现非 `llm.stages.*` 键，或两个 adapter 声明同一 stage，必须立即报配置错误。
调用日志必须记录公共/本地配置路径和合并优先级，但不得记录 API Key。

`llm.provider` 选中一个 profile，代码使用
`llm.providers.<provider>.api.url/model/api.key`。profile 内部解析优先级为：显式通用
环境变量 → provider-specific 环境变量 → 选中 profile 的属性 → 全局属性 → 非敏感
默认值。API Key 只用于 HTTP Authorization 请求头，严禁写入 request、response、
cache、call、trace 或报告。

```properties
llm.provider=deepseek
llm.temperature=0.2
llm.max.tokens=16000
llm.max.input.tokens=16000
llm.max.input.chars=30000
llm.budget.chars.per.token=3.0
llm.input.message.overhead.tokens=16
llm.oversize.prompt.policy=truncate
llm.connect.timeout.millis=30000
llm.read.timeout.millis=120000
llm.api.max.attempts=5
llm.api.retry.initial.delay.millis=1000
llm.api.retry.max.delay.millis=10000
llm.threads=1
llm.skip.existing.compiled=true
llm.force.regenerate=false

llm.providers.deepseek.api.url=<OPENAI_COMPATIBLE_CHAT_COMPLETIONS_URL>
llm.providers.deepseek.model=deepseek-v4-flash
llm.providers.deepseek.api.key=<SECRET>

# 以下阶段配置不写在公共文件，而分别写入对应 adapter/llm.properties。
# 主对比：所有 LLM 阶段统一 16000 output-token 上限
llm.stages.chattester_intention.max.tokens=16000
llm.stages.chattester_initial.max.tokens=16000
llm.stages.mutgen_summary.max.tokens=16000
llm.stages.mutgen_generation.max.tokens=16000
llm.stages.mutgen_compile_repair.max.tokens=16000
llm.stages.mutgen_assert_repair.max.tokens=16000
llm.stages.hits_slicing.max.tokens=16000
llm.stages.hits_slice_test.max.tokens=16000
llm.stages.hits_test_repair.max.tokens=16000
llm.stages.a3test_generation.max.tokens=16000
llm.stages.aster_initial.max.tokens=16000
llm.stages.aster_repair.max.tokens=16000
llm.stages.aster_coverage.max.tokens=16000
llm.stages.intut_generation.max.tokens=16000
llm.stages.mutap_initial.max.tokens=16000
llm.stages.mutap_syntax_repair.max.tokens=16000
llm.stages.mutap_survivor_augmentation.max.tokens=16000

# ChatTester 作者工程对所有 GPT-3.5 调用显式使用 temperature=0
llm.stages.chattester_intention.temperature=0
llm.stages.chattester_initial.temperature=0
llm.stages.chattester_compile_repair.temperature=0
llm.stages.chattester_runtime_repair.temperature=0

# MutGen 作者流程使用确定性采样
llm.stages.mutgen_summary.temperature=0
llm.stages.mutgen_generation.temperature=0
llm.stages.mutgen_compile_repair.temperature=0
llm.stages.mutgen_assert_repair.temperature=0

# MuTAP 作者配置
llm.stages.mutap_initial.temperature=0.8
llm.stages.mutap_syntax_repair.temperature=0.8
llm.stages.mutap_survivor_augmentation.temperature=0.8

# HITS 论文使用 greedy decoding；统一 DeepSeek 适配保持 temperature=0
llm.stages.hits_slicing.temperature=0
llm.stages.hits_slice_test.temperature=0
llm.stages.hits_test_repair.temperature=0
llm.stages.a3test_generation.temperature=0
# ASTER 未公开 temperature；以下 0 是统一 DeepSeek 重实现设置，不冒充原生参数
llm.stages.aster_initial.temperature=0
llm.stages.aster_repair.temperature=0
llm.stages.aster_coverage.temperature=0
# IntUT 报告多候选采样但未公开 temperature；0.2 是 DeepSeek 重实现设置
llm.stages.intut_generation.temperature=0.2
```

高层调用沿用 `MuTestLLM` 的职责拆分：配置加载器负责 profile 与运行策略，公共
client 负责 HTTP/重试/缓存，Baseline 工作流负责 stage、prompt、编译反馈和审计日志。
调用点可显式选择 cache/fresh；未显式指定时由 `llm.force.regenerate` 决定。这样后续
七个 Baseline 可以共用接口，同时按各自论文流程选择缓存、阶段预算和修复轮次。

- `LLM_API_URL` 必须是最终 `/chat/completions` 地址；
- Key 不写入 Git、本文档、prompt、response 或结果文件；
- 配置合并顺序为：默认值 → 公共 properties → 公共环境变量 → Baseline 本地阶段
  properties → 显式调用；公共 provider profile 内仍按通用环境变量 → provider-specific
  环境变量 → profile 属性 → 全局属性 → 默认值解析；
- provider 是本地 profile 名，报告中还要说明真实 API 服务平台；
- Full 实验开始后冻结 URL、DeepSeek 模型标识、各方法有来源依据的阶段级
  temperature、DeepSeek 必需的 thinking 适配配置和超时；
- 若服务商无法固定模型快照，记录调用日期并把后端漂移列为有效性威胁。
- 全局配置只提供公共传输默认值；论文方法有明确设置时必须在对应 adapter 的
  `llm.properties` 中使用 `llm.stages.<stage>.*` 覆盖，禁止为了方便让七个 Baseline 共用并不存在于原文的
  temperature、max token 或修复次数。
- 论文/作者代码未声明最大输入 Token 时，统一将其标记为
  `API_REQUIRED_ADAPTATION`，并使用 `llm.max.input.tokens` 和
  `llm.max.input.chars`；阶段级 `llm.stages.<stage>.max.input.tokens/chars` 优先。
- 输入预算必须在缓存查找和 HTTP 发送前检查。`error` 策略直接形成可审计失败；
  `truncate` 只截断 user prompt 中部并保留首尾，日志必须同时记录原始/发送后的字符数、
  Token 估算、估算公式与 `truncated`。不得把本地估算冒充服务商实际 Token，实际统计
  仍以响应 usage 为准。

### 4.3 公共客户端接口

公共 `llm_gateway.py` 支持阶段特定的 system prompt，因为以下阶段不能共用固定的
“只输出 Java”提示：

- HITS 的 method-slice JSON；
- IntUT 的 test-intention JSON；
- MutGen 的方法/变异摘要；
- ASTER 的当前通过测试、未覆盖 focal 行及覆盖增强响应；
- A3Test 适配版的一次端到端生成响应及确定性验证审计。

所有方法调用同一个 Baselines 公共接口：

```python
call_llm(baseline, stage, system_prompt, user_prompt,
         cache_dir, max_tokens, use_cache)
```

要求：

1. `baseline + stage + 实际发送的 systemPrompt/userPrompt + profile/model + temperature + maxTokens` 全部进入缓存指纹；
2. 测试生成/修复阶段要求只输出 JUnit 4 Java；
3. slicing/intention/planning/summary 阶段要求只输出指定 JSON schema；
4. 不同 Baseline、不同阶段不得误用缓存；
5. 独立重复实验设置 `useCache=false`，同一次故障重跑可以用缓存；
6. 保存原始 response、解析后的 content、usage、归一化 Token、尝试次数、缓存命中和耗时；
7. JSON 解析失败只允许一次格式修复调用，且计入预算；
8. 服务端不支持的参数不得伪造，例如不支持 `seed` 时只记录 repeat ID。

### 4.4 Java 与 Python 上游的接入

- Java 上游通过其 Baselines 私有适配器或公共文件式桥接调用
  `Baselines/adapters/common/llm_gateway.py`；
- Python 上游不得继续调用 Codex/Llama/Ollama 专属 SDK；
- Python 代码直接调用同一公共 gateway，不得各自实现 HTTP 客户端；
- 允许保留论文 prompt 模板，但模型客户端、Key、重试、缓存和 usage 记录全部替换；
- 每次调用写明 `baseline/stage/method_group_id/round`。

### 4.5 适用于所有 LLM Baseline 的 Token 与缓存统计硬约束

A3Test、ChatTester、HITS、ASTER、MuTAP、MutGen、IntUT 必须统一使用
`Baselines/adapters/common/token_accounting.py`，不得各自解释服务商 usage 字段。
完整字段与兼容映射以 `Baselines/adapters/common/TOKEN_ACCOUNTING.md` 为准。

每个逻辑调用的 `call.json`、`response.json` 以及该调用关联的每条
`call_mutant_trace.jsonl` 必须包含同一份 `token_usage`：

- `input_tokens`：服务商报告的全部输入 Token；
- `uncached_input_tokens`：普通/未缓存输入（Uncached Input）；
- `cache_read_input_tokens`：缓存读取（Cache Read）；
- `cache_write_input_tokens`：缓存写入（Cache Write）；
- `output_tokens`、`reasoning_output_tokens`、`total_tokens`；
- `local_response_cache_hit`、`api_request_performed`；
- `cache_write_tokens_reported`，用于区分“服务商报告为 0”和“服务商未报告”。

缓存命中率固定为：

```text
cache_hit_rate = cache_read_input_tokens
                 / (cache_read_input_tokens + uncached_input_tokens)
```

分母为 0 时记为 0。run 级命中率必须先汇总 Token 再计算，不得平均各调用百分比。
DeepSeek 的 `prompt_cache_hit_tokens` 映射为 Cache Read，
`prompt_cache_miss_tokens` 映射为 Uncached Input。服务商未返回缓存写入字段时，必须
记录 `cache_write_input_tokens=0` 且 `cache_write_tokens_reported=false`，禁止推测。

本地 prompt-hash response cache 命中不会请求服务商，本次调用的 Token 计数全部为
0；缓存文件中携带的历史 usage 只能放在 `source_response_token_usage`，不得再次计入
总量。每个 run 必须生成 `token_usage_calls.jsonl` 和 `token_usage_summary.json`。
EvoSuite、Randoop 不调用 LLM，Token 字段统一为 0，并在横向报告标为
`not_applicable`。

### 4.6 目录隔离硬约束

- `Baselines/sources/<Method>/**` 是作者原始仓库或论文 Artifact 的固定快照，必须
  保持作者发布时或固定 commit 时的原样；它只用于来源追溯、方法核对和人工参考；
- 禁止在 `sources` 中编辑、格式化、重命名、删除或替换作者文件，禁止修改其中的
  Prompt、模型接口、依赖和配置，也禁止因为当前复现暂时用不到而清理作者文件；
- 禁止在 `sources` 中原地编译、执行实验或产生 `target`、`build`、`dist`、
  `__pycache__`、虚拟环境、缓存、测试用例、日志及报告；
- 所有具体复现与适配只能在 `Baselines/adapters/<Method>` 中完成。需要复用作者实现
  时，先从 `sources/<Method>` 复制或人工迁移所需代码到对应 adapter，再仅修改
  adapter 中的副本；不得把适配后的文件回写 `sources`；
- 跨方法公共能力只能放在 `Baselines/adapters/common`，方法私有代码、依赖、构建
  文件和运行入口只能放在对应 `Baselines/adapters/<Method>`；
- 模型请求与响应、缓存、Token、变异体关联日志及映射写入新的
  `Baselines/runs/<run_id>`；生成测试源码、`.class` 和编译/JUnit 报告写入对应工程的
  `<project_root>/baselines/<Baseline>/runs/...` 隔离子树。两处均不得覆盖 `sources`、
  adapter 源码或项目真实源码/构建目录；
- `MuTestLLM/**` 对 Baseline 工作为只读区域；
- Baseline 自有实现、依赖、POM、配置和缓存必须位于 `Baselines/**`；只有与被测工程
  绑定的生成测试源码、编译产物及其执行报告按上述规则进入工程级 `baselines` 子树；
- 可以阅读并人工迁移已有实现思路，但不得让 Baselines 编译引用
  `MuTestLLM/target/classes` 或在其目录执行 Maven；
- 每次实施后必须验证 `MuTestLLM` 零改动，同时核对对应 `sources/<Method>` 与固定
  commit/Artifact 一致。若上游文件名无法由 Windows 表示，以固定的 bare Git 仓库
  为完整权威副本，Windows 可用导出仅作为等内容副本，不得改写上游 blob 内容。

标准实施顺序固定为：

```text
sources/<Method>（作者原码，只读核对）
        ↓ 复制/人工迁移一次
adapters/<Method>（复现、修改、编译与执行入口）
        ↓ 每次实验同时新建控制面 run 与工程隔离产物树
runs/<run_id>（模型调用、关联日志、Token、映射和汇总）
<project>/baselines/<Baseline>/runs/...（Java、class、编译/JUnit 报告）
```

任何直接修改 `sources`、在 `sources` 中构建/运行，或将生成物写回 `sources` 的做法，
都视为违反本复现方案，必须停止并恢复作者快照后再继续。

### 4.7 论文方法忠实性与配置来源硬约束

每个 Baseline 开始实现前，必须在对应 `adapters/<Method>/README.md` 或
`ADAPTATION_PLAN.md` 建立配置来源表。每一项阶段、Prompt、调用条件、循环终止条件、
temperature、Token 限制、超时、JDK/JUnit、编译命令和测试命令只能属于以下一类：

1. `PAPER`：论文正文、附录或补充材料明确给出；
2. `AUTHOR_CODE`：固定 commit 的作者代码或配置明确给出；
3. `API_REQUIRED_ADAPTATION`：替换为 DeepSeek-V4-Flash 后为接口兼容所必需；
4. `EVALUATION_COMMON`：不改变生成方法、只用于统一评估的公共设置；
5. `UNRESOLVED`：尚未找到可靠依据，禁止用于正式复现结果。

冲突时优先记录冲突而不是自行选择：论文明确值优先于作者仓库默认值；若作者代码是
论文实验的可执行制品，则同时保存两者证据和最终选择理由。原论文模型名称是唯一允许
被统一实验前提覆盖的项目：所有实际 LLM 调用强制使用 DeepSeek-V4-Flash，但模型以外
的流程和配置仍按上述来源优先级执行。任何偏离都必须写入
`adaptation_deltas`，不能静默发生。主对比新增唯一例外：所有 LLM 阶段统一使用
`max_tokens=16000`，标记为 `UNIFIED_OUTPUT_BUDGET_ADAPTATION`，避免4096等较小上限把
方法效果混同为输出截断能力。论文/作者原始输出上限保留为单独 Native-budget 敏感性实验，
不得与统一预算主结果混合。

“一次方法生成流程”不等于“一次模型调用”。调用次数必须由原方法的真实阶段和条件
分支决定：只有实际走到该阶段才调用，重试、格式修复、反馈修复和重新推断意图都要
分别记录并计入实际调用数。运行前给出的“预计调用数”只能是根据已核实协议计算的
区间或上界，并注明依据，不能成为新的停止条件。

### 4.8 可配置高并发硬约束

大模型生成和独立 JVM 变异执行都必须提供有界多 worker 版本，同时保留
`workers=1` 串行模式用于调试和复核。并发只改变独立任务的调度，不得改变论文方法
内部协议、模型可见信息、测试语义、变异隔离方式、End-to-End MS 或 Executable MS 计算。

#### 测试生成并发

- `Baselines/configs/llm.properties` 中的 `llm.threads` 是默认方法级 worker 数；
- `run_batch.py --workers N` 和 `run_feedback_batch.py --workers N` 可在一次运行中覆盖，
  `--workers 0` 表示读取 `llm.threads`；
- 不同 focal method 可以并发；同一方法内部的 intention、initial、论文允许的 feedback、
  repair 和停止判断必须按原文顺序串行，禁止把有依赖的阶段并发。ChatTester 仅并行不同
  方法；单个方法内部的编译修复串行，编译成功后的原程序执行不产生 LLM 反馈；
- 每个 worker 必须使用独立 target run 目录、call ID、缓存键和日志文件；不得共享或
  覆盖请求、响应、Token、Java 源码和状态文件；
- 实际 `worker_count`、配置来源、每个任务起止时间和失败必须写入 batch metadata/
  summary。服务商限流仍由公共 gateway 的有界重试处理，不能通过无限线程绕过；
- `--stop-on-error` 为保持精确停止语义只允许与 `--workers 1` 一起使用。

#### 变异执行并发

- `Baselines/configs/java.properties` 中的 `java.mutant.workers` 是默认 worker 数；
- `mutant_execution.py --workers N` 可覆盖配置，`--workers 0` 表示读取 properties；
- 原程序隔离基线始终只执行一次并完成后才启动 worker 池；
- worker 仅负责并发编排外部 Java 进程。每个唯一变异制品仍必须拥有独立 overlay、
  独立输出目录和全新的 JVM/ClassLoader，绝对不能在同一 JVM 内并发替换类；
- 结果按冻结 manifest 顺序稳定回收，不允许按完成先后改变 mutant/source-record 映射；
- `worker_count` 必须记录在 index/summary。超时、KILLED/LIVE/基础设施错误判定与串行
  模式完全相同；正式使用前必须验证 `workers=1` 与 `workers>1` 的逐变异结果一致；
- worker 数必须有界并由使用者根据 CPU、内存、API 限流和项目 JVM 负载手动调整。

推荐起始配置如下，正式实验冻结实际值并落盘：

```properties
# Baselines/configs/llm.properties
llm.threads=8

# Baselines/configs/java.properties
java.mutant.workers=4
```

## 5. 统一任务、输入和输出

### 5.1 目标清单

从 `Baselines/data/mutant_total.xlsx` 生成冻结清单，并写入
`Baselines/data/target_manifest.jsonl`：

转换只有一个公共实现：`Baselines/adapters/common/build_target_manifest.py`；路径常量只有一个
公共来源：`Baselines/adapters/common/manifest_paths.py`。ChatTester、MutGen、MuTAP、HITS、
A3Test、ASTER、IntUT、EvoSuite 和 Randoop 的正式默认输入全部是完整
`target_manifest.jsonl`。`target_manifest.first10.jsonl` 等有界清单只能由 F5/CLI 显式传入，
不得在某个 baseline 内硬编码成正式默认值。

适配器脚本采用目录内角色命名：`adapters/<Baseline>` 已经表达方法身份，因此 Python 文件名
不得再次带该 baseline 前缀。统一使用 `protocol.py`、`context.py`、`analysis.py`、`rag.py`、
`pipeline.py`、`source_cleaner.py`、`test_protocol.py` 等简名。论文阶段 ID、调用日志 stage、
run ID、JSON 结果文件和配置文件继续保留 baseline 前缀，以保证跨目录汇总时身份明确；该规则
只针对 adapter 内 Python 脚本文件名，不改作者 `sources`。

```json
{
  "operator": "AOIS_1",
  "line": 0,
  "method": "void_method(int)",
  "class": "Example",
  "class_f": "Example",
  "mutation_statement": "x => ++x",
  "package": "main.java.org.example.Example",
  "project": "project-version",
  "file_path": "...",
  "original_graph_path": "...",
  "mutant_graph_path": "...",
  "equivalent": 0,
  "method_group_id": "project|className|methodSignature",
  "class_name": "...",
  "method_signature": "...",
  "source_path": "...",
  "mutant_uid": "sha256(project|className|methodSignature|normalizedFilePath)",
  "source_record_uid": "sha256(original12Fields|sourceRowNumber)",
  "mutation_operator": "...",
  "mutation_line": 0,
  "original_source_path": "...",
  "mutated_source_path": "...",
  "classpath": ["..."],
  "test_framework": "junit4"
}
```

规范化关联键固定为：

```text
method_group_id = projectName + targetClassName + methodSignature
mutant_uid      = method_group_id + normalized(file_path)
source_record_uid = Excel 原始 12 字段 + 原始行号
```

`mutantName/operator` 仅作可读显示字段，不能充当唯一键。

清单保留“一行一个变异体关联记录”的粒度（原始数据可能多行指向同一物理
`.class`），因此必须读取 Excel 的 `file_path` 并保存为 `mutant_artifact_path`，但
测试生成任务不能按该字段逐行调用模型。生成前按
`project + class_name + method_signature` 分组去重：同一方法只构造一次上下文、
调用一次该 Baseline 的生成流程；随后把生成的测试套件映射回该组全部
`file_path/mutant_id`。评估时按规范化 `file_path` 逐唯一制品执行，再把结果映射回
全部关联记录。`file_path` 指向变异后的 `.class`，用于变异
执行和结果关联，不作为 ChatTester、A3Test、ASTER、HITS、IntUT 的模型输入。

上述 12 个 Excel 原字段名是冻结 schema，后续不得改名或丢弃：

```text
operator, line, method, class, class_f, mutation_statement,
package, project, file_path, original_graph_path,
mutant_graph_path, equivalent
```

允许额外添加 `class_name/method_signature/mutant_id/...` 等规范化字段，但不能用
这些别名替代原字段。

### 5.2 公共输入与信息隔离

所有方法可获得 focal class/method 源码、签名、字段、构造函数、import、可访问项目 API、必要依赖、classpath 和 JUnit 4 输出规范。方法包含修复阶段时，可以获得真实编译错误和原程序运行错误。

变异信息按论文机制控制：

- MuTAP、MutGen 可获得其机制所需的存活变异体、位置、算子或 diff；
- A3Test、ASTER、ChatTester、HITS、IntUT 默认不获得目标 mutant diff、RIP 证据或击杀结果；
- IntUT 可获得静态分支与路径条件，但不得获得 MutantsTestLLM 的 RIP 证据；
- 任何方法都不得读取 MutantsTestLLM 的最终测试、标签或实验结果。

### 5.3 生成粒度和公平比较

- A3Test、ASTER、ChatTester、HITS、IntUT：每个 focal method 生成一次测试套件，再执行该方法下全部变异体；
- MuTAP、MutGen：围绕同一 focal method 读取存活变异体并迭代增补测试，每轮记录看到的变异体集合；
- MutantsTestLLM：保留当前目标变异体级生成方式。

正式主实验先报告：

1. **Unified-output-budget**：各方法保留原生阶段、Prompt、调用条件与停止规则，但每次
   LLM 调用统一允许最多16000个输出 Token；短摘要/意图仍由 Prompt 约束，不要求消耗上限；
2. **Efficiency**：每杀死一个变异体的调用数、Token、费用和时间。

统一的是单次输出容量，不统一调用次数、输入 Token、迭代轮数或修复次数。Native-budget
按论文/作者代码的原始输出上限另建 run 作为敏感性分析，不得与主对比合并。

### 5.4 统一产物

控制面与模型证据保存在 Baselines：

```text
Baselines/runs/<experiment_run_id>/.../<target_or_method_id>/
├── metadata.json
├── call_mutant_trace.jsonl
├── calls/<call_id>/{call,request,response}.json
├── prompts/<stage>_<round>.txt
├── responses/<stage>_<round>.json
├── parsed/<stage>_<round>.json-or-java
├── token_usage_calls.jsonl
├── token_usage_summary.json
├── project_artifacts.json
├── project_artifacts_final.json
└── state.json
```

可执行测试产物放在与源程序对应的工程根目录，但必须位于独立 Baseline 子树：

```text
<project_root>/baselines/<Baseline>/runs/<experiment_run_id>/<method_id>/
├── index.json
├── rounds/<nnn>/
│   ├── src/<package-path>/*.java
│   ├── classes/<package-path>/*.class
│   └── report/{classpath,compile,execution,result}.txt-or-json
└── final/
    ├── src/<package-path>/*.java
    ├── classes/<package-path>/*.class
    └── report/
```

`result.json` 包含实现身份、commit、DeepSeek provider/URL namespace/model、prompt 哈希、stage、round、缓存状态、API 尝试次数、Token、费用、耗时、编译状态、原程序状态和逐变异体结果。

生成测试的 `.java`、`.class` 与编译/JUnit 报告统一写入上述项目级隔离目录；通过
`experiment_run_id + method_id + round` 保证与源程序、Baseline 和修复轮次严格对应。
禁止写入项目真实的 `src`、`target`、`build`、已有测试目录或共享全局 classes 目录，
也禁止写入 `sources`、`adapters`、`MuTestLLM`。每轮必须新建独立输出目录，防止旧
`.class` 造成假成功。普通方法在原生生成/修复协议终止且原程序通过后才复制到
`final`，不是“首个通过就提前结束”。MuTAP/MutGen 等原文包含变异反馈的方法，每个
原程序通过的中间套件发布为不可变 `accepted/<round>` 快照供该轮 survivor 评估；只有
原生变异反馈循环按论文规则终止后的最终套件才复制到 `final`。编译器、执行器和
classpath 组织可只读参考 `MuTestLLM`，但公共实现必须重写到
`Baselines/adapters/common`；Baseline 特有反馈状态机只能放在对应 adapter。Baselines
run 必须保存双向映射 JSON，禁止形成无法追溯的工程内文件。

变异体评估必须与生成、编译修复、原程序反馈分阶段执行。只有状态为
`ORIGINAL_PASSED` 的不可变 `final` 或 `accepted/<round>` 测试快照才能进入评估。公共实现
`adapters/common/mutant_execution.py` 按 `LLMTestExecutor.java` 的核心语义执行：

1. 使用同一 JUnit worker 在原程序上独立执行一次，记录逐测试结果；
2. 完整读取 `source_records.jsonl`，按规范化 `file_path` 识别唯一物理变异制品；
   不得按重复的 `operator` 名去重，但完全相同的 `.class` 只执行一次，所有原始
   Excel 行仍分别保留并指向该次执行；
3. 每个变异体复制到独立 classpath overlay，并启动一个全新的 JVM，禁止在同一
   JVM 内替换类；
4. 变异比较阶段对原程序和变异体使用相同的 **逐测试方法 JUnit 行为超时**。每次仍启动
   一个全新 JVM，且不得为每个 `@Test` 重新启动 JVM。原程序验证必须完整执行测试类；
   变异体执行采用 fail-fast，第一个断言失败、异常、资源错误或已映射逐测试超时即可确认
   杀死并停止剩余测试，只有 `LIVE` 变异体必须穷尽完整测试类。进程创建、JVM 启动、
   classpath 查找和测试类的非初始化加载不计入行为超时。Java worker 在 JUnit 执行前写出
   `JUNIT_EXECUTION_STARTED`，并在每个测试开始/结束时原子发布 `TEST_STARTED` /
   `TEST_FINISHED`。公共执行器在每个 `TEST_STARTED` 上重置独立的 4 秒期限，因此 94 个
   测试是“每个最多 4 秒”，而不是“94 个合计 4 秒”。启动超时记为
   `ERROR_STARTUP_TIMEOUT`；无活动测试时的 JUnit 生命周期超时记为
   `ERROR_LIFECYCLE_TIMEOUT`，二者均不得算击杀。只有原程序对应测试在限制内通过，而变异体
   测试失败或该特定测试方法超过 4 秒，才分别记为 `KILLED` 或 `KILLED_TIMEOUT`；
5. 类缺失、依赖缺失、loader/linkage 错误、没有发现测试、worker 错误和缺失
   `.class` 均记为基础设施错误，不得误算击杀，也不进入任一 MS 分母；
6. 跨 Baseline 比较的主变异得分使用
   `End-to-End MS = KILLED / (KILLED + LIVE + NOT_EXECUTED_INVALID_TEST)`，按唯一物理
   制品计算。若生成失败、编译或论文修复上限失败、原程序执行失败/超时，导致该方法
   没有有效测试进入变异执行，则其全部唯一关联变异体记为
   `NOT_EXECUTED_INVALID_TEST` 并进入主分母，从而惩罚无法产出有效测试的 Baseline；
   这些记录不得伪装成 `LIVE`；
7. 同时保留诊断指标 `Executable MS = KILLED / (KILLED + LIVE)`，只说明真正进入变异
   执行的子集。基础设施错误与标签冲突单独报告，不生成等价排除得分。每条结果完整保留 Excel
   原始 12 字段、物理制品级 `mutant_uid`、记录级 `source_record_uid`、测试类、杀死
   测试、耗时及日志路径；`equivalent` 只作审计标签，不改变 End-to-End MS 或
   Executable MS 公式。

详细执行制品写入工程级方法目录的短路径
`mx/<yyyyMMddHHmmssSSS>/`（规避 Java 8/Windows `MAX_PATH`），Baselines target 下
保留 `mutant_execution/<id>/summary.json`、`latest.json` 和双向路径映射。该阶段只做
评估，ChatTester、A3Test、ASTER、HITS、IntUT 的击杀结果不得反馈给模型；仅
MuTAP/MutGen 按各自论文的后续存活变异体反馈阶段读取结果。

### 5.5 模型调用与源程序关联日志

每次逻辑模型调用都必须先生成唯一 `call_id`，并写入
`calls/<call_id>/call.json`。无论调用成功、失败、重试还是命中缓存，都要记录：

- `run_id`、`call_id`、`baseline`、`implementation_identity`；
- `stage`、`round`、`method_group_id`；
- 原程序 `source_path`、源文件 SHA-256、focal method 签名；
- prompt SHA-256、system/user prompt 文件路径；
- provider、API URL namespace、实际 model、temperature、max_tokens、输入预算及是否截断；
- `status`（`PREPARED/SENT/SUCCESS/ERROR/CACHE_HIT`）、attempts、耗时；
- 输入/输出/缓存 Token、finish reason、错误分类；
- request/response 文件路径；
- 严禁记录 API Key 或 Authorization header。

由于一次方法级调用可能关联多个变异体，统一追加
`call_mutant_trace.jsonl`：一行表示一个 `call_id × source_record_uid` 关联。同一次
调用关联 N 条 Excel 原始记录时写 N 行，N 行共享相同
`call_id/stage/round/prompt_hash`；指向相同物理 `.class` 的记录共享同一
`mutant_uid`，但不得丢弃重复来源行。每行都必须
在顶层完整包含以下 Excel 原字段：

```text
operator, line, method, class, class_f, mutation_statement,
package, project, file_path, original_graph_path,
mutant_graph_path, equivalent
```

同时记录 `source_path`、`method_group_id`、`mutant_uid`、`source_record_uid`、
`visible_to_model`。
`visible_to_model=false` 表示该变异体只用于调用关联和事后评估，没有进入 prompt；
MuTAP/MutGen 某轮确实读取存活变异体信息时，只有实际暴露的记录可设为 `true`。
禁止为了满足日志关联而把 `file_path`、变异语句或等价标签注入本来不使用变异
信息的 Baseline prompt。

调用级 `call.json` 用于成本和失败统计；逐变异体 `call_mutant_trace.jsonl` 用于从
任意调用回溯到 `mutant_total.xlsx` 原始记录。两者都必须由公共日志模块生成，
各 Baseline 不得自定义缺字段格式。

## 6. 公共实现结构

```java
interface BaselineRunner {
    GeneratedSuite generate(TargetContext target, BaselineRunConfig config);
}
```

Runner 只负责论文方法特有的上下文和阶段控制；API、代码提取、编译、执行、MuJava
评估与日志复用公共模块。所有 Baseline 的公共边界固定为：

```text
adapter/<Method> 生成或修复测试
        ↓
adapters/common/java_test_pipeline.py 编译并执行原程序
        ↓ ORIGINAL_PASSED + project_artifacts_final.json
adapters/common/mutant_execution.py 统一执行 MuJava 变异体
        ↓
mutant-results.jsonl + mutant-results.csv + summary.json
```

`mutant_execution.py` 是 A3Test、ASTER、ChatTester、HITS、IntUT、MuTAP、MutGen
七种 LLM Baseline 共用的唯一变异执行实现。EvoSuite、Randoop 暂按搜索式特殊控制组
单独冻结，等七种 LLM 主线稳定后再决定如何接入，不在本轮改造。各 LLM adapter 可以提供一个只负责参数和
默认路径的薄入口，例如 `ChatTester/run_mutant_execution.py`，但不得复制、分叉或
修改 KILLED/LIVE、超时、基础设施错误及分母算法。公共执行器不得导入任何 Baseline
私有 Prompt/模型模块，因此它不会改变各方法的生成语义。

当前公共 worker 接受单个测试类；ChatTester、MutGen、MuTAP 和 ASTER adapter 均将累计测试
规范化为一个 JUnit 4 类，HITS 将切片结果合并为一个 suite 类，因此已经可以完整接入。后续
IntUT 或其他方法若忠实协议必须生成多个测试类，才扩展为 `test_classes: []` 并保留旧
`test_class` 向后兼容；在扩展前不得宣称公共脚本支持多测试类套件。

以下结构以 2026-03-02 工作区中的实际目录为准。目录名区分大小写书写，适配器统一沿用已经创建的名称，不再创建一套小写重复目录：

```text
E:\PHD\MutantsTestLLM\Baselines\
├── ReferencePapers\
│   └── baseline_reproduction_implementation_plan.md   # 唯一方案文档
├── sources\
│   └── ChatTester\                                    # 当前已固定的上游源码
├── adapters\
│   ├── common\
│   │   ├── llm_gateway.py
│   │   ├── manifest_paths.py
│   │   ├── build_target_manifest.py
│   │   ├── java_test_pipeline.py
│   │   ├── project_artifacts.py
│   │   ├── mutant_execution.py
│   │   └── java-tools\
│   │       ├── pom.xml
│   │       └── src\main\java\baselines\common\tools\
│   │           ├── TargetContextCli.java
│   │           └── JUnitResultWorker.java
│   ├── ChatTester\
│   ├── EvoSuite\lib\
│   └── Randoop\lib\
├── configs\
├── data\
│   └── target_manifest.jsonl                           # Phase 0 生成
└── runs\                                               # 原始逐次实验产物及汇总
```

空占位目录不保留；某个 Baseline 开始实现时才创建对应 `sources/<Method>` 和
`adapters/<Method>`。目录职责和清理规范以 `Baselines/README.md` 为准。

实际路径映射如下：

| 方法 | 上游代码目录 | 本项目适配目录 |
|---|---|---|
| A3Test | `Baselines/sources/A3Test` | `Baselines/adapters/A3Test` |
| ASTER | `Baselines/sources/ASTER` | `Baselines/adapters/ASTER` |
| ChatTester | `Baselines/sources/ChatTester` | `Baselines/adapters/ChatTester` |
| HITS | `Baselines/sources/HITS/chatunitest-core` 和 `chatunitest-maven-plugin` | `Baselines/adapters/HITS` |
| IntUT | 无公开作者实现；不创建伪造源码快照 | `Baselines/adapters/IntUT` |
| MuTAP | `Baselines/sources/MuTAP` | `Baselines/adapters/MuTAP` |
| MutGen | `Baselines/sources/MutGen` | `Baselines/adapters/MutGen` |
| EvoSuite（可选控制组） | `Baselines/sources/EvoSuite` | `Baselines/adapters/EvoSuite` |
| Randoop（可选控制组） | `Baselines/sources/Randoop` | `Baselines/adapters/Randoop` |

传统工具版本已经唯一冻结：EvoSuite 1.0.6 和 Randoop 4.3.4。重复版本已删除，
具体 SHA-256 和目录约束见各自 `adapters/<Method>/README.md`。

`sources` 只放固定版本的上游仓库/制品，用于追溯和对照；不在其中改代码、安装
依赖或原地编译。本地修改、依赖声明、构建目录和可执行入口全部放到相应
`adapters/<Method>`，公共实现放到 `adapters/common`。`runs` 只保存实验产物，不得
作为未声明的源码、答案或提示输入；公共执行器可以读取当前 run 的显式映射和制品，
MuTAP/MutGen 也可在论文规定的反馈节点读取已登记的上一轮 survivor 结果，但每次读取
都必须在 stage 日志中声明来源，其他方法不得读取变异结果。

所有方法接入公共执行器时必须满足同一输入契约：

| 输入 | 强制内容 |
|---|---|
| `metadata.json` | Baseline、项目根目录/构建根目录、目标类、方法签名和源码路径 |
| `state.json` | 对所选 `final` 或 `accepted/<round>` 快照必须为 `ORIGINAL_PASSED`；其他状态拒绝执行 |
| `source_records.jsonl` | 该方法关联的全部 Excel 原始记录及冻结 12 字段 |
| `project_artifacts_final.json` 或轮次快照映射 | 源码/classes、`test_classes`、已验证 classpath 与原程序执行结果 |

输出契约同样固定：每个物理变异体有稳定 `mutant_uid`，每条 Excel 记录有独立
`source_record_uid`；项目级目录保存原程序和逐变异 JVM 的完整命令、classpath、
JUnit 逐测试 JSON 与日志，Baseline run 保存双向指针和汇总。任一方法若绕过该契约，
其 MS 不得进入主结果表。

所有后续 LLM Baseline 完成原程序验证后，均使用同一个通用入口：

```powershell
python -B adapters/common/mutant_execution.py `
  --run-dir runs/<experiment_run_id>/targets/<target_or_method_id>
```

方法私有入口只能传递 run 目录、终态 final 或轮次 accepted 快照，不得复制执行器
代码。当前 CLI 在实现轮次快照参数前只允许终态 `final`；MuTAP/MutGen 正式接入前必须
增加显式 `--artifact-result <snapshot.json>`（或等价参数）。每轮使用新的 execution ID，
并把该轮所见 survivor 集合、允许反馈给模型的记录及新增击杀单独落盘；反馈视图必须
移除 `equivalent` 等原方法不应看到的审计标签。

## 7. 七种 Baseline 的生成方案

本节中除 ChatTester 已完成固定作者代码的逐行协议核对外，其余方法的阶段描述目前只
作为复现路线，不自动构成可执行配置。表中标为 `UNRESOLVED` 的调用条件和轮次必须在
实现该方法前从论文/作者代码补证；补证前禁止用统一的 1、2 次等经验值运行正式实验。

### 7.1 A3Test-DS-Adapted

作者仓库固定到 commit `14fa2c4a5bf44bf51201f293e8dd599033652027`。论文与作者代码表明，
A3Test 的断言增强发生在 PLBART 的预训练权重中，而不是推理时先生成骨架再调用第二个模型补断言。
因此正式 DeepSeek 适配不得实现旧版计划中的 `skeleton → assertion → repair` 三阶段链。

1. 上下文严格采用论文的 `fm+fc+c+m+f`：focal method、focal class、构造器签名、其他
   public 方法签名和 public 字段；
2. `a3test_generation` 每个去重 focal method 固定调用 1 次，生成单个概率意义上的最佳完整
   JUnit 4 测试，不生成多个候选或多个测试方法；
3. 断言知识由统一 DeepSeek 的 assertion-augmented 指令表达，这是替换原 PLBART assertion
   pre-training 的必要适配，因此实现名称固定为 `A3Test-DS-Adapted`；
4. 生成后按作者 `postProcessingScript.py` 做确定性验证：补 `@Test`、`public`、`void`、
   `test` 前缀，并按作者规则处理可恢复的不平衡结束括号；该步骤不调用模型、不计 repair；
5. 论文和作者推理代码没有编译或执行反馈修复。测试只编译/执行一次；失败直接进入
   `NOT_EXECUTED_INVALID_TEST`，禁止添加 `a3test_repair`；
6. 原程序通过后收集 focal/line/branch coverage 并执行全部关联物理变异体。覆盖和变异结果
   不反馈给生成模型；
7. 主实验遵循论文 one iteration。论文的 6 iterations 比较只能作为独立敏感性实验，使用
   新 run ID，不能混入单次生成主结果。

| Stage | 输出 | 调用条件与上限 |
|---|---|---|
| `a3test_generation` | 单个完整、带断言的 JUnit 4 测试类 | 每个 focal method 固定 1 次 |
| deterministic verification | 签名/名称/结束括号校正后的测试 | 固定 1 次本地规则；0 次 LLM |

所有生成调用使用 DeepSeek-V4-Flash、temperature=0、output-token 上限 16000。原始输出和校正后
测试必须同时保留，`verification.json` 记录具体规则变更、断言数及 vacuous assertion 诊断；
不得擅自删除 `assertTrue(true)` 等输出，因为作者后处理并没有该过滤规则。编译/原程序失败不得
触发新的 LLM 调用。原仓库 checkpoint 仅用于来源核对，主对比不使用 PLBART 权重。

### 7.2 ChatTester-DS-Adapted

1. 从 FudanSELab 官方仓库提取 Java 分析、初始 prompt、反馈和覆盖流程；
2. 用统一 gateway 替换原模型接口；
3. `chattester_intention` 先推断 focal method 的自然语言意图；
4. `chattester_initial` 生成完整 JUnit 4；
5. 立即在隔离工作区编译测试；仅在编译失败时把真实编译错误和相关代码上下文反馈给模型；
6. 编译成功后立即停止 ChatTester 的 LLM 修复，再在原程序上执行。执行失败只计入
   execution correctness，不反馈给模型，也不进入变异评估；
7. 原程序通过的最终套件执行全部相关变异体；JaCoCo 另行计算论文的 statement
   coverage 与 branch coverage，覆盖率不触发模型调用。

| Stage | 论文优先正式协议中的调用条件 | 单次输出 |
|---|---|---|
| `chattester_intention` | 初始生成固定 1 次 | 单句方法意图 |
| `chattester_initial` | 初始生成固定 1 次 | 测试方法/JUnit 4 源码 |
| `chattester_compile_repair` | 仅初始或上一轮编译失败时，每轮 1 次 | 修复后的测试方法/JUnit 4 源码 |
| `chattester_runtime_repair` | 正式论文优先实验禁止调用；仅作为作者制品差异记录 | 不适用 |

论文正文与作者固定 commit 存在冲突，必须分别记录：

- `InitialPhrase_Experiment.py`：模型为 `gpt-3.5-turbo`，意图与初始生成均
  `temperature=0`，顺序是“意图调用 → 初始测试调用 → `mvn test-compile` →
  `mvn test`”；
- 论文第 5.2 节明确规定 iterative refiner 只修复编译错误；编译成功即停止修复，
  不修复 execution error。编译错误数未下降属于 invalid refinement；实验参数为 3，
  累计数超过该值后终止；
- `ChatGptTester.py` 制品额外实现了 Surefire `testRepair`、重新意图调用，以及
  `iter > 3 or IterTest > 5 or TotalIter > 30`。这些仅属于 `AUTHOR_CODE` artifact
  mode，不能覆盖论文明确规则，也不能混入正式结果；
- 正式协议的单方法调用数仍是动态的：固定 1 次 intention、1 次 initial，再加实际
  发生的 compile-repair 调用；不得写死为总计 2 次，但不得加入 runtime-repair 调用。

因此，“前 10 个方法预计 20 次调用”只覆盖未出现编译失败时的固定初始调用，不是
正式实验的调用上限。正式运行必须执行真实编译分支并报告每个方法的实际调用数；
如另做 Equal-budget 截断实验，必须使用独立 run 和明确标签。

JaCoCo 是迭代完成后的 statement/branch coverage 计算，不应误写成
`coverage_refine` 模型阶段。分别报告初始结果和编译修复后结果。第三方 ChatTester
版本和 artifact-mode `testRepair` 结果不能混入论文优先的官方适配结果。

**当前实施状态：** 官方 `main` 已固定到 commit
`af8ea1c643a401317c54ee7936d829851d512109`。由于上游实验文件名包含
`?`、`<`、`>` 等 Windows 非法字符，源码采用
`Baselines/sources/ChatTester/repository.git` bare 仓库加白名单源码导出的方式保存。
上游 `Java_Analyzer` 曾完成一次兼容性构建验证；其原地 `target` 已删除，今后不在
`sources` 编译。实际上下文提取由 `adapters/common/java-tools` 独立构建。详细来源与适配约束见
`Baselines/adapters/ChatTester/SOURCE.md` 和 `ADAPTATION_PLAN.md`。共享 Java 网关
已替换为 Baselines 公共 `llm_gateway.py`；Python 文件桥 `deepseek_gateway.py` 和
LLM 阶段适配器 `llm.py` 已实现。公共 `java-tools/TargetContextCli` 仅在
Baselines 内构建；离线隔离自检及三个代码提取/外壳单元测试已通过，
且自检不读取 Key、不发送 API 请求。公共 `build_target_manifest.py` 与
`TargetContextCli` 已实现；当前 smoke 清单固定为 `commons-csv-1.2` 的
`Token.reset()`（3 个变异体），源码路径全部解析成功。`prepare_run.py` 已完成
真实目标的上下文和 intention prompt 生成。已使用 Baselines 自有配置
完成首次真实 smoke：`chattester_intention` 与 `chattester_initial` 均一次成功，使用
模型标识 `deepseek-v4-flash`，总计 445 prompt tokens、1662 completion tokens，生成
`Token_ChatTesterTest.java`，状态为 `GENERATED_NOT_COMPILED`。两次调用与 3 个变异体
形成 6 条 trace，所有记录均包含原 Excel 的 12 个字段，运行产物未检出 API Key。
又完成前 10 个去重方法的初始生成管线 smoke，最终 10 个方法均产生
Java 测试并关联 1151 条变异体记录；但该批次使用过温度 0.2，且只执行意图与初始
生成，没有执行作者规定的编译、原程序测试和迭代反馈，所以只能用于验证 API、路径、
日志和批处理，**不能计入 ChatTester 忠实复现实验结果**。现已为 ChatTester 的正式
intention、initial、compile-repair stage 配置作者代码明确给出的 `temperature=0`。现已在
`adapters/common/java_test_pipeline.py` 实现独立编译与 JUnit 原程序执行，在
`adapters/ChatTester/run_feedback_pipeline.py` 实现论文优先的仅编译反馈、原程序执行
准入和 invalid-refinement 停止条件，并提供 `run_feedback_batch.py` 批量入口。历史真实
Ant smoke 中，初始测试因包名错误
编译失败，经过 1 次 `chattester_compile_repair` 后成功编译并通过 1 个 JUnit 测试；共
3 次模型调用、2323 tokens。产物位于 Ant 工程的
`baselines/ChatTester/runs/.../Date_b940eb6faec4135b`，Baselines run 保留映射。
该 smoke 的初始生成来自旧温度 0.2 批次，且历史执行曾受 JUnit 3/4 混合 classpath
遮蔽，因此只证明闭环实现可工作，不能计入正式结果；公共 classpath 已修复为存在
JUnit 4 时排除 JUnit 3。已完成公共 MuJava 独立执行器与逐测试 JUnit
worker。该 Ant smoke 的 17 条映射记录实际对应 5 个唯一 `.class` 制品（其中 AOIS_3
有 2 条冲突标签、LOI_1 有 12 条重复关联），不能把重复行当作独立变异体。原程序
1 个测试通过，5/5 个唯一制品均在独立 JVM 中完成，无基础设施错误；raw 结果为
3 KILLED、2 LIVE；由于该 smoke 的全部目标均有效，其端到端与可执行诊断得分相同，
均为 `3 / (3 + 2) = 60%`。现有等价标签包含 1 个冲突，仅作为数据审计
记录，不再计算第二套等价排除得分。17 条原始记录全部保留关联。击杀
样例由同一断言从期望 `1234567890123` 变为实际 `1234567890124` 触发，证明加载的是
变异类而非原类。该 smoke 的生成阶段仍使用过旧温度 0.2，因此只能验收完整技术流水线，
不能作为正式论文比较数据；正式结果必须用冻结的温度 0 配置和新 run ID 重跑。

已按论文优先协议完成 5 个 Ant 方法的正式配置试跑，run ID 为
`20260318132916679_chattester_formal5_001`：5/5 完成意图与初始生成；4/5 编译成功，
其中 2/5 在原程序上通过，2/5 因断言失败被标记 `ORIGINAL_FAILED`，另 1/5 在 4 次
invalid compile refinement 后达到停止条件。本次共 14 次 DeepSeek-V4-Flash API 调用，
输入 24453 tokens、输出 7741 tokens、总计 32194 tokens，缓存读取 7552 tokens，按统一
公式计算缓存命中率为 30.88%；未发生 `chattester_runtime_repair`。两个
`ORIGINAL_PASSED` 方法的 389 条源记录归并为 354 个唯一物理变异体，均在独立 JVM 中
完成且无执行错误：279 KILLED（其中 6 个 `KILLED_TIMEOUT`）、75 LIVE；另外三个
无效测试目标的 455 条关联记录归并为 383 个唯一物理变异体，记为
`NOT_EXECUTED_INVALID_TEST`。因此主得分为
`End-to-End MS = 279 / (279 + 75 + 383) = 37.86%`；诊断用
`Executable MS = 279 / (279 + 75) = 78.81%`。这是小样本流水线与协议试跑，不替代后续
全量实验；JaCoCo statement/branch coverage 公共采集仍待实现。

### 7.3 HITS-ChatUniTest-DS

原作者匿名 Artifact `SlicePromptTest4J-6CF1` 已失效，当前采用 ChatUniTest 的公开第三方 HITS 实现作为可核查代码证据。固定快照为：

- `chatunitest-core` commit `513056beadebee0571c0615e3b13bc84bce65179`；
- `chatunitest-maven-plugin` commit `38f152ce9186bc42f2b08b73a07dce8af54b1378`。
- `ChatUniTest` 总入口 commit `06d164aed21ec4cea29b8ac8661de395bdf50c41`（保留其子模块指针；运行证据使用上述两个独立完整快照）。

正式协议按“论文优先、公开实现补充、未知参数不猜测”执行：

1. 静态上下文包含 focal method、类字段/构造器/方法声明、同类被调用方法体及可解析的显式项目依赖；解析失败的依赖单独记录，禁止虚构；
2. `hits_slicing` 使用 CoT 生成带 summary、外部变量/方法和 `steps[{desp, code}]` 的 method-slice JSON；
3. `hits_slice_test` 为每个 slice 各生成一个完整测试类；
4. 每个切片测试立即编译并在原程序上执行；编译或运行失败时，将真实编译/JVM/JUnit 反馈交给 `hits_test_repair`；
5. 论文明确每个切片最多 10 轮修复，优先于 ChatUniTest 默认的 5 轮；通过后立即停止该切片修复；
6. 只合并在原程序上通过的切片测试，并对合并套件再次编译/执行；规则后处理只做确定性语法、import 和框架归一化；
7. 合并套件通过后才执行该 focal method 对应的全部物理 MuJava 变异体；生成和修复 prompt 均不可见变异信息；
8. 论文/公开提示使用 JUnit 5，统一评估使用 JUnit 4，因此输出后做确定性 JUnit 4 适配并在元数据中明确记录；
9. 论文说明切片格式失败时逐步提高 top-p，但未给精确数值序列；当前按固定 ChatUniTest 行为最多额外重试 3 次且保持 temperature=0，不虚构 top-p 调度。

| Stage | 输出 | 调用条件与上限 |
|---|---|---|
| `hits_slicing` | slice JSON | 初始固定 1 次；JSON/schema 失败时最多额外 3 次 |
| `hits_slice_test` | 单个 slice 的完整测试类 | 每个有效 slice 固定 1 次 |
| `hits_test_repair` | 修复后的完整 slice 测试类 | 仅编译/原程序执行失败时；每 slice 最多 10 轮 |

所有 HITS LLM 阶段统一使用 DeepSeek-V4-Flash、temperature=0、output-token 上限 16000。必须保留 `slices/slice.json`、逐片 `attempt_*.java/result.json`、合并套件、调用与 Token 日志、原程序结果、覆盖报告和逐变异体结果。该实现只能称为 `HITS-ChatUniTest-DS`，不能称为原作者官方代码复跑。

### 7.4 ASTER-Java-DS-Reimplementation

官方 Artifact 已固定在 `Baselines/sources/ASTER`，commit 为
`a6d8ab7b6ee43926821f490cb9e1844f35e7e88d`。仓库包含论文图、生成测试、naturalness
分析器和修改后的 CodaMOSA，但**没有完整 ASTER Java 生成引擎**；因此只作只读证据，Java
主流程位于 `Baselines/adapters/ASTER`，实现名称必须保持
`ASTER-Java-DS-Reimplementation`。

论文与 `figures/prompts.png` 确认的协议为：

1. 预处理以 focal method 为中心提取 focal class、相关构造器；构造器范围包括接收者以及
   application 类型形式参数的构造器，并对构造器参数传递搜索；
2. 提取 focal/参数类型 setters 供状态设置，提取 focal/返回类型 getters 供断言，并提供从
   非 private 方法到 private 方法的调用链；
3. mocking 仅针对用户指定的第三方 API 类型，构造包含 mock 字段、构造/static/API stubs 的
   部分测试骨架；未配置 API 类型时禁止自行猜测需要 mock 的类型；
4. `aster_initial` 将上述上下文和测试骨架交给 LLM，要求补齐输入、调用序列和断言，固定 1 次；
5. 确定性 sanitization 仅抽取 Java、统一包/类名和 JUnit 4 评估接口；随后编译并在原程序执行；
6. 编译失败或 assertion/runtime error 时，`aster_repair` 将当前完整测试、真实错误位置和错误
   信息回传，成功即停止；
7. 只有通过原程序的测试才执行 JaCoCo。`aster_coverage` 直接接收当前通过测试和未覆盖 focal
   method 行并返回完整增强测试；论文图中不存在独立的 `coverage_plan` JSON 阶段；
8. 覆盖候选仍须经过相同编译/原程序修复准入。失败候选不得替换上一个通过版本；最终通过套件
   才进入公共逐变异体隔离执行，任何 ASTER prompt 均不可见变异信息。

| Stage | 输出 | 调用条件 |
|---|---|---|
| `aster_initial` | 完整测试类 | 每个去重 focal method 固定 1 次 |
| `aster_repair` | 修复后的完整测试类 | initial 或 coverage 候选编译/原程序失败时 |
| `aster_coverage` | 保留旧测试并加入覆盖测试的完整类 | 存在可测未覆盖 focal 行时 |

论文说明 repair/coverage 均为迭代流程，但没有公开修复上限、coverage 数值目标、temperature 或
完整代码。Artifact 对每个类保留一组 `ASTER_*_Test_{Passing,Failing}` 与一组
`ASTER_*_Coverage_Test_{Passing,Failing}`，只能支持“至少有一次独立 coverage 阶段”，不能反推
原始修复次数。当前 `aster.properties` 因此把最多 3 次 repair、1 次 coverage 和 100% 可测
focal-line 目标明确标记为**可配置重实现安全边界**，每个 run 写入 `metadata.json`，不得表述为
论文原生参数。主对比三个 stage 全部使用 DeepSeek-V4-Flash、temperature=0、max_tokens=16000。

共享实验按 focal method 选择目标，而公开 Artifact 的 Java 文件多按 focal class 聚合；这是统一
比较协议适配，必须单独披露。Artifact 中同时存在 JUnit 4/5 产物，统一执行器采用确定性 JUnit 4
适配。继续报告 initial、+repair、+coverage 三档过程数据。不得把 CodaMOSA 当作 ASTER 核心代码。

### 7.5 IntUT-DS-Reimplementation

IntUT 论文为 ICSE 2025 的 *Test Intention Guided LLM-based Unit Test Generation*。
截至 2026-03-15，未找到可验证的作者公开实现、IntelliJ 插件、PAINT CFG/SSM-DFS
代码、CodeBERT mock 分类器权重或实验包。因此不得创建虚假的官方源码快照，正式名称固定为
`IntUT-DS-Reimplementation`，实现全部位于 `Baselines/adapters/IntUT`。

论文确认的原生协议为：

1. PAINT 对 focal method 构建 CFG，处理 if、switch、exception、hybrid block；
2. SSM-DFS 遍历并生成到各分支的 condition chain，递归处理异常子图和未 mock 的函数调用；
3. 平行条件组合按论文启发式剪枝，嵌套条件保留；循环回边删除；
4. 每条 condition chain 填入一个 test intention，固定包含 input parameters、mock behavior、
   optional hint specification 和 expected result；
5. 项目导入 IDE 时动态构建 RAG：unit-test example 用 Jaccard 相似度阈值 0.8，mock
   example 按被调用方法检索；
6. Figure 6 的最终 Prompt 顺序固定为完整程序上下文、RAG 示例、测试类前缀/已有测试、
   单条 test intention；
7. LLM 生成多个候选并交给开发者选择；论文消融实验明确每个请求生成 5 个候选；
8. 论文没有自动 repair 或 coverage-feedback 循环。Section VII-E 明确把 recovery mechanism
   留作 future work，因此旧方案中的 `intut_repair` 必须删除，编译/运行失败不得反馈给模型。

本地重实现冻结如下：

| 论文组件 | 本地实现 | 证据/偏差 |
|---|---|---|
| PAINT/SSM-DFS | `analysis.py` | 无公开引擎；以词法分支嵌套重实现，保留嵌套链、剪枝平行组合并显式记录非字节码等价限制 |
| CodeBERT mock classifier | `rag.py` + `analysis.py` | 无权重/训练集；只使用目标项目历史 mock 证据，不让 LLM 猜分类结果 |
| Project RAG | `rag.py` | 从测试调用与同名生产方法重建历史 focal-test pair，仅扫描 `build_root`，排除 `result`、`baselines`、构建目录；当前/历史 focal 的 Jaccard 阈值 0.8 |
| Test intention | `paint_intentions.json` | 每个分支意图保存 inputs/mock/hint/expected result 和 condition chain |
| Test generation | `intut_generation` | 唯一 LLM stage；DeepSeek-V4-Flash，max_tokens=16000 |
| 五候选 | 每意图 5 次独立 DeepSeek 调用 | 公共 OpenAI-compatible gateway 无可移植 `n=5` 契约；这是 API 必要适配 |
| 开发者选择 | 首个在原程序上编译并通过的候选 | 自动实验的确定性代理；只看原程序结果，不看覆盖或变异体结果 |
| Repair/coverage feedback | 无 | 忠实于论文；失败候选不再调用模型 |

论文未披露多候选采样 temperature，主实验显式采用 DeepSeek `temperature=0.2` 以保留候选
差异，不冒充原生参数。论文公开最大执行函数数、最大调用递归层数这两个控制项但未给实验值；
重实现安全边界集中在 `intut.properties` 并逐 run 落盘。所有 IntUT Prompt 禁止获得变异行、
变异语句、RIP 证据、等价标签、存活/击杀结果；同一 focal method 的最终通过套件再交给公共
`mutant_execution.py` 执行其全部唯一物理变异体。

### 7.6 MuTAP-Java-DS-Adapted

官方仓库已固定到 commit `57c38c0c13df2c4d2b5714491e2c67a6d0463928`，只读保存于
`Baselines/sources/MuTAP`。主实现采用论文优先协议，而不是直接运行作者的 Python/HumanEval
脚本：

1. `mutap_initial` 使用 zero-shot Prompt 生成完整 JUnit 4；
2. `mutap_syntax_repair` 只在真实 `javac` 失败时调用，论文优先上限为 10 次；
3. IntendedBehaviorFixer 在原程序上校验断言。Python 制品可直接求值并替换 expected；通用
   Java 无安全的表达式求值器，因此当前 `JAVA_REQUIRED_ADAPTATION` 逐测试方法保留原程序
   通过项、剔除失败项，不把运行反馈发送给 LLM；
4. 公共 `mutant_execution.py` 对该方法的全部唯一物理 MuJava 变异体执行中间套件；
5. 从当前 LIVE 且未使用集合中按固定 seed 的随机顺序选择一个幸存变异体，只把该变异体
   完整源码提供给 `mutap_survivor_augmentation`；不提供 `equivalent`、图路径或最终答案；
6. 新测试通过原程序后累计，并立即对同一变异体集合重新执行；
7. 全部变异体被击杀，或当前已无尚未用于 Prompt 的 LIVE 时停止。没有自创固定轮数上限，
   每个物理幸存变异体最多反馈一次；
8. 按单个测试的击杀数降序执行 greedy oracle minimization：只有能扩大累计击杀集合的测试
   才保留；最终套件重新通过原程序后发布 `final` 并统一计分。

| Stage | 输出 | 原生调用规则状态 |
|---|---|---|
| `mutap_initial` | 完整 JUnit 4 | `PAPER + AUTHOR_CODE + JAVA_ADAPTATION`；一次初始调用 |
| `mutap_syntax_repair` | 完整 JUnit 4 | `PAPER`；仅编译失败触发，最多 10 次 |
| IntendedBehaviorFixer | 原程序有效测试 | `JAVA_REQUIRED_ADAPTATION`；确定性处理、无 LLM 调用 |
| `mutap_survivor_augmentation` | 补充 JUnit 4 方法 | `PAPER`；每个尚未使用的当前 LIVE 最多一次 |
| Oracle minimization | 最小化 JUnit 4 | `PAPER`；确定性处理、无 LLM 调用 |

论文算法与固定 commit 的 Codex 脚本存在差异：论文要求单个 survivor 后重新计算；作者
`augmented_prompt.py` 实际遍历初始 survivor 列表后由 `Merge_all_mut.py` 统一合并。主实现按
论文算法；artifact-mode 可另做敏感性实验，禁止混入主结果。README 的“最多 10 次尝试”与
代码 `rep=3` 也同时保留证据，主实验按论文优先使用 10。temperature 保持 Codex 路径的
0.8；所有三个 LLM stage 的主对比输出上限统一为 16000。报告初始 MS、每轮 survivor、
每轮增量、最小化前后规模和最终 MS。

### 7.7 MutGen-DS-Adapted

作者复现包已固定到 commit `69dd37ddfab16b8c5efd22117dc3d213ab05418d`。论文优先
协议已经核对：移除源代码注释并生成 1--3 句用途/输入格式摘要；将摘要、无注释源代码，
以及变异体编号、变异语句、状态和算子组成 mutation-feedback prompt；生成 JUnit 4 后，
逐个过滤编译或断言失败的测试，并对每个失败测试最多进行一次 LLM 修复；把通过的新增测试
并入累计套件，再重新执行变异测试。正式实验固定最多四轮，因为论文预实验观察到第四轮后
趋于收敛；若全部可执行变异体已被击杀可提前停止。不得自行采用其他轮数或按统一调用预算
截断 Native-budget 结果。

1. `mutgen_summary`：对移除注释前的原始 focal-method 上下文生成摘要，随后提供无注释源码；
2. `mutgen_generation`：首轮把尚无测试覆盖证据的目标变异体标为 `NO_COVERAGE`，后续轮次
   只反馈当前 `LIVE`/`NO_COVERAGE` 变异体；反馈包含论文规定的 id、mutated statement、
   status、operator，不包含 `equivalent` 或 MutantsTestLLM 的 RIP/答案；
3. 解析为独立测试方法并逐个编译、在原程序执行；
4. `mutgen_compile_repair` 或 `mutgen_assert_repair`：每个失败测试至多调用一次，修复后再次
   过滤；不得把失败测试直接并入累计套件；作者代码识别到
   `method ... is already defined` 时先机械添加 `dedul_` 前缀，该分支不调用 LLM，且同样
   只允许一次修复后验证；
5. 将 PIT 报告接口替换为公共 MuJava 轮次快照接口，但保持反馈语义；每轮必须使用新的
   execution ID，不能覆盖最终盲评结果；
6. 合并本轮通过测试与历史累计套件，保留 test-to-mutant-feedback 映射并重新评估；
7. 最多执行四轮，记录每轮 survivor、增量测试、增量击杀和累计得分。

| Stage | 输出 | 原生调用规则状态 |
|---|---|---|
| `mutgen_summary` | 1--3 句用途与输入格式摘要 | `VERIFIED_PAPER`：预处理 1 次并供各轮复用 |
| `mutgen_generation` | YAML 中的新增 JUnit 4 测试方法 | `VERIFIED_PAPER`：每轮 1 次，最多 4 轮 |
| `mutgen_compile_repair` | 单个修复测试方法 | `VERIFIED_PAPER`：每个编译失败测试至多 1 次 |
| `mutgen_assert_repair` | 单个修复测试方法 | `VERIFIED_PAPER`：每个断言失败测试至多 1 次 |

作者制品和论文存在需要显式保留的差异：`runeach.sh` 依赖已有 `_ESTest.java`，并且当前
commit 中调用 `noplan_parsePITestReport.py` 注入 mutation report 的代码被注释；脚本还会
原地改写安装环境中的 prompt 模板和目标源文件。因此不能直接在 `sources` 中运行，也不能
把当前 shell 脚本称为无需适配即可执行的完整论文实现。正式主结果按论文协议在
`adapters/MutGen` 重建，以空 JUnit 外壳从头生成；作者脚本仅作为 prompt、一次修复和四轮
配置的证据。PIT→MuJava 属于 `EVALUATION_COMMON` 后端替换，不改变允许反馈给模型的信息。

**当前实施状态（2026-03-18）：** 已实现独立准备、YAML 响应解析、累计 JUnit 4 套件、逐测试
编译/原程序验证、作者机械重名修复、编译/断言单次 LLM 修复、四轮 MuJava 快照反馈、最终
发布、逐变异体执行、Token/缓存/12 字段追踪，以及方法级和变异级并发入口。Native MutGen
调用强制关闭本地 response cache，保证四轮均真实重新调用模型；provider 返回的 Cache Read/
Write 仍正常统计。公共变异执行器新增显式 `artifact-result`，中间轮次不覆盖最终
`latest.json`，ChatTester 默认路径和行为保持不变。

真实单目标 `20260322_mutgen_smoke_002` 在 Ant `JUnitTest.setThread(int)` 上完成四轮：共 1 次
摘要和 4 次 generation API 调用，全部 temperature 0、无本地缓存命中；9 次重复方法名按作者
代码机械修复，没有产生 LLM repair 调用。最终累计 25 个原程序通过的测试方法，7 个唯一
物理变异体中 5 KILLED、2 LIVE，四轮得分均为 71.43%，无变异执行基础设施错误；总计
4311 input tokens、3425 output tokens，其中 provider Cache Read 为 2304 tokens。该结果只用于
全流程 smoke，不替代多方法 pilot。早期 `_001` 因错误地对重复方法名调用 LLM 且发生本地响应
缓存复用，已明确作废，不得进入实验结果。

进入 Pilot 前仍有一项忠实性缺口：当前公共 MuJava 执行结果只能区分 KILLED/LIVE，不能确认
存活变异行是否从未覆盖。因此 `_002` 对执行后的全部 survivor 保守标为 LIVE，不伪造
NO_COVERAGE。必须补充公共 JaCoCo 行覆盖采集，把未覆盖变异行映射为 NO_COVERAGE 后，才能
宣称完整复现论文的两类反馈；现有 prompt、trace 和轮次 schema 已支持这两种状态。

## 8. 传统测试生成控制组

本节已经从历史草案升级为可执行控制组规范。EvoSuite/Randoop 不调用 DeepSeek，也不得
增加 LLM 修复；它们不改变第 1–7 节的 LLM 规则。两者通过
两套方法分别由 `adapters/EvoSuite/pipeline.py` 和
`adapters/Randoop/pipeline.py` 实现各自的命令、生成、筛选、清理审计与批处理逻辑；
`common` 只保留多源 Java 编译、原程序验证、方法级变异体展开及统一汇总。工具生成均按
`project + class` 去重；输入选择与主 Baseline
一致，仍是前 N 个唯一 `project + class + method_signature` 及这些方法关联的全部物理变异体。
公共 `mutation_batch_results.jsonl` 与底层 `mutant-results.jsonl` 均必须逐条保留
`operator、line、method、class、class_f、mutation_statement、package、project、file_path、
original_graph_path、mutant_graph_path、equivalent` 这 12 个原始审计字段。

EvoSuite 和 Randoop 不调用 DeepSeek-V4-Flash，也不属于七种 LLM Baseline；它们用于回答“LLM 方法相对传统自动测试生成是否有增益”。二者使用与 LLM 测试相同的原程序验证器和 MuJava 变异执行器，但公平预算按生成墙钟时间衡量，不能用 LLM Token 预算衡量。

### 8.1 EvoSuite：迁移现有思路到 Baselines 独立工具

#### 8.1.1 现有实现

以下代码只作为只读参考，不直接编译、不修改，也不作为 Baseline classpath：

```text
MuTestLLM/src/main/java/mujava/testgenerator/
├── EvoSuiteTestGenerator.java
├── EvoSuiteCleaner.java
└── EvoSuiteMutantKillDetailCollector.java
```

独立入口位于 `Baselines/adapters/EvoSuite/run_batch.py`，EvoSuite 专属实现位于
`Baselines/adapters/EvoSuite/pipeline.py`。已经保留的能力包括：

- 从公共 target manifest 定位项目、目标类、目标方法和项目根目录；
- 通过 `build_project_classpath` 构建项目 classpath；
- 按目标类去重后调用 EvoSuite `-generateSuite`；
- 默认 `MaxTime` 停止条件、60 秒搜索预算和 `max_size=20`；
- 将原始测试和 report 写到批次的 `class_generations/`；
- 批量编译清理后的测试；
- 在原程序和 MuJava 变异体上执行 JUnit 4；
- 复用唯一公共 `mutant_execution.py` 和 `summarize_mutation_batch.py`，不再维护第二套
  可能与 LLM Baseline 判定不一致的 Collector。

迁移版本必须参数化以下内容：

- `EvoSuiteTestGenerator.main` 中的输入 Excel 路径；
- `evosuiteJarPath=./lib/evosuite-master-1.0.6.jar`；
- 搜索预算 60 秒和 `max_size=20`；
- `EvoSuiteCleaner.main` 中的项目名和 `Programs` 相对路径；
- Collector 中的默认 ProgramsRoot、Excel 和输出 CSV 绝对路径；
- 单模块/多模块项目的路径分支。

这些值已经进入 `Baselines/adapters/EvoSuite/evosuite.properties`，随机种子和并发数也可
由 CLI 覆盖。所有适配代码、依赖和构建产物全部位于 Baselines；不得回写 MuTestLLM。

#### 8.1.2 清理规则和审计要求

`EvoSuiteCleaner` 目前会：

- 将 `*_ESTest` 重命名为 `*Test`；
- 删除 `_ESTest_scaffolding` 继承；
- 删除 `EvoRunner`、`EvoRunnerParameters` 和 `org.evosuite.*` import；
- 处理 `verifyException`、未声明异常、Executor/Future 等生成结构；
- 从 EvoSuite runtime/mock 污染语句开始，递归删除相关依赖链。

上述清理能让测试脱离 EvoSuite runtime，但递归删除可能同时改变测试行为。为避免以
清理器能力替代生成器能力，当前正式协议保留 EvoSuite runtime 与 scaffolding；
`raw_tests` 必须是生成器输出的字节级快照。`clean_tests` 只允许两类可审计的执行框架
规范化：JUnit 5 到统一 JUnit 4 的确定性兼容改写，以及把已有 `@Test(timeout=...)`
统一为公共逐测试方法上限 4000 ms。不得删除测试方法、调用链、输入或断言，并保存：

```text
Baselines/runs/<run_id>/EvoSuite/<target>/raw_tests/
Baselines/runs/<run_id>/EvoSuite/<target>/clean_tests/
Baselines/runs/<run_id>/EvoSuite/<target>/cleaning.diff
```

`cleaning_audit.json` 必须记录每个文件的输入/输出哈希、逐项 transformation、
`test_timeout_annotation_change_count` 和 `configured_test_timeout_millis`。如果未来启用旧式
语义清理，必须另立实验配置，同时报告 raw 与 cleaned 两组数据；任何测试消失或断言减少都要计入
`CLEANING_SEMANTIC_CHANGE`，不得静默替代当前正式协议。

#### 8.1.3 运行流水线

```text
target_manifest / mutant Excel
        ↓
EvoSuiteTestGenerator
        ↓
raw *_ESTest.java + scaffolding + report
        ↓
审计化 JUnit/逐测试超时规范化与 cleaning audit
        ↓
clean JUnit 4 source + cleaning.diff
        ↓
公共编译器 + 原程序执行器
        ↓
MuJava 逐变异体执行
        ↓
公共 mutant_execution + summarize_mutation_batch
        ↓
evosuite_mutant_kill_detail_merged.csv
```

当前可执行入口为：

```powershell
python -B adapters/EvoSuite/run_batch.py `
  --manifest data/target_manifest.first10.jsonl --limit 5 `
  --batch-dir runs/<new_evosuite_batch> --workers 1 --mutant-workers 4 --seed 42
```

打开 Baselines 后也可选择 VS Code 配置
`EvoSuite A. Full Batch End-to-End (5 Methods)`。`--workers` 是并行类生成数，
`--mutant-workers` 是每个方法的隔离 JVM worker 数。

#### 8.1.4 版本和公平预算

- 固定使用 `Baselines/adapters/EvoSuite/lib/evosuite-master-1.0.6.jar`；
- Full 实验不得临时切换或混合其他 EvoSuite 版本；
- 每个目标类固定相同 `search_budget`、`max_size` 和随机种子；
- 至少运行 3 个种子；
- 生成时间包含 EvoSuite 搜索，不包含后续统一变异执行时间，二者分开报告。

### 8.2 Randoop：新增对称适配器

#### 8.2.1 当前制品和已解决阻塞

当前保留：

```text
Baselines/adapters/Randoop/lib/randoop-4.3.4.jar
Baselines/adapters/Randoop/lib/randoop-all-4.3.4.jar
```

仓库原有 core jar 不能直接运行。对 4.3.4 的启动检查为：

```powershell
java -cp Baselines/adapters/Randoop/lib/randoop-4.3.4.jar randoop.main.Main help
```

入口 `randoop.main.Main` 存在，但启动失败：

```text
NoClassDefFoundError: org/plumelib/options/Options$ArgException
```

这说明该 JAR 是缺少传递依赖的精简包，不能直接用于实验。正式适配器固定要求：

`Baselines/adapters/Randoop/lib/randoop-all-4.3.4.jar`

它必须来自官方 4.3.4 Release。适配器在生成前检查 all-in-one jar；缺失属于
`DATA_OR_TOOL_MISMATCH`/制品缺失，不能归类为 Randoop 生成失败。

#### 8.2.2 已实现入口和公共复用

当前入口为：

```text
Baselines/adapters/Randoop/run_batch.py
Baselines/adapters/Randoop/pipeline.py
```

它复用以下公共能力：

- `build_project_classpath` 与多源 `JavaTestPipeline.compile_and_run_suite`；
- target manifest/Excel 定位逻辑；
- JUnit 4 编译与原程序执行；
- MuJava 变异体发现、超时和状态判定；
- `mutant_execution.py` 的唯一物理变异体键、进程隔离和结果字段；
- `summarize_mutation_batch.py` 与 `summarize_efficiency.py` 的统一分数和时间口径。

不能直接复用 `EvoSuiteCleaner`，因为其中删除 `EvoRunner`、scaffolding、EvoSuite mock 和污染链的规则不适用于 Randoop。

#### 8.2.3 生成器设计

Randoop 专属适配器对 manifest 中的目标类去重，使用项目 classpath 和固定时间预算运行
`gentests`。当前命令结构如下，参数名来自 4.3.4 官方手册并须以本地 help 复核：

```powershell
java -cp "<randoop-runtime-classpath>;<project-classpath>" `
  randoop.main.Main gentests `
  --testclass=<fully.qualified.Class> `
  --time-limit=60 `
  --junit-output-dir=<run-dir>/raw_tests `
  --regression-test-basename=RegressionTest `
  --error-test-basename=ErrorTest `
  --randomseed=<seed>
```

实施要求：

- 每个目标类生成一次，复用于该类/方法下全部变异体；
- 固定 60 秒只是首个公平配置，正式值与 EvoSuite 的每类时间预算一致；
- 保存完整命令、stdout、stderr、exit code 和生成统计；
- `RegressionTest*.java` 进入主比较；
- `ErrorTest*.java` 单独保存在 raw 审计目录，不进入正式原程序/变异评估；
- 任何原程序失败测试都不能执行变异体；
- 不使用 DeepSeek 修复 Randoop 测试，否则不再是纯传统控制组。

#### 8.2.4 Randoop 清理器

当前 `clean_tests` 是 raw 的字节级快照，并记录 `cleaning_audit.json` 与空语义 diff。
若未来启用 Cleaner，只允许以下机械处理：

- 统一 package 和输出目录；
- 处理与项目测试类名冲突；
- 删除生成文件头等不影响语义的内容；
- 按公共规则统一 JUnit 4 import；
- 检测重复测试方法和完全相同的测试文件。

不得删除调用链、改变输入、添加断言或调用 LLM。raw/clean 源码和 diff 都要保存。无法机械修复的测试标记 `TEST_COMPILE_FAILURE`。

#### 8.2.5 执行与汇总

```text
target_manifest
      ↓
Randoop gentests（按类去重）
      ↓
raw RegressionTest/ErrorTest
      ↓
字节级 clean 快照与 cleaning audit
      ↓
公共编译器 + 原程序执行器
      ↓
MuJava 逐变异体执行
      ↓
公共 mutant_execution + summarize_mutation_batch
      ↓
mutation_batch_results.jsonl + mutation_batch_summary.json
```

公共结果至少包括 project、class、method、变异体唯一键、生成 seed、测试类、原程序
状态、KILLED/LIVE/错误状态、生成时间和失败分类，并保留 manifest 原始关联字段。

#### 8.2.6 Randoop 验收门槛

- 完整 runtime classpath 下 `help gentests` 成功；
- 一个目标类能生成至少一个测试源文件；
- 测试能进入公共编译器并在原程序执行；
- 至少一个测试完成 MuJava 变异执行；
- 4.3.4 完成 smoke 后才决定是否需要 4.3.3；
- Full 实验至少 3 个随机种子，并与 EvoSuite 使用相同每类墙钟预算。

## 9. 测试规范化与验证

### 9.1 允许的机械修改

- 删除 Markdown 围栏；
- 补齐 package/import 和测试类外壳；
- 按规则重命名测试类；
- 做有明确等价关系的 JUnit 5 → JUnit 4 转换；
- 统一换行、编码和路径。

不得静默改变输入、调用和断言语义。语义修改必须走 DeepSeek repair 并计入预算。保存规范化前后 diff，同时报告 raw/normalized CompR。

### 9.2 状态和失败分类

```text
GENERATED
  -> PARSED | RESPONSE_PARSE_FAILED
  -> NORMALIZED | NORMALIZATION_FAILED
  -> COMPILED | TEST_COMPILE_FAILURE
  -> ORIGINAL_PASSED | ORIGINAL_ASSERTION_FAILURE | TEST_RUNTIME_FAILURE | ORIGINAL_TIMEOUT
  -> MUTANT_KILLED | MUTANT_KILLED_TIMEOUT | MUTANT_SURVIVED
     | MUTANT_INVALID | MUTANT_INFRASTRUCTURE_ERROR
```

失败类别至少包括：`API_FAILURE`、`RESPONSE_PARSE_FAILED`、`ENV_SETUP_FAILURE`、
`BUILD_FAILURE`、`NORMALIZATION_FAILED`、`TEST_COMPILE_FAILURE`、
`TEST_RUNTIME_FAILURE`、`ORIGINAL_ASSERTION_FAILURE`、`ORIGINAL_TIMEOUT`、
`MUTANT_KILLED_TIMEOUT`、`MUTANT_INFRASTRUCTURE_ERROR`、`TARGET_NOT_REACHED`、
`MUTANT_SURVIVED`、`INVALID_ORACLE`、`DEPENDENCY_OR_MOCK_FAILURE`、
`DATA_OR_TOOL_MISMATCH`、`SKIPPED_BY_DESIGN`。

原程序未通过的测试不得执行任何变异体，也不得伪装成 LIVE；但它所关联的唯一物理变异体
以 `NOT_EXECUTED_INVALID_TEST` 进入 End-to-End MS 主指标分母。Executable MS 只统计真正
执行的 KILLED/LIVE 子集。失败类别从日志自动判定，不能统一写成“模型能力不足”。

## 10. 公平性和指标

### 10.1 必须相同

- 项目版本、源码、classpath、JDK 8、项目验证时实际使用的 JUnit 4 和 Hamcrest；
- 七种 LLM Baseline 统一使用 DeepSeek-V4-Flash provider、API URL 和 model；
- 主对比的所有 LLM stage 统一 `max_tokens=16000`；temperature、thinking、输入预算、调用
  条件和停止规则仍按论文/作者代码及必要的 DeepSeek 接口适配分别冻结；
- 目标方法和有效非等价变异体集合；
- 编译、原程序执行、MuJava 执行和超时规则；
- 等价/无效/超时变异体的分母规则；
- 机械规范化和 Token/费用统计方式。

### 10.2 指标

- **CompR**：编译成功任务 / 有效生成任务；报告 raw 与 normalized；
- **ExecR**：原程序执行成功任务 / 编译成功任务；
- **End-to-End MS（主指标）**：固定为
  `KILLED / (KILLED + LIVE + NOT_EXECUTED_INVALID_TEST)`，对唯一物理变异制品计算；
  `MUTANT_KILLED_TIMEOUT` 属于 KILLED 子类。生成、编译/修复上限、原程序执行失败或
  超时造成的未执行变异体进入分母，反映完整测试生成流水线的有效性；
- **Executable MS（诊断指标）**：固定为 `KILLED / (KILLED + LIVE)`，仅反映实际进入
  变异执行的子集；
- **计分边界**：`NOT_EXECUTED_INVALID_TEST` 不得伪装成 LIVE 或 KILLED；公共执行器的
  基础设施错误不惩罚 Baseline，排除于两个分母并单独报告；等价标签冲突仅审计；不得
  用重复 Excel 行扩大任何分母；
- **Killed/Live/Error/Timeout**：分别报告数量，不能只给一个 MS 百分比；
- **TKR**：成功杀死指定目标的任务 / 可评价目标；
- **Coverage**：JaCoCo 行/分支覆盖；
- **Test Suite Size**：测试类、测试方法、断言和有效代码行；
- **Cost**：调用数、输入/输出/缓存 Token、实际费用及各阶段耗时；
- **Efficiency**：每个击杀的调用数、Token、费用和时间；
- **Stability**：超时、flaky、解析失败和修复次数。

除上述统一主指标外，还要保留方法原论文指标作为忠实性证据：ChatTester 的编译率、
执行率和覆盖率，HITS 的切片与覆盖效果，IntUT 的分支覆盖，以及 MuTAP/MutGen 每轮
survivor 和增量 MS。但这些辅助指标不得替代统一 MuJava 主结果。

非确定性方法至少独立运行 3 次，预算允许时 5 次；独立实验关闭响应缓存。报告均值、中位数、标准差和 95% 置信区间，并在相同目标上做配对比较。

## 11. 推荐配置

```yaml
experiment:
  run_id: "20260321-pilot-01"
  repeats: 3
  compile_timeout_sec: 120
  generation_test_timeout_sec: 60 # 生成/修复阶段的原程序验证
  mutation_worker_startup_timeout_sec: 60 # JVM/类加载基础设施上限，不属于杀死判定
  mutation_test_method_timeout_sec: 10 # 每个 @Test 独立上限；TEST_STARTED 时重置
  mutation_junit_lifecycle_timeout_sec: 60 # 无活动测试时的 @BeforeClass/@AfterClass 保护

llm:
  common_config_file: "Baselines/configs/llm.properties"
  stage_config_file: "Baselines/adapters/<Baseline>/llm.properties"
  config_precedence: "default < common properties/environment < baseline-stage < explicit-call"
  provider: "FROM_PROPERTY_llm.provider"
  api_url: "FROM_SELECTED_PROVIDER_PROFILE"
  model: "FROM_SELECTED_PROVIDER_PROFILE"
  temperature: "FROM_PROVIDER_OR_GLOBAL_PROPERTY"
  max_input_tokens: "FROM_STAGE_PROVIDER_OR_GLOBAL_PROPERTY"
  max_input_chars: "FROM_STAGE_PROVIDER_OR_GLOBAL_PROPERTY"
  oversize_prompt_policy: "FROM_PROPERTY"
  max_output_tokens: "FROM_STAGE_PROVIDER_OR_GLOBAL_PROPERTY"
  max_api_attempts: "FROM_PROVIDER_OR_GLOBAL_PROPERTY"
  use_cache_for_independent_repeat: false
  generation_workers: "FROM_PROPERTY_llm.threads"

budget:
  mode: "native"                 # native | equal；正式主结果必须先跑 native
  method_protocol: "FROM_PAPER_OR_PINNED_AUTHOR_CODE"
  max_calls_per_method: null      # native 不设统一臆定上限
  max_total_tokens_per_method: null
  compile_repair_stop: "METHOD_SPECIFIC_VERIFIED_RULE"
  runtime_repair_stop: "METHOD_SPECIFIC_VERIFIED_RULE"
  coverage_round_stop: "METHOD_SPECIFIC_VERIFIED_RULE_OR_NOT_APPLICABLE"
  mutation_round_stop: "METHOD_SPECIFIC_VERIFIED_RULE_OR_NOT_APPLICABLE"

traditional_baselines:
  evosuite_config_file: "Baselines/adapters/EvoSuite/evosuite.properties"
  randoop_config_file: "Baselines/adapters/Randoop/randoop.properties"
  evosuite_version: "1.0.6"       # 正式冻结版本，不与其他版本混跑
  randoop_version: "4.3.4"
  generation_time_sec_per_class: 60
  repeats: 3
  smoke_seed: 42
  full_fixed_seeds: [11, 23, 37]
  generation_workers: "FROM_EACH_ADAPTER_LOCAL_PROPERTY"
  preserve_raw_and_clean_tests: true

evaluation:
  jdk: "8"
  junit: "PROJECT_NATIVE_JUNIT4" # 保持项目验证时版本；不得由 worker 偷换版本
  mutation_engine: "mujava"
  coverage_engine: "jacoco"
  primary_mutation_score: "KILLED / (KILLED + LIVE + NOT_EXECUTED_INVALID_TEST)"
  diagnostic_executable_ms: "KILLED / (KILLED + LIVE)"
  invalid_test_policy: "unique mapped mutants enter primary denominator"
  infrastructure_error_policy: "excluded_and_reported"
  equivalent_labels: "audit_only"
  equivalence_conflict_policy: "flag_only"
  mutant_workers: "FROM_PROPERTY_java.mutant.workers"
```

论文/作者工程已经明确的配置不得在 Pilot 中调参。只有
`API_REQUIRED_ADAPTATION`、`EVALUATION_COMMON` 和统一16000输出预算参数可以在
Pilot 后最多调整一次；每项调整必须有依据、保存前后值并使用新 run。Full 开始后
冻结全部配置。

## 12. 实施顺序和准入

### Phase 0：公共基础设施

1. 从 `Baselines/data/mutant_total.xlsx` 生成并冻结 `Baselines/data/target_manifest.jsonl`；
2. 在 `Baselines/adapters/common` 实现支持自定义 system prompt 的统一 gateway；
3. 实现公共调用日志与 `call_mutant_trace.jsonl`，强制保留 Excel 原始 12 字段；
4. 实现 JUnit 4 规范化、编译、原程序执行、MuJava 和统一结果 schema；
5. 选择同一个 smoke 方法和 10 个 pilot 方法。

### Phase 1–3

1. ChatTester、MutGen、HITS；
2. MuTAP、A3Test；
3. ASTER、IntUT。

### Phase 4：传统控制组

1. EvoSuite 版本冻结为 1.0.6，不在同一正式实验中混用 1.2.0；
2. Randoop 版本冻结为 4.3.4，并使用官方 `randoop-all-4.3.4.jar`；
3. 两者已共用类级去重生成、多源编译、原程序验证、MuJava 变异执行和结果汇总；
4. 先分别完成一个类/一个方法的 smoke，再运行五方法协议验证；
5. Full 对两者使用相同每类时间预算和同一组至少 3 个随机种子；每个种子使用独立 run。

### Smoke 门槛

- LLM Baseline：DeepSeek-V4-Flash 请求成功且实际 model 落盘；
- EvoSuite/Randoop：JAR 及传递依赖加载成功，实际工具版本落盘；
- 生成 JUnit 4；
- 完成编译、原程序执行和至少一个 MuJava 变异体执行；
- prompt、response、test、log、result 全部可追溯。
- 每个逻辑调用都有 `call.json`，且能通过 `call_mutant_trace.jsonl` 回溯到该方法下
  全部 Excel 变异体原记录；12 个必需字段无缺失。

### Pilot 门槛

- 10 个方法、至少 50 个有效变异体；
- 无系统性路径/classpath 错误；
- API、缓存和 Token/费用记录正确；
- 任取 `call_id` 均能恢复对应源程序、方法分组及所有关联变异体；
- 失败可自动分类；
- 无 RIP/答案泄漏；
- Unified-output-budget 保持各方法原文停止规则且每次输出上限为16000；Native-budget
  敏感性实验使用独立 run 与明确标签；
- 指标可从原始日志自动重算。

Full 期间冻结 commit、DeepSeek profile、prompt、预算、目标清单和执行器；修复框架缺陷后使用新 run ID 重跑受影响目标。

## 13. 交付物和完成判定

每个 Baseline 交付：

- `SOURCE.md`：论文、代码、commit、实现身份和许可证；
- `METHOD_MAPPING.md`：论文步骤到本地模块/DeepSeek stage 的映射；
- `ENVIRONMENT.md`：JDK/Python/Maven/依赖和模型 profile；
- `REPRODUCE.md`：从目标清单到测试与评估的命令；
- 无 Key 的 `config.yaml`；
- 全部阶段 prompt、补丁、原始 response、测试和日志；
- smoke/pilot/full 统一 `result.jsonl` 与汇总 CSV/XLSX；
- 适配差异、失败分类和有效性威胁。

一个 LLM Baseline 只有同时满足以下条件才算完成：

- 全部 LLM 阶段使用统一 DeepSeek-V4-Flash API；
- 在当前项目上真正生成 JUnit 4，而不是重放论文已有测试；
- 测试进入相同编译、原程序执行和 MuJava 流水线；
- 能与 MutantsTestLLM 按相同目标和分母计算 CompR、ExecR、MS、TKR、成本和效率；
- 实现来源与适配程度表述准确；
- 结论能由 prompt、response、测试和执行日志自动复核。

EvoSuite/Randoop 完成的判定是：固定版本和种子、在相同每类时间预算内生成测试、保存 raw/clean diff、通过相同原程序与 MuJava 验证器，并能生成逐变异体汇总结果。二者无需也禁止调用 DeepSeek API，不改变本文件七种 LLM Baseline 的范围。

## 14. 首个 Baseline（ChatTester）实战复盘

### 14.1 最终试跑结果与当前完成边界

首个端到端批次为 `runs/20260318_chattester_manual5_001`。5 个去重方法关联 844 条 Excel
源记录，按物理 `.class` 去重后为 737 个变异体：288 KILLED（含 6 个
`KILLED_TIMEOUT`）、66 LIVE、383 个因测试无效未执行。主指标
`End-to-End MS = 288 / 737 = 39.08%`；诊断指标
`Executable MS = 288 / 354 = 81.36%`。其中两个目标通过原程序验证并实际执行 354 个
变异体；另三个目标分别因达到编译修复上限或原程序断言失败而未执行 383 个变异体。

ChatTester 当前已经完成“真实生成 → 论文规定的编译反馈 → 原程序准入 → 独立 JVM 逐变异体
执行 → 统一汇总”的核心闭环。它还不能称为论文辅助指标全部完成：JaCoCo statement/branch
coverage 尚未接入，正式全量目标与至少 3 次独立无缓存重复实验尚未执行。

### 14.2 总方案已经提前覆盖、实践证明必要的规则

| 已有规则 | ChatTester 中的实际作用 |
|---|---|
| `sources` 只读、`adapters` 实现、禁止修改 `MuTestLLM` | 避免上游源码、公共工具和现有方法互相污染 |
| Excel 按 `project + class + method` 去重生成，同时保留原始 12 字段 | 一个方法只生成一次，但能回溯其全部变异体 |
| API Key 只在 `configs/llm.properties`，统一 DeepSeek gateway | 完成模型替换且没有把 Key 写入产物 |
| 每次调用记录 token、缓存、时间和源程序关联 | 能核对真实调用成本，而不是用预计“每方法两次”代替 |
| 生成 worker 与变异 worker 分开配置 | LLM 慢调用和 JVM 启动开销可分别并发 |
| 每个变异体在独立 JVM/类路径中执行 | 防止原类与变异类、前后两个变异体相互污染 |
| 先在原程序上通过，再执行变异体 | 避免把无效断言或运行异常误判为击杀 |
| 论文优先，作者代码差异单独标记 | 排除了作者制品中论文没有规定的 runtime repair |
| Java/.class 放入被测工程的 `baselines/<Baseline>/runs/...` | 编译和运行能使用项目真实依赖与类路径 |
| 唯一物理制品计分、重复源记录只保留关联 | 844 条记录没有错误膨胀为 844 个独立变异体 |

### 14.3 实战中新发现或原方案写得不够明确的问题

1. **协议核对应前置为硬门槛。** ChatTester 论文只允许编译反馈，作者制品却实现了运行反馈。
   后续每个 Baseline 在编码前必须建立“论文段落 / 固定 commit / 本地 stage / 冲突裁决”证据表；
   `UNRESOLVED` 阶段不得真实运行。
2. **覆盖率名称不能混用。** ChatTester 的 statement/branch coverage 是终态评估，不是 LLM
   repair 触发器；line coverage、statement coverage 和 branch coverage必须分别落盘。
3. **项目原生测试框架与 classpath 会遮蔽。** JUnit 3/4 同时存在时，错误顺序可能出现
   “编译或发现测试成功”的假象。公共编译器必须记录每个 jar 的来源、优先级、JUnit 版本，
   并把零测试发现视为失败。
4. **Windows 路径长度是执行正确性问题。** 深层 run 路径会触发 MAX_PATH；变异临时目录统一
   使用短路径 `mx/<id>`，日志仍保存完整映射。
5. **仅报 Executable MS 会隐藏生成失败。** 三个无效目标曾导致 383 个变异体完全未执行；因此
   End-to-End MS 为主指标，Executable MS 只能作为可执行子集诊断。
6. **批次汇总必须覆盖所有终态。** 早期汇总遗漏 `ORIGINAL_FAILED`，造成分母偏小。状态机新增
   状态时必须同步更新汇总测试，不能依赖固定成功分支列表。
7. **源记录数不等于物理变异体数。** 重复 Excel 行和冲突 `equivalent` 标签必须审计，但计分
   键以实际变异 `.class` 身份为准。
8. **手动运行路径容易混淆。** VS Code 配置名、batch root、单个 target 目录和 run ID 必须在
   CLI 参数错误中明确区分；README 示例不得把示例 ID 写成看似真实存在的目录。
9. **正式汇总必须检查批次完整性。** 任一 `ORIGINAL_PASSED` 目标缺少 mutation result 时，
   默认拒绝出正式分；只有显式 `--allow-incomplete` 才能生成标记为无效的诊断汇总。
10. **缓存会破坏独立重复。** 调试可用 provider cache，正式 3/5 次重复必须关闭缓存或使用能
    证明互不复用的配置，并记录 Cache Read/Write/Uncached Input。
11. **调用数是协议分支的结果。** “每方法 2 次”只适用于无编译失败的 ChatTester 初始阶段；
    repair 次数必须由真实错误与论文停止条件决定。
12. **公共 JUnit worker 需支持测试套件。** 当前单测试类接口对 ChatTester 足够；接入会生成
    多类/多文件的 HITS、ASTER、MutGen 前，必须扩展为 `test_classes[]` 并保持逐类结果。
13. **并发还需要全局 API 限流。** worker 数已经可配，但正式大批量前仍需加入跨 worker 的
    semaphore、429 backoff 和服务端 Retry-After 记录。
14. **作者仓库并不总能在 Windows 正常 checkout。** ChatTester 含 Windows 非法文件名，需用
    bare repo 加白名单导出；任何替代保存方式都要记录 commit 和导出清单。
15. **政策修改后要做全文冲突扫描。** 本次仍发现推荐 YAML 留有旧
    `KILLED/(KILLED+LIVE)` 主公式；每次修改公式、准入或路径规则后必须 `rg` 扫描旧表述。
16. **重跑必须幂等且不可覆盖。** 中断后续跑使用新 execution ID；`latest.json` 只是索引，原始
    prompt、response、测试、编译和变异结果不可原地覆盖。
17. **失败责任必须分层。** 测试生成/修复失败进入 End-to-End 分母；依赖缺失、worker 崩溃、
    配置错误等评估基础设施故障排除于两种 MS 分母并单独报告，不能惩罚 Baseline。
18. **方法信息隔离随 Baseline 改变。** ChatTester 不得看到变异反馈；MutGen/MuTAP 可以，但
    只能在论文规定轮次读取允许字段，且永远不能看到 `equivalent` 标签或最终答案。

### 14.4 后续所有 Baseline 的新增强制准入清单

开始真实 API 调用前，必须同时满足：固定论文版本和源码 commit；完成协议证据表；解析
论文/制品冲突；冻结各 stage 的输入、调用条件、最大轮次和停止条件；确认模型能看到的字段；
通过空 Key 离线测试；通过单目标 compile/original smoke；确认项目原生 JUnit classpath。

正式出分前，必须同时满足：所有目标有终态；所有 `ORIGINAL_PASSED` 目标存在完整 mutation
result；唯一物理变异体集合与源记录映射可重算；基础设施错误为零或已明确排除；主指标和
诊断指标均可从 JSONL 重算；原论文辅助指标未完成时必须明确标为 pending，不能把“核心闭环
完成”写成“论文全部复现完成”。

### 14.5 第二个 Baseline 启动状态

第二个方法按既定顺序选择 MutGen。官方仓库已只读固定在
`Baselines/sources/MutGen`，commit 为 `69dd37ddfab16b8c5efd22117dc3d213ab05418d`；实现只放在
`Baselines/adapters/MutGen`。论文已确认的核心规则为：DeepSeek 替换原 Llama-3.3 70B，
temperature 保持 0；JUnit 4；摘要 + 无注释源码 + mutation feedback；编译/断言失败逐测试
最多一次修复；累计通过测试；最多四轮。作者复现包当前 commit 的 mutation-report 注入调用
被注释且会原地改写 site-packages prompt 和目标源码，所以不能直接执行，必须由 adapter
按论文协议显式实现并保留每轮快照。

## 15. 第二个 Baseline（MutGen）实战复盘

### 15.1 已完成闭环

MutGen adapter 已实现摘要、四轮增量生成、逐测试编译/原程序执行、作者规定的单次失败
修复、累计通过测试、逐轮隔离 MuJava 反馈、最终统一评估和 Token 审计。所有 LLM 调用使用
DeepSeek-V4-Flash；本地响应缓存对原生四轮调用关闭，服务端 Cache Read/Write 仍如实记录。

`20260322_mutgen_smoke_004` 是当前有效的覆盖感知单方法 smoke：25 个测试方法通过原程序，
7 个唯一物理变异体中 5 个 KILLED、2 个 LIVE、0 个 NO_COVERAGE，无评估基础设施错误，
`Executable MS = 5 / (5 + 2) = 71.43%`。它只证明单目标闭环，不替代 Pilot 或正式多次实验。

`20260318_mutgen_batch_full5_001` 使用作者原始4096输出上限完成5方法审计：737个唯一物理
变异体中，仅首个方法的7个变异体进入执行并得到5 KILLED、2 LIVE；其余4个方法的结构化
响应以 `finish_reason=length`、4096 completion tokens 截断，730个变异体记为 invalid-test
未执行，End-to-End MS 为0.6784%。该批次保留为 Native-budget 敏感性证据，不得进入统一
16000输出预算的主对比；主对比必须使用新 run ID 重跑 ChatTester 与 MutGen。

`20260318_mutgen_batch_unified16k_001` 使用统一16000输出上限重跑相同5方法：2个方法完成，
107个变异体实际执行并得到99 KILLED、8 LIVE，`Executable MS=92.52%`；其余630个变异体
因无最终有效测试未执行，`End-to-End MS=13.43%`。其中两个大目标仍在16000处截断；另一个
目标实际为 `ORIGINAL_FAILED`，但当次 batch wrapper 因强取缺失的 `accepted_test_count` 将其
误写为 `PIPELINE_FAILED`。该包装器分类缺陷已修复且不改变215个变异体进入 invalid-test
分母的数值。2026-10-30 使用公共汇总器重新统计时，又根据保留的42次编译/修复验证和空
`accepted_tests.json` 只读恢复该目标为 `NO_EXECUTABLE_CANDIDATE`；原始 `state.json` 仍保留
`PIPELINE_FAILED` 供审计。重算后99 KILLED、8 LIVE、630 invalid-test、0基础设施/实验流程
错误，`formal_score_valid=true`，`End-to-End MS=99/737=13.43%`，`Executable MS=99/107=92.52%`。
该批次可作为五函数统一16K协议结果，但仍不是完整数据集 Full 或多次独立重复。

`_001` 因重复方法名错误地使用 LLM 修复并复用本地响应缓存而作废；`_003` 因旧 YAML
围栏解析器拒绝了实际有效响应而作废。两次失败均保留原目录审计，不得计入实验结果。
修正后的解析器优先提取完整非空 YAML 围栏，并已用 `_003` 原始响应和离线测试回归。

### 15.2 LIVE 与 NO_COVERAGE 的公共判定

MutGen 论文反馈区分“已覆盖但存活”和“尚未覆盖”。公共模块现统一提供：

1. `jacoco_coverage.py`：用独立 JVM 和 JaCoCo agent 执行已通过原程序的中间测试套件；
2. `JacocoLineCoverageCli.java`：使用与运行时完全相同的原程序 `.class` 解析 exec 数据，并
   强制核对 class ID；
3. `mutation_line_mapping.py`：在目标方法范围内，将 MuJava pretty-printed mutant 的行号和
   mutation statement 映射回当前项目源码行；
4. `run_pipeline.py`：对 survivor 按覆盖结果生成下一轮 `LIVE`/`NO_COVERAGE`，KILLED 不再反馈。

不得把 Excel/MuJava 行号直接与当前项目 JaCoCo 行号比较。`_004` 中 MuJava 行 93 经审计
映射为当前源码行 177；该行四轮均被覆盖，因此两个 survivor 是 `LIVE`。只有映射已证明且
该行未覆盖时才标为 `NO_COVERAGE`；映射无法证明时保守记为 `LIVE` 并保留 unresolved 审计，
禁止猜测为未覆盖。此状态只用于变异引导反馈，最终统一 MS 仍取独立 JVM 的真实击杀结果。

### 15.3 对后续 Baseline 的新增公共规则

- 覆盖采集必须运行在已经通过原程序准入的不可变测试快照上；原程序失败不能产生覆盖证据。
- JaCoCo exec、原 `.class`、源码和测试快照必须同轮关联；class ID 不匹配属于基础设施错误。
- 变异工具行号若来自格式化/重写源码，必须先显式映射到当前源码；映射策略和置信结果落盘。
- `LIVE`、`NO_COVERAGE` 是论文反馈标签；`KILLED/LIVE` 的最终计分仍由公共隔离执行器决定。
- LLM 的 fenced YAML/JSON 解析要优先完整非空载荷，保留原始响应，并用真实失败响应做回归。
- 固定 schema 的 YAML literal block 若仅丢失结构缩进，可做不改变 Java 内容的确定性修复；
  必须逐轮记录是否触发，不能算作 LLM repair，也不能覆盖原始响应。
- 主对比所有结构化生成、摘要、意图和修复阶段统一 `max_tokens=16000`。若仍以
  `finish_reason=length/max_tokens` 截断，禁止补写缺失 Java/YAML；目标标为
  `MODEL_OUTPUT_TRUNCATED`，其唯一变异体进入 End-to-End 的 invalid-test 分母。作者原始4096等
  上限仅用于独立 Native-budget 敏感性实验，不得混入统一预算主结果。
- 失败 smoke 使用新 run ID 重跑，旧目录只读保留；不得修补后覆盖旧结果或纳入正式汇总。
- 批量 `limit` 表示唯一函数数，不是变异体数；每个入选函数必须保留并执行其全部唯一物理
  变异体。批量 MS 按全部最终逐变异体结果做 micro aggregate，不得平均各函数百分比；中间
  feedback 轮次的重复执行不得重复进入最终分子或分母。
- `batch_metadata.selected_method_count` 声明的任何目标若缺失 state/source records，正式汇总
  必须 fail-closed；prepare/tool 失败也要物化完整源记录并标为基础设施问题，禁止静默缩小分母。

## 16. 第三个 Baseline（MuTAP）启动与首次 smoke

MuTAP 官方固定提交已只读保存到 `Baselines/sources/MuTAP`；Java 适配、证据映射和执行入口
全部位于 `Baselines/adapters/MuTAP`。`ChatTester`、`MutGen` 的私有 adapter 未被修改；MuTAP
复用同一 DeepSeek gateway、Token 追踪、项目内 Java/.class 布局、JUnit 4 原程序准入、逐
变异体独立 JVM 执行和批次汇总器。

首次真实单方法 smoke 为 `runs/20260318170522977_mutap_batch_001`：选取 1 个去重 focal
method，保留 9 条 Excel 源记录并映射为 7 个唯一物理变异体。初始测试经过 1 次真实编译
修复；原程序逐测试行为校正保留 6 个、剔除 1 个失败测试。初始变异执行后有 2 个 LIVE，
因此严格执行 2 次单 survivor 增强，每轮累计后重新执行全部 7 个变异体。两个 survivor
仍未被击杀且均已使用，按论文停止条件结束。贪心最小化从 10 个累计测试中保留 1 个；最终
5 KILLED、2 LIVE、无 invalid/基础设施错误，`Executable MS = End-to-End MS = 71.43%`。

该 smoke 共 4 次真实 API 调用：`mutap_initial` 1 次、`mutap_syntax_repair` 1 次、
`mutap_survivor_augmentation` 2 次。总输入 12437 Token，其中普通/未缓存输入 10261、
Cache Read 2176、Cache Write 未报告（按统一规则记 0）；输出 1781；缓存命中率
`2176 / (2176 + 10261) = 17.50%`。这只证明单目标核心闭环与审计链可运行，不替代 pilot、
多次独立重复或 Full 实验。

MuTAP 后续统一提供两种默认五函数入口，二者使用完全相同的目标选择策略：先按
`project + class + method` 取前 5 个去重 focal method，再保留每个入选方法的全部 Excel
源记录和全部唯一物理变异体。逐步入口为 `prepare_batch.py` → `run_prepared_batch.py`，允许
在不调用 API 的准备阶段后检查五个 target；一键入口为 `run_batch.py --limit 5`。两种入口
最终都调用同一 `prepare_run.prepare`、`run_pipeline.run_pipeline`、公共变异执行器和批次
汇总器，禁止形成两套生成或计分逻辑。VS Code 的三个 MuTAP F5 配置均已固定 `limit=5`、
生成 worker=5、每 target 变异 worker=4；命令行仍允许显式覆盖并发数，但不得改变目标集合。

F5 五函数验证批次 `runs/20260318_mutap_step5_manual_001` 成功证明准备阶段选中 5 个去重
函数、844 条源记录和 737 个唯一物理变异体，并实际让五个 target 并发进入各自 MuTAP
状态机。由于其中三个大函数分别关联 215、100、347 个变异体，严格单-survivor 循环仍有
大量 LIVE，会把一次 F5 验证扩展为非计划正式长实验，因此验证完成后主动中断。该目录明确
标记 `INTERRUPTED_F5_VALIDATION`、`formal_score_valid=false`，不得计入任何正式结果；正式
五函数实验必须使用全新 batch 目录。CLI/F5 后续收到 Ctrl+C 时会自动写入 `INTERRUPTED`
和 `formal_score_valid=false`，避免半成品被误汇总。

## 17. 统一效率、调用次数、修复轮次与费用统计

后续全部 LLM Baseline 复用 `adapters/common/summarize_efficiency.py`，并遵循
`adapters/common/EFFICIENCY_METRICS.md`。该规范依据 `MutantsTestLLM.tex` 的 RQ3 指标设计，
但所有数值必须从当前 Baselines 的真实运行日志计算，禁止把论文表格数字写入本项目结果。

统一报告列包括：`t_pre`（预处理）、`t_gen`（全部模型调用耗时）、`r_avg`（平均原生 LLM
修复轮次）、`t_total`、`t_avg`、API Calls、Input/Output Token、Cache Hit 和 Billed Cost。
同时保留编译尝试数、原程序执行尝试数、变异评估调用数、逐变异体隔离 JVM 执行数及阶段级
调用明细，便于解释总时间。

必须区分：

1. 逻辑模型调用数：Baseline 流程主动发起一次生成或修复；
2. 供应商请求尝试数：包含 HTTP 重试，是主表 `API Calls` 的口径；本地响应缓存命中记 0；
3. 原生 LLM 修复轮次：仅统计论文/作者协议定义的修复 stage，不能把网络重试、MuTAP survivor
   augmentation、MutGen 正常生成迭代或确定性 YAML/重复名修正算成修复。

当前 stage 映射为 ChatTester 的 `chattester_compile_repair`（正式论文优先模式不调用 runtime
repair）、MutGen 的 `mutgen_compile_repair`/`mutgen_assert_repair`、MuTAP 的
`mutap_syntax_repair`、HITS 的 `hits_test_repair`、ASTER 的 `aster_repair`；A3Test 与 IntUT 没有原生
LLM repair stage。IntUT 的五候选都属于 `intut_generation`，候选失败不构成 repair。后续 Baseline 必须在方法映射文档中声明自己的生成、反馈和修复 stage，
不得仅凭 stage 名含有 round 就计为 repair。

时间采用毫秒原始记录、秒级汇总。准备阶段写入 `preprocessing_elapsed_millis`；LLM call 写入
`elapsed_millis` 和逻辑调用 wall time；每次 javac、原程序 JUnit、完整变异评估均记录耗时。
并发情况下，`t_total/t_avg` 使用逐 target 处理时间之和与平均值，另报 batch wall clock；二者
不能混用。旧 run 缺少 `t_pre` 时报告 `null/t_pre_complete=false`，禁止补成 0。

由于当前实验按 `project + class_name + method_signature` 去重后生成一次测试，效率主平均分母是
去重 focal-method target 数，而不是 `mutant_total.xlsx` 行数或唯一物理变异体数。报告必须写明
该分母，不能直接把它标作论文一对一 target-mutant 协议中的 `|M_NE|`。变异体数仍单独报告。
公共报告另给出 `t_avg_per_mapped_unique_mutant_seconds` 作为共享测试套件成本的摊销诊断；它不
代表对每个变异体独立生成测试，正式表格使用时必须写清协议差异。

费用规则为 fail-explicit：API 响应只有带明确 USD 账单字段时才能形成
`provider_billed_cost_usd`；否则为 `null`。`llm.properties` 可选配置普通输入、Cache Read、
Cache Write 和输出的每百万 Token 单价，计算 `estimated_cost_usd`，但估算值不得冒充服务商
账单费用。API Key 继续禁止进入任何统计报告。

最终 `summarize_mutation_batch.py` 会自动生成 target 级及 batch 级
`efficiency_summary.json`。ChatTester、MutGen、MuTAP、HITS、A3Test、ASTER、IntUT 已接入；未来 Baseline 必须复用同一汇总器，
不能私建不兼容的时间或修复统计。已完成旧 run 可以回算调用、Token、缓存和已有时间字段；
缺失的历史计时必须保持缺失标记。当前已回算：

- `20260318_chattester_manual5_001`；
- `20260318_mutgen_batch_unified16k_001`；
- `20260318170522977_mutap_batch_001`；
- `20260318_hits_batch5_001`；
- `20260318_a3test_batch5_003`；
- `20260318_aster_batch5_002`。

这些回算报告用于验证统计管线；是否进入正式论文表格仍由原有 smoke/pilot/full 准入规则决定。

## 18. 第四个 Baseline（HITS）五函数验证复盘

HITS 五函数验证批次为 `runs/20260318_hits_batch5_001`。选择逻辑与 ChatTester、MutGen、MuTAP
相同：先按 `project + class_name + method_signature` 取前 5 个去重函数，再保留这 5 个函数关联
的全部 844 条原始记录和 737 个唯一物理变异体。生成 worker=5；每个通过原程序准入的目标使用
mutant worker=4，并保持每个变异体独立 JVM。

5 个目标中 3 个生成了原程序通过的最终测试套件并执行 454 个变异体；另 2 个目标分别为
`MODEL_OUTPUT_TRUNCATED`（215 个变异体）和 `NO_EXECUTABLE_SLICE_TEST`（68 个变异体）。失败
目标没有从总体分母删除，而是形成 283 个 `NOT_EXECUTED_INVALID_TEST`。最终 micro aggregate 为：

```text
原始关联记录                    844
唯一物理变异体                  737

KILLED                          391
LIVE                             63
NOT_EXECUTED_INVALID_TEST       283

End-to-End MS
= 391 / (391 + 63 + 283)
= 53.05%

Executable MS
= 391 / (391 + 63)
= 86.12%
```

因此总体变异得分固定使用 End-to-End MS；Executable MS 只诊断 454 个实际执行变异体上的测试
强度。单目标 smoke 中 7 个变异体全部执行且 invalid=0，所以两者才会恰好同为 `5/7=71.43%`，
不能据此把 Executable MS 当作总体口径。

本批次共 87 次 DeepSeek 逻辑调用：`hits_slicing` 5 次、`hits_slice_test` 23 次、
`hits_test_repair` 59 次。普通/未缓存输入 174288 Token、Cache Read 121344、Cache Write 未报告
而记 0、总输入 295632、输出 233360、总 Token 528992，缓存命中率
`121344 / (121344 + 174288) = 41.05%`。编译尝试 83 次、原程序执行尝试 80 次、最终变异体 JVM
执行 454 次。该批次 `formal_score_valid=true`，可作为完整五函数协议验证；它仍是单次小样本，
不替代多次独立重复、Pilot 或 Full 实验。

## 19. 第五个 Baseline（A3Test）实现与五函数验证复盘

A3Test 作者代码固定为 `14fa2c4a5bf44bf51201f293e8dd599033652027`。论文和代码审计纠正了
旧计划的关键误解：断言增强来自 PLBART 预训练，不是推理时第二次 assertion 调用；作者也没有
编译/运行反馈修复。因此 `A3Test-DS-Adapted` 每个 focal method 只调用一次
`a3test_generation`，随后只执行确定性签名/括号验证。编译或原程序失败直接作为 invalid，不得
调用模型修复。

单方法 smoke `runs/20260318_a3test_prepare_smoke_001` 对 `JUnitTest.setThread(int)` 生成一个
带有效断言的测试。签名已经满足作者规则，不需要自动补齐；测试一次编译、一次原程序执行均通过，
7 个物理变异体得到 5 KILLED、2 LIVE。模型仅调用 1 次，输入 919、输出 95、总计 1014 Token；
End-to-End MS 与 Executable MS 均为 71.43%。

五函数实现调试中保留两次作废 run：`20260318_a3test_batch5_001` 的 verifier 未识别带
`throws` 的合法零参数测试方法；`_002` 虽识别 `throws`，但重建签名时错误删除了该子句。两者
均已写入 `INVALIDATED.md`，不得用于结果。修复后测试覆盖“识别并保留 throws”以及“拒绝多个
@Test 的单次协议违规”。

有效五函数批次为 `runs/20260318_a3test_batch5_003`：

```text
原始关联记录                    844
唯一物理变异体                  737

KILLED                          294
LIVE                             60
NOT_EXECUTED_INVALID_TEST       383

End-to-End MS
= 294 / (294 + 60 + 383)
= 39.89%

Executable MS
= 294 / (294 + 60)
= 83.05%
```

2 个目标通过原程序并实际执行 354 个变异体；3 个无效目标分别为：不存在类导致
`COMPILE_FAILED`（215 个）、错误断言导致 `ORIGINAL_FAILED`（68 个）、输出两个 `@Test` 违反
单次单测试协议导致 `VERIFICATION_FAILED`（100 个）。这些失败均未触发 LLM repair，并完整进入
End-to-End 分母。

有效批次固定 5 次 `a3test_generation`、0 次修复。普通/未缓存输入 919 Token、Cache Read
6011、Cache Write 未报告而记 0、总输入 6930、输出 2852、总 Token 9782，缓存命中率 86.74%。
编译尝试 4 次、原程序执行 3 次、最终独立变异体 JVM 执行 354 次；
`formal_score_valid=true`。这证明五函数统一协议闭环，不替代 Pilot、多次独立重复或 Full 实验。

## 20. 第六个 Baseline（ASTER）实现与五函数验证复盘

ASTER 官方 Artifact 固定为 `a6d8ab7b6ee43926821f490cb9e1844f35e7e88d`，原样保存在
`sources/ASTER`。论文、Figure 3 和公开产物共同确认 initial、error remediation、coverage
augmentation 三阶段，但仓库没有完整 Java 引擎，也没有公开修复轮数、目标覆盖率和模型
temperature。因此 adapter 名称固定为 `ASTER-Java-DS-Reimplementation`；3 次 repair、1 次
coverage、100% 可测 focal-line 目标属于 `aster.properties` 中的可配置复现边界，不得写成原文参数。

单方法 smoke `runs/20260318_aster_smoke_001` 对 `JUnitTest.setThread(int)` 执行 1 次
`aster_initial`，直接生成 7 个测试，编译与原程序均通过。focal 可测行覆盖率为 100%，按停止规则
没有 repair 或 coverage 调用；7 个物理变异体得到 5 KILLED、2 LIVE，两种 MS 均为 71.43%。

首次五函数目录 `20260318_aster_batch5_001` 作废：多行 `throws` 方法声明与 JavaParser 渲染格式
不同，旧定位器对 `parseTestMethodNamesList` 得到空 focal 行集合并错误跳过 coverage 决策。
原始结果保留且写入 `INVALIDATED.md`，不得计入比较。修复后使用方法声明/name fallback 和
brace-balanced 源码范围，并增加多行 `throws` 回归测试。

有效五函数批次为 `runs/20260318_aster_batch5_002`：选择 5 个去重函数、844 条源记录和 737 个
唯一物理变异体。2 个目标通过原程序并执行 354 个变异体；另外 3 个目标在各自初始候选及最多
3 次真实错误反馈后仍未得到可执行测试，对应 383 个变异体完整计入 invalid-test 分母。

```text
KILLED                          304
LIVE                             50
NOT_EXECUTED_INVALID_TEST       383

End-to-End MS
= 304 / (304 + 50 + 383)
= 41.25%

Executable MS
= 304 / (304 + 50)
= 85.88%
```

批次共 15 次逻辑/供应商调用：`aster_initial` 5、`aster_repair` 9、`aster_coverage` 1；coverage
属于反馈生成而非 repair，故 `r_avg=9/5=1.8`，`feedback_generation_call_count=1`。普通输入
22831 Token、Cache Read 7808、Cache Write 未报告而记 0、总输入 30639、输出 22316、总计
52955，缓存命中率 25.48%。编译尝试 15 次、原程序执行 13 次、最终独立变异体 JVM 执行
354 次。目标 005 的 coverage 候选最终仍有 1 个可测 focal 行未覆盖，在公开上限不明且当前
重实现边界为 1 轮时停止；这一事实保留在 coverage 审计，不能伪报 100%。

该批次 `formal_score_valid=true`，证明 static context → generation → compile/runtime remediation
→ uncovered-line feedback → original validation → mutation evaluation 的统一闭环可运行。它仍只是
一次五函数协议验证，不替代 Pilot、多次独立重复或 Full 实验。

## 21. 第七个 Baseline（IntUT）实现与五函数验证复盘

IntUT 没有可验证的公开作者代码，因此没有创建 `sources/IntUT` 伪快照。独立重实现位于
`adapters/IntUT`，身份固定为 `IntUT-DS-Reimplementation`。本次论文核对纠正了旧计划中的关键
错误：IntUT 原文没有 LLM repair，Section VII-E 将 recovery mechanism 明确留作 future work；
所以编译或原程序失败只影响候选选择，不得反馈给 DeepSeek。论文中的开发者候选选择在自动
实验中以“首个原程序编译并执行通过的候选”代理，选择过程永远不可读取变异体结果。

单目标 `runs/20260318_intut_prepare_smoke_002` 对 `JUnitTest.setThread(int)` 生成 1 条直线意图，
执行 5 次 `intut_generation`，选出 1 个原程序通过的测试。7 个唯一物理变异体得到 5 KILLED、
2 LIVE，End-to-End MS 与 Executable MS 均为 71.43%。

有效五函数批次为 `runs/20260318_intut_batch5_001`。选择规则与前六个 Baseline 相同：前 5 个
去重 focal method、844 条原始关联记录、737 个唯一物理变异体。5 个目标的 intention/candidate
调用数依次为 `1×5`、`4×5`、`2×5`、`10×5`、`42×5`，共 295 次
`intut_generation`；全部属于原生多候选生成，repair 次数固定为 0。

3 个目标获得原程序可执行测试并执行 569 个变异体；2 个目标的全部候选均未通过原程序验证，
对应 168 个变异体进入 `NOT_EXECUTED_INVALID_TEST`。完整 micro aggregate 为：

```text
原始关联记录                    844
唯一物理变异体                  737

KILLED                          409
LIVE                            160
NOT_EXECUTED_INVALID_TEST       168

End-to-End MS
= 409 / (409 + 160 + 168)
= 55.50%

Executable MS
= 409 / (409 + 160)
= 71.88%
```

本批次 `formal_score_valid=true`，没有缺失目标或基础设施错误。普通/未缓存输入 100083 Token、
Cache Read 837632、Cache Write 未报告而记 0、总输入 937715、输出 573724、总 Token 1511439，
缓存命中率 `837632 / (837632 + 100083) = 89.33%`。编译尝试 295 次、原程序执行尝试
232 次、最终独立变异体 JVM 执行 569 次；`t_pre=9.377s`、`t_gen=1803.956s`、五目标处理
时间和 `t_total=2112.831s`，批次墙钟约 1800.216s。

该批次同时暴露 IntUT 的成本特征：调用数是“condition-chain 意图数 × 5 候选”，不能为了与
单次生成 Baseline 表面等调用而任意截断；若做等预算补充实验，必须与原生策略结果分栏。
公开代码缺失造成的 PAINT 字节码 CFG 与 CodeBERT mock classifier 偏差已写入每个 run 的
metadata 和 `METHOD_MAPPING.md`，不得把当前词法重实现宣称为作者官方引擎。候选选择完成后
使用公共 JaCoCo 对 focal-method 行/分支计数，但只作论文辅助指标，不反馈给模型。3 个原程序
通过目标的可执行子集 micro line coverage 为 `74/(74+3)=96.10%`，branch coverage 为
`37/(37+6)=86.05%`；另外 2 个 invalid 目标没有可接受运行制品，明确排除于该辅助分母，因此
不得把这两个数写成“全部 5 个目标覆盖率”。

2026-03-21 进一步纠正了 RAG 实现，使 Jaccard 严格比较当前 focal method 与重建的历史 focal
method，而不是与测试方法文本比较。对本批次 5 个目标离线重建后，test/mock example 仍均为 0，
五个首条 Prompt 的 SHA-256 与原批次逐一相同，因此不改变上述 295 次调用的实际模型输入，原批次
继续有效，无需重跑。

2026-03-21 使用 VS Code 五函数入口启动独立重复 `20260318_intut_vscode_batch5_001`，目标
005 在第 84/210 个候选处发生两次 DNS `getaddrinfo failed`，因此该批次写入
`INVALIDATED.md`，不得报告正式 MS。此次故障还发现并修复了公共汇总器缺陷：
失败分类不再把所有 `PIPELINE_FAILED` 混称为网络问题：明确的模型服务失败写成
`MODEL_PROVIDER_FAILED` 并计入基础设施错误；未知适配器/流程异常保留为 `PIPELINE_FAILED`，
计入不可出分的实验流程错误。二者均排除于两种 MS 分母并强制 `formal_score_valid=false`，
而“模型已正常返回但候选无法通过编译或原程序”的目标仍属于 Baseline 无效测试，必须进入
End-to-End MS 分母。失败 provider retry 会从错误消息回算到 API 尝试次数，但不会增加逻辑调用
或论文修复轮次。

公共网关现使用可配置的 5 次最大 provider 尝试及 1/2/4/8 秒有界指数退避（最大等待 10 秒）。
只重试 DNS/连接/读取故障、瞬时畸形响应、HTTP 408/409/425/429 和 5xx；400/401 等不可恢复
4xx 与本地 URL 配置错误立即失败。该机制只提高所有 LLM Baseline 的公共基础设施韧性，不改变
各论文的 Prompt、候选数、逻辑调用数、反馈条件或停止条件。重试 `try` 范围只包含 HTTP 请求
和响应 JSON 解码；本地缓存写入、响应规范化或适配器错误不得触发重复计费请求。

修复后使用同一 VS Code 配置完成独立重跑 `20260318_intut_vscode_batch5_002`。5 个唯一函数仍
关联 844 条源记录和 737 个唯一物理变异体；3 个目标存在原程序通过测试，2 个目标正常穷尽候选
但没有可执行测试。最终 410 KILLED、159 LIVE、168 NOT_EXECUTED_INVALID_TEST、0 个其他错误：

```text
End-to-End MS = 410 / (410 + 159 + 168) = 55.63%
Executable MS = 410 / (410 + 159) = 72.06%
```

该批次 `formal_score_valid=true`，295 次逻辑生成调用对应 295 次 provider request，说明本次没有
触发网络重试；修复轮次仍为 0。普通/未缓存输入 69820 Token、Cache Read 709220、Cache Write
未报告而记 0、总输入 779040、输出 390975、总 Token 1170015，缓存命中率 91.04%。编译尝试
295 次、原程序执行 231 次、变异评估调用 3 次、独立变异体 JVM 执行 569 次；`t_pre=76.669s`、
`t_gen=1393.542s`、`t_total=1799.322s`、批次墙钟约 1404.340s。3 个可执行目标的辅助 micro
line coverage 仍为 74/77（96.10%），branch coverage 仍为 37/43（86.05%）。本结果验证网络故障
修复后的完整闭环，但仍只是一次五函数独立重复，不替代 Pilot/Full 多次实验。

## 22. EvoSuite 与 Randoop 控制组实现复盘

2026-10-30 已完成两个传统控制组的统一适配器。新增公共
`pipeline.py`（分别位于 EvoSuite、Randoop 目录）和
`JavaTestPipeline.compile_and_run_suite`：前两个分别负责自己的按类生成协议，后者只负责
同时编译 suite、分片和 scaffolding；旧的单文件入口保持不变。两者均使用公共原程序
验证、进程隔离 MuJava 执行和批次汇总。搜索时间通过
`native_generation_elapsed_millis` 单独统计；共享给同类多个方法时只计一次。LLM 调用、修复、
输入/输出/缓存 Token 固定为 0，并标记 `llm_applicable=false`。

Randoop 官方 `randoop-all-4.3.4.jar` 已补齐，SHA-256 为
`BC134DCA4A0DF20A4787148923A2F6F4D0ECF8D40D7BBA16B906E8CF0BDF8BAF`；本地
`help gentests` 已确认 `time-limit`、`junit-output-dir`、`junit-package-name`、`randomseed`、
Regression/Error basename 等参数。原有 core jar 单独启动缺 `org.plumelib.options`，只保留为
制品审计，不得用于正式生成。

同一 TarEntry 方法、seed 42、配置搜索预算 5 秒的端到端 smoke 使用
`target_manifest.traditional_smoke_tarentry.jsonl`，包含 267 条关联记录和 215 个唯一物理
变异体。两者均编译一次、原程序执行一次、变异体 JVM 执行 215 次且
`formal_score_valid=true`：

```text
EvoSuite: KILLED_TIMEOUT=212, LIVE=3
End-to-End MS = Executable MS = 212 / 215 = 98.60%
t_gen=85.284s, t_total=365.732s, API/Token=0

Randoop: KILLED=6, LIVE=209
End-to-End MS = Executable MS = 6 / 215 = 2.79%
t_gen=8.003s, t_total=30.240s, API/Token=0
```

EvoSuite 的 5 秒是搜索预算而非整个进程墙钟上限，classpath 分析、启动和测试写出使实际
生成阶段达到 85.284 秒；正式报告必须同时保留配置预算和实测墙钟。上述 TarEntry smoke
产生于旧版“整段 JVM 进程墙钟”超时协议，212 个 `KILLED_TIMEOUT` 及 98.60% 仅保留为
历史诊断，不得进入正式跨 Baseline 比较。正式结果必须按当前逐测试方法协议重跑：JVM 启动
与类加载使用独立基础设施上限，每个 `TEST_STARTED` 为当前测试方法重新启动独立行为期限。

该 TarEntry 制品曾用执行 ID `20260318_tarentry_junit10s_001` 按“整套共享 10 秒”的过渡协议
复核。
EvoSuite suite 含 94 个测试方法、380 个断言，全部测试带 `@Test(timeout = 4000)`；旧原程序
JUnit 内部运行约 2.648 秒，而 4-worker 下旧 212 个超时集中在整进程 5.015--5.463 秒。
新协议下 JVM/测试类启动仅 128--369 ms，JUnit suite 实际耗时 4.372--5.942 秒，最终为
1 KILLED、214 LIVE、0 KILLED_TIMEOUT，Executable MS 与 End-to-End MS 均为
`1 / 215 = 0.47%`。唯一真实杀死为 `SDL_136`，由 `test18` 和 `test55` 的断言发现。
因此旧 98.60% 是套件规模、4-worker CPU 竞争和 5 秒整进程阈值共同造成的假阳性，不能代表
EvoSuite 的真实变异杀死能力。由于该次 JUnit suite 均在 10 秒内结束，它仍可作为诊断依据，
但启用逐测试协议后不再作为正式结果；正式结果须重新执行并发布新的 execution ID。

`runs/20260318_evosuite_jxpath5_001` 曾按两阶段协议和 5 秒行为上限重跑 5 个目标。其 231 个
`KILLED_TIMEOUT` 均在 JUnit 开始标记后达到上限，而启动耗时仅约 121--361 ms；因此它们
不包含 JVM 加载时间。不过这些超时全部来自含 29 个测试的
`SimplePathInterpreter_ESTest`：原程序 JUnit 基准约 3.23--3.35 秒，4-worker 并发下超时
集中在 5.025--5.153 秒；13/16 个测试的另两个套件没有超时。这说明 5 秒结果主要受并发
调度和阈值边界影响，不能直接解释为 231 个死循环。公共行为上限自 2026-10-30 起放宽为
10 秒，正式结果必须重跑；旧的 81.50% 只保留为 5 秒敏感性结果。

10 秒重跑执行 ID `20260318_junit10s_001` 保持测试、变异体、classpath、seed 和 4-worker
不变，最终得到 118 killed、228 live、0 timeout/error，Executable MS 与 End-to-End MS
均为 `118 / 346 = 34.10%`。其中 114 个为普通 JUnit 失败，4 个为变异引发递归后的
`StackOverflowError`，分类为 `KILLED_RESOURCE`。逐一映射原 231 个 5 秒超时后，63 个
转为普通 killed、4 个转为 resource killed、164 个转为 live。因此正式统计固定采用
整套共享 10 秒的行为上限。它与 5 秒的 81.50% 都只能作为历史阈值敏感性数据，不能作为
逐测试方法协议下的正式主结果。

首个前五函数目标 `JUnitTest` 位于 EvoSuite 1.0.6 原生拒绝的 package 范围。此类结果固定
分类为 `GENERATOR_UNSUPPORTED_TARGET`，其关联变异体进入 End-to-End MS 分母；只有缺 JAR、
classpath 损坏或无法启动等实验环境问题才归为 `DATA_OR_TOOL_MISMATCH` 并使正式分数失效。
验证批次 `20260318_evosuite_unsupported_target_002` 对 7 个物理变异体得到 7 个
`NOT_EXECUTED_GENERATOR_UNSUPPORTED_TARGET`、End-to-End MS=0，且
`formal_score_valid=true`。这条区分适用于后续所有传统生成器能力限制。

随后按目录所有权进一步拆分实现：删除 `adapters/common/traditional_baseline.py`，将
EvoSuite 的全部方法逻辑固定到 `adapters/EvoSuite/pipeline.py`，将 Randoop 的
全部方法逻辑固定到 `adapters/Randoop/pipeline.py`。两边不互相导入，也不从
common 导入生成器协议；common 只提供 `JavaTestPipeline`、`mutant_execution` 和统一
summary 等与方法无关的组件。拆分后的 `20260318_randoop_split_smoke_001` 仍得到
6 KILLED、209 LIVE、MS=2.79%，并实际执行 215 个物理变异体；
`20260318_evosuite_split_compile_smoke_001` 实际完成 TarEntry 测试生成、多源编译和原程序
执行，状态为 `ORIGINAL_PASSED`，该目录标记为 integration-only 且不用于 MS 报告。另一个
独立入口批次 `20260318_evosuite_split_smoke_001` 继续正确识别原生不支持目标并将 7 个
变异体计入 End-to-End 分母。

## 23. 全局变异执行超时协议与历史结果审计

2026-10-30 对 ChatTester、MutGen、MuTAP、HITS、A3Test、ASTER、IntUT、EvoSuite 和
Randoop 的变异执行入口做了全量核查。九种方法最终都调用唯一公共实现
`adapters/common/mutant_execution.py::execute_mutants`；没有 Baseline 保留私有的
`KILLED_TIMEOUT` 分类器。因此以下规则是全局协议，不允许在单个适配器中覆盖：

1. **启动基础设施时钟**：`java.mutant.startup.timeout.seconds=60`。只保护进程创建、JVM
   启动、classpath 查找和测试类的非初始化加载。达到上限记
   `ERROR_STARTUP_TIMEOUT`，属于基础设施错误，绝不算杀死；
2. **逐测试方法行为时钟**：`java.mutant.test.timeout.seconds=4`。Java worker 先通过
   `Class.forName(testClass, false, loader)` 完成不触发静态初始化的测试类加载，再写出
   `JUNIT_EXECUTION_STARTED`。原程序使用完整 JUnit 运行；变异体使用同一 JUnit 4 runner
   并启用 fail-fast。监听器对每个测试发布
   `TEST_STARTED`、`TEST_FINISHED` 或 `TEST_IGNORED`；Python 执行器在每个
   `TEST_STARTED` 上重置 4 秒期限。测试方法及其 `@Before`/`@After`、被测方法和测试触发
   的 SUT 延迟加载均计入当前测试方法的时间；94 个测试可以累计超过 4 秒，只要每个测试
   自身不超过 4 秒；
3. **JUnit 生命周期时钟**：`java.mutant.lifecycle.timeout.seconds=60`。只在没有活动测试
   方法时保护 `@BeforeClass`、`@AfterClass`、测试发现及监听器收尾。达到上限记为
   `ERROR_LIFECYCLE_TIMEOUT`，属于基础设施/生命周期错误，不算杀死；
4. **生成/搜索预算**：与变异执行完全分离。EvoSuite 使用原生 `search_budget`，Randoop
   使用原生 `time-limit`；二者的外层进程保护均至少为“搜索预算 + 120 秒”，只防止生成器
   失控，不把 JVM 启动挤占搜索预算。LLM HTTP 连接/读取超时、编译超时和生成后原程序验证
   超时也属于各自阶段，均不得产生 `KILLED_TIMEOUT`；
5. **变异体 fail-fast**：原程序验证不得提前停止；变异体在首个原程序通过的测试出现
   断言失败、异常、`OutOfMemoryError`、`StackOverflowError` 或逐测试超时后立即停止。
   已杀死变异体不再执行剩余测试，因为后续结果不会改变变异得分。`LIVE` 必须穷尽全部测试。
   每条执行记录必须保存 `fail_fast`、`fail_fast_stop_requested`、`stopped_early` 和首个
   `killing_tests`；
6. `KILLED_TIMEOUT` 只能表示：该测试在原程序上通过，但在变异体上从其
   `TEST_STARTED` 起超过 4 秒。结果必须记录 `timed_out_test_id` 和
   `timeout_phase=TEST_METHOD`。类缺失、链接错误、无测试、worker 启动失败及生命周期超时
   继续归为基础设施错误；
7. 多 worker 只改变吞吐量，不得改变计时起点。worker 数、启动时间、每个测试方法时间、
   JUnit suite 总时间和总墙钟必须逐变异体记录。正式报告必须记录
   `execution_semantics`、startup/test-method/lifecycle 三个 timeout 和 worker 数。

全局入口核查如下：

| Baseline | 变异执行入口 | 结论 |
|---|---|---|
| ChatTester | `run_mutant_execution.py` | 公共执行器 |
| MutGen | `run_pipeline.py`，含论文原生中间反馈轮 | 公共执行器 |
| MuTAP | `run_pipeline.py`，含迭代/最小化轮 | 公共执行器 |
| HITS | `run_pipeline.py` | 公共执行器 |
| A3Test | `run_pipeline.py` / `run_mutant_execution.py` | 公共执行器 |
| ASTER | `run_pipeline.py` / `run_mutant_execution.py` | 公共执行器 |
| IntUT | `run_pipeline.py` / `run_mutant_execution.py` | 公共执行器 |
| EvoSuite | `pipeline.py` | 公共执行器；搜索预算独立 |
| Randoop | `pipeline.py` | 公共执行器；搜索预算独立 |

磁盘历史审计共发现 55 个已发布的 `mutant_execution/latest.json`。此前重新执行的 5 个
EvoSuite jxpath 目标使用“两阶段握手 + 整套共享 10 秒”协议，其余 50 个主要记录更早的
`original-once_then_parallel-fresh-jvm-per-mutant` 和 5 秒整进程时限；二者都早于当前
逐测试方法协议。旧结果保留作审计，
但不得进入正式跨 Baseline 比较。已发现含旧 `KILLED_TIMEOUT` 的批次包括 ChatTester、
A3Test、ASTER、HITS、IntUT、EvoSuite TarEntry smoke 和 Randoop；MuTAP/MutGen 即使当前
汇总未显示 timeout，其发布指针仍是旧执行语义，也必须统一重跑以保证协议一致。

重跑只复用已经通过原程序验证的 `final` 测试制品和原有 `source_records.jsonl`，不重新调用
DeepSeek、不重新生成或修复测试；每个目标用新的 execution ID 调用公共执行器并发布新的
`latest.json`，随后重新执行 `summarize_mutation_batch.py`。效率汇总只统计 `latest.json`
指向的正式执行，不累计 smoke、5 秒敏感性实验或历史重跑目录。
