# MyBatis Assistant

面向 IntelliJ IDEA 的 MyBatis 智能开发助手，采用独立实现路线开发。

当前版本为 `0.1.0-alpha.1` 内部候选。S0～S12 的功能链已合入 `main`，S13 的无界面自动化与正式发布链路已经建立。`main` 集成基线 `37bedad9` 在 2026-08-20 通过主 CI、CodeQL、三系统、五宿主、加固生命周期 1/1、可选依赖隔离 5/5 与生命周期 100/100，13 个发布必需检查均属于同一 SHA；该基线包含 105 个测试套件、760/760 平台测试、15389/18234（84.40%）整体行覆盖率、最低 `IU-252.28539.54` Verifier `Compatible`，CI ZIP SHA-256 为 `72fd5d7a3d514bed6ca21e418529fd62523010b148537c85ca2a9f58aa48e7ad`。任何后续 Alpha 提交仍须在其精确 `main` SHA 上重新通过同一组门。副屏/独立测试机实机、真实数据库、生产签名、Marketplace 首次初始化及升降级属于独立交付证据；取得前不把 S13 或 1.0 标记为已验收。

本轮 `alpha.1` 已在本地通过 760/760 平台测试、84.40% 整体行覆盖率、三类样例、结构/本地化/SBOM、最低 IDE Verifier 以及临时签名、验签和篡改拒绝；本地 ZIP SHA-256 为 `0143c7b234d4c12c3955adbcfd8d62d5f8a28dc282d7122c7e0d0a46e5fd779f`。这些是本地候选证据，不替代合入后精确 `main` SHA 的远端门。

