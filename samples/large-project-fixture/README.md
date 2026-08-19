# 大型项目确定性夹具

该夹具不提交生成后的数千个源码文件。平台测试会在临时目录中确定性生成物理文件树（合成结构夹具），并在测试结束后删除：

- 4 个 Gradle Java 模块；
- 5 个数据源命名/目录维度；
- 1024 张模拟表及对应 Mapper 接口、XML；
- 5120 个 MyBatis statement；
- 20 个按模块和数据源拆分的 schema 文件。

测试会固定整个生成输入的 SHA-256 指纹，逐个执行带指纹生成区的安全合并，再用产品 XML 扫描器确认全部 namespace、SQL fragment 和 statement。人工内容位于生成区外，合并后必须保留。

该夹具不导入 Gradle 工程、不建立 Workspace Model 模块、不注册 Database Tools 数据源，也不等同于真实 IDE 索引、实库连接或 UI/EDT 性能证据。

## 验证命令

```bash
./gradlew test --tests '*S13GeneratedLargeProjectFixtureTest'
```

生成器位于测试源码中，目的是让 Linux、macOS 和 Windows 使用同一份 Java 实现，不依赖 Python、Shell 或数据库服务。
