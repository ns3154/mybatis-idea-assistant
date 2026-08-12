# S4 参数、ResultMap、TypeAlias 与安全重构任务卡

> 阶段：S4 参数、ResultMap、TypeAlias 与安全重构
> 日期：2026-08-11
> 状态：开发完成，当前候选本地统一质量门已通过，待远端与副屏验收

## 目标

在 S2/S3 的统一模型和精确引用上，补齐日常 Mapper XML 所需的参数名、嵌套属性、ResultMap、TypeAlias 与跨文件重命名语义。所有检查只报告静态可证明的问题；所有重构必须由 IntelliJ 原生引用与重构处理器完成预览、冲突检测和单次 Undo。

## 固定边界

- 参数名遵守 MyBatis `ParamNameResolver`：排除 `RowBounds`/`ResultHandler`，支持显式 `@Param`、未注解参数的实际名/`argN`/数字回退名、`paramN`、单参数对象和集合/数组包装名；
- Java 源参数名是否进入运行期字节码可能受编译配置影响，检查不得把仅依赖实际参数名的表达式误报为必错；
- Map 键、`${}` 动态标识符和无法静态确定的 Provider/动态 SQL保持未知，不伪造属性；
- S4 只解析点路径、数组/列表索引与明确的 Mapper 属性位置；完整 OGNL 语法、foreach/bind 变量作用域和动态 SQL source map 由 S5/S6 负责；
- ResultMap 的 `property` 只按当前嵌套 Java 类型解析；`column` 只有 READY 元数据和唯一表目标时才提供数据库候选或可证明检查，没有元数据时不报告不存在；
- TypeAlias 多目标必须保留候选，未解析占位符和不可见模块不猜测；
- 重命名遇到多目标、只读、冲突或不完整 PSI 时停止，不做文本替换兜底。

## 分批实施

| 批次 | 内容 | 状态 | 退出证据 |
|---|---|---|---|
| S4-A | 参数命名、特殊参数、单参数、集合/数组、Map 与泛型上下文模型 | 开发完成，待 S4 统一验收 | 纯模型与平台 PSI 黄金测试；参数模型行覆盖率不低于 90% |
| S4-B | `#{}`、`${}`、`keyProperty`、`property`、`collection` 的静态引用、补全与检查 | 开发完成，待 S4 统一验收 | 根名/嵌套属性/索引/未知 Map/不完整输入、Dumb/取消与真实编辑器测试 |
| S4-C | ResultMap property/column、缺失映射 Quick Fix、类型与 TypeAlias | 代码与当前候选本地统一门已通过，待远端/实机 | association/collection/constructor/discriminator/extends、READY/唯一单表、TOCTOU、Undo 与别名冲突测试 |
| S4-D | statement、`@Param`、resultMap/refid、实体属性的安全重命名 | 开发完成，待 S4 统一验收 | 原生预览、多文件、只读、冲突、模块隔离、单次 Undo 与副屏实机 |

## S4-A 验收矩阵

| 编号 | 场景 | 必须输出 | 保守边界 |
|---|---|---|---|
| S4-A-01 | 单个普通对象 | 支持直接属性与 `_parameter`；不伪造对象参数名包装 | 只有单集合/数组才按运行配置暴露实际名 |
| S4-A-02 | 单个 `List`/集合 | `collection`、`list`、实际名和元素类型 | Map/原始集合元素未知时不猜类型 |
| S4-A-03 | 单个数组 | `array`、实际名和组件类型 | 多维数组逐层解析 |
| S4-A-04 | 多参数 | 显式名、`param1..N`，以及未注解参数的实际名/`argN`/数字回退名指向正确参数 | `paramN` 与显式同名冲突时显式名优先 |
| S4-A-05 | 特殊参数 | `RowBounds`/`ResultHandler` 不进入命名与编号 | 其 Java 下标不改变普通参数的 `paramN` 序号 |
| S4-A-06 | 继承泛型 | 在当前 Mapper 上替换后的参数/属性类型 | 无法替换的类型参数保持未知 |
| S4-A-07 | 生命周期 | Dumb、项目关闭、失效 PSI 类型化降级；取消传播 | 不缓存瞬时失败，不持有裸 PSI 跨写动作 |

## S4-B 验收矩阵

