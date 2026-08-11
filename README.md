# MyBatis Assistant

面向 IntelliJ IDEA 的 MyBatis 智能开发助手，采用独立实现路线开发。

当前处于 `0.1.0-SNAPSHOT` 开发预览阶段，S0～S2 已通过阶段验收，已形成可验证的双向语义主链与增量语义底座：

- 识别 Java Mapper 接口；
- 识别 MyBatis XML 的 `namespace`；
- 为 `select`、`insert`、`update`、`delete` 建立 statement 索引；
- 从 Java Mapper 方法导航到 XML statement，重复 statement 交由平台展示候选；
- 从 XML statement 导航回精确全限定名对应的 Java 接口方法，Java 重载全部保留为候选；
- 当精确 namespace 的 Mapper XML 已存在但同名 statement 缺失时，在 Java 方法名提供保守警告；
- 类型化区分无 Mapper XML、statement 缺失、多候选、索引未就绪、源失效和不支持的源，不做猜测跳转。
- 统一索引 Mapper namespace、四类 statement、`resultMap` 与 SQL fragment，重复声明保留全部候选；
- 识别 Java/Kotlin K2 Mapper、继承泛型方法、参数、返回实体、注解 SQL、`@Mapper` 与 `@MapperScan` 来源；
- 增量解析 MyBatis XML、Spring Boot/MyBatis-Plus 配置与 TypeAlias，并按模块依赖边界限制结果；
- 对未保存编辑、文件移动/删除、项目根变化、Dumb Mode 和取消请求做精确失效或保守降级。

## 开发环境

- IntelliJ IDEA 2026.1 / Build 261
- Java 21
- Gradle 9.3 Wrapper
- IntelliJ Platform Gradle Plugin 2.18.1

## 常用命令

```bash
mvn --batch-mode --file samples/java-mybatis-minimal/pom.xml clean verify
mvn --batch-mode --file samples/semantic-corpus/pom.xml clean verify
./gradlew check verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
./scripts/verify-sandbox-lifecycle.sh 20
./scripts/verify-optional-dependency-isolation.sh
./gradlew runIde
```

插件 ZIP 生成在 `build/distributions/`。

需要构造升级测试包时，可通过项目属性覆盖版本号：

```bash
./gradlew buildPlugin -PpluginVersion=0.1.1-SNAPSHOT
```

## 项目资料

- [交付阶段实施规划](交付阶段实施规划.md)
- [首批开发任务卡](docs/首批任务卡.md)
- [功能矩阵](docs/功能矩阵.md)
- [交付功能行为矩阵](docs/交付功能行为矩阵.md)
- [S0 产品基线与语料任务卡](docs/S0-产品基线与语料任务卡.md)
- [S0 产品基线与语料验收记录](docs/S0-产品基线与语料验收记录.md)
- [S1 插件骨架与流水线任务卡](docs/S1-插件骨架与流水线任务卡.md)
- [S1 插件骨架与流水线验收记录](docs/S1-插件骨架与流水线验收记录.md)
- [S2 统一语义模型与增量索引任务卡](docs/S2-统一语义模型与增量索引任务卡.md)
- [S2 统一语义模型与增量索引验收记录](docs/S2-统一语义模型与增量索引验收记录.md)
- [风险清单](docs/风险清单.md)
- [首批验收记录](docs/首批验收记录.md)
- [交付完成度审计](docs/交付完成度审计.md)
- [安全策略](.github/SECURITY.md)

## 独立实现边界

本项目依据 MyBatis、IntelliJ Platform 的公开文档和自行编写的测试语料独立实现，不反编译、不复制任何商业插件的源码、资源、名称或界面。

## 许可证

本项目采用 [Apache License 2.0](LICENSE) 许可证。