- 识别 Java Mapper 接口；
- 识别 MyBatis XML 的 `namespace`；
- 为 `select`、`insert`、`update`、`delete` 建立 statement 索引；
- 从 Java Mapper 方法导航到 XML statement，重复 statement 交由平台展示候选；
- 从 XML statement 导航回精确全限定名对应的 Java 接口方法，Java 重载全部保留为候选；
- 当精确 namespace 的 Mapper XML 已存在但同名 statement 缺失时，在 Java 方法名提供保守警告；
- 类型化区分无 Mapper XML、statement 缺失、多候选、索引未就绪、源失效和不支持的源，不做猜测跳转。
- 统一索引 Mapper namespace、四类 statement、`resultMap` 与 SQL fragment，重复声明保留全部候选；
- 识别 Java/Kotlin K2 Mapper、继承泛型方法、参数、返回实体、注解 SQL、`@Mapper` 与 `@MapperScan` 来源；
- 为 Kotlin K2 Mapper 提供 XML/注解双向导航、注解参数引用与补全、缺失参数/statement 保守检查；
- 通过锁定基类 FQN 识别 MyBatis-Plus、MyBatis-Flex 与 TkMapper，推导唯一具体实体，隔离框架内建方法与自定义 XML 方法，并把 Plus/Flex 绑定安全传递给 Wrapper 生成；
- 增量解析 MyBatis XML、Spring Boot/MyBatis-Plus 配置与 TypeAlias，并按模块依赖边界限制结果；
- 对未保存编辑、文件移动/删除、项目根变化、Dumb Mode 和取消请求做精确失效或保守降级。
- 为 XML `namespace`、statement `id`、`refid`、`resultMap`、`extends` 与 Java 注解 SQL/Provider 建立精确引用，支持查找使用；
- 支持继承 Mapper 方法、注解 SQL 与 Provider 方法的精确导航，唯一目标直达，多目标全部保留；
- 提供缺失 Mapper XML、重复 statement 等默认检查，并将高误报风险的 namespace、未使用 statement、缺失 `@Param` 检查默认关闭；
- 提供可预览、单次撤销、冲突停止的 Mapper XML、statement、`@Param` 修复；未使用 statement 仅提供定位，不自动删除代码。
- 按 MyBatis 参数命名规则解析 `#{}`、`${}`、`keyProperty`、`property` 与 `collection` 的根名、点路径和索引路径，对未知 Map 键与完整 OGNL 保守降级；
- 解析 ResultMap 的可写属性、嵌套类型、constructor/discriminator 分支与 TypeAlias，提供精确引用、查找使用、`property`/`column` 补全和低误报检查；当 READY 元数据、唯一单表目标和可写 Java 属性均可证明时，提供缺失映射的保守 Quick Fix，语义不完整或目标歧义时不写入；
- 使用 IntelliJ 原生 Rename 同步更新 statement、resultMap/SQL fragment、`@Param`、JavaBean 属性与稳定类型引用；遇到动态 OGNL、include、多目标、只读或不完整语义时在写入前停止。
- 将动态 statement 编译为不会组合爆炸的符号化 IR，覆盖 `if/choose/where/set/trim/foreach/bind/include`，并为普通文本、CDATA、entity 与 include property 建立双向字符级 source map；循环、重复目标、Dumb Mode、失效源与取消均保守停止。
- 为 `test`、`when/test` 与 `bind/value` 注入自有 OGNL 语言，提供可恢复语法树、高亮、保守类型推导、引用、补全、Find Usages、作用域内 Rename 和可证明错误检查；分析过程不执行项目代码。
- 把 S5 动态程序压成有界代表 SQL，并在 Database Tools 可用时接入真实 SQL PSI；`#{}`、`${}`、动态标签、entity 与跨片段位置均可回映射到原 XML；
- 只在后台读取 Database Tools 已加载模型，统一处理超时、取消、模型失效和多数据源；编辑器线程不连接数据库、不等待 I/O；
- 为唯一解析且仍与 Database Tools 当前模型一致的 Mapper XML 表名、列名建立软引用，支持导航和 Find Usages；动态、歧义、加载中、过期快照或无法精确回映射时不建立引用；
- 在无 Database Tools 的 Community/Android Studio 环境提供项目级 JDBC 元数据适配：驱动 JAR、URL、用户名和 schema 必须显式配置，密码只进入 PasswordSafe，连接与元数据读取只在后台按需发生；
- 提供 SQL 关键字、常用函数、别名、表列补全，以及不存在/歧义表列、ResultMap 缺列和基础 Java/JDBC 类型不匹配检查；另提供默认关闭的无 WHERE update/delete 保守检查；动态标识符、加载中、语法不完整和目标不唯一时保持静默。
- 从 Database Tools 已加载且由用户明确选择的表生成 Entity、Mapper、Mapper XML 和 Service；支持 Standard/MyBatis-Plus 模板、命名、字段过滤、注释、类型/TypeHandler、自增键与关键字转义；
- 所有数据库生成先形成全量计划，支持按文件选择、候选文本和原生差异；稳定生成区以 SHA-256 防止覆盖用户修改，区域外手写内容、未保存编辑和 LF/CRLF 均保留；
- 批量创建与更新由单个命名 IDE Command 承载，执行前复核 TOCTOU 并创建 Local History 标签；写入异常自动回滚，冲突或失败不留半成品，整批支持一次 Undo/Redo。
- 以确定性方法语法生成 Mapper 方法和静态/动态 XML，覆盖投影、条件、排序、聚合、Top/分页及六类数据库方言；`insertBatch(Collection<Entity>)` 生成一条 set-based 插入 statement（Oracle 为 `INSERT ALL`），null/空/含 null 元素在动态 SQL 绑定阶段失败；只有集合 `IN/NOT IN` 的对应输入进入 `1 = 0` 失败关闭。该能力不隐式启用 JDBC `ExecutorType.BATCH`，不自动分片，也不承诺任意集合大小、跨驱动原子性或零副作用。Database Tools 的方法生成和 Wrapper 预览共用可选条件选择模型：只允许可真实判空的 AND 条件，OR、无参和 primitive 标量/范围条件不可选，UPDATE/DELETE 必须保留至少一个必选谓词；取消选择在预览或写入前结束；
- 显式选择框架与版本后预览 MyBatis-Plus/Flex Wrapper 代码，不按类路径猜测，也不使用不安全 SQL 尾部拼接；`MyBatisWrapperGeneratedCompileFixtureTest` 与 Maven 样例逐字绑定当前生成器输出，Plus 3.5.17/Flex 1.11.8 真实编译运行 5/5，不外推其他版本；
- 从两张同数据源、已加载表中显式选择 FK↔PK、Join 类型和输出字段后预览 Join SQL，不按列名猜测业务关系。
- 从单条 CREATE TABLE、显式投影 SELECT 或 Java PSI 预览 Entity/Mapper/ResultMap/Java 行模型/六方言 DDL；类型未知时使用明确占位并要求确认，不执行项目代码；
- 本地还原 MyBatis `Preparing`/`Parameters` 日志，安全处理交错线程、null、JSON、枚举、时间和特殊字符；二进制、截断或错配时拒绝半成品；
- 幂等格式化 MyBatis XML，保留动态标签、CDATA、注释和换行风格；先预览，再以一个命令写入并支持 Undo；
- 通过显式参数面板受控执行单条 SQL：只读事务回滚，危险 SQL 两次确认后提交，结果有界、可取消且日志对象脱敏；数据库能力仅在 Database Tools 可选依赖存在时启用；
- 从当前 Mapper 抽象方法生成 JUnit 5/Jupiter 或 JUnit 4 只读测试骨架，不写文件、不连接数据库。
- 提供 schema v2 的非敏感设置迁移、确定性导入导出、恢复默认和中英文界面语言覆盖；损坏、未来版本和敏感键整体拒绝。
- 提供默认关闭、只绑定 `127.0.0.1` 的项目级 MCP；随机内存令牌、会话、Host/Origin、请求体上限和白名单共同守门，写工具仍须 preview→confirm 并支持 Undo/失败回滚。
- 生成可复现 CycloneDX 1.6 SBOM；正式发布只从 CI secret 注入签名材料和 Marketplace token，仓库不保存私钥或运行期凭据。

