# 生命周期 Inspection 最小语料

该目录是生命周期与可选依赖隔离门专用的真实 IntelliJ Java 模块。它通过
`.iml` 固定 Java 与资源源码根，避免命令行 Inspection 在 Maven/Gradle 异步导入
完成前开始扫描。语料仅使用 Java 语法和基本类型，不绑定本机 SDK 名称，因此不会
把不同 runner 的 JDK 安装名混入证据链。

唯一预期的 MyBatis 问题是
`io.github.mybatisideaassistant.lifecycle.UserMapper.findSummary` 没有对应 Java
方法。`findById` 必须双向匹配，用来证明 Java Mapper 与 Mapper XML 均已进入索引。

该最小语料只验证真实 IDE 进程中的插件加载、索引和 Inspection 证据链，不能替代
`samples/semantic-corpus` 的完整 Maven 语义契约。
