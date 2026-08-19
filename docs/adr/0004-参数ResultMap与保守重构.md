# ADR-0004：参数、ResultMap 与保守重构

- 状态：已采纳
- 日期：2026-08-11
- 决策范围：S4 参数路径、ResultMap、TypeAlias 引用与跨文件重命名
- 关联文档：`docs/S4-参数ResultMap-TypeAlias与安全重构任务卡.md`、`docs/S4-参数ResultMap-TypeAlias与安全重构验收记录.md`

## 背景

S3 已经提供 namespace、statement、resultMap 和 SQL fragment 的精确符号引用，但参数表达式与 ResultMap 属性仍缺少 Java 类型语义。若每个 XML 属性各自解析参数或 JavaBean 属性，会产生多套命名规则、读写方向混淆、模块串联和重命名半成品。

MyBatis 的参数名还受到 `@Param`、特殊参数、单/多参数、集合包装名和编译参数名配置影响。完整 OGNL、动态 SQL、foreach/bind 局部变量又超出 S4 的静态点路径范围，因此不能把“没有证明存在”直接当作错误。

## 决策

### 1. 参数上下文遵守 MyBatis 命名规则

参数模型排除 `RowBounds` 和 `ResultHandler`，保留显式 `@Param`、`paramN`、实际名、`argN`、数字回退名，以及单集合/数组的包装名。解析结果携带类型和可信边界；实际参数名是否稳定未知时，不据此报告必错。

参数引用只处理根名、点路径、数组/列表索引和任务卡明确列出的 XML 属性。Map 动态键、Provider 动态输出、完整 OGNL、foreach item/index 与 bind 作用域保持未知，交由 S5/S6。

### 2. Java 属性模型显式区分 READ 与 WRITE

参数表达式消费可读属性，ResultMap `property` 消费可写属性。两者共享同一个 Java 属性解析器，但必须携带访问方向：

- READ 接受 getter 或可读字段；
- WRITE 接受 setter、构造参数或可写字段；
- 泛型、数组/集合元素和嵌套点路径逐段替换当前类型；
- Map、未知类型和歧义目标保守降级，不选择第一个候选。

这避免只有 getter 的属性被错误用于 ResultMap 写入，也避免只有 setter 的属性被错误用于参数读取。

### 3. ResultMap 类型由当前位置逐层推导

ResultMap 根类型来自 `type`，并合并 `extends`；association/collection 可用 `javaType`、`ofType` 或当前属性类型切换上下文；constructor、discriminator 与嵌套 resultMap 在各自分支内解析。继承循环、类型循环、未解析别名和多候选会停止展开。

`column` 在没有数据库元数据时不做不存在检查。S4 只验证 Java 侧可写属性与可证明的类型引用。

### 4. TypeAlias 是正式 PSI 引用

内置、显式配置、默认配置、包扫描和 `@Alias` 统一经模块可见的 TypeAlias 模型解析，并向 XML 类型属性提供引用、查找使用和补全候选。冲突别名返回全部目标；未解析占位符和不可见模块不参与。

### 5. 重命名必须复用原生引用并先做安全审计

statement、resultMap、SQL fragment、JavaBean 属性、Java 类型与 `@Param` 都通过 IntelliJ Rename 的引用查找、预览、冲突和 Undo 链路更新，不做全项目文本替换。

`@Param` 在写入前额外审计所有精确 Mapper XML：

- 注解 SQL/Provider/Flush、重载方法、非法新名称直接拒绝；
- `test`、`bind value` 等完整 OGNL 只要仍引用旧别名就报告冲突；
- 静态 include 无法解析，或旧别名出现在被 include 的 SQL fragment 中时报告冲突；
- 只有占位符、`collection`、`keyProperty` 等 S4 已建模的简单路径才允许联动重命名。

因此 S4 宁可停止重构，也不允许 Java 与 XML 只改一半。

## 后果

### 正面

- 参数引用、ResultMap 属性、检查和重构共享同一事实源；
- 读写属性方向明确，低误报边界可测试；
- TypeAlias、模块可见性和原生 Find Usages/Rename 行为一致；
- 所有允许的重构可预览、可单次撤销，冲突在写入前暴露。

### 代价

- S4 不支持完整 OGNL 和动态 SQL 局部变量，部分合法场景只能保持未解析；
- `@Param` 重命名遇到动态表达式或无法解析 include 会被拒绝；
- 无数据库元数据时不能验证 `column`。

这些限制必须在 Inspection 描述、任务卡与验收记录中保持可见，不得用文本替换绕开。

## 重新评估条件

出现以下任一情况时重新评估本决策：

- S5/S6 已交付可恢复的动态 SQL source map 和完整 OGNL PSI，可安全扩大 `@Param` 重命名范围；
- S7 数据库元数据模型能够在无阻塞前提下精确验证列；
- 新 MyBatis 版本改变参数命名或集合包装规则；
- IntelliJ Rename 公共 API 变化导致当前原生预览、冲突或 Undo 语义不再成立。
