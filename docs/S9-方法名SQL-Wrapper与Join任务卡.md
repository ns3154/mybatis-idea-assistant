# S9 方法名 SQL、Wrapper 与 Join 任务卡

> 阶段：S9 确定性方法语法、SQL/XML、Wrapper 与显式 Join
> 日期：2026-08-12
> 状态：开发完成，待最终统一质量门与副屏验收

## 目标

从用户明确选择且已经加载的数据库表建立字段词典。方法名必须经过独立语法解析并产生唯一 AST，才能生成 Mapper 方法、MyBatis XML、MyBatis-Plus/Flex Wrapper；两表 Join 必须由用户明确选择外键/主键关系、Join 类型和输出字段。歧义、版本不支持、方言不支持、已有声明冲突或目标变化时停止，不输出半成品。

## 固定边界

- 方法名最长 512 个字符；字段词元来自确定 schema，不做编辑距离、大小写近似或目录猜测；
- 支持查询、更新、删除、计数、存在性、聚合、字段投影、And/Or、比较、排序、Distinct、First/Top 与分页；更新和删除必须含有条件；
- 返回类型、`@Param`、集合、范围、分页参数和 XML 占位符由 AST 推导；实体类型必须是安全的 Java 全限定名；
- 动态可选条件只允许 AND 条件树；OR 中省略单个条件会改变逻辑，直接拒绝；
- MyBatis-Plus 与 Flex 必须由用户显式选择框架和版本；Plus 最低 3.5，Flex 最低 1.7.2；不探测类路径；
- Plus 分页不使用 `last` 拼接；Flex 更新和聚合 Wrapper 暂不生成；两框架相反的 `likeLeft/likeRight` 语义分别适配；
- Join 只接受用户所选两张同数据源、非 LOADING 表，关系必须是一侧外键和另一侧主键；不按列名猜测；
- MySQL 拒绝 FULL JOIN；SQLite 因版本差异只提供 INNER/LEFT；输出字段必须显式选择且标签唯一；
- 静态 SQL 写入复用 S8 全量预览、稳定方法区、TOCTOU、单命令、Local History 和失败回滚；Wrapper/Join 当前只读预览，不落盘；
- Database Tools 动作只在可选描述符注册，核心 `methodsql` 包不引用 JetBrains 数据库类型。

## 分批实施

| 批次 | 内容 | 当前状态 | 退出证据 |
|---|---|---|---|
| S9-A | 确定性方法语法、字段词典、歧义与位置化诊断 | 代码与自动化完成 | 大字段词典重复解析、取消、非法语法、超长、条件与排序测试 |
| S9-B | Mapper 方法、静态/动态 XML、方言 limit/page 与类型推导 | 代码与自动化完成 | Java/XML PSI、真实 MyBatis/H2、六方言、读写与聚合测试 |
| S9-C | Plus/Flex Wrapper 适配 | 代码与自动化完成 | 锁定 Plus 3.5.17/Flex 1.11.8 编译语料、版本/能力拒绝测试 |
| S9-D | 显式 Join、预览动作与 S8 安全写入集成 | 代码与自动化完成，待副屏 | FK/PK 身份、链式/复合关系、方言、动作注册、冲突和原子计划测试 |

## 验收矩阵

| 编号 | 场景 | 必须输出 | 保守边界 |
|---|---|---|---|
| S9-01 | 同一方法名和 schema 重复解析 | 每次得到相等且唯一的 AST | 任意多种合法切分均返回歧义诊断 |
| S9-02 | select/get/find/query 与投影 | 集合、单值 Optional 或 Map 返回类型和确定 SQL | 未知字段、非法操作组合或不安全类型不生成 |
| S9-03 | update/delete | 显式 SET、`@Param` 和有条件 WHERE | 无条件写 SQL 必须拒绝 |
| S9-04 | 动态条件 | AND 树生成 `<where>/<if>`，集合生成 `<foreach>` | OR 动态省略、无参条件动态化必须拒绝 |
| S9-05 | limit/page 与方言 | MySQL/PostgreSQL/SQLite/H2、Oracle/Generic、SQL Server 各自合法语法 | SQL Server 无 OrderBy 分页拒绝；不静默降级通用语法 |
| S9-06 | Wrapper | Plus/Flex 生成结果在锁定真实依赖下编译 | 不支持版本/操作明确拒绝，不输出 `last` 或错误 Like API |
| S9-07 | Join | 用户选择 FK↔PK、类型和字段后只读预览 SQL | 无证据关系、跨数据源、重复别名/标签和不支持方言停止 |
| S9-08 | Mapper/XML 写入 | 全量预览后在一个命令中更新独立方法区 | 手改方法区、已有手写声明、目标缺失或并发变化零写入 |
| S9-09 | 可选依赖 | Database Tools 存在时注册三个 S9 动作 | 禁用数据库插件后核心解析和生成类仍可加载 |
| S9-10 | 统一门 | 测试、覆盖率、语料、结构、261 Verifier 与 ZIP 全绿 | 历史报告不能替代当前代码；副屏 UI 未验不得标为已验收 |

## 统一门禁

- `methodsql` 核心行覆盖率不得低于 85%，整体不得低于 70%；
- 真实 MyBatis/H2 样例必须运行生成形态的查询、IN、动态条件、更新和计数；
- Plus/Flex 语料必须在锁定真实版本下编译；
- 最低 IDEA 2026.1 GA Plugin Verifier 不得出现兼容、内部 API 或实验 API 阻断；
- 当前产物仍需 20/20 生命周期、5/5 可选依赖隔离和副屏 Database Tools 三个动作的实机复核；
- 上述门全部关闭前，S9 状态保持“开发完成，待最终统一验收”。