当前候选包含三类验证资产：S5 `v1` 版本化动态 SQL 黄金文件；Java 21 + Spring Boot 4.1.0 的四模块 Gradle 样例；以及包含五类数据库版本文本夹具、1024 张表、1024 个 Mapper 和 5120 个 statement 的确定性合成物理文件树夹具。本地统一证据还包括 Java/MyBatis 样例 8/8、语义语料 11/11、Gradle Spring 四模块 2/2 及 `bootJar`，临时密钥签名/验签/篡改拒绝/归档身份，以及加固生命周期 1/1、可选依赖隔离 5/5 和发布静态契约。五库语料只有文本/版本/token 契约，大夹具只有合成文件树与五命名维度；二者都不是插件产品解析、真实数据库/数据源、驱动兼容、IDE 项目导入或 UI 证据，真实达梦 DM8 仍需外部环境验收。

## 开发环境

- IntelliJ IDEA 2025.2.6.2 / Build 252（最低编译 SDK）
- IntelliJ IDEA 2025.2～2026.2（交付兼容矩阵）
- Java 21
- Gradle 9.7 Wrapper
- IntelliJ Platform Gradle Plugin 2.18.1

## 常用命令

```bash
mvn --batch-mode --file samples/java-mybatis-minimal/pom.xml clean verify
mvn --batch-mode --file samples/semantic-corpus/pom.xml clean verify
./gradlew -p samples/gradle-spring-boot-multimodule clean check :app:bootJar --no-daemon
./gradlew check verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
./gradlew verifyCyclonedxBom
./scripts/verify-sandbox-lifecycle.sh 1 samples/lifecycle-inspection-corpus true 2026.1.4
./scripts/verify-optional-dependency-isolation.sh
./gradlew runIde
```

`samples/semantic-corpus` 是完整 Maven 语义库。`samples/lifecycle-inspection-corpus`
是生命周期与可选依赖隔离门专用的最小真实 IntelliJ 模块：它不含 Maven/Gradle
导入，避免离线 Inspection 与异步项目导入竞态；Java Mapper 与 Mapper XML 均进入
IDE 索引，并且只保留一个可精确断言的 `MyBatisUnusedStatement` 问题。它不能替代
上述完整语义库验证。

S13 正式候选需把生命周期命令提升为 `./scripts/verify-sandbox-lifecycle.sh 100 samples/lifecycle-inspection-corpus true 2026.1.4`。项目、重建索引和平台版本参数不能省略；否则脚本只验证基础启停，不会形成 Inspection/MCP 的正式候选证据。旧脚本在历史候选上的 100/100 只算基线；加固脚本必须先在新 HEAD 跑 1 次审查报告，再在同一 HEAD 跑满 100 次，并与 5/5 可选依赖隔离共同留证。涉及图形界面的路径只在副屏或独立测试环境运行。

插件 ZIP 生成在 `build/distributions/`。

需要构造升级测试包时，可通过项目属性覆盖版本号：

```bash
./gradlew buildPlugin -PpluginVersion=0.1.0-alpha.2
```

## 项目资料

