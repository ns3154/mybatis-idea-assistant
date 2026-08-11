package io.github.ns3154.mybatisassistant.sqltool.conversion;

import com.intellij.openapi.progress.ProgressManager;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析一条保守的 CREATE TABLE DDL，不执行 SQL，也不连接数据库。
 */
public final class MyBatisCreateTableParser {
    public static final int MAX_INPUT_BYTES = 2 * 1024 * 1024;
    private static final Pattern HEADER = Pattern.compile(
            "\\G\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONSTRAINT_START = Pattern.compile(
            "(?i)^(?:CONSTRAINT\\s+[^\\s]+\\s+)?"
                    + "(PRIMARY\\s+KEY|FOREIGN\\s+KEY|UNIQUE|KEY|INDEX|CHECK)\\b");
    private static final Pattern TYPE_END = Pattern.compile(
            "(?i)\\s+(?=NOT\\s+NULL\\b|NULL\\b|PRIMARY\\s+KEY\\b|FOREIGN\\s+KEY\\b|"
                    + "DEFAULT\\b|COMMENT\\b|REFERENCES\\b|CHECK\\b|COLLATE\\b|"
                    + "GENERATED\\b|AUTO_INCREMENT\\b|IDENTITY\\b|UNIQUE\\b)");
    private static final Pattern PRIMARY_KEY = Pattern.compile(
            "(?i)PRIMARY\\s+KEY\\s*\\(([^)]*)\\)");
    private static final Pattern FOREIGN_KEY = Pattern.compile(
            "(?i)FOREIGN\\s+KEY\\s*\\(([^)]*)\\)");
    private static final Pattern COMMENT = Pattern.compile(
            "(?is)\\bCOMMENT\\s*(?:=\\s*)?'((?:''|[^'])*)'");

    private MyBatisCreateTableParser() {
    }

    public static @NotNull MyBatisCreateTableParseResult parse(@NotNull String ddl) {
        if (ddl.length() > MAX_INPUT_BYTES
                || ddl.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            return failure(MyBatisDdlDiagnosticCode.INPUT_TOO_LARGE, 0,
                    "DDL 超过 2 MiB 本地解析上限");
        }
        ddl = stripSqlComments(ddl);
        if (ddl == null) {
            return failure(MyBatisDdlDiagnosticCode.MALFORMED_DDL, 0,
                    "DDL 包含未闭合的块注释");
        }
        Matcher header = HEADER.matcher(ddl);
        if (!header.find()) {
            return failure(MyBatisDdlDiagnosticCode.NOT_CREATE_TABLE, 0,
                    "仅支持单条 CREATE TABLE DDL");
        }
        Identifier tableIdentifier = qualifiedIdentifier(ddl, header.end());
        if (tableIdentifier == null) {
            return failure(MyBatisDdlDiagnosticCode.MALFORMED_DDL, header.end(),
                    "CREATE TABLE 缺少合法表名");
        }
        int bodyStart = skipWhitespace(ddl, tableIdentifier.end);
        if (bodyStart >= ddl.length() || ddl.charAt(bodyStart) != '(') {
            return failure(MyBatisDdlDiagnosticCode.MALFORMED_DDL, bodyStart,
                    "表名后缺少列定义括号");
        }
        int bodyEnd = matchingParenthesis(ddl, bodyStart);
        if (bodyEnd < 0) {
            return failure(MyBatisDdlDiagnosticCode.MALFORMED_DDL, bodyStart,
                    "列定义括号未闭合");
        }
        String tail = ddl.substring(bodyEnd + 1).trim();
        if (containsAdditionalStatement(tail)) {
            return failure(MyBatisDdlDiagnosticCode.MULTIPLE_STATEMENTS, bodyEnd + 1,
                    "一次只允许转换一条 CREATE TABLE，已拒绝后续语句");
        }
        List<String> definitions;
        try {
            definitions = splitDefinitions(ddl.substring(bodyStart + 1, bodyEnd));
        } catch (IllegalArgumentException malformed) {
            return failure(MyBatisDdlDiagnosticCode.MALFORMED_DDL, bodyStart + 1,
                    malformed.getMessage());
        }
        if (definitions.isEmpty()) {
            return failure(MyBatisDdlDiagnosticCode.NO_COLUMNS, bodyStart + 1,
                    "CREATE TABLE 未包含列定义");
        }
        return buildTable(tableIdentifier, definitions, tail);
    }

