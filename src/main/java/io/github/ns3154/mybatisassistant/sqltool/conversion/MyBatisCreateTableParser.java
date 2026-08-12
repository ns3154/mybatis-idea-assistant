package io.github.ns3154.mybatisassistant.sqltool.conversion;

import com.intellij.openapi.progress.ProgressManager;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
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
    private static final Pattern TYPE_END = Pattern.compile(
            "(?i)\\s+(?=NOT\\s+NULL\\b|NULL\\b|PRIMARY\\s+KEY\\b|FOREIGN\\s+KEY\\b|"
                    + "DEFAULT\\b|COMMENT\\b|REFERENCES\\b|CHECK\\b|COLLATE\\b|"
                    + "GENERATED\\b|AS\\b|AUTO_INCREMENT\\b|IDENTITY\\b|UNIQUE\\b)");
    private static final Pattern PRIMARY_KEY_COLUMNS_START = Pattern.compile(
            "(?i)PRIMARY\\s+KEY\\s*\\(");
    private static final Pattern FOREIGN_KEY_COLUMNS_START = Pattern.compile(
            "(?i)FOREIGN\\s+KEY\\s*\\(");
    private static final Pattern COMMENT = Pattern.compile(
            "(?is)\\bCOMMENT\\s*(?:=\\s*)?'((?:''|[^'])*)'");

    private MyBatisCreateTableParser() {
    }

    public static @NotNull MyBatisCreateTableParseResult parse(@NotNull String ddl) {
        if (ddl.length() > MAX_INPUT_BYTES
                || ddl.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            return failure(MyBatisDdlDiagnosticCode.INPUT_TOO_LARGE, 0,
                    MyBatisAssistantBundle.message(
                            "sqltool.conversion.error.ddl.too.large"));
        }
        ddl = stripSqlComments(ddl);
        if (ddl == null) {
            return failure(MyBatisDdlDiagnosticCode.MALFORMED_DDL, 0,
                    MyBatisAssistantBundle.message(
                            "sqltool.conversion.error.ddl.comment.unclosed"));
        }
        if (containsUnsupportedDollarQuote(ddl)) {
            return failure(MyBatisDdlDiagnosticCode.UNSUPPORTED_DEFINITION, 0,
                    MyBatisAssistantBundle.message(
                            "sqltool.conversion.error.ddl.column.unsupported",
                            safeDefinition(ddl)));
        }
        Matcher header = HEADER.matcher(ddl);
        if (!header.find()) {
            return failure(MyBatisDdlDiagnosticCode.NOT_CREATE_TABLE, 0,
                    MyBatisAssistantBundle.message(
                            "sqltool.conversion.error.ddl.not.create.table"));
        }
        QualifiedIdentifier tableIdentifier = qualifiedIdentifier(ddl, header.end());
        if (tableIdentifier == null) {
            return failure(MyBatisDdlDiagnosticCode.MALFORMED_DDL, header.end(),
                    MyBatisAssistantBundle.message(
                            "sqltool.conversion.error.ddl.table.name"));
        }
        int bodyStart = skipWhitespace(ddl, tableIdentifier.end);
        if (bodyStart >= ddl.length() || ddl.charAt(bodyStart) != '(') {
            return failure(MyBatisDdlDiagnosticCode.MALFORMED_DDL, bodyStart,
                    MyBatisAssistantBundle.message(
                            "sqltool.conversion.error.ddl.columns.parenthesis.missing"));
        }
        int bodyEnd = matchingParenthesis(ddl, bodyStart);
        if (bodyEnd < 0) {
            return failure(MyBatisDdlDiagnosticCode.MALFORMED_DDL, bodyStart,
                    MyBatisAssistantBundle.message(
                            "sqltool.conversion.error.ddl.columns.parenthesis.unclosed"));
        }
        String tail = ddl.substring(bodyEnd + 1).trim();
        if (containsAdditionalStatement(tail)) {
            return failure(MyBatisDdlDiagnosticCode.MULTIPLE_STATEMENTS, bodyEnd + 1,
                    MyBatisAssistantBundle.message(
                            "sqltool.conversion.error.ddl.multiple"));
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
                    MyBatisAssistantBundle.message(
                            "sqltool.conversion.error.ddl.columns.empty"));
        }
        return buildTable(tableIdentifier, definitions, tail);
    }

    private static MyBatisCreateTableParseResult buildTable(
            QualifiedIdentifier tableIdentifier,
            List<String> definitions,
            String tail) {
        Set<String> primaryKeys = new LinkedHashSet<>();
        Set<String> foreignKeys = new LinkedHashSet<>();
        List<String> columnDefinitions = new ArrayList<>();
        for (String definition : definitions) {
            ProgressManager.checkCanceled();
            ConstraintKind constraintKind = constraintKind(definition.strip());
            if (constraintKind == ConstraintKind.INVALID) {
                return failure(MyBatisDdlDiagnosticCode.UNSUPPORTED_DEFINITION, 0,
                        MyBatisAssistantBundle.message(
                                "sqltool.conversion.error.ddl.column.unsupported",
                                safeDefinition(definition)));
            }
            if (constraintKind != ConstraintKind.NONE) {
                boolean parsed = constraintKind != ConstraintKind.PRIMARY_KEY
                        || collectColumns(definition, PRIMARY_KEY_COLUMNS_START, primaryKeys);
                parsed = parsed && (constraintKind != ConstraintKind.FOREIGN_KEY
                        || collectColumns(
                                definition, FOREIGN_KEY_COLUMNS_START, foreignKeys));
                if (!parsed) {
                    return failure(MyBatisDdlDiagnosticCode.UNSUPPORTED_DEFINITION, 0,
                            MyBatisAssistantBundle.message(
                                    "sqltool.conversion.error.ddl.column.unsupported",
                                    safeDefinition(definition)));
                }
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
                        MyBatisAssistantBundle.message(
                                "sqltool.conversion.error.ddl.column.unsupported",
                                safeDefinition(definition)));
            }
            if (!names.add(draft.name.toLowerCase(Locale.ROOT))) {
                return failure(MyBatisDdlDiagnosticCode.DUPLICATE_COLUMN, 0,
                        MyBatisAssistantBundle.message(
                                "sqltool.conversion.error.column.duplicate", draft.name));
            }
            drafts.add(draft);
        }
        if (drafts.isEmpty()) {
            return failure(MyBatisDdlDiagnosticCode.NO_COLUMNS, 0,
                    MyBatisAssistantBundle.message(
                            "sqltool.conversion.error.ddl.columns.convertible.empty"));
        }
        List<MyBatisDatabaseColumn> columns = drafts.stream()
                .map(draft -> draft.toColumn(
                        primaryKeys.contains(draft.normalizedName()),
                        foreignKeys.contains(draft.normalizedName())))
                .toList();
        QualifiedName qualified = qualifiedName(tableIdentifier.segments);
        Optional<String> comment = comment(tail);
        MyBatisDatabaseTable table = new MyBatisDatabaseTable(
                qualified.catalog, qualified.schema, qualified.name, comment, columns);
        return new MyBatisCreateTableParseResult.Success(
                table, warnings, !warnings.isEmpty());
    }

    private static ConstraintKind constraintKind(String definition) {
        Identifier token = identifier(definition, 0);
        if (token == null) {
            return ConstraintKind.NONE;
        }
        int cursor = token.end;
        boolean namedConstraint = isKeyword(token, "CONSTRAINT");
        if (namedConstraint) {
            Identifier name = identifier(definition, cursor);
            if (name == null) {
                return ConstraintKind.INVALID;
            }
            token = identifier(definition, name.end);
            if (token == null) {
                return ConstraintKind.INVALID;
            }
            cursor = token.end;
        }
        if (isKeyword(token, "PRIMARY") || isKeyword(token, "FOREIGN")) {
            Identifier key = identifier(definition, cursor);
            if (key == null || !isKeyword(key, "KEY")) {
                return ConstraintKind.INVALID;
            }
            return isKeyword(token, "PRIMARY")
                    ? ConstraintKind.PRIMARY_KEY : ConstraintKind.FOREIGN_KEY;
        }
        if (isKeyword(token, "UNIQUE") || isKeyword(token, "KEY")
                || isKeyword(token, "INDEX") || isKeyword(token, "CHECK")) {
            return ConstraintKind.OTHER;
        }
        return namedConstraint ? ConstraintKind.INVALID : ConstraintKind.NONE;
    }

    private static boolean isKeyword(Identifier identifier, String expected) {
        String text = identifier.text;
        return !text.isEmpty()
                && text.charAt(0) != '"' && text.charAt(0) != '`' && text.charAt(0) != '['
                && text.equalsIgnoreCase(expected);
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
        if (remainder.matches("(?is)^AS\\b.*")) {
            // SQL Server 等方言允许省略计算列类型；无法可靠推导 JDBC 类型时拒绝转换。
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
        String semanticModifiers = maskQuotedContent(modifiers);
        int jdbcType = jdbcType(typeName);
        if (jdbcType == Types.OTHER) {
            warnings.add(MyBatisAssistantBundle.message(
                    "sqltool.conversion.warning.ddl.type.unknown",
                    unquote(name.text), typeName));
        }
        boolean primary = contains(semanticModifiers, "PRIMARY\\s+KEY");
        boolean foreign = contains(semanticModifiers, "REFERENCES\\b");
        boolean nullable = !primary && !contains(semanticModifiers, "NOT\\s+NULL");
        boolean generatedIdentity = contains(semanticModifiers,
                "GENERATED\\s+(?:(?:ALWAYS|BY\\s+DEFAULT(?:\\s+ON\\s+NULL)?)\\s+)?"
                        + "AS\\s+IDENTITY\\b");
        boolean identity = generatedIdentity || contains(semanticModifiers,
                "(?:^|\\s)IDENTITY\\s*(?:\\([^)]*\\))?"
                        + "(?:\\s+NOT\\s+FOR\\s+REPLICATION)?"
                        + "(?=\\s*(?:$|NOT\\s+NULL\\b|NULL\\b|PRIMARY\\s+KEY\\b|"
                        + "UNIQUE\\b|COMMENT\\b))");
        boolean autoIncrement = contains(semanticModifiers,
                "AUTO_INCREMENT\\b|AUTOINCREMENT\\b")
                || identity
                || typeName.equalsIgnoreCase("SERIAL")
                || typeName.equalsIgnoreCase("BIGSERIAL");
        boolean generated = (contains(semanticModifiers, "GENERATED\\b")
                && !generatedIdentity)
                || semanticModifiers.matches("(?is)^\\s*AS\\b.*");
        return new ColumnDraft(
                unquote(name.text),
                typeName,
                jdbcType,
                nullable,
                primary,
                foreign,
                autoIncrement,
                generated,
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
                    throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                            "sqltool.conversion.error.ddl.column.parenthesis.mismatch"));
                }
            } else if (current == ',' && depth == 0) {
                addDefinition(definitions, body.substring(start, index));
                start = index + 1;
            }
        }
        if (quote != 0 || depth != 0) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "sqltool.conversion.error.ddl.column.unclosed"));
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

    private static QualifiedIdentifier qualifiedIdentifier(String source, int start) {
        Identifier first = identifier(source, start);
        if (first == null) {
            return null;
        }
        List<String> segments = new ArrayList<>(3);
        segments.add(unquote(first.text));
        int end = first.end;
        while (true) {
            int dot = skipWhitespace(source, end);
            if (dot >= source.length() || source.charAt(dot) != '.') {
                return new QualifiedIdentifier(segments, end);
            }
            Identifier next = identifier(source, dot + 1);
            if (next == null || segments.size() == 3) {
                return null;
            }
            segments.add(unquote(next.text));
            end = next.end;
        }
    }

    private static QualifiedName qualifiedName(List<String> segments) {
        return switch (segments.size()) {
            case 1 -> new QualifiedName(
                    Optional.empty(), Optional.empty(), segments.getFirst());
            case 2 -> new QualifiedName(
                    Optional.empty(), Optional.of(segments.getFirst()), segments.get(1));
            case 3 -> new QualifiedName(
                    Optional.of(segments.getFirst()), Optional.of(segments.get(1)),
                    segments.get(2));
            default -> throw new IllegalStateException(
                    "Unexpected qualified identifier segment count");
        };
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

    private static boolean collectColumns(
            String definition,
            Pattern startPattern,
            Set<String> target) {
        Matcher matcher = startPattern.matcher(definition);
        if (!matcher.find()) {
            return false;
        }
        int cursor = matcher.end();
        boolean found = false;
        while (cursor < definition.length()) {
            Identifier identifier = identifier(definition, cursor);
            if (identifier == null) {
                return false;
            }
            target.add(unquote(identifier.text).toLowerCase(Locale.ROOT));
            found = true;
            cursor = skipWhitespace(definition, identifier.end);
            if (cursor >= definition.length()) {
                return false;
            }
            char delimiter = definition.charAt(cursor);
            if (delimiter == ')') {
                return found;
            }
            if (delimiter != ',') {
                return false;
            }
            cursor++;
        }
        return false;
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

    private static String maskQuotedContent(String text) {
        StringBuilder masked = new StringBuilder(text);
        char closing = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            char next = index + 1 < text.length() ? text.charAt(index + 1) : 0;
            if (closing != 0) {
                if (current == closing && next == closing) {
                    masked.setCharAt(index, ' ');
                    masked.setCharAt(index + 1, ' ');
                    index++;
                } else if (current == closing) {
                    masked.setCharAt(index, ' ');
                    closing = 0;
                } else {
                    masked.setCharAt(index, ' ');
                    if (current == '\\' && next != 0) {
                        masked.setCharAt(index + 1, ' ');
                        index++;
                    }
                }
            } else if (current == '\'' || current == '"' || current == '`') {
                masked.setCharAt(index, ' ');
                closing = current;
            } else if (current == '[') {
                masked.setCharAt(index, ' ');
                closing = ']';
            }
        }
        return masked.toString();
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

    private static boolean containsUnsupportedDollarQuote(String source) {
        char quote = 0;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : 0;
            if (quote != 0) {
                if (current == quote && next == quote) {
                    index++;
                } else if (current == quote) {
                    quote = 0;
                } else if (current == '\\' && next != 0) {
                    index++;
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                continue;
            }
            if (current == '[') {
                quote = ']';
                continue;
            }
            if (current != '$' || index > 0 && isIdentifierPart(source.charAt(index - 1))) {
                continue;
            }
            int delimiterEnd = source.indexOf('$', index + 1);
            if (delimiterEnd < 0) {
                continue;
            }
            String tag = source.substring(index + 1, delimiterEnd);
            if (tag.isEmpty() || tag.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIdentifierPart(char character) {
        return Character.isLetterOrDigit(character)
                || character == '_' || character == '$';
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

    private record QualifiedIdentifier(List<String> segments, int end) {
        private QualifiedIdentifier {
            segments = List.copyOf(segments);
        }
    }

    private record QualifiedName(
            Optional<String> catalog,
            Optional<String> schema,
            String name) {
    }

    private enum ConstraintKind {
        NONE,
        INVALID,
        PRIMARY_KEY,
        FOREIGN_KEY,
        OTHER
    }

    private record ColumnDraft(
            String name,
            String typeName,
            int jdbcType,
            boolean nullable,
            boolean primaryKey,
            boolean foreignKey,
            boolean autoIncrement,
            boolean generated,
            Optional<String> comment,
            int position) {
        private String normalizedName() {
            return name.toLowerCase(Locale.ROOT);
        }

        private MyBatisDatabaseColumn toColumn(boolean tablePrimary, boolean tableForeign) {
            boolean primary = primaryKey || tablePrimary;
            return new MyBatisDatabaseColumn(
                    name, typeName, jdbcType, primary ? false : nullable,
                    primary, foreignKey || tableForeign, autoIncrement, generated,
                    comment, position);
        }
    }
}
