# S12 MCP、设置、国际化与产品化验收记录

> 日期：2026-08-12
> 分支：`codex/s12-productization`
> 状态：本地自动化通过；国际化迁移、签名发布与副屏实机待最终收口

## 已验证实现

- 设置 schema v2：默认离线、MCP/写工具默认关闭、端口与白名单归一化、v1→v2 迁移、确定性非敏感导入导出、损坏/未来版本/重复键/敏感键整体拒绝。
- 回环 MCP：只绑定 `127.0.0.1`，随机内存令牌、Host/Origin/Bearer、会话、方法、Content-Type、1 MiB 请求体和 64 会话上限共同守门；关闭、换端口和项目关闭释放资源。
- 工具：Mapper、statement、参数、引用、数据源/schema 有界只读；CRUD、statement、JUnit 只形成预览，确认使用一次性短期 token、复核 TOCTOU，并复用单个 IDE Command、Undo 和失败恢复。
- 产品化：中英文资源键对称；设置、MCP、数据库界面、生成器及完整 SQL 工具链完成资源化，并由 `verifyLocalizedUserInterface` 阻止新增硬编码中文字符串；明暗 SVG 图标、Apache-2.0 LICENSE/NOTICE、隐私说明、第三方清单、发布升级/回滚说明已加入。
- 发布链：MCP `serverInfo.version` 读取实际插件描述符；CycloneDX 1.6 SBOM 固定版本、许可证和 VCS，去除时间/随机/CI 字段；签名与 Marketplace 凭据只读 CI secret，缺失时发布失败但本地构建不受影响。

## 本地自动化证据

执行：

```bash
mvn --batch-mode --file samples/java-mybatis-minimal/pom.xml clean verify
mvn --batch-mode --file samples/semantic-corpus/pom.xml clean verify
./gradlew clean check verifyPluginProjectConfiguration verifyPluginStructure buildPlugin
go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.7 .github/workflows/*.yml
```

结果：

- Java + MyBatis 样例：6 tests，0 failure/error；语义语料各模块全部成功。
- IntelliJ Platform：634 tests，0 failure/error/skip；96 个测试套件。
- 整体行覆盖率：13768/16489，83.50%；S12 产品化核心：1065/1204，88.46%；均高于 70%/85% 阻断线。
- Checkstyle main/test、插件项目配置、插件结构、Action 工作流语法全部通过；Plugin Verifier 1.409 对最低 IntelliJ IDEA 2026.1 GA（IU-261.22158.277）结论为 `Compatible`，并判定插件大概率可动态启停。
- ZIP：`af350f30cf539079d24e8affcb9abce7d57c3bc5cc096cd5d017c67f1aa679c3`；包内只有插件 JAR，JAR 含 LICENSE、NOTICE、明暗图标和中英文资源，不含独立第三方运行时 JAR。
- CycloneDX JSON：`e16f1b2b86b2c0ea5dd1c08cc15a41820ebe3057c336d2a4dc12df75ff951903`；XML：`b32e0ec1b0058caba09147d310e5ebcd4a1dd04f47c7ca7c7c72aae0b3abe906`。连续两次 `--rerun-tasks` 输出哈希一致。

## 仍未验收

- SQL 工具链的格式化、日志还原、受控执行、DDL/SELECT/Java 转换、注解迁移和测试骨架，以及生成器的校验、冲突、回滚与候选代码注释已完成资源化；统一语义、方法名 SQL、重构、动态 SQL、OGNL 等历史核心诊断仍可能透传固定中文，仍需继续迁移，不得提前宣称全产品英文错误文案完成。
- 当前未提供 `CERTIFICATE_CHAIN`、`PRIVATE_KEY`、`PRIVATE_KEY_PASSWORD` 和 `PUBLISH_TOKEN`，因此只验证了失败关闭配置，没有生成真实签名包或发布 Marketplace。
- 按用户要求不占用主屏；当前无副屏，未运行 `runIde`、IDE 生命周期脚本、真实 MCP 客户端、设置页、安装、升级、降级、禁用和卸载验收。
- 本批仍需运行远端 CI 和兼容矩阵；这些证据未写入本记录前，S12 保持“部分完成”。
