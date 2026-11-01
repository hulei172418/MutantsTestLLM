# HITS 上游代码快照

本目录只保存用于核对 HITS 实现的公开第三方上游源码，禁止在这里加入本项目适配逻辑或实验输出。

原论文匿名制品 `SlicePromptTest4J-6CF1` 已无法读取，因此当前没有可验证的原作者公开代码快照。这里固定的是 ZJU-ACES-ISE 的 ChatUniTest 实现：

- `chatunitest-core`：<https://github.com/ZJU-ACES-ISE/chatunitest-core>，commit `513056beadebee0571c0615e3b13bc84bce65179`
- `chatunitest-maven-plugin`：<https://github.com/ZJU-ACES-ISE/chatunitest-maven-plugin>，commit `38f152ce9186bc42f2b08b73a07dce8af54b1378`
- `ChatUniTest` 总入口：<https://github.com/ZJU-ACES-ISE/ChatUniTest>，commit `06d164aed21ec4cea29b8ac8661de395bdf50c41`。其子模块只保留 Git 指针，未在总入口目录内初始化；可执行证据使用上面两个独立、完整的仓库快照。

ChatUniTest README、`HITSRunner`、`HITS.java` 与 `prompt/hits_*.ftl` 明确提供了 HITS 的公开实现入口，但它不是 HITS 论文作者公开的官方制品。因此实验名称固定为 `HITS-ChatUniTest-DS`，不能标注为“官方 HITS 复现”。

所有 DeepSeek 接入、目标数据适配、编译、原程序执行和变异体评估代码位于 `Baselines/adapters/HITS`。这两个上游仓库必须保持下载时原样。
