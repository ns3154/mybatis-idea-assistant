# 可构建语义语料

该工程是 S0 的独立语义语料入口，不是演示应用。它用真实依赖和可编译源码固定后续 S2～S11 必须支持的输入结构。

## 覆盖范围

- `contracts-java`：Java Mapper、继承、单/多参数、`@Param`、集合、Map、泛型、嵌套属性和注解 SQL；
- `spring-application`：跨模块 XML、`@MapperScan`、TypeAlias、ResultMap、association、collection、constructor、columnPrefix 和全部常用动态标签；
- `kotlin-k2`：Kotlin 2.3 Mapper、数据类、可空类型、默认参数和 XML；
- `framework-adapters`：MyBatis-Plus、MyBatis-Flex、TkMapper 的真实基类；其中 Plus 3.5.17/Flex 1.11.8 会直接编译由当前 Wrapper 生成器导出并逐字锁定的 Java 语料；
- `dialect-fixtures`：MySQL、PostgreSQL、Oracle、SQL Server、SQLite、达梦六种方言夹具；其中首五种另有精确版本清单和版本特征 SQL，避免“支持某数据库”却没有可追溯版本边界。

## 验证命令

```bash
mvn --batch-mode --file samples/semantic-corpus/pom.xml clean verify
```

失败语料只放在各模块的 `src/test/resources/corpus/failures` 下，不进入应用运行时资源。
