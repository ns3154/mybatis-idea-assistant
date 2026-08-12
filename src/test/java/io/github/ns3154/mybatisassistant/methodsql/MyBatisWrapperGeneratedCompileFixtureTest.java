package io.github.ns3154.mybatisassistant.methodsql;

import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 把当前 Wrapper 生成器的真实输出锁定为 Maven 编译语料，避免手写 API 镜像假绿。
 */
public class MyBatisWrapperGeneratedCompileFixtureTest {
    private static final String UPDATE_ENV = "UPDATE_WRAPPER_COMPILE_FIXTURES";
    private static final String PLUS_VERSION = "3.5.17";
    private static final String FLEX_VERSION = "1.11.8";
    private static final String PLUS_PACKAGE =
            "io.github.mybatisideaassistant.corpus.framework.plus";
    private static final String FLEX_PACKAGE =
            "io.github.mybatisideaassistant.corpus.framework.flex";
    private static final Path FRAMEWORK_SOURCE_ROOT = Path.of(
            "samples", "semantic-corpus", "framework-adapters", "src", "main", "java");
    private static final Path SEMANTIC_CORPUS_POM = Path.of(
            "samples", "semantic-corpus", "pom.xml");

    @Test
    public void locksMavenCompiledFixturesToCurrentGeneratorOutput() throws IOException {
        assertFixture(
                PLUS_PACKAGE,
                "PlusGeneratedWrapperSample",
                MyBatisWrapperFramework.MYBATIS_PLUS,
                PLUS_VERSION,
                plusSchema(),
                "io.github.mybatisideaassistant.corpus.framework.plus.PlusUser");
        assertFixture(
                FLEX_PACKAGE,
                "FlexGeneratedWrapperSample",
                MyBatisWrapperFramework.MYBATIS_FLEX,
                FLEX_VERSION,
                flexSchema(),
                "io.github.mybatisideaassistant.corpus.framework.flex.FlexUser");
    }

    @Test
    public void locksMavenFrameworkDependencyVersions() throws IOException {
        String pom = Files.readString(SEMANTIC_CORPUS_POM, StandardCharsets.UTF_8);
        assertTrue(pom.contains("<mybatis.plus.version>" + PLUS_VERSION
                + "</mybatis.plus.version>"));
        assertTrue(pom.contains("<mybatis.flex.version>" + FLEX_VERSION
                + "</mybatis.flex.version>"));
    }

    private static void assertFixture(
            String packageName,
            String className,
            MyBatisWrapperFramework framework,
            String version,
            MyBatisMethodSchema schema,
            String entityType) throws IOException {
        String expected = fixtureSource(
                packageName, className, framework, version, schema, entityType);
        Path source = FRAMEWORK_SOURCE_ROOT
                .resolve(packageName.replace('.', '/'))
                .resolve(className + ".java");
        if ("true".equalsIgnoreCase(System.getenv(UPDATE_ENV))) {
            Files.createDirectories(source.getParent());
            Files.writeString(source, expected, StandardCharsets.UTF_8);
        }
        assertTrue("Wrapper 编译语料不存在：" + source, Files.isRegularFile(source));
        assertEquals(
                "Wrapper 编译语料不是当前生成器的真实输出；如本次有意修改生成器，"
                        + "请设置 " + UPDATE_ENV + "=true 重新运行本测试",
                expected,
                normalizeLineEndings(Files.readString(source, StandardCharsets.UTF_8)));
    }

    private static String fixtureSource(
            String packageName,
            String className,
            MyBatisWrapperFramework framework,
            String version,
            MyBatisMethodSchema schema,
            String entityType) {
        return "package " + packageName + ";\n\n"
                + "/**\n"
                + " * 由当前 MyBatisWrapperGenerator 直接导出，并由 Maven 使用锁定框架版本编译。\n"
                + " */\n"
                + "public final class " + className + " {\n"
                + "    private " + className + "() {\n"
                + "    }\n\n"
                + methodSource(
                        "collectionsAndRange",
                        "findByRankInAndStatusNotInAndAgeBetween",
                        framework,
                        version,
                        schema,
                        entityType)
                + "\n"
                + methodSource(
                        "singleOr",
                        "findByStatusOrAgeGreaterThan",
                        framework,
                        version,
                        schema,
                        entityType)
                + "\n"
                + methodSource(
                        "multiOr",
                        "findByStatusOrAgeGreaterThanAndActiveTrue",
                        framework,
                        version,
                        schema,
                        entityType)
                + "\n"
                + methodSource(
                        "parameterNameConflicts",
                        "findByWrapperOrGroup1AndActiveTrue",
                        framework,
                        version,
                        schema,
                        entityType)
                + "}\n";
    }

    private static String methodSource(
            String javaMethodName,
            String parsedMethodName,
            MyBatisWrapperFramework framework,
            String version,
            MyBatisMethodSchema schema,
            String entityType) {
        MyBatisMethodParseResult parseResult = MyBatisMethodNameParser.parse(
                parsedMethodName, schema);
        assertTrue("编译语料方法名必须可解析：" + parsedMethodName,
                parseResult instanceof MyBatisMethodParseResult.Success);
        MyBatisMethodQuery query = ((MyBatisMethodParseResult.Success) parseResult).query();
        MyBatisMethodGeneration methodGeneration = MyBatisMethodSqlGenerator.generate(
                new MyBatisMethodGenerationRequest(
                        schema,
                        query,
                        MyBatisSqlDialect.POSTGRESQL,
                        entityType,
                        true,
                        Set.of()));
        MyBatisWrapperGeneration wrapperGeneration = MyBatisWrapperGenerator.generate(
                new MyBatisWrapperGenerationRequest(
                        schema,
                        query,
                        methodGeneration,
                        framework,
                        version,
                        entityType,
                        MyBatisSqlDialect.POSTGRESQL,
                        true,
                        Set.of()));

        String parameters = methodGeneration.parameters().stream()
                .map(parameter -> "            " + parameter.javaType() + " " + parameter.name())
                .collect(Collectors.joining(",\n"));
        String body = wrapperGeneration.code().lines()
                .map(line -> "        " + line)
                .collect(Collectors.joining("\n"));
        return "    public static " + wrapperGeneration.wrapperType() + " " + javaMethodName
                + "(\n" + parameters + ") {\n"
                + body + "\n"
                + "        return " + wrapperGeneration.variableName() + ";\n"
                + "    }\n";
    }

    private static MyBatisMethodSchema plusSchema() {
        return new MyBatisMethodSchema("users", fields());
    }

    private static String normalizeLineEndings(String source) {
        return source.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static MyBatisMethodSchema flexSchema() {
        return new MyBatisMethodSchema(
                Optional.empty(), Optional.of("audit"), "users", fields());
    }

    private static List<MyBatisMethodField> fields() {
        return List.of(
                field("rankValue", "Rank", "order", "java.lang.Integer", Types.INTEGER),
                field("status", "Status", "status", "java.lang.String", Types.VARCHAR),
                field("age", "Age", "age", "java.lang.Integer", Types.INTEGER),
                field("active", "Active", "active", "java.lang.Boolean", Types.BOOLEAN),
                field("wrapper", "Wrapper", "wrapper_value", "java.lang.String", Types.VARCHAR),
                field("group1", "Group1", "group_value", "java.lang.String", Types.VARCHAR));
    }

    private static MyBatisMethodField field(
            String property,
            String token,
            String column,
            String javaType,
            int jdbcType) {
        return new MyBatisMethodField(
                property,
                token,
                column,
                javaType,
                Optional.empty(),
                jdbcType,
                true,
                false,
                false);
    }
}
