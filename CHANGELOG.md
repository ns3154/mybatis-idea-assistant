# 变更记录

## 未发布

- 暂无。

## [0.1.0-alpha.2] - 2026-08-23

### 修复

- 修复签名发布工作流在完整质量门执行 `clean` 后丢失发布说明、无法固化未签名候选的问题。

## [0.1.0-alpha.1] - 2026-08-20

### 新增

- 创建面向 IntelliJ IDEA 2026.1 的现代插件工程。
- 建立 MyBatis XML statement 增量索引。
- 支持 Java Mapper 方法到 XML statement 的 gutter 导航。
- 支持 XML statement 到 Java Mapper 方法的反向 gutter 导航与重载候选。
- 增加类型化解析结果和 namespace marker 索引，索引版本升级到 2。
- 支持多候选选择、Dumb Mode 降级、索引竞态处理、智能指针生命周期和查询取消。
- 增加缺失 XML statement 的保守 Inspection，并排除无 Mapper XML、重载、默认/静态方法及注解 SQL 场景。
- 为 gutter 与 Inspection 共享类型化解析缓存，加入索引查询预算和未保存 XML 修改失效回归。
- 加入 Java + MyBatis + H2 样例、平台测试、CI 与 Plugin Verifier。
- 加入最低 2026.1 GA 阻断验证、2026.2 定时兼容矩阵、Dependabot 与安全报告入口。
- 增加 schema v2 设置迁移、确定性非敏感导入导出、恢复默认和中英文界面语言覆盖。
- 增加默认关闭的回环 MCP 服务、随机内存令牌、严格传输校验、有界只读工具与 preview→confirm 受控写工具。
- 增加明暗插件图标、隐私说明、NOTICE、第三方组件清单和发布升级/回滚手册。
- 增加可复现 CycloneDX 1.6 SBOM、CI secret 注入的插件签名和显式 Marketplace 发布工作流。
- 将最低编译 SDK 下调到 IntelliJ IDEA 2025.2.6.2，并建立 2025.2、2025.3、2026.1、2026.2 四个稳定大版本兼容矩阵。
- 增加 Linux、macOS、Windows 三系统无界面回归，以及 100 万行、2000 Mapper、10000 statement 性能压力门。
- 修复 MCP statement 预览在 Undo 后可能读取陈旧 PSI 范围的问题，并增加 CodeQL Java/Kotlin 安全扫描。
- 修复 JDBC 与 MCP 测试夹具写死 Unix 绝对路径导致 Windows 回归失败的问题。
- 为 ResultMap 增加直接 `column` 补全；仅在 READY 元数据、唯一单表目标和可写 Java 属性均可证明时，为缺失映射提供保守 Quick Fix。
- 为动态 SQL 增加 `v1` 版本化黄金文件，固定普通文本、动态标签、include/property 与字符级 source map 的代表行为。
- 明确方法名批量生成采用 set-based SQL，而不是 JDBC `ExecutorType.BATCH`：支持 `insertBatch(Collection<Entity>)` 单条 set-based 插入 statement（Oracle 为 `INSERT ALL`）与集合 `IN/NOT IN`；集合输入失败关闭，并拒绝所有谓词均可省略的 update/delete。
- 增加 Java 21 + Spring Boot 4.1.0 四模块 Gradle 样例、五类数据库版本化 SQL 文本夹具，以及 1024 表、1024 Mapper、5120 statement 的确定性生成夹具。
- 加固生命周期与可选依赖隔离脚本：执行真实 Inspection，校验 MCP 回环/鉴权/端口释放、精确进程残留、项目零改写及版本化严重日志白名单；历史 100/100 仅保留为旧脚本基线。
- 拆分 GitHub 草稿 Release 与 Marketplace 发布链，增加候选 SHA、标签、必需检查、制品身份、签名、校验和与来源证明守门；发布环境、标签保护和 immutable releases 已配置，真实 secret、环境审批与首次 Marketplace 人工上传仍待执行。
