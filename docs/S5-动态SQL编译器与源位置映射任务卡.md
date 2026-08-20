# S5 动态 SQL 编译器与源位置映射任务卡

> 阶段：S5 动态 SQL 编译器与字符级源位置映射
> 日期：2026-08-11
> 状态：开发完成，当前候选本地统一门已通过，待 S5 远端与实机验收

## 目标

把 MyBatis XML statement 编译为可取消、可增量失效的符号化动态 SQL 中间表示，并建立虚拟 SQL 与原始 XML 的双向字符级位置映射。S5 不负责完整 OGNL 类型系统和 SQL 方言检查，但必须为 S6/S7 提供不会指数展开、能够准确回映射的唯一事实源。

## 固定边界

- 不用正则拼接整棵 XML；编译入口消费 XML PSI，纯 IR、条件与 source map 模型不依赖编辑器状态；
- `if`、`choose` 等分支保存符号化条件和树结构，不穷举条件组合；
- `where`、`set`、`trim`、`foreach` 保留运行期结构与属性，不伪装成唯一静态 SQL；
- `include` 只解析静态、唯一、模块可见的 SQL fragment；支持跨 namespace、嵌套 property 替换和循环诊断；
- `bind`、foreach item/index 建立词法作用域，但表达式 AST 与类型推导由 S6 消费；
- XML 注释不进入 SQL；普通文本、CDATA 与 entity 解码后的虚拟字符必须保留来源映射；
- 生成的前后缀、分隔符、占位标记等合成文本必须标记为 `SYNTHETIC`，不得冒充原文精确字符；
- Dumb Mode、索引竞态、项目关闭、失效 PSI 和用户取消均类型化停止，不扫描项目文件系统、不吞取消异常。

## 分批实施

| 批次 | 内容 | 状态 | 退出证据 |
|---|---|---|---|
| S5-A | 符号化 IR、映射段、双向查询、普通文本/CDATA/entity 编译 | 开发完成，待统一验收 | 纯模型不变量测试、平台 XML 黄金测试、字符级 round-trip |
| S5-B | `if`、`choose/when/otherwise`、`where`、`set`、`trim` | 开发完成，待统一验收 | 嵌套标签、空分支、前后缀覆盖、无组合爆炸测试 |
| S5-C | `foreach`、`bind`、`include`、property 替换、跨 namespace 与循环 | 开发完成，待统一验收 | 作用域、唯一解析、循环/多目标/占位符失败测试；元素类型由 S6 消费 S4 类型模型 |
| S5-D | 缓存失效、模糊测试、性能、下游回映射 API 与真实 IDE 验收 | 代码与统一数据门完成，待生命周期与副屏 | 随机树、未保存编辑、1000 节点预算、取消、生命周期和副屏交互 |
| S5-E | `v1` 版本化黄金文件 | 代码、定向测试与当前候选本地统一门已通过 | 三组输入 XML/期望输出固定静态、动态与 include/property/source-map 行为；版本目录不可静默覆盖 |

## S5-A 验收矩阵

| 编号 | 场景 | 必须输出 | 保守边界 |
|---|---|---|---|
| S5-A-01 | 普通 SQL 文本与空白 | 顺序、换行和占位符文本保持；每个虚拟字符可回到原 XML | 不自动格式化或折叠空白 |
| S5-A-02 | CDATA | CDATA 内容进入虚拟 SQL，边界标记不进入 | 内容字符映射到 CDATA 内部，不映射到 `<![CDATA[` |
| S5-A-03 | XML entity | `&lt;`、`&gt;`、`&amp;` 等解码为 SQL 字符 | 一个虚拟字符可映射到完整 entity 源范围 |
| S5-A-04 | XML 注释 | 注释不进入虚拟 SQL | 注释源范围反向查询为空 |
| S5-A-05 | 映射查询 | 支持虚拟范围→一个或多个源范围、源偏移→虚拟范围 | 文件、范围非法时返回类型化失败，不截断猜测 |
| S5-A-06 | IR 不变量 | 节点不可变、子节点有序、范围合法、条件与作用域显式 | 不持有裸失效 PSI，不把异常文本作为状态 |

## S5-B 验收矩阵

| 编号 | 场景 | 必须输出 | 保守边界 |
|---|---|---|---|
| S5-B-01 | `if` | 条件文本、条件来源与有序 body 节点 | 条件不在 S5 求真假 |
| S5-B-02 | `choose` | when 顺序、otherwise 唯一性和短路语义 | 非法重复 otherwise 产生诊断，不选第一个静默修复 |
| S5-B-03 | `where`/`set` | 独立 Trim 节点保存 prefix 与覆盖规则 | 子节点运行期全空时不强行生成 WHERE/SET |
| S5-B-04 | `trim` | prefix/suffix/prefixOverrides/suffixOverrides 保持原始语义 | 动态或不完整属性产生诊断并保留可分析子树 |
| S5-B-05 | 复杂度 | 深度与节点数线性增长 | 禁止按分支数量生成笛卡尔积 |

## S5-C 验收矩阵

| 编号 | 场景 | 必须输出 | 保守边界 |
|---|---|---|---|
| S5-C-01 | `foreach` | collection、item、index、open/close/separator、nullable 与 body；item/index 词法作用域 | 集合元素类型未知时保持 unknown，不伪造 Object 属性 |
| S5-C-02 | `bind` | name/value 与声明后可见作用域 | S5 仅保存表达式和作用域，不实现完整 OGNL AST |
| S5-C-03 | 本 namespace include | 唯一 SQL fragment 展开并保留跨文件 source map | 重复 fragment、动态 refid 或失效目标停止该 include |
| S5-C-04 | 跨 namespace include | 精确 namespace + id、模块可见性与来源文件 | 不按文件名/简单 namespace 猜测 |
| S5-C-05 | include property | 静态 name/value 替换作用域可嵌套且可追踪来源 | 动态 name、循环替换或无法确定值产生诊断 |
| S5-C-06 | include 循环 | 输出包含完整 include 链的循环诊断并终止该分支 | 不递归溢出，不污染其他 statement |

## S5-D 统一门禁

```bash
mvn --batch-mode --file samples/java-mybatis-minimal/pom.xml clean verify
mvn --batch-mode --file samples/semantic-corpus/pom.xml clean verify
./gradlew check verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
./scripts/verify-sandbox-lifecycle.sh 1 samples/lifecycle-inspection-corpus true 2026.1.4
./scripts/verify-sandbox-lifecycle.sh 100 samples/lifecycle-inspection-corpus true 2026.1.4
./scripts/verify-optional-dependency-isolation.sh
```

截至 2026-08-19，S5-A～S5-E 已通过 760/760、84.40%、各分区覆盖率、最低 Verifier、结构、本地化和 SBOM；2026-08-20，`37bedad9` 的远端必需检查、生命周期 1/1、100/100 与隔离 5/5 全部通过。后续发布候选的精确 `main` SHA 和副屏真实 IDEA 验收仍是阻断项；全部通过后，S5 才能标记为已验收。
