# S12 MCP、设置、国际化与产品化验收记录

> 日期：2026-08-12
> 分支：`codex/s12-productization`
> 状态：原 S12 自动门保留；发布链加固已通过本地统一门与 `bb4a93c` 远端必需检查，最终 `main` SHA、真实签名、Marketplace 和副屏实机待收口

## 已验证实现

- 设置 schema v2：默认离线、MCP/写工具默认关闭、端口与白名单归一化、v1→v2 迁移、确定性非敏感导入导出、损坏/未来版本/重复键/敏感键整体拒绝。
- 回环 MCP：只绑定 `127.0.0.1`，随机内存令牌、Host/Origin/Bearer、会话、方法、Content-Type、1 MiB 请求体和 64 会话上限共同守门；关闭、换端口和项目关闭释放资源。
- 工具：Mapper、statement、参数、引用、数据源/schema 有界只读；CRUD、statement、JUnit 只形成预览，确认使用一次性短期 token、复核 TOCTOU，并复用单个 IDE Command、Undo 和失败恢复。
- 产品化：中英文资源键对称；全部生产 Java 源码完成资源化，并由 `verifyLocalizedUserInterface` 全目录阻止新增硬编码中文字符串；明暗 SVG 图标、Apache-2.0 LICENSE/NOTICE、隐私说明、第三方清单、发布升级/回滚说明已加入。
- 发布链：MCP `serverInfo.version` 读取实际插件描述符；CycloneDX 1.6 SBOM 固定版本、许可证和 VCS，去除时间/随机/CI 字段；签名与 Marketplace 凭据只读 CI secret，缺失时发布失败但本地构建不受影响。
- 发布链增量：严格校验标签/版本/渠道和 CHANGELOG；以候选 SHA 复核必需检查；签名现有精确 ZIP 后验证签名、归档身份、SHA-256 和来源证明；先创建 GitHub 草稿 Release，再由独立 Marketplace 工作流发布同一个已签名 ZIP，成功复核后才公开 GitHub Release。

## 原 S12 自动化证据快照

本节 634 个测试、覆盖率、ZIP、SBOM 和远端运行属于发布链加固前的阶段快照，不得当作当前候选的数字。当前候选的统一本地证据单独记录于下节；`bb4a93c` 的统一 CI、三系统、兼容矩阵和 CodeQL 已通过，最终 `main` SHA 仍待。

执行：

```bash
mvn --batch-mode --file samples/java-mybatis-minimal/pom.xml clean verify
mvn --batch-mode --file samples/semantic-corpus/pom.xml clean verify
./gradlew clean check verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.7 .github/workflows/*.yml
```

结果：

- Java + MyBatis 样例：6 tests，0 failure/error；语义语料各模块全部成功。
- IntelliJ Platform：634 tests，0 failure/error/skip；96 个测试套件。
- 整体行覆盖率：13841/16581，83.48%；S12 产品化核心：1069/1211，88.27%；均高于 70%/85% 阻断线。
- Checkstyle main/test、插件项目配置、插件结构、Action 工作流语法全部通过；Plugin Verifier 1.409 对最低 IntelliJ IDEA 2026.1 GA（IU-261.22158.277）、当前 2026.1.4（IU-261.26222.65）和下一稳定版 2026.2（IU-262.8665.258）均为纯 `Compatible`，无内部 API/计划移除 API，并判定插件大概率可动态启停。
- 2026.2 首轮门禁发现 `PluginManagerCore` 内部 API 与 `SimpleListCellRenderer.create` 计划移除 API；已分别改为构建期版本资源和公开 renderer 子类，复跑后两项均归零。
- ZIP：`3fc63e37b3b57158a6abfb699911d2d1d487aae5953cd6f94183ce4200c16a04`；包内只有插件 JAR，JAR 含 LICENSE、NOTICE、明暗图标、中英文资源和与 Gradle 版本同源的 MCP 版本资源，不含独立第三方运行时 JAR。
- CycloneDX JSON：`e16f1b2b86b2c0ea5dd1c08cc15a41820ebe3057c336d2a4dc12df75ff951903`；XML：`b32e0ec1b0058caba09147d310e5ebcd4a1dd04f47c7ca7c7c72aae0b3abe906`。连续两次 `--rerun-tasks` 输出哈希一致。
- GitHub Actions [持续集成 #31566640300](https://github.com/ns3154/mybatis-idea-assistant/actions/runs/31566640300) 全绿，远端重新执行双 Maven 语料、634 个平台测试、覆盖率、项目/插件结构、最低 2026.1 GA Verifier，并上传 ZIP 与验证报告。
- GitHub Actions [兼容矩阵 #31566645932](https://github.com/ns3154/mybatis-idea-assistant/actions/runs/31566645932) 的 IntelliJ IDEA 2026.1、2026.2 与 Android Studio 2026.1.2.10 三个作业全部通过。

## 2026-08-19 当前候选本地证据

- 禁用构建缓存并强制重跑 `./gradlew clean check verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin` 成功（`BUILD SUCCESSFUL`，2m33s）；103 个测试套件中的 744/744 平台测试通过，失败、错误和跳过均为 0。
- 整体行覆盖率为 15389/18234（84.40%）；各分区覆盖率、Checkstyle、SBOM、`verifyLocalizedUserInterface`、项目与插件结构均通过。
- Java/MyBatis 样例 8/8、语义语料 11/11、Gradle Spring 四模块 2/2 及 `bootJar` 通过；最低 `IU-252.28539.54` Verifier 为 `Compatible`。
- 临时密钥签名、验签、篡改拒绝、归档身份、加固生命周期 1/1、可选依赖隔离 5/5 以及发布静态契约均通过；本地候选 ZIP SHA-256 为 `57b1d53bd1a8f359b3d41206408e78dbb04c7150619f3b936f756a6ce5697e12`。
- 这些签名证据使用临时密钥，只验证失败关闭与制品契约，不代表生产证书、Environment 审批或 Marketplace 发布已完成。

## 仍未验收

- GitHub 已配置 `release-signing`、`release-github`、`marketplace-preview`、`marketplace-production` 四个受保护环境、`v*` 标签规则和 immutable releases；这些是治理配置，不等同于已发布。
- 当前 Environment 尚未配置 `CERTIFICATE_CHAIN`、`PRIVATE_KEY`、`PRIVATE_KEY_PASSWORD` 和 `PUBLISH_TOKEN`，因此没有生成真实密钥签名包或发布 Marketplace。签名三项应只写入 `release-signing`；`PUBLISH_TOKEN` 应分别写入需要使用的 Marketplace 环境，禁止粘贴到对话或提交到仓库。
- Marketplace 首次上传仍需人工创建/初始化条目并决定是否 `Hidden`。首次人工包必须使用不会被后续自动化复用的唯一版本；同一版本不能覆盖重传。`Hidden` 只能在首次上传时选择，公开后不能重新隐藏，因此上线决定需单独审批。
- 正式发布运行时仍需维护者批准签名、GitHub Release 和对应 Marketplace 环境；环境批准、真实签名验证、来源证明与 Marketplace 接收结果尚无证据。
- 按用户要求不占用主屏；当前无副屏，未运行 `runIde`、IDE 生命周期脚本、真实 MCP 客户端、设置页、安装、升级、降级、禁用和卸载验收。
