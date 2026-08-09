# Java + MyBatis 最小样例

这个项目是 MyBatis IDEA Assistant 首批开发的可运行语料，验证 Java Mapper 接口、XML `namespace` 和 statement `id` 的关联。它不是插件源码，也不依赖外部数据库。

## 环境

- JDK 21
- Maven 3.9 或兼容版本

## 构建

在仓库根目录执行：

```bash
mvn -f samples/java-mybatis-minimal/pom.xml clean verify
```

测试会启动 H2 内存数据库、加载 MyBatis 配置、执行建表脚本，并通过真实 Mapper 代理调用 `findById` 和 `insert`。成功结果同时证明以下关系有效：

```text
io.github.mybatisideaassistant.sample.mapper.UserMapper
                              │ namespace 完全相同
                              ▼
mappers/UserMapper.xml
                              │ 方法名与 statement id 完全相同
                              ▼
findById / insert
```

## 目录说明

```text
src/main/java
├── domain/User.java                 可映射实体
└── mapper/UserMapper.java           正常 Mapper 接口

src/main/resources
├── mybatis-config.xml               只加载正常 Mapper
├── schema.sql                       H2 建表与初始数据
└── mappers/UserMapper.xml           正常 XML

src/test/java
├── MyBatisAssociationTest.java      真实运行时关联测试
├── FailureCorpusContractTest.java   失败语料隔离契约测试
└── fixtures                         可编译的失败 Mapper 接口

src/test/resources/corpus/failures
├── namespace-mismatch               namespace 不匹配
└── statement-missing                statement 缺失
```

## 失败语料为何不会破坏构建

失败 Mapper 接口放在 `src/test/java`，因此仍会经过 Java 编译，能够被后续 IntelliJ Fixture 当成真实 Java PSI 使用。对应 XML 放在测试资源的独立 `corpus/failures` 目录，不会被生产 `mybatis-config.xml` 加载。

`FailureCorpusContractTest` 会反向锁定失败条件：

- namespace 不匹配语料必须继续保持 Java 全限定名与 XML namespace 不同；
- statement 缺失语料必须保持 namespace 正确，但没有与 Java 方法同名的 statement。

这样既能让 `mvn verify` 保持绿色，也能防止后续整理样例时误把失败语料“修好”。

## 首批插件验收用法

1. 在目标 IDEA 中以 Maven 项目打开本目录；
2. 从 `UserMapper.findById` 验证到正常 XML statement 的跳转；
3. 把 `src/test/java` 与 `src/test/resources/corpus/failures` 作为平台测试输入；
4. 对 namespace 不匹配语料断言“无 Mapper XML 关联”；
5. 对 statement 缺失语料断言“namespace 已匹配但 statement 不存在”。

失败语料只定义解析失败，不要求 MyBatis 在运行时加载它们。
