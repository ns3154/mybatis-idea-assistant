# S9 方法名 SQL、Wrapper 与 Join 任务卡

> 阶段：S9 确定性方法语法、SQL/XML、Wrapper 与显式 Join
> 日期：2026-08-12
> 状态：原 S9 能力开发完成；set-based 批量与失败关闭增量已随当前候选通过本地统一门，待远端与副屏验收

## 目标

从用户明确选择且已经加载的数据库表建立字段词典。方法名必须经过独立语法解析并产生唯一 AST，才能生成 Mapper 方法、MyBatis XML、MyBatis-Plus/Flex Wrapper；两表 Join 必须由用户明确选择外键/主键关系、Join 类型和输出字段。歧义、版本不支持、方言不支持、已有声明冲突或目标变化时停止，不输出半成品。

## 固定边界

- 方法名最长 512 个字符；字段词元来自确定 schema，不做编辑距离、大小写近似或目录猜测；
- 支持查询、更新、删除、计数、存在性、聚合、字段投影、And/Or、比较、排序、Distinct、First/Top、分页和显式 set-based 批量；更新和删除必须含有不可整体省略的条件；
- “批量”不指 JDBC `ExecutorType.BATCH`：`insertBatch(Collection<Entity>)` 生成一条 set-based 插入 statement（Oracle 使用 `INSERT ALL`），集合 `IN/NOT IN` 条件生成 `<foreach>`；插件不隐式切换 MyBatis ExecutorType；
- null/空集合必须失败关闭：`IN/NOT IN` 生成 `1 = 0`，`insertBatch` 在动态 SQL 绑定阶段抛异常；update/delete 的 WHERE 若所有谓词都可能被动态省略，则在计划阶段拒绝；
- 返回类型、`@Param`、集合、范围、分页参数和 XML 占位符由 AST 推导；实体类型必须是安全的 Java 全限定名；
- 动态可选条件只允许 AND 条件树；OR 中省略单个条件会改变逻辑，直接拒绝。Database Tools 的方法生成与 Wrapper 预览共用选择模型：无参、primitive 标量/范围条件不可选，集合条件仍可选，UPDATE/DELETE 至少保留一个必选谓词；取消发生在计划、预览和写入之前；
- MyBatis-Plus 与 Flex 必须由用户显式选择框架和版本，不探测类路径；输入语法能识别 Plus 3.5.x 和 Flex 1.7.2+ 的 1.x，但当前交付验证/接受范围只锁定 Plus 3.5.17 与 Flex 1.11.8，不得向其他版本外推；
- Plus 分页不使用 `last` 拼接；Flex 更新和聚合 Wrapper 暂不生成；两框架相反的 `likeLeft/likeRight` 语义分别适配；
- Join 只接受用户所选两张同数据源、非 LOADING 表，关系必须是一侧外键和另一侧主键；不按列名猜测；
- MySQL 拒绝 FULL JOIN；SQLite 因版本差异只提供 INNER/LEFT；输出字段必须显式选择且标签唯一；
- 静态 SQL 写入复用 S8 全量预览、稳定方法区、TOCTOU、单命令、Local History 和失败回滚；Wrapper/Join 当前只读预览，不落盘；
- Database Tools 动作只在可选描述符注册，核心 `methodsql` 包不引用 JetBrains 数据库类型。

## 分批实施

| 批次 | 内容 | 当前状态 | 退出证据 |
|---|---|---|---|
| S9-A | 确定性方法语法、字段词典、歧义与位置化诊断 | 代码与自动化完成 | 大字段词典重复解析、取消、非法语法、超长、条件与排序测试 |
| S9-B | Mapper 方法、静态/动态 XML、set-based 批量、方言 limit/page 与类型推导 | 批量增量已随当前候选通过本地统一门 | Java/XML PSI、真实 MyBatis/H2、单条 set-based 插入 statement、IN/NOT IN 集合失败关闭、全可选写谓词拒绝、六方言、读写与聚合测试 |
| S9-C | Plus/Flex Wrapper 适配 | 锁定版本自动门完成 | `MyBatisWrapperGeneratedCompileFixtureTest` 与 Maven 样例逐字绑定当前生成器输出，Plus 3.5.17/Flex 1.11.8 真实编译运行 5/5；不外推其他版本 |
| S9-D | 显式 Join、可选条件选择、预览动作与 S8 安全写入集成 | 代码与定向自动化完成，待统一门与副屏 | FK/PK 身份、链式/复合关系、方言、共享选择模型、取消零写、动作注册、冲突和原子计划测试 |

