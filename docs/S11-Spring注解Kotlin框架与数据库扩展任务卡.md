# S11 Spring、注解、Kotlin、框架与数据库扩展任务卡

> 阶段：S11 兼容矩阵扩展
> 日期：2026-08-12
> 状态：六个代码切面与 IDEA/Android Studio 远端自动门均已完成；可选依赖隔离和副屏实机待收口

## 目标

在不污染核心类加载、不扩大模糊推断范围的前提下，把既有统一 Mapper 模型扩展到 Spring 注入、MyBatis 注解 SQL、Kotlin K2、MyBatis-Plus/Flex/TkMapper 和完整数据库兼容矩阵。所有可选平台能力必须只在对应描述符中注册；无法证明语义或迁移安全时必须明确拒绝。

## 固定边界

- 注解 SQL 参数只识别 `#{}`/`${}`，复用 XML 参数路径模型；动态 Map 键保持未知，不制造缺失属性误报；
- 注解 SQL 迁移只接受一个直接声明的 `@Select/@Insert/@Update/@Delete`，且目标 namespace 只能有一个可写 XML；重载、重复注解容器、Provider、`<script>`、附加 MyBatis 方法注解和不可靠返回泛型全部拒绝；
- 迁移前展示 Java/XML 两个文件的完整前后文本；执行时复用单个 IDE Command、TOCTOU 复核和一次 Undo，任何文件变化都整批停止；
- Spring 注入导航只接受 `@Autowired`、`@Resource`、`@Inject` 的显式注入点和已由统一模型确认的 Mapper；不把 `@Qualifier`、普通字段、普通类或无 Mapper 证据接口当作注入关系；
- Spring 扩展只在 Spring 可选描述符中注册；核心描述符和核心 API 不引用 Spring 类型；
- 达梦必须使用独立方言标识，不得静默回退 Generic；标识符、IDENTITY、limit/page 和 Join 使用已验证语义；
- Community JDBC 只接受用户显式启用的数据源、方言、URL、驱动类和绝对 JAR 路径，不扫描项目、不下载驱动、不主动测试连接；
- JDBC 非敏感配置只写项目私有 workspace，密码只通过 PasswordSafe 读写且按项目与数据源隔离；密码读写、连接和 DatabaseMetaData 遍历都不得发生在 EDT；
- Android Studio 只要求平台、Java 与 XML 核心模块；Database Tools、Spring、Kotlin 和 YAML 继续保持可选描述符隔离；
- Dumb Mode、索引竞态、项目关闭、失效 PSI 和取消均按平台控制流静默或向上传播，不重试、不扫描项目；
- 没有真实副屏时不启动 IDEA、`runIde` 或 Computer Use，自动测试不能冒充实机验收。

## 分批实施

| 批次 | 内容 | 当前状态 | 退出证据 |
|---|---|---|---|
| S11-A | 注解 SQL 参数引用、补全与检查 | 代码与自动化完成 | 根/嵌套参数、List、Map、无关注解、Dumb/PCE、真实 Inspection 与 SQL 补全测试 |
| S11-B | 注解 SQL 到 XML 的安全迁移 | 代码与自动化完成，待副屏 | 四类注解、数组拼接、转义/resultType、冲突拒绝、TOCTOU、双文件一次 Undo、动作注册测试 |
| S11-C | Spring 显式注入关系 | 代码与自动化完成，待隔离和副屏 | 真实可选描述符 gutter、字段/构造器/参数、多 XML 候选、误报静默、Dumb/PCE 测试 |
| S11-D | Kotlin K2 编辑器能力 | 代码与自动化完成，待隔离和副屏 | XML/注解双向导航、参数引用/补全、缺失参数/statement 检查、默认/可空参数、data class、多目标、Dumb/PCE 测试 |
| S11-E | Plus/Flex/TkMapper 统一模型适配 | 代码与本地/远端自动门完成 | 锁定基类 FQN/真实版本契约、Java/Kotlin 实体推导、内建/自定义方法隔离、Wrapper 绑定与不支持范围拒绝 |
| S11-F | 六数据库、Community JDBC 与 Android Studio | 代码与本地/远端自动门完成，待实机 | 达梦方言、真实 H2 JDBC、PasswordSafe、无 Database/Spring 核心描述符与 Android Studio Verifier 矩阵 |