- [交付阶段实施规划](交付阶段实施规划.md)
- [首批开发任务卡](docs/首批任务卡.md)
- [功能矩阵](docs/功能矩阵.md)
- [交付功能行为矩阵](docs/交付功能行为矩阵.md)
- [S0 产品基线与语料任务卡](docs/S0-产品基线与语料任务卡.md)
- [S0 产品基线与语料验收记录](docs/S0-产品基线与语料验收记录.md)
- [S1 插件骨架与流水线任务卡](docs/S1-插件骨架与流水线任务卡.md)
- [S1 插件骨架与流水线验收记录](docs/S1-插件骨架与流水线验收记录.md)
- [S2 统一语义模型与增量索引任务卡](docs/S2-统一语义模型与增量索引任务卡.md)
- [S2 统一语义模型与增量索引验收记录](docs/S2-统一语义模型与增量索引验收记录.md)
- [S3 精确引用、基础检查与安全修复任务卡](docs/S3-精确引用基础检查与安全修复任务卡.md)
- [S3 精确引用、基础检查与安全修复验收记录](docs/S3-精确引用基础检查与安全修复验收记录.md)
- [S4 参数、ResultMap、TypeAlias 与安全重构任务卡](docs/S4-参数ResultMap-TypeAlias与安全重构任务卡.md)
- [S4 参数、ResultMap、TypeAlias 与安全重构验收记录](docs/S4-参数ResultMap-TypeAlias与安全重构验收记录.md)
- [S5 动态 SQL 编译器与源位置映射任务卡](docs/S5-动态SQL编译器与源位置映射任务卡.md)
- [S5 动态 SQL 编译器与源位置映射验收记录](docs/S5-动态SQL编译器与源位置映射验收记录.md)
- [S6 OGNL 语言支持任务卡](docs/S6-OGNL语言支持任务卡.md)
- [S6 OGNL 语言支持验收记录](docs/S6-OGNL语言支持验收记录.md)
- [S7 类型安全 SQL 与数据库元数据任务卡](docs/S7-类型安全SQL与数据库元数据任务卡.md)
- [S7 类型安全 SQL 与数据库元数据验收记录](docs/S7-类型安全SQL与数据库元数据验收记录.md)
- [S8 数据库代码生成与安全合并任务卡](docs/S8-数据库代码生成与安全合并任务卡.md)
- [S8 数据库代码生成与安全合并验收记录](docs/S8-数据库代码生成与安全合并验收记录.md)
- [S9 方法名 SQL、Wrapper 与 Join 任务卡](docs/S9-方法名SQL-Wrapper与Join任务卡.md)
- [S9 方法名 SQL、Wrapper 与 Join 验收记录](docs/S9-方法名SQL-Wrapper与Join验收记录.md)
- [S10 转换、格式化、日志、执行与测试任务卡](docs/S10-转换格式化日志执行与测试任务卡.md)
- [S10 转换、格式化、日志、执行与测试验收记录](docs/S10-转换格式化日志执行与测试验收记录.md)
- [S11 Spring、注解、Kotlin、框架与数据库扩展任务卡](docs/S11-Spring注解Kotlin框架与数据库扩展任务卡.md)
- [S11 Spring、注解、Kotlin、框架与数据库扩展验收记录](docs/S11-Spring注解Kotlin框架与数据库扩展验收记录.md)
- [S12 MCP、设置、国际化与产品化任务卡](docs/S12-MCP设置国际化与产品化任务卡.md)
- [S13 性能、可靠性、跨版本与正式交付任务卡](docs/S13-性能可靠性跨版本与正式交付任务卡.md)
- [S13 性能、可靠性、跨版本与正式交付验收记录](docs/S13-性能可靠性跨版本与正式交付验收记录.md)
- [安装与首次使用](docs/安装与首次使用.md)
- [兼容矩阵](docs/兼容矩阵.md)
- [常见问题与故障排查](docs/常见问题与故障排查.md)
- [隐私说明](docs/隐私说明.md)
- [第三方组件与 SBOM](docs/第三方组件与SBOM.md)
- [发布、升级与回滚](docs/发布升级与回滚.md)
- [风险清单](docs/风险清单.md)
- [首批验收记录](docs/首批验收记录.md)
- [交付完成度审计](docs/交付完成度审计.md)
- [安全策略](.github/SECURITY.md)

## 独立实现边界

本项目依据 MyBatis、IntelliJ Platform 的公开文档和自行编写的测试语料独立实现，不反编译、不复制任何商业插件的源码、资源、名称或界面。

## 许可证

本项目采用 [Apache License 2.0](LICENSE) 许可证，并提供发布署名与分发边界说明 [NOTICE](NOTICE)。
