# MyBatis Assistant

面向 IntelliJ IDEA 的 MyBatis 智能开发助手，采用独立实现路线开发。

当前处于 `0.1.0-SNAPSHOT` 开发预览阶段，S0～S3 已通过阶段验收，S4～S9 已完成开发并进入各自最终统一验收：

- 识别 Java Mapper 接口；
- 识别 MyBatis XML 的 `namespace`；
- 为 `select`、`insert`、`update`、`delete` 建立 statement 索引；
- 从 Java Mapper 方法导航到 XML statement，重复 statement 交由平台展示候选；
- 从 XML statement 导航回精确全限定名对应的 Java 接口方法，Java 重载全部保留为候选；
- 当精确 namespace 的 Mapper XML 已存在但同名 statement 缺失时，在 Java 方法名提供保守警告；
- 类型化区分无 Mapper XML、statement 缺失、多候选、索引未就绪、源失效和不支持的源，不做猜测跳转。
- 统一索引 Mapper namespace、四类 statement、`resultMap` 与 SQL fragment，重复声明保留全部候选；
- 识别 Java/Kotlin K2 Mapper、继承泛型方法、参数、返回实体、注解 SQL、`@Mapper` 与 `@MapperScan` 来源；
- 增量解析 MyBatis XML、Spring Boot/MyBatis-Plus 配置与 TypeAlias，并按模块依赖边界限制结果；
- 对未保存编辑、文件移动/删除、项目根变化、Dumb Mode 和取消请求做精确失效或保守降级。
- 为 XML `namespace`、statement `id`、`refid`、`resultMap`、`extends` 与 Java 注解 SQL/Provider 建立精确引用，支持查找使用；
- 支持继承 Mapper 方法、注解 SQL 与 Provider 方法的精确导航，唯一目标直达，多目标全部保留；
- 提供缺失 Mapper XML、重复 statement 等默认检查，并将高误报风险的 namespace、未使用 statement、缺失 `@Param` 检查默认关闭；
- 提供可预览、单次撤销、冲突停止的 Mapper XML、statement、`@Param` 修复；未使用 statement 仅提供定位，不自动删除代码。
- 按 MyBatis 参数命名规则解析 `#{}`、`${}`、`keyProperty`、`property` 与 `collection` 的根名、点路径和索引路径，对未知 Map 键与完整 OGNL 保守降级；
- 解析 ResultMap 的可写属性、嵌套类型、constructor/discriminator 分支与 TypeAlias，提供精确引用、查找使用、补全候选和低误报检查；
- 使用 IntelliJ 原生 Rename 同步更新 statement、resultMap/SQL fragment、`@Param`、JavaBean 属性与稳定类型引用；遇到动态 OGNL、include、多目标、只读或不完整语义时在写入前停止。
- 将动态 statement 编译为不会组合爆炸的符号化 IR，覆盖 `if/choose/where/set/trim/foreach/bind/include`，并为普通文本、CDATA、entity 与 include property 建立双向字符级 source map；循环、重复目标、Dumb Mode、失效源与取消均保守停止。
- 为 `test`、`when/test` 与 `bind/value` 注入自有 OGNL 语言，提供可恢复语法树、高亮、保守类型推导、引用、补全、Find Usages、作用域内 Rename 和可证明错误检查；分析过程不执行项目代码。
- 把 S5 动态程序压成有界代表 SQL，并在 Database Tools 可用时接入真实 SQL PSI；`#{}`、`${}`、动态标签、entity 与跨片段位置均可回映射到原 XML；
- 只在后台读取 Database Tools 已加载模型，统一处理超时、取消、模型失效和多数据源；编辑器线程不连接数据库、不等待 I/O；
- 提供 SQL 关键字、常用函数、别名、表列补全，以及不存在/歧义表列、ResultMap 缺列和基础 Java/JDBC 类型不匹配检查；动态标识符、加载中、语法不完整和目标不唯一时保持静默。
- 从 Database Tools 已加载且由用户明确选择的表生成 Entity、Mapper、Mapper XML 和 Service；支持 Standard/MyBatis-Plus 模板、命名、字段过滤、注释、类型/TypeHandler、自增键与关键字转义；
- 所有数据库生成先形成全量计划，支持按文件选择、候选文本和原生差异；稳定生成区以 SHA-256 防止覆盖用户修改，区域外手写内容、未保存编辑和 LF/CRLF 均保留；
- 批量创建与更新由单个命名 IDE Command 承载，执行前复核 TOCTOU 并创建 Local History 标签；写入异常自动回滚，冲突或失败不留半成品，整批支持一次 Undo/Redo。
- 以确定性方法语法生成 Mapper 方法和静态/动态 XML，覆盖投影、条件、排序、聚合、Top/分页及六类数据库方言；歧义或无条件写操作在生成前拒绝；
- 显式选择框架与版本后预览可编译的 MyBatis-Plus/Flex Wrapper，不按类路径猜测，也不使用不安全 SQL 尾部拼接；
- 从两张同数据源、已加载表中显式选择 FK↔PK、Join 类型和输出字段后预览 Join SQL，不按列名猜测业务关系。

## 开发环境

- IntelliJ IDEA 2026.1 / Build 261
- Java 21
- Gradle 9.3 Wrapper
- IntelliJ Platform Gradle Plugin 2.18.1

## 常用命令

```bash
mvn --batch-mode --file samples/java-mybatis-minimal/pom.xml clean verify
mvn --batch-mode --file samples/semantic-corpus/pom.xml clean verify
./gradlew check verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
./scripts/verify-sandbox-lifecycle.sh 20
./scripts/verify-optional-dependency-isolation.sh
./gradlew runIde
```

插件 ZIP 生成在 `build/distributions/`。

需要构造升级测试包时，可通过项目属性覆盖版本号：

```bash
./gradlew buildPlugin -PpluginVersion=0.1.1-SNAPSHOT
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
- [风险清单](docs/风险清单.md)
- [首批验收记录](docs/首批验收记录.md)
- [交付完成度审计](docs/交付完成度审计.md)
- [安全策略](.github/SECURITY.md)

## 独立实现边界

本项目依据 MyBatis、IntelliJ Platform 的公开文档和自行编写的测试语料独立实现，不反编译、不复制任何商业插件的源码、资源、名称或界面。

## 许可证

本项目采用 [Apache License 2.0](LICENSE) 许可证。
