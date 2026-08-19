package io.github.ns3154.mybatisassistant.sqltool.conversion;

import com.intellij.openapi.progress.ProgressManager;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 从已确认的 Java 字段模型生成可预览 DDL，不读取数据库或凭证。
 */
public final class MyBatisJavaDdlGenerator {
    private MyBatisJavaDdlGenerator() {
    }

    public static @NotNull MyBatisJavaDdlGeneration generate(
            @NotNull MyBatisJavaTableSchema table,
            @NotNull MyBatisSqlDialect dialect) {
        validate(table);
        List<String> warnings = new ArrayList<>();
        String qualifiedTable = table.schema()
                .map(schema -> quote(schema, dialect) + ".")
                .orElse("") + quote(table.tableName(), dialect);
        List<String> primaryKeys = table.fields().stream()
                .filter(MyBatisJavaFieldSchema::primaryKey)
                .map(field -> quote(field.columnName(), dialect))
                .toList();
        StringBuilder ddl = new StringBuilder("CREATE TABLE ")
                .append(qualifiedTable).append(" (\n");
        for (int index = 0; index < table.fields().size(); index++) {
            ProgressManager.checkCanceled();
            MyBatisJavaFieldSchema field = table.fields().get(index);
            TypeMapping type = sqlType(field.javaType(), dialect);
            if (type.warning != null) {
                warnings.add(MyBatisAssistantBundle.message(
                        "sqltool.conversion.warning.field", field.propertyName(), type.warning));
            }
            boolean sqliteInlineKey = dialect == MyBatisSqlDialect.SQLITE
                    && field.primaryKey() && field.autoIncrement()
                    && isIntegerType(type.sqlType);
            ddl.append("    ").append(quote(field.columnName(), dialect)).append(' ')
                    .append(sqliteInlineKey ? "INTEGER" : type.sqlType);
            if (sqliteInlineKey) {
                ddl.append(" PRIMARY KEY AUTOINCREMENT");
            } else {
                appendIdentity(ddl, field, dialect);
                if (field.autoIncrement() && dialect == MyBatisSqlDialect.GENERIC) {
                    warnings.add(MyBatisAssistantBundle.message(
                            "sqltool.conversion.warning.identity.generic",
                            field.propertyName()));
                }
                if (!field.nullable() || field.primaryKey()) {
                    ddl.append(" NOT NULL");
                }
            }
            if (dialect == MyBatisSqlDialect.MYSQL && field.comment().isPresent()) {
                ddl.append(" COMMENT '").append(sqlString(field.comment().orElseThrow()))
                        .append('\'');
            }
            boolean lastColumn = index == table.fields().size() - 1;
            boolean tablePrimary = !primaryKeys.isEmpty()
                    && !(primaryKeys.size() == 1 && sqliteInlineKey);
            if (lastColumn && !tablePrimary) {
                ddl.append('\n');
            } else {
                ddl.append(",\n");
            }
        }
        if (!primaryKeys.isEmpty()) {
            boolean sqliteInlineKey = primaryKeys.size() == 1 && table.fields().stream()
                    .anyMatch(field -> field.primaryKey() && field.autoIncrement()
                            && dialect == MyBatisSqlDialect.SQLITE
                            && isIntegerType(sqlType(field.javaType(), dialect).sqlType));
            if (!sqliteInlineKey) {
                ddl.append("    PRIMARY KEY (").append(String.join(", ", primaryKeys))
                        .append(")\n");
            }
        }
        ddl.append(')');
        if (dialect == MyBatisSqlDialect.MYSQL && table.comment().isPresent()) {
            ddl.append(" COMMENT='").append(sqlString(table.comment().orElseThrow()))
                    .append('\'');
        }
        ddl.append(";\n");
        appendComments(ddl, table, qualifiedTable, dialect, warnings);
        for (MyBatisJavaIndexSchema index : table.indexes()) {
            ddl.append("CREATE ").append(index.unique() ? "UNIQUE " : "")
                    .append("INDEX ").append(quote(index.name(), dialect))
                    .append(" ON ").append(qualifiedTable).append(" (")
                    .append(index.columns().stream()
                            .map(column -> quote(column, dialect))
                            .reduce((left, right) -> left + ", " + right).orElseThrow())
                    .append(");\n");
        }
        return new MyBatisJavaDdlGeneration(ddl.toString(), warnings, !warnings.isEmpty());
    }

