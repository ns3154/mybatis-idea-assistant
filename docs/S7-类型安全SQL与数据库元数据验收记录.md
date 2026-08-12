# S7 类型安全 SQL 与数据库元数据验收记录

> 日期：2026-08-12
> 当前结论：代码与平台自动化开发完成；最终阶段验收尚未关闭

## 已实现范围

- 消费 S5 符号程序生成单条有界代表 SQL：`#{}` 转参数标记、`${}` 转显式动态标识符，if/foreach 只保留一次代表路径，choose 只选有序首分支并记录折叠诊断；
- 通过可选 Database Tools 描述符注册 SQL PSI 服务、MultiHostInjector、XML 宿主补全和 schema Inspection；数据库插件禁用时核心描述符不引用这些实现类；
- 根据 statement `databaseId` 或唯一 READY 元数据方言选择 MySQL、PostgreSQL、Oracle、SQL Server 或 Generic SQL；SQLite/H2 使用 Generic 兼容解析；多方言保持 Generic；
- 使用与 Database Tools 类型解耦的不可变数据源、schema、表、列、主外键、JDBC 类型和新鲜度模型；提供方统一在后台执行，可取消、可超时；
- Database Tools 适配器只读已加载模型，不连接、刷新、执行 SQL 或索取凭证；模型变化立即失效快照，旧后台结果不能回写；
- 提供公共 SQL 关键字、常用函数、statement 别名和 READY 表列补全；无元数据时关键字、函数与别名仍可用；
- 对唯一解析、精确回映射且快照仍匹配当前 Database Tools 数据源的表列建立软引用，支持导航和数据库 PSI Find Usages；动态、歧义、LOADING、过期或目标失效时静默；
- 只报告可证明的不存在/歧义表列；单表目标唯一时补充 ResultMap 缺列和基础 Java/JDBC 类型不匹配；动态标识符、复合 column、多表、加载中、语法不完整、Dumb Mode 和失效源保持静默；
- 提供默认关闭的危险写 Inspection，仅在直接 update/delete 的确定代表 SQL 中可证明没有 WHERE 时提示；字符串、引号标识符、注释、动态标识符和不完整结构不会造成确定报告；
- SQL 诊断范围通过 S5 source map 回到原 XML；动态标签后的表列仍保持精确范围；完整和未闭合参数占位符不抢占原 XML 参数引用。

## 当前自动化证据

当前代码已通过：

```bash
./gradlew clean check verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin buildPlugin
mvn --batch-mode --file samples/java-mybatis-minimal/pom.xml clean verify
mvn --batch-mode --file samples/semantic-corpus/pom.xml clean verify
```

结果：

- 386/386 平台测试通过，失败、错误和跳过均为 0；
- 整体行覆盖率 6937/7778，即 89.19%；
- S7 核心 `sql`、`sql.intellij`、`database` 与 `MyBatisSqlSchemaInspection` 合计 837/915 行，即 91.48%，并已纳入不低于 85% 的 Gradle 硬门；
- 可选 `database.intellij` 适配层 78/99 行，即 78.79%，已纳入不低于 70% 的独立硬门；
- 1000 个 choose 分支只生成一个代表分支；1000 表 READY 快照执行 100 个热分析样本，P95 小于 150ms；
- Java 最小样例 4/4 通过；语义语料六个 reactor 模块及 6 个契约测试通过；
- Checkstyle、覆盖率、项目配置、插件结构和打包全部通过；Plugin Verifier 对最低 `IU-261.22158.277` 判定 `Compatible`，并判定插件可动态启停；
- 当前 `0.1.0-SNAPSHOT` ZIP 大小为 684971 字节，SHA-256 为 `e7400a2b3eecf2b16fd857d76d6cce3bb64321a672d4b353cab039a4628959b2`。

## 关键保守边界

- 代表 SQL 只用于编辑器分析，不代表运行期全分支结果，不执行 OGNL，也不展开集合；
- 编辑器补全与 Inspection 只消费已经完成的快照，绝不 `join` 后台任务或在 EDT 等待数据库；
- 数据源、schema 或表目标不唯一时不猜测；ResultMap 只在被 statement 引用且所有表引用收敛到同一物理表时检查；
- SQL 有解析错误、参数占位符未闭合、`${}` 动态表列或元数据仍为 LOADING 时不制造二次误报；
- 可选数据库实现仅由 `io.github.ns3154.mybatisassistant-withDatabase.xml` 注册；核心元数据接口不暴露 JetBrains 数据库类型。

## 尚未关闭的验收项

- 当前 S7 产物的 20/20 沙箱生命周期；
- 当前 S7 产物的 5/5 可选依赖隔离，重点验证禁用 `com.intellij.database` 后核心导航、引用、OGNL 和非数据库检查仍正常加载；
- 检测到真实副屏后，只在副屏运行 IDEA 2026.1，人工验证动态 SQL 注入、方言、表列/函数/别名补全、表列与 ResultMap 警告刷新及数据库模型变化失效；
- 提交并推送 S7 分支后的远端 CI 证据。

当前系统未检测到可用副屏，因此本批没有启动 IDEA 或 Computer Use，也没有运行会启动真实 IDE 的生命周期与依赖隔离脚本。以上门禁全部通过前，S7 状态保持“开发完成，待最终统一验收”，不得写成“已验收”。
