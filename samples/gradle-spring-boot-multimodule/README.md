# Gradle Spring Boot 多模块样例

这是一个可独立命令行构建的 Java 21 + Spring Boot + MyBatis 固定输入工程，用于验证工程自身的真实模块依赖、Mapper 接口与 XML 关联、动态 SQL、Spring Boot 配置和内存 H2 运行链路，并供后续插件 IDE 集成与实机验收复用。当前命令行测试不构成插件功能或 IDE 导入证据。

## 模块依赖

```text
domain <- api <- data <- app
                      ^
                      +-- MyBatis Mapper XML
```

- `domain`：领域对象和查询条件，不依赖框架；
- `api`：仓储契约，只依赖 `domain`；
- `data`：MyBatis Mapper 接口与 XML，实现 `api` 契约；
- `app`：`SpringBootApplication`、MyBatis 扫描与内存 H2 配置。

## 构建与验证

在仓库根目录执行：

```bash
./gradlew -p samples/gradle-spring-boot-multimodule clean check :app:bootJar --no-daemon
```

该样例统一复用仓库根目录已校验 SHA-256 的 Gradle 9.3.0 Wrapper，避免维护第二套
Wrapper 二进制。命令行验证必须从仓库根目录执行；单独导入 IntelliJ IDEA 的结果仍以
副屏或独立测试机验收记录为准，未取得该证据前不宣称真实 IDE 导入已通过。

四个子项目的 `gradle.lockfile` 固定已解析外部依赖的版本；当前未配置 Gradle 依赖制品
校验元数据，因此这里只证明版本解析边界，不宣称依赖制品经过字节级校验或跨系统制品
字节完全一致。

契约测试包含两层：

1. `data` 直接用 MyBatis 解析跨模块类型和动态 SQL；
2. `app` 启动无 Web 服务器的 Spring 测试上下文，以内存 H2 执行 Mapper XML 查询。

构建和测试只使用进程内内存 H2，不会启动外部数据库进程、Web 服务器或其他网络服务，也不需要本地数据库账号。
