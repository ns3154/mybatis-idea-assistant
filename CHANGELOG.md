# 变更记录

## 未发布

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
