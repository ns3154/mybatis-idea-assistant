# S11 Spring、注解、Kotlin、框架与数据库扩展验收记录

> 日期：2026-08-12
> 当前结论：注解 SQL、Spring 注入与 Kotlin K2 编辑器切面代码和自动化已完成；S11 全阶段尚未完成

## 已实现范围

- 在 Java MyBatis 直接 SQL 注解内为 `#{}`/`${}` 建立根参数和嵌套属性引用，接入已注入 SQL 的补全，并提供默认开启、仅报告可证明缺失路径的 Inspection；
- 支持 `@Select/@Insert/@Update/@Delete` 直接注解及其 List 容器的参数语义；无关注解、动态 Map、Dumb Mode、索引竞态和取消按保守边界处理；
- 提供“迁移注解 SQL 到 XML”动作：只处理单个直接内联注解和唯一 Mapper XML，按 MyBatis 字符串数组空格拼接语义生成 statement，并保守推导 select `resultType`；
- 迁移对重载、重复注解容器、附加 MyBatis 方法注解、动态 `<script>`、已有 statement、多 XML、未知泛型、只读和项目外目标拒绝；完整展示两个文件前后文本，以单个命令原子更新并支持一次 Undo；
- Spring 可选描述符注册注入 gutter，支持 `@Autowired`、javax/jakarta `@Resource` 与 `@Inject` 的字段、方法和构造器参数；只对统一模型确认的 Mapper 建立到 XML 的精确关系，多 XML 候选全部保留；
- Spring 注入代码仅依赖 Java PSI，不把 Spring API 类型暴露到核心描述符；Dumb Mode 守门在注解限定名解析之前，smart→dumb 竞态降级静默。
- Kotlin 可选描述符注册 K2 line marker、注解参数引用和两类 Inspection；Kotlin 函数可导航到 XML/直接 SQL 注解，XML 可反向回到 Kotlin 源函数，多 XML 候选全部保留；
- Kotlin 直接 SQL 注解支持根参数、默认参数、可空参数、data class 嵌套属性、转义 `${}` 和引用变体补全；动态 Map 与真实 Kotlin 字符串插值保持静默；
- Kotlin 缺失 statement 检查只消费唯一 light method 的类型化结果；重载、带函数体和注解 SQL 不误报。Kotlin gutter 使用非合并标准标记并复用多目标导航处理器，避免与 Kotlin 自带继承标记发生非对称合并。

## 当前自动化证据

- 590 个 IntelliJ 平台测试通过，失败、错误和跳过均为 0；
- 整体行覆盖率 11985/14238（84.18%）；S11 注解/Spring/Kotlin 核心 765/887（86.25%），高于 85% 硬门；
- Spring 注入导航新增 7 个注册级/解析级测试；注解迁移包含四类语义、失败边界、TOCTOU、原子写入、一次 Undo、取消和动作注册测试；
- Kotlin K2 新增 12 个平台测试，覆盖真实扩展注册、正反向导航、注解目标、多 XML 候选、默认/可空参数、data class、转义占位符、补全、精确检查、动态 Map、Dumb Mode 与取消传播；
- `check`、插件项目配置、插件结构和最低 IDEA 2026.1 GA Plugin Verifier 在当前 Kotlin 代码上通过，Verifier 结论为 `Compatible`；
- Java + MyBatis + H2 最小样例 6/6 通过；语义语料 Spring 2/2、Kotlin K2 1/1、框架 2/2、六方言 2/2 通过；
- Spring/YAML bundled plugin 已进入 Gradle 依赖和锁文件，运行时仍由 optional depends 控制。
- 当前 Kotlin ZIP 为 1,233,950 字节，SHA-256 为 `a461226313a806de39801083cd970dabaefcc9c674b8ae005434d7294e6781df`。
- GitHub Actions run `31550322961` 在 6 分 39 秒内通过两个 Maven 语料、完整 Gradle 门、ZIP 与报告上传。

## 尚未关闭的验收项

- 当前 Kotlin 分支远端 CI 最终结果；
- 当前产物 20/20 生命周期与 Kotlin、Spring、YAML、Database Tools、全部禁用共 5/5 可选依赖隔离；
- 在真实副屏 IDEA 2026.1 验证注解参数补全/检查、双文件迁移预览与一次 Undo、Spring 字段和构造器参数 gutter；
- S11-E Plus/Flex/TkMapper 模型适配、S11-F 六数据库/Community JDBC/Android Studio 降级尚未开发。

没有可用副屏时不启动 IDEA、`runIde`、生命周期脚本或 Computer Use。上述项目关闭前，S11 只能标记“部分完成”。
