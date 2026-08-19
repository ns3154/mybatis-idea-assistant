# S9 方法名 SQL、Wrapper 与 Join 验收记录

> 日期：2026-08-12
> 当前结论：原 S9 快照保留；set-based 批量与失败关闭增量已随 2026-08-19 当前工作区通过本地统一门，远端与最终阶段验收未关闭

## 已实现范围

- 建立最长 512 字符的确定性方法语法和不可变查询 AST，覆盖查询、更新、删除、计数、存在性、聚合、字段投影、比较、And/Or、排序、Distinct、First/Top 与分页；
- 多种合法字段切分统一作为歧义失败；未知字段、非法顺序、越界 Top 和无条件更新/删除给出位置化诊断；
- 推导 Mapper 返回类型、稳定 `@Param`、集合/范围/分页参数，并生成静态或动态 MyBatis XML；动态 OR 省略条件被拒绝；
- 覆盖 MySQL、PostgreSQL、Oracle、SQL Server、SQLite、H2 和 Generic 的标识符、布尔值、limit/page；SQL Server 无排序分页拒绝；
- 显式生成 MyBatis-Plus/Flex Wrapper，适配版本门、条件参数位置和 Like 方向差异；不使用不安全 `last` 拼接；
- 显式 Join 只接受 FK↔PK 字段身份、用户选择的类型和输出属性；拒绝未知/重复别名、跨 schema 字段、重复输出标签和不支持方言；
- 方法名 SQL 复用 S8 计划并以独立方法区原子写入 Mapper/XML；Wrapper 与 Join 提供 Database Tools 右键只读预览入口；
- 三个 S9 数据库动作只在可选数据库描述符注册；生成核心不引用 Database Tools、Plus 或 Flex 类型。
- set-based 批量语义：`insertBatch(Collection<Entity>)` 生成一条 set-based 插入 statement（Oracle 为 `INSERT ALL`）；IN/NOT IN 集合谓词生成 `<foreach>`；这不是 JDBC `ExecutorType.BATCH`，也不改变项目运行期 executor 配置。
- `IN/NOT IN` 集合为 null/空/含 null 元素时进入 `1 = 0` 失败关闭分支；`insertBatch` 的 null/空/含 null 元素在动态 SQL 绑定阶段抛异常；update/delete 如果全部 WHERE 谓词都可能被省略则拒绝生成。
- `insertBatch` 不自动分片，不承诺任意集合大小、跨驱动原子性、事务提交结果、生成键回填或零副作用；SQL Server 仅静态限制到 `min(1000, 2100/可写字段数)`，其余方言由调用方按数据库/驱动限制分片。
- Database Tools 方法生成和 Wrapper 预览已共用 AND-only 可选条件选择模型；OR、无参、primitive 标量/范围条件禁选，UPDATE/DELETE 至少保留一个必选谓词，取消在计划、预览和写入前返回。共享模型、选择结果和两条生产分支已有代码级守门；当前自动化未直接驱动两个动作的真实对话框取消，仍待副屏验收。

## 原 S9 自动化证据快照

本节 467 个测试、覆盖率、ZIP 和远端运行属于 set-based 批量增量前的 S9 快照。新增批量/失败关闭路径已进入本地统一门，并随 `bb4a93c` 完成三系统、兼容、CodeQL、隔离与生命周期 100/100。

当前代码已经在一次任务图中执行并通过：

```bash
./gradlew clean check verifyPluginProjectConfiguration verifyPluginStructure \
  verifyPlugin buildPlugin --no-daemon
mvn --batch-mode --file samples/java-mybatis-minimal/pom.xml clean verify
mvn --batch-mode --file samples/semantic-corpus/pom.xml clean verify
```

- 467/467 平台测试通过，失败、错误和跳过均为 0；
- 整体行覆盖率 9535/10961，即 86.99%；`methodsql` 核心行覆盖率 1104/1237，即 89.25%，独立 85% 硬门通过；
- 真实 MyBatis 代理与 H2 共 6 个测试通过，覆盖生成形态的查询、IN、动态可选条件、更新、计数和隔离失败语料；
- `MyBatisWrapperGeneratedCompileFixtureTest` 直接调用当前生成器，与 Maven 编译的检入 Java 语料逐字比对；Plus 3.5.17/Flex 1.11.8 真实依赖下已编译运行 5/5，覆盖引用标识符、`IN/NOT_IN`、`BETWEEN`、单/多 OR 及 Flex `RawQueryTable`，不外推为其他版本；
- 最低 IDEA 2026.1 GA Plugin Verifier 最终结果为 Compatible，且无内部 API/实验 API 阻断；
- 首轮 Verifier 曾因选择对话框误用内部 `MessagesService` 失败；实现已改为公开 `DialogWrapper`/Swing API 并复验通过，该失败不被删除或写成绿灯；
- ZIP 已重新构建，SHA-256 为 `c67ae181e57c7ad1fc0c798d838c21a71eb8e39a334d5361c58275dd1937e78d`；三个 S9 动作的真实扩展注册、表数量/有效性/LOADING/同数据源守门和底层生成行为均有平台测试。
- GitHub Actions [运行 #31541144714](https://github.com/ns3154/mybatis-idea-assistant/actions/runs/31541144714) 已在提交 `7b62bfd` 上通过，耗时 6 分 42 秒；远端重新执行了两个 Maven 语料、完整 Gradle 测试与覆盖率、项目/插件结构、最低 261 Plugin Verifier，并上传插件 ZIP 与验证报告。

### 2026-08-19 当前候选本地证据

- 禁用构建缓存并强制重跑 `./gradlew clean check verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin` 成功（`BUILD SUCCESSFUL`，2m33s）；103 个测试套件中的 744/744 平台测试通过，失败、错误和跳过均为 0。
- 整体行覆盖率 15389/18234（84.40%）；各分区覆盖率、Checkstyle、本地化、SBOM、项目与插件结构均通过。
- Java/MyBatis 样例 8/8、语义语料 11/11、Gradle Spring 四模块 2/2 及 `bootJar` 通过；最低 `IU-252.28539.54` Verifier 为 `Compatible`。
- 候选 ZIP SHA-256 为 `57b1d53bd1a8f359b3d41206408e78dbb04c7150619f3b936f756a6ce5697e12`。

## 尚未关闭的验收项

- `bb4a93c` 的加固生命周期 1/1、100/100 与两轮 5/5 Database Tools 可选依赖隔离已通过；最终 `main` SHA 仍须复验；
- 最终 `main` SHA 的主 CI、三系统、CodeQL、五宿主和加固生命周期远端门；`bb4a93c` 的对应 Draft 候选门已全部通过；
- 检测到真实副屏后，在副屏 IDEA 2026.1 验证方法名写入的预览/冲突/Undo，以及 Wrapper/Join 的框架、关系、字段选择和只读预览；
- 当前无可用副屏，因此未启动 IDEA、`runIde` 或 Computer Use，也未占用主屏幕。

以上项目全部通过前，S9 只能标记“开发完成，待最终统一验收”，不得写成“已验收”。
