# MyBatis Assistant

面向 IntelliJ IDEA 的 MyBatis 智能开发助手，采用独立实现路线开发。

当前处于 `0.1.0-SNAPSHOT` 开发预览阶段，首批只打通一条可验证的语义主链：

- 识别 Java Mapper 接口；
- 识别 MyBatis XML 的 `namespace`；
- 为 `select`、`insert`、`update`、`delete` 建立 statement 索引；
- 在唯一匹配时，从 Mapper 方法行号图标跳转到 XML statement；
- 对缺失、重复、重载等不确定场景保持静默，不做错误跳转。

## 开发环境

- IntelliJ IDEA 2026.1 / Build 261
- Java 21
- Gradle 9.3 Wrapper
- IntelliJ Platform Gradle Plugin 2.18.1

## 常用命令

```bash
./gradlew test
./gradlew buildPlugin
./gradlew verifyPlugin
./gradlew runIde
```

插件 ZIP 生成在 `build/distributions/`。

## 项目资料

- [商业级阶段实现规划](商业级阶段实现规划.md)
- [首批开发任务卡](docs/首批任务卡.md)
- [功能矩阵](docs/功能矩阵.md)
- [风险清单](docs/风险清单.md)
- [首批自动化验收记录](docs/首批验收记录.md)

## 独立实现边界

本项目依据 MyBatis、IntelliJ Platform 的公开文档和自行编写的测试语料独立实现，不反编译、不复制任何商业插件的源码、资源、名称或界面。

## 许可证

许可证尚未确定。在仓库加入明确许可证文件前，代码版权仍由作者保留。