| 编号 | 场景 | 必须输出 | 保守边界 |
|---|---|---|---|
| S4-B-01 | 占位符参数 | `#{}`、`${}` 中的根名、点路径与索引路径引用到参数或只读 Java 属性 | `${}` 只建立静态引用，不推断 SQL 标识符语义 |
| S4-B-02 | Mapper 属性位置 | `keyProperty`、statement `property` 与 `foreach collection` 使用同一参数上下文 | 完整 OGNL、foreach item/index 和 bind 变量留到 S5/S6 |
| S4-B-03 | 补全 | 根名或属性前缀明确时返回确定候选 | Map 动态键、原始集合和未知泛型不伪造候选 |
| S4-B-04 | 检查 | 只在静态可证明路径不存在时标记最小范围 | 实际参数名不稳定、未知类型、Dumb Mode、失效源保持静默 |
| S4-B-05 | 生命周期 | 未保存 XML 编辑、Dumb、取消与索引竞态安全失效 | 不扫描项目文件，不吞取消异常 |

## S4-C 验收矩阵

| 编号 | 场景 | 必须输出 | 保守边界 |
|---|---|---|---|
| S4-C-01 | ResultMap 属性 | 普通 `result`/`id`、association、collection、嵌套点路径解析到可写 setter/字段 | 只读属性不作为可写目标；未知 Map 键保持未知 |
| S4-C-02 | 构造器映射 | `constructor/idArg/arg` 的 `name` 解析到匹配构造参数 | 缺失参数名或多构造器歧义时不猜目标 |
| S4-C-03 | 类型继承与分支 | resultMap `type`、`extends`、association/collection `javaType/ofType` 与 discriminator `case/resultMap` 组合后得到当前类型 | 循环继承和未解析类型及时停止 |
| S4-C-04 | TypeAlias | 内置、显式、默认、包扫描和 `@Alias` 可引用、查找使用和补全 | 冲突别名保留全部目标；不可见模块与占位符不参与 |
| S4-C-05 | 检查 | 只报告可证明不存在的可写属性或类型 | `column` 无数据库元数据时不报告不存在 |
| S4-C-06 | `column` 补全与缺失映射 Quick Fix | 直接 ResultMap 映射的 `column` 从 READY 唯一表返回候选；简单 ResultMap 可按主键优先补齐缺失 `<id>/<result>` | 仅接受唯一 resultMap、唯一单表、可写 Java 属性和简单静态映射；extends、嵌套复杂映射、动态值、重复列/属性、语法错误、元数据变化或目标变化时不提供或停止写入 |

## S4-D 验收矩阵

| 编号 | 场景 | 必须输出 | 保守边界 |
|---|---|---|---|
| S4-D-01 | Mapper 方法与 XML 符号 | 原生 Rename 同步 statement `id`、resultMap/SQL fragment 声明及精确引用 | 多目标、空名称、Dumb、只读或失效目标在写入前停止 |
| S4-D-02 | `@Param` | 从注解字符串进入原生 Rename，同步稳定参数引用并支持预览和单次 Undo | 注解 SQL、重载、完整 OGNL、无法解析 include 或非法标识符明确报冲突，不做半重命名 |
| S4-D-03 | JavaBean 属性 | getter、setter 或字段重命名同步参数只读路径与 ResultMap 可写路径 | 访问器改成普通方法时不生成无效属性名 |
| S4-D-04 | Java 类型 | 全限定类型和包扫描产生的稳定短别名随类名更新 | 显式 `@Alias`/配置别名保持稳定，不错误改写业务别名 |
| S4-D-05 | 作用域与恢复 | 只更新当前模块及依赖可见引用；单次 Undo 恢复所有文件 | 不跨无依赖模块，不以全文文本替换兜底 |

## 阶段统一门禁

```bash
mvn --batch-mode --file samples/java-mybatis-minimal/pom.xml clean verify
mvn --batch-mode --file samples/semantic-corpus/pom.xml clean verify
./gradlew check verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
./scripts/verify-sandbox-lifecycle.sh 1
./scripts/verify-sandbox-lifecycle.sh 100
./scripts/verify-optional-dependency-isolation.sh
```

当前代码已经覆盖 S4-A～S4-D，并叠加了 ResultMap `column` 补全与保守缺失映射 Quick Fix。截至 2026-08-12，包含该新路径的工作区已通过独占本地统一门：722/722 平台测试，整体行覆盖率 15135/17958（84.28%），各分区覆盖率、最低 `IU-252.28539.54` Verifier、结构、本地化和 SBOM 均通过。新 HEAD 远端门、加固生命周期 1/100、5/5 可选依赖隔离和副屏真实 IDEA 验收仍待补齐；以上全部通过前，S4 不得标记为已验收。
