package io.github.ns3154.mybatisassistant.sqltool.conversion;

import io.github.ns3154.mybatisassistant.sqltool.log.MyBatisJdbcPlaceholderRewriteResult;
import io.github.ns3154.mybatisassistant.sqltool.log.MyBatisJdbcPlaceholderRewriter;
import io.github.ns3154.mybatisassistant.sqltool.log.MyBatisSqlRisk;
import io.github.ns3154.mybatisassistant.sqltool.log.MyBatisSqlRiskAssessment;
import io.github.ns3154.mybatisassistant.sqltool.log.MyBatisSqlRiskClassifier;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 把保守可解析的单条 SELECT 转为纯内存 MyBatis 预览产物。
 */
public final class MyBatisSelectArtifactConverter {
    public static final int MAX_INPUT_BYTES = 1024 * 1024;

    private MyBatisSelectArtifactConverter() {
    }

    public static @NotNull MyBatisSelectArtifactConversionResult convert(
            @NotNull String sql,
            @NotNull String basePackage,
            @NotNull String mapperSimpleName,
            @NotNull String methodName) {
        if (sql.length() > MAX_INPUT_BYTES
                || sql.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            return failure(0, "SELECT 超过 1 MiB 转换上限");
        }
        String validation = validateNames(basePackage, mapperSimpleName, methodName);
        if (validation != null) {
            return failure(0, validation);
        }
        MyBatisSqlRiskAssessment risk = MyBatisSqlRiskClassifier.assess(sql);
        if (!risk.structurallyValid() || risk.statementCount() != 1
                || risk.risk() != MyBatisSqlRisk.READ_ONLY
                || !sql.stripLeading().regionMatches(true, 0, "SELECT", 0, 6)) {
            return failure(0, "仅支持单条、词法完整且以 SELECT 开头的只读 SQL");
        }
        MyBatisSelectProjectionResult projection =
                MyBatisSelectProjectionParser.parse(sql);
        if (projection instanceof MyBatisSelectProjectionResult.Failure failed) {
            return failure(failed.offset(), failed.message());
        }
        List<MyBatisSelectColumn> columns =
                ((MyBatisSelectProjectionResult.Success) projection).columns();
        int placeholders = io.github.ns3154.mybatisassistant.sqltool.log
                .MyBatisSqlPlaceholderAnalyzer.analyze(sql).placeholderCount();
        List<String> replacements = new ArrayList<>(placeholders);
        for (int index = 1; index <= placeholders; index++) {
            replacements.add("#{param" + index + ",jdbcType=OTHER}");
        }
        MyBatisJdbcPlaceholderRewriteResult rewritten =
                MyBatisJdbcPlaceholderRewriter.rewrite(sql, replacements);
        if (rewritten instanceof MyBatisJdbcPlaceholderRewriteResult.Failure failed) {
            return failure(0, failed.message());
        }
        String myBatisSql = ((MyBatisJdbcPlaceholderRewriteResult.Success) rewritten).sql();
        String rowName = mapperSimpleName.endsWith("Mapper")
                ? mapperSimpleName.substring(0, mapperSimpleName.length() - 6) + "Row"
                : mapperSimpleName + "Row";
        return new MyBatisSelectArtifactConversionResult.Success(
                mapperSource(basePackage, mapperSimpleName, methodName, rowName, placeholders),
                xmlSource(basePackage, mapperSimpleName, methodName, rowName, myBatisSql, columns),
                rowSource(basePackage, rowName, columns),
                List.of("SELECT 结果 JDBC 类型未知，Java 字段与参数使用 Object/OTHER；应用前必须确认"));
    }

    private static String mapperSource(
            String basePackage,
            String mapperName,
            String methodName,
            String rowName,
            int placeholders) {
        StringBuilder source = new StringBuilder("package ")
                .append(basePackage).append(".mapper;\n\n")
                .append("import ").append(basePackage).append(".model.")
                .append(rowName).append(";\n")
                .append("import java.util.List;\n");
        if (placeholders > 0) {
            source.append("import org.apache.ibatis.annotations.Param;\n");
        }
        source.append("\npublic interface ").append(mapperName).append(" {\n")
                .append("    List<").append(rowName).append("> ")
                .append(methodName).append('(');
        for (int index = 1; index <= placeholders; index++) {
            if (index > 1) {
                source.append(", ");
            }
            source.append("@Param(\"param").append(index)
                    .append("\") Object param").append(index);
        }
        return source.append(");\n}\n").toString();
    }