    private static void validate(MyBatisJavaTableSchema table) {
        Set<String> columns = new HashSet<>();
        for (MyBatisJavaFieldSchema field : table.fields()) {
            String normalized = field.columnName().toLowerCase(Locale.ROOT);
            if (!columns.add(normalized)) {
                throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                        "sqltool.conversion.error.column.duplicate", field.columnName()));
            }
        }
        for (MyBatisJavaIndexSchema index : table.indexes()) {
            for (String column : index.columns()) {
                if (!columns.contains(column.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                            "sqltool.conversion.error.index.column.unknown",
                            index.name(), column));
                }
            }
        }
    }

    private static TypeMapping sqlType(String javaType, MyBatisSqlDialect dialect) {
        String normalized = javaType.replace("[]", "[]").strip();
        String simple = normalized.substring(Math.max(
                normalized.lastIndexOf('.'), normalized.lastIndexOf('$')) + 1);
        return switch (simple) {
            case "byte", "Byte" -> new TypeMapping("TINYINT", null);
            case "short", "Short" -> new TypeMapping("SMALLINT", null);
            case "int", "Integer" -> new TypeMapping("INTEGER", null);
            case "long", "Long" -> new TypeMapping("BIGINT", null);
            case "float", "Float" -> new TypeMapping("REAL", null);
            case "double", "Double" -> new TypeMapping("DOUBLE PRECISION", null);
            case "boolean", "Boolean" -> new TypeMapping("BOOLEAN", null);
            case "BigDecimal" -> new TypeMapping("DECIMAL(19, 2)", null);
            case "BigInteger" -> new TypeMapping("DECIMAL(38, 0)", null);
            case "String", "CharSequence", "char", "Character" ->
                    new TypeMapping("VARCHAR(255)", null);
            case "LocalDate", "Date" -> new TypeMapping("DATE", null);
            case "LocalTime", "Time" -> new TypeMapping("TIME", null);
            case "LocalDateTime", "Instant", "Timestamp", "OffsetDateTime", "ZonedDateTime" ->
                    new TypeMapping("TIMESTAMP", null);
            case "UUID" -> dialect == MyBatisSqlDialect.POSTGRESQL
                    ? new TypeMapping("UUID", null) : new TypeMapping("CHAR(36)", null);
            case "byte[]", "Byte[]" -> dialect == MyBatisSqlDialect.POSTGRESQL
                    ? new TypeMapping("BYTEA", null) : new TypeMapping("BLOB", null);
            default -> new TypeMapping(
                    "VARCHAR(255)",
                    MyBatisAssistantBundle.message(
                            "sqltool.conversion.warning.java.type.fallback", javaType));
        };
    }

    private static void appendIdentity(
            StringBuilder ddl,
            MyBatisJavaFieldSchema field,
            MyBatisSqlDialect dialect) {
        if (!field.autoIncrement()) {
            return;
        }
        switch (dialect) {
            case MYSQL -> ddl.append(" AUTO_INCREMENT");
            case SQL_SERVER -> ddl.append(" IDENTITY(1,1)");
            case DAMENG -> ddl.append(" IDENTITY(1,1)");
            case POSTGRESQL, ORACLE, H2 -> ddl.append(" GENERATED BY DEFAULT AS IDENTITY");
            case SQLITE, GENERIC -> {
                // SQLite 在 INTEGER PRIMARY KEY 上单独处理；通用方言不猜测自增语法。
            }
            default -> throw new IllegalStateException(MyBatisAssistantBundle.message(
                    "sqltool.conversion.error.dialect.unknown"));
        }
    }

    private static void appendComments(
            StringBuilder ddl,
            MyBatisJavaTableSchema table,
            String qualifiedTable,
            MyBatisSqlDialect dialect,
            List<String> warnings) {
        if (dialect == MyBatisSqlDialect.MYSQL) {
            return;
        }
        if (dialect == MyBatisSqlDialect.POSTGRESQL
                || dialect == MyBatisSqlDialect.ORACLE
                || dialect == MyBatisSqlDialect.H2) {
            table.comment().ifPresent(comment -> ddl.append("COMMENT ON TABLE ")
                    .append(qualifiedTable).append(" IS '")
                    .append(sqlString(comment)).append("';\n"));
            for (MyBatisJavaFieldSchema field : table.fields()) {
                field.comment().ifPresent(comment -> ddl.append("COMMENT ON COLUMN ")
                        .append(qualifiedTable).append('.')
                        .append(quote(field.columnName(), dialect)).append(" IS '")
                        .append(sqlString(comment)).append("';\n"));
            }
        } else if (table.comment().isPresent()
                || table.fields().stream().anyMatch(field -> field.comment().isPresent())) {
            warnings.add(MyBatisAssistantBundle.message(
                    "sqltool.conversion.warning.comment.unsupported"));
        }
    }

    private static String quote(String identifier, MyBatisSqlDialect dialect) {
        if (identifier.isBlank() || identifier.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "sqltool.conversion.error.sql.identifier.invalid"));
        }
        return switch (dialect) {
            case MYSQL -> "`" + identifier.replace("`", "``") + "`";
            case SQL_SERVER -> "[" + identifier.replace("]", "]]" ) + "]";
            default -> "\"" + identifier.replace("\"", "\"\"") + "\"";
        };
    }

    private static boolean isIntegerType(String sqlType) {
        return sqlType.equals("TINYINT") || sqlType.equals("SMALLINT")
                || sqlType.equals("INTEGER") || sqlType.equals("BIGINT");
    }

    private static String sqlString(String value) {
        return value.replace("'", "''");
    }

    private record TypeMapping(String sqlType, String warning) {
    }
}
