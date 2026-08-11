# S6 OGNL 语言支持验收记录

> 日期：2026-08-12
> 当前结论：代码与自动化开发完成；最终阶段验收尚未关闭

## 已实现范围

- 自有不可执行词法器、Pratt 语法器、不可变 AST、稳定诊断、错误恢复、深度上限与取消传播；
- `test`、`when/test`、`bind/value` 共用的 OGNL Language、ParserDefinition、XML 注入 PSI 与语法高亮；
- Mapper 参数、`_parameter`、`_databaseId`、bind、foreach item/index 的词法作用域；
- Java/Kotlin 类型、属性、方法、集合/数组索引、集合伪属性、静态全限定类型与成员的保守推导；
- 属性、方法、变量和静态成员的引用、导航与补全；bind/foreach 声明的 Find Usages 和作用域内原生 Rename；
- 只报告可证明缺失符号的 Inspection；解析诊断、未知 Map 键、未知上下文变量、重载歧义和瞬时生命周期状态保持静默；
- entity 解码文本到 XML 原始文本的精确范围映射，以及 XML、Java、索引、Dumb 状态和项目根变化驱动的缓存失效。

## 自动化证据

当前 S6 产物统一门禁：

```bash
./gradlew clean check verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
mvn --batch-mode --file samples/java-mybatis-minimal/pom.xml clean verify
mvn --batch-mode --file samples/semantic-corpus/pom.xml clean verify
```

结果：

- 335/335 平台测试通过，失败、错误和跳过均为 0；其中类名包含 OGNL 的定向用例 54/54 通过；
- 整体行覆盖率 6004/6744，即 89.03%；
- `index`、`model`、`resolve`、`dynamic`、`ognl` 核心合计 4033/4535，即 88.93%；
- `ognl` 包 1487/1712，即 86.86%，并已纳入不低于 85% 的 Gradle 硬门；
- 热缓存补全核心在真实注入 PSI 上执行 100 个样本，P95 小于 150ms；随机输入、深度限制和取消传播均有自动化门禁；
- Java 最小样例 4/4 通过；语义语料六个 reactor 模块及 6 个契约测试通过；
- Plugin Verifier 对最低 `IU-261.22158.277` 判定 `Compatible`，并判定插件可动态启停；
- 当前 `0.1.0-SNAPSHOT` ZIP 大小为 594714 字节，SHA-256 为 `1342c58b1dc1c1c5a6bf14fa3230b55d3ab9952442076922b1998e982a2f1385`；`since-build="261"` 且没有 `until-build`。

## 关键保守边界

- 生产代码不执行 OGNL、不反射调用项目方法、不初始化用户类；
- 动态 Map 键、未知 `#context` 变量、原始集合、动态下标、方法重载和重复可见静态类型返回 unknown，不制造确定错误；
- 解析存在诊断时不叠加语义误报；Dumb Mode、索引竞态、失效 PSI 与项目关闭不缓存为稳定结果；
- bind 和 foreach Rename 只更新同一词法作用域内的精确引用；非法名称或同 statement 绑定冲突时在写入前停止；
- XML entity 通过 decoded-to-raw 边界表映射，引用、检查和 Rename 都使用原始宿主文本范围。

## 尚未关闭的验收项

- 当前 S6 产物的 20/20 沙箱生命周期；
- 当前 S6 产物的 5/5 可选依赖隔离；
- 检测到真实副屏后，只在副屏运行 IDEA 2026.1，人工验证注入高亮、补全、导航、Find Usages、Rename preview/Undo、检查刷新和日志。

当前系统未检测到可用副屏，因此本批没有启动 IDEA 或 Computer Use。以上三项全部通过前，S6 状态保持“开发完成，待最终统一验收”，不得写成“已验收”。