    private static String xmlSource(
            String basePackage,
            String mapperName,
            String methodName,
            String rowName,
            String sql,
            List<MyBatisSelectColumn> columns) {
        String namespace = basePackage + ".mapper." + mapperName;
        String type = basePackage + ".model." + rowName;
        String resultMap = methodName + "ResultMap";
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n")
                .append("<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" ")
                .append("\"https://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n")
                .append("<mapper namespace=\"").append(namespace).append("\">\n")
                .append("  <resultMap id=\"").append(resultMap).append("\" type=\"")
                .append(type).append("\">\n");
        for (MyBatisSelectColumn column : columns) {
            xml.append("    <result column=\"").append(xml(column.sqlLabel()))
                    .append("\" property=\"").append(column.javaProperty())
                    .append("\" jdbcType=\"OTHER\"/>\n");
        }
        xml.append("  </resultMap>\n\n")
                .append("  <select id=\"").append(methodName)
                .append("\" resultMap=\"").append(resultMap).append("\">\n")
                .append(indent(xml(sql.stripTrailing().replaceFirst(";\\s*$", "")), 4))
                .append("\n  </select>\n")
                .append("</mapper>\n");
        return xml.toString();
    }

    private static String rowSource(
            String basePackage,
            String rowName,
            List<MyBatisSelectColumn> columns) {
        StringBuilder source = new StringBuilder("package ")
                .append(basePackage).append(".model;\n\n")
                .append("public class ").append(rowName).append(" {\n");
        for (MyBatisSelectColumn column : columns) {
            source.append("    private Object ").append(column.javaProperty()).append(";\n");
        }
        for (MyBatisSelectColumn column : columns) {
            String upper = Character.toUpperCase(column.javaProperty().charAt(0))
                    + column.javaProperty().substring(1);
            source.append("\n    public Object get").append(upper).append("() {\n")
                    .append("        return ").append(column.javaProperty()).append(";\n")
                    .append("    }\n\n")
                    .append("    public void set").append(upper).append("(Object value) {\n")
                    .append("        this.").append(column.javaProperty()).append(" = value;\n")
                    .append("    }\n");
        }
        return source.append("}\n").toString();
    }

    private static String validateNames(String basePackage, String mapper, String method) {
        if (basePackage.isBlank() || mapper.isBlank() || method.isBlank()) {
            return "基础包名、Mapper 名称和方法名不能为空";
        }
        for (String segment : basePackage.split("\\.")) {
            if (!javaIdentifier(segment)) {
                return "基础包名不合法：" + basePackage;
            }
        }
        if (!javaIdentifier(mapper) || !javaIdentifier(method)) {
            return "Mapper 名称或方法名不是合法 Java 标识符";
        }
        return null;
    }

    private static boolean javaIdentifier(String value) {
        if (value.isBlank() || !Character.isJavaIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int index = 1; index < value.length(); index++) {
            if (!Character.isJavaIdentifierPart(value.charAt(index))) {
                return false;
            }
        }
        return !SetHolder.JAVA_KEYWORDS.contains(value);
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String indent(String text, int spaces) {
        String prefix = " ".repeat(spaces);
        return prefix + text.replace("\n", "\n" + prefix);
    }

    private static MyBatisSelectArtifactConversionResult.Failure failure(
            int offset,
            String message) {
        return new MyBatisSelectArtifactConversionResult.Failure(offset, message);
    }

    private static final class SetHolder {
        private static final java.util.Set<String> JAVA_KEYWORDS = java.util.Set.of(
                "abstract", "assert", "boolean", "break", "byte", "case", "catch",
                "char", "class", "const", "continue", "default", "do", "double",
                "else", "enum", "extends", "final", "finally", "float", "for", "goto",
                "if", "implements", "import", "instanceof", "int", "interface", "long",
                "native", "new", "package", "private", "protected", "public", "return",
                "short", "static", "strictfp", "super", "switch", "synchronized", "this",
                "throw", "throws", "transient", "try", "void", "volatile", "while",
                "true", "false", "null", "record", "sealed", "permits", "var", "yield");

        private SetHolder() {
        }
    }
}