## 当前切面验收矩阵

| 编号 | 场景 | 必须输出 | 保守边界 |
|---|---|---|---|
| S11-01 | 注解 SQL 参数 | 根参数和嵌套属性引用、导航、补全与精确警告 | Map 动态键、无关注解、Dumb/竞态时不误报 |
| S11-02 | 直接内联 SQL 迁移 | Java 移除注解，唯一 XML 新增语义等价 statement | Provider、List、附加属性/注解、重载、动态 script 和多 XML 零写入 |
| S11-03 | 双文件安全写入 | 完整预览、TOCTOU、单命令和一次 Undo | 任一文件变化、只读、项目外目标或失效 PSI 整批停止 |
| S11-04 | Spring 注入导航 | 显式注入变量到精确 namespace XML；多目标全部保留 | `@Qualifier` 单独存在、普通字段/类型、无 Mapper 证据、Dumb Mode 静默 |
| S11-05 | 可选依赖隔离 | 禁用 Spring 后主插件继续加载且核心能力可用 | Spring 扩展类不得从核心描述符或核心公开类型加载 |
| S11-06 | 当前批质量门 | 平台测试、85% S11 核心覆盖率、Checkstyle、结构、261 Verifier、ZIP 与双 Maven 语料全绿 | 生命周期、隔离和副屏未完成前不得把整个 S11 标为已验收 |
| S11-07 | Kotlin K2 编辑器 | Kotlin 函数与 XML/注解精确导航，注解参数根/嵌套属性引用和补全，缺失路径/statement 精确警告 | 动态 Map、运行期字符串插值、重载、带函数体、Dumb/竞态时静默；多 XML 全部保留 |
| S11-08 | Plus/Flex/TkMapper 统一模型 | Java/Kotlin Mapper 形成框架、具体实体、基类方法签名的类型化绑定；自定义方法继续消费 XML 模型 | 原始/未解析实体泛型、跨框架混用、多基类候选明确拒绝；TkMapper 不伪造 Wrapper 支持；版本只接受用户显式值 |
| S11-09 | 六数据库与 Community JDBC | 达梦使用独立方言；显式 JDBC 配置可形成表、列、键、注释和类型快照；密码只在 PasswordSafe | Generic/未知方言拒绝；缺密码、禁用或未选数据源静默；坏驱动类型化失败；取消传播；不在 EDT 连接 |
| S11-10 | Android Studio 安全降级 | 核心描述符只依赖平台、Java、XML；无 Database/Spring 时 Community 元数据和核心编辑能力仍可加载 | 可选实现不进入核心描述符；Android Studio 2026.1.2 稳定版进入定时/手动 Verifier；真实安装仍需副屏 |

## 统一门禁

- S11 注解、Spring、Kotlin 与框架模型核心类合并行覆盖率不得低于 85%，整体覆盖率不得低于 70%；
- 最低 IDEA 2026.1 GA Plugin Verifier、两个 Maven 样例和插件 ZIP 必须基于当前代码重新生成；
- Spring/YAML 新增为测试编译期 bundled plugin 后必须更新依赖锁，且与 `plugin.xml` 的 optional depends 保持一致；
- 当前产物仍需 20/20 生命周期、5/5 可选依赖隔离，以及副屏真实注解补全/迁移预览/Undo和 Spring gutter 验收；
- 当前产物 5/5 隔离、生命周期和副屏实机未完成前，S11 状态保持“部分完成”，不得写成“已验收”。
