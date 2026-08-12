# 第三方组件与 SBOM

## 发布包边界

正式插件 ZIP 只应包含本项目编译产物、资源和 Apache-2.0 的许可证/NOTICE。IntelliJ Platform、Java、Database Tools、Kotlin、Spring、YAML、Gson、MyBatis、H2、JUnit、JDBC 驱动和构建工具均不应被复制进插件 ZIP。

运行期使用的 IntelliJ Platform API 与 Gson 由目标 IDE 提供；Java、Database Tools、Kotlin、Spring 和 YAML 插件是宿主依赖或可选宿主依赖。用户配置的 JDBC 驱动从用户指定位置加载，插件不下载、不重分发，用户需要自行确认驱动许可证与组织合规要求。

## 源码仓库使用的第三方组件

| 类别 | 组件 | 用途 | 是否进入插件 ZIP |
|---|---|---|---|
| 宿主平台 | IntelliJ Platform 2026.1.4 与内置插件 API | 编译、平台测试和运行宿主 | 否 |
| 构建 | Gradle Wrapper 9.3、IntelliJ Platform Gradle Plugin 2.18.1 | 编译、验证、打包、签名和发布 | 否 |
| 构建审计 | CycloneDX Gradle Plugin 3.4.1 | 生成 CycloneDX 1.6 SBOM | 否 |
| 质量 | Checkstyle 13.10.0、JaCoCo 0.8.15 | 静态检查与覆盖率 | 否 |
| 测试 | JUnit 4.13.2、H2 2.3.232 | 平台测试与数据库测试 | 否 |
| 样例 | MyBatis、H2、JUnit 等样例 Maven 依赖 | 验证语料与演示 | 否 |
| 用户提供 | 各数据库 JDBC 驱动 | Community 环境按需读取元数据 | 否 |

准确版本以 `settings.gradle.kts`、`build.gradle.kts`、两个样例的 `pom.xml`、依赖锁文件和当次 SBOM 为准；本表是便于人工审阅的摘要，不替代许可证原文。

## 生成与验证 SBOM

```bash
./gradlew verifyCyclonedxBom
```

输出位于：

- `build/reports/cyclonedx/bom.json`
- `build/reports/cyclonedx/bom.xml`

SBOM 使用 CycloneDX 1.6，主组件声明 Apache-2.0。任务只纳入插件的 `runtimeClasspath`，排除 IDE SDK、构建环境和测试依赖；当前运行时配置没有独立捆绑依赖，因此组件列表为空是预期结果。

为保证同一源码、版本和依赖锁的输出可复现，构建关闭随机 BOM 序列号、CI 构建地址，并在生成后移除规范允许省略的当前时间字段。`verifyCyclonedxBom` 还会阻止错误版本、缺失许可证、随机字段和发布密钥字段进入报告。

发布前必须同时检查：

1. 连续两次强制生成的 JSON/XML SHA-256 完全一致；
2. ZIP 内容没有宿主平台、测试库、JDBC 驱动、私钥、访问令牌或密码；
3. 新增的运行时依赖已更新本清单，并逐项核对许可证、NOTICE、漏洞公告和重分发条件；
4. SBOM 与签名 ZIP、SHA-256 校验文件一起保存为发布证据。

本项目未复制第三方商业插件实现。若后续引入需要 NOTICE 或源码披露的组件，必须在合并前更新 `NOTICE`、本文件和自动化门禁。
