# S9 方法名 SQL、Wrapper 与 Join 验收记录

> 日期：2026-08-12
> 当前结论：代码与自动化能力已实现；最终阶段验收尚未关闭

## 已实现范围

- 建立最长 512 字符的确定性方法语法和不可变查询 AST，覆盖查询、更新、删除、计数、存在性、聚合、字段投影、比较、And/Or、排序、Distinct、First/Top 与分页；
- 多种合法字段切分统一作为歧义失败；未知字段、非法顺序、越界 Top 和无条件更新/删除给出位置化诊断；
- 推导 Mapper 返回类型、稳定 `@Param`、集合/范围/分页参数，并生成静态或动态 MyBatis XML；动态 OR 省略条件被拒绝；
- 覆盖 MySQL、PostgreSQL、Oracle、SQL Server、SQLite、H2 和 Generic 的标识符、布尔值、limit/page；SQL Server 无排序分页拒绝；
- 显式生成 MyBatis-Plus/Flex Wrapper，适配版本门、条件参数位置和 Like 方向差异；不使用不安全 `last` 拼接；
- 显式 Join 只接受 FK↔PK 字段身份、用户选择的类型和输出属性；拒绝未知/重复别名、跨 schema 字段、重复输出标签和不支持方言；
- 方法名 SQL 复用 S8 计划并以独立方法区原子写入 Mapper/XML；Wrapper 与 Join 提供 Database Tools 右键只读预览入口；
- 三个 S9 数据库动作只在可选数据库描述符注册；生成核心不引用 Database Tools、Plus 或 Flex 类型。

## 当前自动化证据

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
- Plus 3.5.17 与 Flex 1.11.8 锁定依赖下的生成形态 Wrapper 均编译，框架契约测试 2/2 通过；
- 最低 IDEA 2026.1 GA Plugin Verifier 最终结果为 Compatible，且无内部 API/实验 API 阻断；
- 首轮 Verifier 曾因选择对话框误用内部 `MessagesService` 失败；实现已改为公开 `DialogWrapper`/Swing API 并复验通过，该失败不被删除或写成绿灯；
- ZIP 已重新构建，SHA-256 为 `c67ae181e57c7ad1fc0c798d838c21a71eb8e39a334d5361c58275dd1937e78d`；三个 S9 动作的真实扩展注册、表数量/有效性/LOADING/同数据源守门和底层生成行为均有平台测试。
- GitHub Actions [运行 #31541144714](https://github.com/ns3154/mybatis-idea-assistant/actions/runs/31541144714) 已在提交 `7b62bfd` 上通过，耗时 6 分 42 秒；远端重新执行了两个 Maven 语料、完整 Gradle 测试与覆盖率、项目/插件结构、最低 261 Plugin Verifier，并上传插件 ZIP 与验证报告。

## 尚未关闭的验收项

- 当前产物的 20/20 沙箱生命周期与 5/5 Database Tools 可选依赖隔离；
- 检测到真实副屏后，在副屏 IDEA 2026.1 验证方法名写入的预览/冲突/Undo，以及 Wrapper/Join 的框架、关系、字段选择和只读预览；
- 当前无可用副屏，因此未启动 IDEA、`runIde` 或 Computer Use，也未占用主屏幕。

以上项目全部通过前，S9 只能标记“开发完成，待最终统一验收”，不得写成“已验收”。