    private static MyBatisCreateTableParseResult buildTable(
            Identifier tableIdentifier,
            List<String> definitions,
            String tail) {
        Set<String> primaryKeys = new LinkedHashSet<>();
        Set<String> foreignKeys = new LinkedHashSet<>();
        List<String> columnDefinitions = new ArrayList<>();
        for (String definition : definitions) {
            ProgressManager.checkCanceled();
            Matcher constraint = CONSTRAINT_START.matcher(definition.strip());
            if (constraint.find()) {
                collectColumns(PRIMARY_KEY.matcher(definition), primaryKeys);
                collectColumns(FOREIGN_KEY.matcher(definition), foreignKeys);
            } else {
                columnDefinitions.add(definition);
            }
        }
        List<ColumnDraft> drafts = new ArrayList<>();
        Set<String> names = new HashSet<>();
        List<String> warnings = new ArrayList<>();
        for (String definition : columnDefinitions) {
            ColumnDraft draft = column(definition, drafts.size(), warnings);
            if (draft == null) {
                return failure(MyBatisDdlDiagnosticCode.UNSUPPORTED_DEFINITION, 0,
                        "无法安全解析列定义：" + safeDefinition(definition));
            }
            if (!names.add(draft.name.toLowerCase(Locale.ROOT))) {
                return failure(MyBatisDdlDiagnosticCode.DUPLICATE_COLUMN, 0,
                        "列名重复：" + draft.name);
            }
            drafts.add(draft);
        }
        if (drafts.isEmpty()) {
            return failure(MyBatisDdlDiagnosticCode.NO_COLUMNS, 0,
                    "CREATE TABLE 未包含可转换列");
        }
        List<MyBatisDatabaseColumn> columns = drafts.stream()
                .map(draft -> draft.toColumn(
                        primaryKeys.contains(draft.normalizedName()),
                        foreignKeys.contains(draft.normalizedName())))
                .toList();
        QualifiedName qualified = qualifiedName(tableIdentifier.text);
        Optional<String> comment = comment(tail);
        MyBatisDatabaseTable table = new MyBatisDatabaseTable(
                Optional.empty(), qualified.schema, qualified.name, comment, columns);
        return new MyBatisCreateTableParseResult.Success(
                table, warnings, !warnings.isEmpty());
    }

    private static ColumnDraft column(
            String definition,
            int position,
            List<String> warnings) {
        String stripped = definition.strip();
        Identifier name = identifier(stripped, 0);
        if (name == null) {
            return null;
        }
        String remainder = stripped.substring(name.end).stripLeading();
        if (remainder.isEmpty()) {
            return null;
        }
        Matcher typeEnd = TYPE_END.matcher(remainder);
        String typeName = typeEnd.find()
                ? remainder.substring(0, typeEnd.start()).strip()
                : remainder.strip();
        if (typeName.isEmpty() || unbalancedParentheses(typeName)) {
            return null;
        }
        String modifiers = remainder.substring(typeName.length());
        int jdbcType = jdbcType(typeName);
        if (jdbcType == Types.OTHER) {
            warnings.add("列 " + unquote(name.text) + " 的数据库类型 " + typeName
                    + " 未知，将以 Object/OTHER 生成并要求确认");
        }
        boolean primary = contains(modifiers, "PRIMARY\\s+KEY");
        boolean foreign = contains(modifiers, "REFERENCES\\b");
        boolean nullable = !primary && !contains(modifiers, "NOT\\s+NULL");
        boolean autoIncrement = contains(modifiers,
                "AUTO_INCREMENT\\b|AUTOINCREMENT\\b|IDENTITY\\b|GENERATED\\s+.+IDENTITY\\b")
                || typeName.equalsIgnoreCase("SERIAL")
                || typeName.equalsIgnoreCase("BIGSERIAL");
        return new ColumnDraft(
                unquote(name.text),
                typeName,
                jdbcType,
                nullable,
                primary,
                foreign,
                autoIncrement,
                comment(modifiers),
                position);
    }

