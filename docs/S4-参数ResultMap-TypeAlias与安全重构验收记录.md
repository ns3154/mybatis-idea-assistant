# S4 参数、ResultMap、TypeAlias 与安全重构验收记录

> 日期：2026-08-11
> 目标版本：IntelliJ IDEA 2026.1 / Build 261
> 当前结论：原 S4 快照保留；ResultMap 新增路径已纳入 2026-08-12 工作区本地统一候选门并通过，远端和实机证据仍待补齐

## 1. 交付范围

- MyBatis 参数命名上下文：`@Param`、`paramN`、实际名/回退名、特殊参数、单对象、集合、数组、Map 和继承泛型；
- 参数路径：`#{}`、`${}`、`keyProperty`、`property`、`collection` 的静态根名、点路径和索引路径引用、补全与检查；
- ResultMap：可写 Java 属性、constructor 参数、association/collection 嵌套类型、discriminator 分支、extends 与 TypeAlias 引用；
- TypeAlias：内置、显式、默认、包扫描与 `@Alias` 的引用、查找使用和候选；
- 原生重命名：Mapper 方法/statement、resultMap/SQL fragment、`@Param`、JavaBean 属性和稳定类型引用；
- 写入安全：预览、冲突检测、模块隔离、只读/失效停止、单次 Undo；完整 OGNL 和不确定 include 不做半重命名。
- ResultMap 增量：直接映射的 `column` 补全；仅对 READY 元数据、唯一单表、唯一 resultMap、简单静态子映射与可写 Java 属性形成缺失映射计划，并通过 ModCommand 预览/Undo、源文本和元数据世代复核失败关闭。

## 2. 历史自动化预验快照

本节 254 个测试和覆盖率属于 ResultMap 增量前的 S4 阶段快照，不得当作当前候选的数字。当前候选的统一本地证据单独记录于下节，远端结果仍不得由本地结果替代。

| 项目 | 当前证据 | 最终状态 |
|---|---|---|
| IntelliJ Platform 测试 | 254 个测试通过，0 失败 | 通过 |
| 整体行覆盖率 | 3748/4151，90.29% | 通过 70% 门槛 |
| 核心 index/model/resolve | 1914/2109，90.75% | 通过 85% 门槛 |
| 主要包 | index 91.94%、model 92.02%、resolve 85.98%、reference 91.71%、inspection 90.93%、refactoring 77.97%、navigation 97.00% | 通过当前门槛 |
| Maven 最小样例 | 4 个测试通过 | 通过 |
| Maven 语义语料 | 六模块 reactor 成功，6 个契约测试通过 | 通过 |
| Plugin Verifier | `IU-261.22158.277 Compatible`，无 deprecated/scheduled-for-removal 报告 | 通过 |
| 当前 ZIP | `mybatis-idea-assistant-0.1.0-SNAPSHOT.zip`，SHA-256 `dd1007d4d807d98ef486dda087644cebe1d06eb8cfbe44b1a213bd9753aaa4b3` | 通过构建与结构验证 |
| 加固沙箱生命周期 1/100 | 先跑 1 次审查 Inspection、MCP、进程和零改写，再在同一 HEAD 跑 100/100 | 待当前产物实跑 |
| 5/5 可选依赖隔离 | 先前阶段证据不能替代本批 | 待当前产物复跑 |

## 3. 2026-08-12 当前候选本地证据

- 独占执行 `./gradlew clean check verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin` 成功（`BUILD SUCCESSFUL`，2m25s）；101 个测试套件中的 722/722 平台测试通过，失败、错误和跳过均为 0。
- 整体行覆盖率为 15135/17958（84.28%）；包含 ResultMap 增量的各分区覆盖率硬门、Checkstyle、本地化、SBOM、项目与插件结构均通过。
- Java/MyBatis 样例 8/8、语义语料 11/11、Gradle Spring 四模块 2/2 及 `bootJar` 通过；最低 IDE `IU-252.28539.54` 的 Plugin Verifier 结果为 `Compatible`。
- 候选 ZIP SHA-256 为 `85992cb50b0656c4745aac9eda68f0b18ec2d8ba64099c3caebdab1d423a87e9`。该本地证据不替代新 HEAD 远端、加固生命周期 1/100 与 5/5 隔离、副屏真实 IDEA 验收。

## 4. 关键行为证据

| 能力 | 正向证据 | 保守失败证据 |
|---|---|---|
| 参数模型 | 单/多参数、特殊参数、集合/数组、Map、`paramN`、实际名与继承泛型均有平台测试 | 未知类型、动态 Map 键、Dumb、失效与取消不伪造目标 |
| 参数路径 | 根名、嵌套属性、索引、占位符及 Mapper 属性位置可解析并提供候选 | 完整 OGNL、foreach/bind 局部变量与不完整输入保持未知 |
| ResultMap | READ/WRITE 属性方向分离；setter/字段、constructor、嵌套类型、extends 与 discriminator 分支可解析 | 只读属性、循环、未解析类型和动态 Map 不报伪错误 |
| TypeAlias | 内置、显式、默认、包扫描、`@Alias`、冲突候选和模块边界均有引用测试 | 冲突不选第一个，占位符和不可见模块不猜测 |
| 原生 Rename | statement、resultMap/SQL fragment、属性、类型、`@Param` 均经引用和 RenameProcessor 更新 | 多目标、只读、Dumb、失效、非法名称、注解 SQL、重载、OGNL/include 冲突在写入前停止 |
| 恢复 | 多文件安全重命名由一个原生命令承载 | 单次 Undo 恢复 Java/XML 全部修改，不留样例差异 |
| ResultMap 列与补齐 | READY 唯一表提供直接 `column` 候选；简单映射按数据库顺序且主键优先形成 `<id>/<result>` 计划 | LOADING/歧义/多表、extends、复杂子映射、只读、语法错误、属性不唯一、源文本或元数据变化均不写入 |

## 5. 副屏真实 IDEA 验收

本批要求在 IntelliJ IDEA 2026.1 GA 的副屏窗口完成以下路径：

1. 在 `@Param("user")` 上执行 Rename，确认包含 `test="user..."` 的动态 OGNL 被冲突对话框明确拦截；
2. 在 `deleteByIds(@Param("ids"))` 上预览 `ids → userIds`，确认只命中精确 XML 引用；
3. 执行后确认 Java 与 `<foreach collection>` 同步更新；
4. 单次 Undo 后两处同时恢复；
5. 退出后核对窗口持久化坐标属于副屏，当前运行日志中本插件已加载且无本插件异常。

当前系统只检测到内建主屏，最近一次交互的持久化坐标为 `x=164`，因此该次交互不计入副屏验收。外接/副屏恢复后必须重新执行并把确证结果更新到本节。

## 6. 阶段结论

S4-A～S4-D 的代码、测试和文档已经进入收口状态；ResultMap 新增路径可写为“代码已实现且当前候选本地统一门已通过”。新 HEAD 远端必需检查、加固生命周期 1/100、5/5 可选依赖隔离与副屏真实 IDEA 路径全部通过前，本阶段保持“待最终验收”，不得写成“已验收”。
