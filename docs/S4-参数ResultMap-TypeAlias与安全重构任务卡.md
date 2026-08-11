# S4 参数、ResultMap、TypeAlias 与安全重构任务卡

> 阶段：S4 参数、ResultMap、TypeAlias 与安全重构
> 日期：2026-08-11
> 状态：开发中

## 目标

在 S2/S3 的统一模型和精确引用上，补齐日常 Mapper XML 所需的参数名、嵌套属性、ResultMap、TypeAlias 与跨文件重命名语义。所有检查只报告静态可证明的问题；所有重构必须由 IntelliJ 原生引用与重构处理器完成预览、冲突检测和单次 Undo。

## 固定边界

- 参数名遵守 MyBatis `ParamNameResolver`：排除 `RowBounds`/`ResultHandler`，支持显式 `@Param`、未注解参数的实际名/`argN`/数字回退名、`paramN`、单参数对象和集合/数组包装名；
- Java 源参数名是否进入运行期字节码可能受编译配置影响，检查不得把仅依赖实际参数名的表达式误报为必错；
- Map 键、`${}` 动态标识符和无法静态确定的 Provider/动态 SQL保持未知，不伪造属性；
- S4 只解析点路径、数组/列表索引与明确的 Mapper 属性位置；完整 OGNL 语法、foreach/bind 变量作用域和动态 SQL source map 由 S5/S6 负责；
- ResultMap 的 `property` 只按当前嵌套 Java 类型解析，`column` 没有数据库元数据时只做结构补全，不报告不存在；
- TypeAlias 多目标必须保留候选，未解析占位符和不可见模块不猜测；
- 重命名遇到多目标、只读、冲突或不完整 PSI 时停止，不做文本替换兜底。

## 分批实施

| 批次 | 内容 | 状态 | 退出证据 |
|---|---|---|---|
| S4-A | 参数命名、特殊参数、单参数、集合/数组、Map 与泛型上下文模型 | 开发完成，待 S4 统一验收 | 纯模型与平台 PSI 黄金测试；参数模型行覆盖率不低于 90% |
| S4-B | `#{}`、`${}`、`keyProperty`、`property`、`collection` 的静态引用、补全与检查 | 开发完成，待 S4 统一验收 | 根名/嵌套属性/索引/未知 Map/不完整输入、Dumb/取消与真实编辑器测试 |
| S4-C | ResultMap property/类型、TypeAlias 引用与补全 | 待实现 | association/collection/constructor/discriminator/extends/columnPrefix 与别名冲突测试 |
| S4-D | statement、`@Param`、resultMap/refid、实体属性的安全重命名 | 待实现 | 原生预览、多文件、只读、冲突、模块隔离、单次 Undo 与副屏实机 |

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

## 阶段统一门禁

```bash
mvn --batch-mode --file samples/java-mybatis-minimal/pom.xml clean verify
mvn --batch-mode --file samples/semantic-corpus/pom.xml clean verify
./gradlew check verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
./scripts/verify-sandbox-lifecycle.sh 20
./scripts/verify-optional-dependency-isolation.sh
```

只有 S4-A～S4-D 全部通过自动化、最低 261 Verifier 和副屏真实 IDEA 验收后，S4 才能标记为已验收。
