package io.github.ns3154.mybatisassistant.sqltool.execution;

import com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 解析每行 TYPE:value 的显式参数面板，不执行表达式或类型转换代码。
 */
public final class MyBatisSqlParameterPanelParser {
    public static final int MAX_PARAMETERS = 500;
    private static final Map<String, Integer> NULL_TYPES = Map.ofEntries(
            Map.entry("VARCHAR", Types.VARCHAR),
            Map.entry("CHAR", Types.CHAR),
            Map.entry("INTEGER", Types.INTEGER),
            Map.entry("BIGINT", Types.BIGINT),
            Map.entry("DECIMAL", Types.DECIMAL),
            Map.entry("BOOLEAN", Types.BOOLEAN),
            Map.entry("DATE", Types.DATE),
            Map.entry("TIMESTAMP", Types.TIMESTAMP),
            Map.entry("OTHER", Types.OTHER));

    private MyBatisSqlParameterPanelParser() {
    }

    public static @NotNull MyBatisSqlParameterParseResult parse(@NotNull String panel) {
        List<MyBatisSqlParameter> parameters = new ArrayList<>();
        String[] lines = panel.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            ProgressManager.checkCanceled();
            String line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            if (parameters.size() >= MAX_PARAMETERS) {
                return failure(index + 1, "参数数量超过 " + MAX_PARAMETERS + " 个上限");
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                return failure(index + 1, "参数必须使用 TYPE:value 格式");
            }
            String typeText = line.substring(0, separator).strip().toUpperCase(Locale.ROOT);
            String valueText = line.substring(separator + 1);
            MyBatisSqlParameter parameter = parameter(typeText, valueText);
            if (parameter == null) {
                return failure(index + 1, "参数类型或值无效；支持 STRING、LONG、DECIMAL、"
                        + "BOOLEAN、DATE、TIMESTAMP、NULL");
            }
            parameters.add(parameter);
        }
        return new MyBatisSqlParameterParseResult.Success(parameters);
    }

    private static MyBatisSqlParameter parameter(String type, String value) {
        try {
            return switch (type) {
                case "STRING" -> parameter(MyBatisSqlParameterType.STRING, value, Types.VARCHAR);
                case "LONG" -> parameter(
                        MyBatisSqlParameterType.LONG, Long.valueOf(value.strip()), Types.BIGINT);
                case "DECIMAL" -> parameter(
                        MyBatisSqlParameterType.DECIMAL,
                        new BigDecimal(value.strip()), Types.DECIMAL);
                case "BOOLEAN" -> booleanParameter(value);
                case "DATE" -> parameter(
                        MyBatisSqlParameterType.DATE,
                        Date.valueOf(LocalDate.parse(value.strip())), Types.DATE);
                case "TIMESTAMP" -> parameter(
                        MyBatisSqlParameterType.TIMESTAMP,
                        Timestamp.valueOf(LocalDateTime.parse(value.strip())), Types.TIMESTAMP);
                case "NULL" -> nullParameter(value);
                case "BINARY", "BYTES", "BLOB" -> null;
                default -> null;
            };
        } catch (NumberFormatException | DateTimeParseException invalid) {
            return null;
        }
    }

    private static MyBatisSqlParameter booleanParameter(String value) {
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!"true".equals(normalized) && !"false".equals(normalized)) {
            return null;
        }
        return parameter(
                MyBatisSqlParameterType.BOOLEAN,
                Boolean.valueOf(normalized),
                Types.BOOLEAN);
    }

    private static MyBatisSqlParameter nullParameter(String value) {
        Integer jdbcType = NULL_TYPES.get(value.strip().toUpperCase(Locale.ROOT));
        return jdbcType == null ? null
                : parameter(MyBatisSqlParameterType.NULL, null, jdbcType);
    }

    private static MyBatisSqlParameter parameter(
            MyBatisSqlParameterType type,
            Object value,
            int jdbcType) {
        return new MyBatisSqlParameter(type, value, jdbcType);
    }

    private static MyBatisSqlParameterParseResult.Failure failure(
            int lineNumber,
            String message) {
        return new MyBatisSqlParameterParseResult.Failure(lineNumber, message);
    }
}
