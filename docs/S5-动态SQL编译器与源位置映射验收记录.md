# S5 动态 SQL 编译器与源位置映射验收记录

> 日期：2026-08-12
> 当前结论：代码与自动化开发完成；最终阶段验收尚未关闭

> 证据分层：下列 280 个测试与覆盖率是 S5 原阶段快照；`dynamic-sql-golden/v1` 三组版本化黄金已纳入本地统一门，并随 `37bedad9` 完成远端必需检查与生命周期 100/100。

## 已实现范围

- 不依赖平台状态的不可变符号 IR：文本、序列、If、Choose、Trim、ForEach、Bind 与 Include；
- 普通文本、CDATA、XML entity 和 include property 的 `EXACT`、`DECODED`、`SYNTHETIC` 压缩映射段，以及双向范围查询；
- `if/when` 条件、choose 短路顺序、where/set/trim 属性与 foreach 运行期属性；
- foreach item/index、bind 和 include property 的显式词法绑定；
- 本 namespace、跨 namespace 与嵌套 include 的唯一索引解析、局部 property 覆盖和跨文件来源；
- 重复 fragment、动态 refid、非法属性、property 环和 include 环的类型化诊断；
- statement、已访问 fragment、XML 符号索引、Dumb 状态和项目根共同驱动的缓存失效；瞬时失败不缓存，取消异常原样传播。
- `src/test/resources/dynamic-sql-golden/v1` 三组输入/期望黄金，固定静态文本、动态标签、include/property 与 source map 的外部化行为；后续不允许静默改写 `v1` 期望。

## 自动化证据

S5 定向测试覆盖 26 个用例，包括字符级 round-trip、200/1000 条件线性节点预算、30 组固定随机树、未保存 statement/fragment 编辑、索引新增重复目标、Dumb 恢复和编译中取消。定向命令：

```bash
./gradlew test --tests 'io.github.ns3154.mybatisassistant.dynamic.*' checkstyleMain checkstyleTest --rerun-tasks
```

结果：`BUILD SUCCESSFUL`，S5 定向测试 26/26 通过，主代码与测试 Checkstyle 通过。

原 S5 快照统一门禁：

```bash
./gradlew check verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin --rerun-tasks
mvn --batch-mode --file samples/java-mybatis-minimal/pom.xml clean verify
mvn --batch-mode --file samples/semantic-corpus/pom.xml clean verify
```

结果：

- 280/280 平台测试通过，失败和错误均为 0；
- 整体行覆盖率 4379/4865，即 90.01%；
- `index`、`model`、`resolve`、`dynamic` 核心合计 2545/2823，即 90.15%；
- `dynamic` 包 631/714，即 88.38%，并已纳入不低于 85% 的 Gradle 硬门；
- Java 最小样例 4/4 通过；语义语料六个 reactor 模块及 6 个契约测试通过；
- Plugin Verifier 对最低 `IU-261.22158.277` 判定 `Compatible`，并判定插件可动态启停；
- 当前 `0.1.0-SNAPSHOT` ZIP 的 SHA-256 为 `9b733183e18cf8ed151cf62739113f6ff0bad91728394de6fdcd15361821eb06`；包内描述包含 S5 能力，`since-build="261"` 且没有 `until-build`。

### 2026-08-19 当前候选本地证据

- 禁用构建缓存并强制重跑 `./gradlew clean check verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin` 成功（`BUILD SUCCESSFUL`，2m33s）；105 个测试套件中的 760/760 平台测试通过，失败、错误和跳过均为 0。
- 整体行覆盖率为 15389/18234（84.40%）；各分区覆盖率、Checkstyle、本地化、SBOM、项目与插件结构均通过。
- Java/MyBatis 样例 8/8、语义语料 11/11、Gradle Spring 四模块 2/2 及 `bootJar` 通过；最低 `IU-252.28539.54` Verifier 为 `Compatible`。
- `0.1.0-alpha.1` 本地候选 ZIP SHA-256 为 `0143c7b234d4c12c3955adbcfd8d62d5f8a28dc282d7122c7e0d0a46e5fd779f`。

## 尚未关闭的验收项

- `37bedad9` 的加固生命周期 1/1 与 100/100 报告均已审查通过；后续发布候选的精确 `main` SHA 仍须复验；
- 当前 S5 工作区的 5/5 可选依赖隔离已通过，仍需远端同一 SHA 复验；
- 后续发布候选的精确 `main` SHA 的主 CI、三系统、CodeQL、五宿主和发布必需检查；
- 检测到真实副屏后，在副屏运行 IDEA 2026.1 的动态 XML 编辑、缓存刷新和日志验收。

当前候选本地统一代码/数据门与 `37bedad9` 远端全门、生命周期 1/1、100/100、隔离 5/5 已通过；后续发布候选的精确 `main` SHA 与副屏项全部通过前，S5 状态保持“开发完成，待最终验收”。