    private static int jdbcType(String typeName) {
        String base = typeName.toUpperCase(Locale.ROOT)
                .replaceAll("\\s+UNSIGNED\\b", "")
                .replaceAll("\\s+ZEROFILL\\b", "")
                .replaceFirst("\\s*\\(.*", "")
                .strip();
        return switch (base) {
            case "TINYINT", "INT1" -> Types.TINYINT;
            case "SMALLINT", "INT2" -> Types.SMALLINT;
            case "INT", "INTEGER", "MEDIUMINT", "INT4", "SERIAL" -> Types.INTEGER;
            case "BIGINT", "INT8", "BIGSERIAL" -> Types.BIGINT;
            case "DECIMAL", "NUMERIC", "NUMBER", "MONEY" -> Types.DECIMAL;
            case "FLOAT", "FLOAT4" -> Types.FLOAT;
            case "REAL" -> Types.REAL;
            case "DOUBLE", "DOUBLE PRECISION", "FLOAT8" -> Types.DOUBLE;
            case "BOOLEAN", "BOOL" -> Types.BOOLEAN;
            case "BIT" -> Types.BIT;
            case "CHAR", "CHARACTER", "NCHAR" -> Types.CHAR;
            case "VARCHAR", "VARCHAR2", "CHARACTER VARYING" -> Types.VARCHAR;
            case "NVARCHAR", "NVARCHAR2", "NATIONAL CHARACTER VARYING" -> Types.NVARCHAR;
            case "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT" -> Types.LONGVARCHAR;
            case "DATE" -> Types.DATE;
            case "TIME", "TIME WITHOUT TIME ZONE" -> Types.TIME;
            case "TIME WITH TIME ZONE", "TIMETZ" -> Types.TIME_WITH_TIMEZONE;
            case "TIMESTAMP", "DATETIME", "TIMESTAMP WITHOUT TIME ZONE" -> Types.TIMESTAMP;
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ" -> Types.TIMESTAMP_WITH_TIMEZONE;
            case "BINARY" -> Types.BINARY;
            case "VARBINARY", "BYTEA" -> Types.VARBINARY;
            case "BLOB", "LONGBLOB", "MEDIUMBLOB", "TINYBLOB" -> Types.BLOB;
            case "CLOB", "NCLOB" -> Types.CLOB;
            case "JSON", "JSONB", "UUID", "XML", "INET" -> Types.OTHER;
            default -> Types.OTHER;
        };
    }