## 验收矩阵

| 编号 | 场景 | 必须输出 | 保守边界 |
|---|---|---|---|
| S9-01 | 同一方法名和 schema 重复解析 | 每次得到相等且唯一的 AST | 任意多种合法切分均返回歧义诊断 |
| S9-02 | select/get/find/query 与投影 | 集合、单值 Optional 或 Map 返回类型和确定 SQL | 未知字段、非法操作组合或不安全类型不生成 |
| S9-03 | update/delete | 显式 SET、`@Param` 和不可整体省略的有条件 WHERE | 无条件写 SQL、或所有 WHERE 谓词均可省略的写计划必须拒绝 |
| S9-04 | 动态/集合条件 | AND 树生成 `<where>/<if>`；IN/NOT IN 集合生成带 null/空失败关闭分支的 `<foreach>` | OR 动态省略、无参条件动态化必须拒绝；null/空集合不得生成非法 SQL 或恒真写条件 |
| S9-04B | 批量插入 | `insertBatch(Collection<Entity>)` 生成一个 Mapper 参数和一条 set-based 插入 statement | null/空/含 null 元素在动态 SQL 绑定阶段失败；不自动分片，不承诺任意集合大小、跨驱动原子性或零副作用；不是 JDBC `ExecutorType.BATCH` |
| S9-05 | limit/page 与方言 | MySQL/PostgreSQL/SQLite/H2、Oracle/Generic、SQL Server 各自合法语法 | SQL Server 无 OrderBy 分页拒绝；不静默降级通用语法 |
| S9-06 | Wrapper | Plus/Flex 锁定版本直接编译当前生成器输出 | 已覆盖引用标识符、`IN/NOT_IN`、`BETWEEN`、单/多 OR 分组及 Flex `RawQueryTable`；不支持版本/操作明确拒绝 |
| S9-07 | Join | 用户选择 FK↔PK、类型和字段后只读预览 SQL | 无证据关系、跨数据源、重复别名/标签和不支持方言停止 |
| S9-08 | Mapper/XML 写入 | 全量预览后在一个命令中更新独立方法区 | 手改方法区、已有手写声明、目标缺失或并发变化零写入 |
| S9-09 | 可选依赖 | Database Tools 存在时注册三个 S9 动作 | 禁用数据库插件后核心解析和生成类仍可加载 |
| S9-10 | 统一门 | 测试、覆盖率、语料、结构、最低 252 Verifier 与 ZIP 全绿 | 历史报告不能替代当前代码；副屏 UI 未验不得标为已验收 |

## 统一门禁

- `methodsql` 核心行覆盖率不得低于 85%，整体不得低于 70%；
- 真实 MyBatis/H2 样例必须运行生成形态的查询、集合失败关闭、动态条件、set-based 插入、更新和计数；NOT IN 及目标数据库特有方言仍需各自语料/实库门；
- `MyBatisWrapperGeneratedCompileFixtureTest` 必须先证明 Maven 编译语料与当前生成器输出逐字一致，再由 Maven 在 Plus 3.5.17/Flex 1.11.8 真实依赖下编译运行 5/5；该证据不外推其他版本；
- 最低 IDEA 2025.2.6.2（`IU-252.28539.54`）Plugin Verifier 不得出现兼容、内部 API 或实验 API 阻断；
- 原 S9 快照已有独立自动门；set-based 批量增量已通过本地统一门（760/760，15389/18234，84.40%，最低 Verifier `Compatible`），并随 `37bedad9` 通过远端必需检查、生命周期 1/1、100/100 与隔离 5/5；后续发布候选的精确 `main` SHA 和副屏 Database Tools 三个动作的实机复核仍待；
- 上述门全部关闭前，S9 状态保持“开发完成，待最终统一验收”。
