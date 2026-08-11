package io.github.ns3154.mybatisassistant.sqltool.testgen;

import com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;

/**
 * 生成 JUnit 4/5 Mapper 方法测试骨架，不加载 Mapper 类，也不访问数据库。
 */
public final class MyBatisMapperTestSkeletonGenerator {
    private static final Set<String> PRIMITIVE_NUMBERS = Set.of(
            "byte", "short", "int", "long", "float", "double");

    private MyBatisMapperTestSkeletonGenerator() {
    }

    public static @NotNull MyBatisMapperTestGeneration generate(
            @NotNull MyBatisMapperTestRequest request) {
        ProgressManager.checkCanceled();
        String className = className(request);
        StringBuilder source = new StringBuilder();
        if (!request.packageName().isBlank()) {
            source.append("package ").append(request.packageName()).append(";\n\n");
        }
        appendImports(source, request.platform());
        appendClass(source, request, className);
        return new MyBatisMapperTestGeneration(className + ".java", source.toString());
    }

    private static void appendImports(
            StringBuilder source,
            MyBatisJUnitPlatform platform) {
        if (platform == MyBatisJUnitPlatform.JUNIT_5) {
            source.append("import org.junit.jupiter.api.BeforeEach;\n")
                    .append("import org.junit.jupiter.api.Test;\n\n")
                    .append("import static org.junit.jupiter.api.Assertions.assertNotNull;\n\n");
        } else {
            source.append("import org.junit.Before;\n")
                    .append("import org.junit.Test;\n\n")
                    .append("import static org.junit.Assert.assertNotNull;\n\n");
        }
    }

    private static void appendClass(
            StringBuilder source,
            MyBatisMapperTestRequest request,
            String className) {
        boolean junit4 = request.platform() == MyBatisJUnitPlatform.JUNIT_4;
        source.append(junit4 ? "public class " : "class ")
                .append(className).append(" {\n")
                .append("    private ").append(request.mapperQualifiedName())
                .append(" mapper;\n\n")
                .append(junit4 ? "    @Before\n" : "    @BeforeEach\n")
                .append(junit4 ? "    public void setUp() {\n" : "    void setUp() {\n")
                .append("        // TODO 使用项目的 MyBatis/Spring 测试上下文注入真实 Mapper\n")
                .append("        mapper = null;\n")
                .append("    }\n\n")
                .append("    @Test\n")
                .append(junit4 ? "    public void " : "    void ")
                .append(testMethodName(request.methodName())).append("() {\n");
        for (MyBatisMapperTestParameter parameter : request.parameters()) {
            ProgressManager.checkCanceled();
            String expression = defaultExpression(parameter.canonicalType());
            source.append("        ");
            if ("null".equals(expression)) {
                source.append(parameter.canonicalType());
            } else {
                source.append("var");
            }
            source.append(' ').append(parameter.name()).append(" = ")
                    .append(expression).append(";\n");
        }
        if (!request.parameters().isEmpty()) {
            source.append('\n');
        }
        source.append("        ");
        if (!request.returnsVoid()) {
            source.append("var actual = ");
        }
        source.append("mapper.").append(request.methodName()).append('(');
        for (int index = 0; index < request.parameters().size(); index++) {
            if (index > 0) {
                source.append(", ");
            }
            source.append(request.parameters().get(index).name());
        }
        source.append(");\n");
        if (request.returnsVoid()) {
            source.append("        // TODO 断言数据库状态或受影响记录\n");
        } else {
            source.append("        assertNotNull(actual);\n");
        }
        source.append("    }\n}");
    }

    static @NotNull String defaultExpression(@NotNull String canonicalType) {
        String type = canonicalType.strip();
        if ("boolean".equals(type) || "java.lang.Boolean".equals(type)) {
            return "false";
        }
        if ("char".equals(type) || "java.lang.Character".equals(type)) {
            return "'\\0'";
        }
        if (PRIMITIVE_NUMBERS.contains(type)) {
            return switch (type) {
                case "long" -> "0L";
                case "float" -> "0F";
                case "double" -> "0D";
                default -> "0";
            };
        }
        if (Set.of("java.lang.Byte", "java.lang.Short", "java.lang.Integer")
                .contains(type)) {
            return "0";
        }
        if ("java.lang.Long".equals(type)) {
            return "0L";
        }
        if ("java.lang.Float".equals(type)) {
            return "0F";
        }
        if ("java.lang.Double".equals(type)) {
            return "0D";
        }
        if ("java.lang.String".equals(type) || "String".equals(type)) {
            return "\"\"";
        }
        if ("java.math.BigDecimal".equals(type)) {
            return "java.math.BigDecimal.ZERO";
        }
        if ("java.time.LocalDate".equals(type)) {
            return "java.time.LocalDate.now()";
        }
        if ("java.time.LocalDateTime".equals(type)) {
            return "java.time.LocalDateTime.now()";
        }
        if ("java.util.UUID".equals(type)) {
            return "new java.util.UUID(0L, 0L)";
        }
        if (type.endsWith("[]") && !type.contains("<") && !type.contains("?")) {
            return "new " + type.substring(0, type.length() - 2) + "[0]";
        }
        String rawType = type.replaceAll("<.*>", "");
        if ("java.util.List".equals(rawType)
                || "java.util.Collection".equals(rawType)) {
            return "java.util.List.of()";
        }
        if ("java.util.Set".equals(rawType)) {
            return "java.util.Set.of()";
        }
        if ("java.util.Map".equals(rawType)) {
            return "java.util.Map.of()";
        }
        if ("java.util.Optional".equals(rawType)) {
            return "java.util.Optional.empty()";
        }
        return "null";
    }

    private static @NotNull String className(MyBatisMapperTestRequest request) {
        String suffix = request.stableSignature().equals(request.methodName() + "()")
                ? "" : "_" + Integer.toUnsignedString(
                        request.stableSignature().hashCode(), 36).toUpperCase(Locale.ROOT);
        return request.mapperSimpleName() + capitalize(request.methodName())
                + suffix + "Test";
    }

    private static @NotNull String testMethodName(String methodName) {
        return "test" + capitalize(methodName);
    }

    private static @NotNull String capitalize(String text) {
        if (text.isEmpty()) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