    private static List<String> splitDefinitions(String body) {
        List<String> definitions = new ArrayList<>();
        int start = 0;
        int depth = 0;
        char quote = 0;
        for (int index = 0; index < body.length(); index++) {
            ProgressManager.checkCanceled();
            char current = body.charAt(index);
            char next = index + 1 < body.length() ? body.charAt(index + 1) : 0;
            if (quote != 0) {
                if (current == quote && next == quote) {
                    index++;
                } else if (current == quote) {
                    quote = 0;
                } else if (current == '\\' && next != 0) {
                    index++;
                }
            } else if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            } else if (current == '[') {
                quote = ']';
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth < 0) {
                    throw new IllegalArgumentException("列定义括号不匹配");
                }
            } else if (current == ',' && depth == 0) {
                addDefinition(definitions, body.substring(start, index));
                start = index + 1;
            }
        }
        if (quote != 0 || depth != 0) {
            throw new IllegalArgumentException("列定义包含未闭合的引号或括号");
        }
        addDefinition(definitions, body.substring(start));
        return definitions;
    }

    private static int matchingParenthesis(String text, int opening) {
        int depth = 0;
        char quote = 0;
        for (int index = opening; index < text.length(); index++) {
            ProgressManager.checkCanceled();
            char current = text.charAt(index);
            char next = index + 1 < text.length() ? text.charAt(index + 1) : 0;
            if (quote != 0) {
                if (current == quote && next == quote) {
                    index++;
                } else if (current == quote) {
                    quote = 0;
                } else if (current == '\\' && next != 0) {
                    index++;
                }
            } else if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            } else if (current == '[') {
                quote = ']';
            } else if (current == '(') {
                depth++;
            } else if (current == ')' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static Identifier identifier(String source, int start) {
        int cursor = skipWhitespace(source, start);
        if (cursor >= source.length()) {
            return null;
        }
        int end = cursor;
        char opening = source.charAt(cursor);
        if (opening == '"' || opening == '`' || opening == '[') {
            char closing = opening == '[' ? ']' : opening;
            end++;
            while (end < source.length()) {
                if (source.charAt(end) == closing) {
                    if (end + 1 < source.length() && source.charAt(end + 1) == closing) {
                        end += 2;
                    } else {
                        end++;
                        break;
                    }
                } else {
                    end++;
                }
            }
            if (end > source.length() || source.charAt(end - 1) != closing) {
                return null;
            }
        } else {
            char first = source.charAt(end);
            if (!Character.isLetter(first) && first != '_' && first != '$') {
                return null;
            }
            while (end < source.length()) {
                char character = source.charAt(end);
                if (Character.isLetterOrDigit(character)
                        || character == '_' || character == '$') {
                    end++;
                } else {
                    break;
                }
            }
            if (end == cursor) {
                return null;
            }
        }
        return new Identifier(source.substring(cursor, end), end);
    }

    private static Identifier qualifiedIdentifier(String source, int start) {
        Identifier first = identifier(source, start);
        if (first == null) {
            return null;
        }
        StringBuilder text = new StringBuilder(first.text);
        int end = first.end;
        while (true) {
            int dot = skipWhitespace(source, end);
            if (dot >= source.length() || source.charAt(dot) != '.') {
                return new Identifier(text.toString(), end);
            }
            Identifier next = identifier(source, dot + 1);
            if (next == null) {
                return null;
            }
            text.append('.').append(next.text);
            end = next.end;
        }
    }

    private static QualifiedName qualifiedName(String identifier) {
        String[] parts = identifier.split("\\.");
        String name = unquote(parts[parts.length - 1]);
        Optional<String> schema = parts.length > 1
                ? Optional.of(unquote(parts[parts.length - 2])) : Optional.empty();
        return new QualifiedName(schema, name);
    }

    private static String unquote(String identifier) {
        String value = identifier.strip();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if (first == '"' && last == '"' || first == '`' && last == '`'
                    || first == '[' && last == ']') {
                return value.substring(1, value.length() - 1)
                        .replace("" + last + last, "" + last);
            }
        }
        return value;
    }

    private static void collectColumns(Matcher matcher, Set<String> target) {
        if (!matcher.find()) {
            return;
        }
        for (String name : matcher.group(1).split(",")) {
            target.add(unquote(name).toLowerCase(Locale.ROOT));
        }
    }

    private static Optional<String> comment(String text) {
        Matcher matcher = COMMENT.matcher(text);
        return matcher.find()
                ? Optional.of(matcher.group(1).replace("''", "'")) : Optional.empty();
    }

    private static boolean contains(String text, String expression) {
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(text).find();
    }

    private static boolean containsAdditionalStatement(String tail) {
        char quote = 0;
        for (int index = 0; index < tail.length(); index++) {
            char current = tail.charAt(index);
            char next = index + 1 < tail.length() ? tail.charAt(index + 1) : 0;
            if (quote != 0) {
                if (current == quote && next == quote) {
                    index++;
                } else if (current == quote) {
                    quote = 0;
                } else if (current == '\\' && next != 0) {
                    index++;
                }
            } else if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            } else if (current == ';') {
                return !tail.substring(index + 1).isBlank();
            }
        }
        return false;
    }

    private static String stripSqlComments(String source) {
        StringBuilder result = new StringBuilder(source);
        char quote = 0;
        int blockDepth = 0;
        boolean lineComment = false;
        for (int index = 0; index < source.length(); index++) {
            ProgressManager.checkCanceled();
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : 0;
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                } else {
                    result.setCharAt(index, ' ');
                }
            } else if (blockDepth > 0) {
                if (current == '/' && next == '*') {
                    result.setCharAt(index, ' ');
                    result.setCharAt(index + 1, ' ');
                    blockDepth++;
                    index++;
                } else if (current == '*' && next == '/') {
                    result.setCharAt(index, ' ');
                    result.setCharAt(index + 1, ' ');
                    blockDepth--;
                    index++;
                } else if (current != '\n' && current != '\r') {
                    result.setCharAt(index, ' ');
                }
            } else if (quote != 0) {
                if (current == quote && next == quote) {
                    index++;
                } else if (current == quote) {
                    quote = 0;
                } else if (current == '\\' && next != 0) {
                    index++;
                }
            } else if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            } else if (current == '[') {
                quote = ']';
            } else if (current == '-' && next == '-' || current == '/' && next == '*') {
                result.setCharAt(index, ' ');
                result.setCharAt(index + 1, ' ');
                if (current == '-') {
                    lineComment = true;
                } else {
                    blockDepth = 1;
                }
                index++;
            } else if (current == '#') {
                result.setCharAt(index, ' ');
                lineComment = true;
            }
        }
        return blockDepth == 0 ? result.toString() : null;
    }

    private static boolean unbalancedParentheses(String text) {
        int depth = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '(') {
                depth++;
            } else if (text.charAt(index) == ')' && --depth < 0) {
                return true;
            }
        }
        return depth != 0;
    }

    private static void addDefinition(List<String> target, String source) {
        String definition = source.strip();
        if (!definition.isEmpty()) {
            target.add(definition);
        }
    }

    private static int skipWhitespace(String source, int start) {
        int cursor = start;
        while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static String safeDefinition(String definition) {
        String singleLine = definition.replaceAll("\\s+", " ").strip();
        return singleLine.length() <= 80 ? singleLine : singleLine.substring(0, 80) + "…";
    }

    private static MyBatisCreateTableParseResult.Failure failure(
            MyBatisDdlDiagnosticCode code,
            int offset,
            String message) {
        return new MyBatisCreateTableParseResult.Failure(code, Math.max(0, offset), message);
    }

    private record Identifier(String text, int end) {
    }

    private record QualifiedName(Optional<String> schema, String name) {
    }

    private record ColumnDraft(
            String name,
            String typeName,
            int jdbcType,
            boolean nullable,
            boolean primaryKey,
            boolean foreignKey,
            boolean autoIncrement,
            Optional<String> comment,
            int position) {
        private String normalizedName() {
            return name.toLowerCase(Locale.ROOT);
        }

        private MyBatisDatabaseColumn toColumn(boolean tablePrimary, boolean tableForeign) {
            boolean primary = primaryKey || tablePrimary;
            return new MyBatisDatabaseColumn(
                    name, typeName, jdbcType, primary ? false : nullable,
                    primary, foreignKey || tableForeign, autoIncrement, comment, position);
        }
    }
}
