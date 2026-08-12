package io.github.ns3154.mybatisassistant.methodsql;

import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import org.jetbrains.annotations.NotNull;

import java.sql.Types;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 将唯一方法 AST 确定性生成 Java 方法和 MyBatis XML statement。
 */
public final class MyBatisMethodSqlGenerator {
    private MyBatisMethodSqlGenerator() {
    }

    public static @NotNull MyBatisMethodGeneration generate(
            @NotNull MyBatisMethodGenerationRequest request) {
        List<MyBatisMethodCondition> conditions = conditions(request.query().predicate());
        validateOptionalConditions(request, conditions);
        ParameterModel parameterModel = parameters(request, conditions);
        String returnType = returnType(request);
        String javaMethod = returnType + " " + request.query().methodName() + "("
                + parameterModel.parameters().stream()
                        .map(MyBatisMethodParameter::declaration)
                        .collect(Collectors.joining(", "))
                + ");";
        String body = statementBody(request, conditions, parameterModel);
        String xml = statement(request, body);
        return new MyBatisMethodGeneration(
                returnType,
                parameterModel.parameters(),
                javaMethod,
                xml,
                xmlTextToPreview(body),
                !request.optionalConditionIndexes().isEmpty()
                        || conditions.stream().anyMatch(MyBatisMethodSqlGenerator::usesXmlElement));
    }

    private static void validateOptionalConditions(
            @NotNull MyBatisMethodGenerationRequest request,
            @NotNull List<MyBatisMethodCondition> conditions) {
        for (int index : request.optionalConditionIndexes()) {
            if (index >= conditions.size()) {
                throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                        "methodsql.generator.error.optional.index", index));
            }
            if (conditions.get(index).comparison().parameterCount() == 0) {
                throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                        "methodsql.generator.error.optional.parameterless", index));
            }
        }
        if (!request.optionalConditionIndexes().isEmpty()
                && containsOr(request.query().predicate().orElse(null))) {
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.generator.error.optional.or"));
        }
    }

    private static @NotNull ParameterModel parameters(
            @NotNull MyBatisMethodGenerationRequest request,
            @NotNull List<MyBatisMethodCondition> conditions) {
        NameAllocator names = new NameAllocator();
        List<MyBatisMethodParameter> parameters = new ArrayList<>();
        for (MyBatisMethodField field : request.query().subjectFields()) {
            if (request.query().operation() != MyBatisMethodOperation.UPDATE) {
                break;
            }
            String name = names.allocate("new" + field.methodToken());
            parameters.add(parameter(
                    name, field.javaType(), MyBatisMethodParameterRole.UPDATE_VALUE, field));
        }
        Map<MyBatisMethodCondition, ConditionParameters> conditionParameters =
                new IdentityHashMap<>();
        for (MyBatisMethodCondition condition : conditions) {
            MyBatisMethodField field = condition.field();
            String base = field.propertyName();
            ConditionParameters values;
            switch (condition.comparison()) {
                case IS_NULL, IS_NOT_NULL, TRUE, FALSE -> values = ConditionParameters.empty();
                case BETWEEN -> {
                    MyBatisMethodParameter start = parameter(
                            names.allocate(base + "Start"),
                            field.javaType(), MyBatisMethodParameterRole.RANGE_START, field);
                    MyBatisMethodParameter end = parameter(
                            names.allocate(base + "End"),
                            field.javaType(), MyBatisMethodParameterRole.RANGE_END, field);
                    parameters.add(start);
                    parameters.add(end);
                    values = new ConditionParameters(List.of(start, end), Optional.empty());
                }
                case IN, NOT_IN -> {
                    MyBatisMethodParameter collection = parameter(
                            names.allocate(base + "Values"),
                            "java.util.Collection<" + boxed(field.javaType()) + ">",
                            MyBatisMethodParameterRole.COLLECTION,
                            field);
                    parameters.add(collection);
                    values = new ConditionParameters(List.of(collection), Optional.empty());
                }
                case STARTING_WITH, ENDING_WITH, CONTAINING -> {
                    MyBatisMethodParameter value = parameter(
                            names.allocate(base),
                            field.javaType(), MyBatisMethodParameterRole.CONDITION_VALUE, field);
                    parameters.add(value);
                    values = new ConditionParameters(
                            List.of(value), Optional.of(names.allocate(base + "Pattern")));
                }
                default -> {
                    MyBatisMethodParameter value = parameter(
                            names.allocate(base),
                            field.javaType(), MyBatisMethodParameterRole.CONDITION_VALUE, field);
                    parameters.add(value);
                    values = new ConditionParameters(List.of(value), Optional.empty());
                }
            }
            conditionParameters.put(condition, values);
        }
        Optional<MyBatisMethodParameter> offset = Optional.empty();
        Optional<MyBatisMethodParameter> pageSize = Optional.empty();
        if (request.query().paged()) {
            offset = Optional.of(parameter(
                    names.allocate("offset"),
                    "long", MyBatisMethodParameterRole.PAGE_OFFSET, null));
            pageSize = Optional.of(parameter(
                    names.allocate("pageSize"),
                    "int", MyBatisMethodParameterRole.PAGE_SIZE, null));
            parameters.add(offset.orElseThrow());
            parameters.add(pageSize.orElseThrow());
        }
        return new ParameterModel(
                List.copyOf(parameters), conditionParameters, offset, pageSize);
    }

    private static @NotNull String statementBody(
            @NotNull MyBatisMethodGenerationRequest request,
            @NotNull List<MyBatisMethodCondition> conditions,
            @NotNull ParameterModel parameters) {
        StringBuilder body = new StringBuilder();
        appendBinds(body, conditions, parameters);
        switch (request.query().operation()) {
            case SELECT -> appendSelect(body, request, conditions, parameters);
            case COUNT, EXISTS, SUM, AVERAGE, MINIMUM, MAXIMUM ->
                    appendAggregate(body, request, conditions, parameters);
            case UPDATE -> appendUpdate(body, request, conditions, parameters);
            case DELETE -> appendDelete(body, request, conditions, parameters);
            default -> throw new IllegalStateException(MyBatisMethodSqlMessages.message(
                    "methodsql.error.operation.unknown", request.query().operation()));
        }
        return body.toString();
    }

    private static void appendSelect(
            @NotNull StringBuilder body,
            @NotNull MyBatisMethodGenerationRequest request,
            @NotNull List<MyBatisMethodCondition> conditions,
            @NotNull ParameterModel parameters) {
        MyBatisMethodQuery query = request.query();
        body.append("SELECT ");
        if (query.distinct()) {
            body.append("DISTINCT ");
        }
        if (request.dialect() == MyBatisSqlDialect.SQL_SERVER && query.limit().isPresent()) {
            body.append("TOP (").append(query.limit().orElseThrow()).append(") ");
        }
        if (query.subjectFields().isEmpty()) {
            body.append(request.schema().fields().stream()
                    .map(field -> identifier(field.columnName(), request))
                    .collect(Collectors.joining(", ")));
        } else {
            body.append(query.subjectFields().stream()
                    .map(field -> identifier(field.columnName(), request))
                    .collect(Collectors.joining(", ")));
        }
        body.append(" FROM ").append(identifier(request.schema().tableName(), request));
        appendWhere(body, request, conditions, parameters);
        appendOrder(body, request);
        appendLimit(body, request, parameters);
    }

    private static void appendAggregate(
            @NotNull StringBuilder body,
            @NotNull MyBatisMethodGenerationRequest request,
            @NotNull List<MyBatisMethodCondition> conditions,
            @NotNull ParameterModel parameters) {
        MyBatisMethodQuery query = request.query();
        body.append("SELECT ");
        switch (query.operation()) {
            case COUNT -> {
                if (query.subjectFields().isEmpty()) {
                    body.append("COUNT(*)");
                } else {
                    body.append("COUNT(");
                    if (query.distinct()) {
                        body.append("DISTINCT ");
                    }
                    body.append(identifier(
                            query.subjectFields().get(0).columnName(), request)).append(')');
                }
            }
            case EXISTS -> body.append("CASE WHEN COUNT(*) > 0 THEN 1 ELSE 0 END");
            case SUM, AVERAGE, MINIMUM, MAXIMUM -> {
                String function = switch (query.operation()) {
                    case SUM -> "SUM";
                    case AVERAGE -> "AVG";
                    case MINIMUM -> "MIN";
                    case MAXIMUM -> "MAX";
                    default -> throw new IllegalStateException();
                };
                body.append(function).append('(');
                if (query.distinct()) {
                    body.append("DISTINCT ");
                }
                body.append(identifier(
                        query.subjectFields().get(0).columnName(), request)).append(')');
            }
            default -> throw new IllegalStateException(MyBatisMethodSqlMessages.message(
                    "methodsql.generator.error.aggregate.operation", query.operation()));
        }
        body.append(" FROM ").append(identifier(request.schema().tableName(), request));
        appendWhere(body, request, conditions, parameters);
    }

    private static void appendUpdate(
            @NotNull StringBuilder body,
            @NotNull MyBatisMethodGenerationRequest request,
            @NotNull List<MyBatisMethodCondition> conditions,
            @NotNull ParameterModel parameters) {
        body.append("UPDATE ").append(identifier(request.schema().tableName(), request))
                .append(" SET ");
        int parameterIndex = 0;
        for (int index = 0; index < request.query().subjectFields().size(); index++) {
            if (index > 0) {
                body.append(", ");
            }
            MyBatisMethodField field = request.query().subjectFields().get(index);
            body.append(identifier(field.columnName(), request)).append(" = ")
                    .append(placeholder(parameters.parameters().get(parameterIndex++), field));
        }
        appendWhere(body, request, conditions, parameters);
    }

    private static void appendDelete(
            @NotNull StringBuilder body,
            @NotNull MyBatisMethodGenerationRequest request,
            @NotNull List<MyBatisMethodCondition> conditions,
            @NotNull ParameterModel parameters) {
        body.append("DELETE FROM ").append(identifier(request.schema().tableName(), request));
        appendWhere(body, request, conditions, parameters);
    }

    private static void appendWhere(
            @NotNull StringBuilder body,
            @NotNull MyBatisMethodGenerationRequest request,
            @NotNull List<MyBatisMethodCondition> conditions,
            @NotNull ParameterModel parameters) {
        if (conditions.isEmpty()) {
            return;
        }
        if (request.optionalConditionIndexes().isEmpty()) {
            body.append(" WHERE ").append(renderPredicate(
                    request.query().predicate().orElseThrow(), request, parameters));
            return;
        }
        body.append("\n<where>\n");
        for (int index = 0; index < conditions.size(); index++) {
            MyBatisMethodCondition condition = conditions.get(index);
            String sql = renderCondition(condition, request, parameters);
            if (request.optionalConditionIndexes().contains(index)) {
                body.append("  <if test=\"")
                        .append(xmlAttribute(optionalTest(
                                parameters.conditionParameters().get(condition))))
                        .append("\">AND ").append(sql).append("</if>\n");
            } else {
                body.append("  AND ").append(sql).append('\n');
            }
        }
        body.append("</where>");
    }

    private static @NotNull String renderPredicate(
            @NotNull MyBatisMethodPredicate predicate,
            @NotNull MyBatisMethodGenerationRequest request,
            @NotNull ParameterModel parameters) {
        if (predicate instanceof MyBatisMethodCondition condition) {
            return renderCondition(condition, request, parameters);
        }
        MyBatisMethodJunction junction = (MyBatisMethodJunction) predicate;
        String separator = junction.kind() == MyBatisMethodJunctionKind.AND ? " AND " : " OR ";
        return junction.children().stream()
                .map(child -> child instanceof MyBatisMethodJunction
                        ? "(" + renderPredicate(child, request, parameters) + ")"
                        : renderPredicate(child, request, parameters))
                .collect(Collectors.joining(separator));
    }

    private static @NotNull String renderCondition(
            @NotNull MyBatisMethodCondition condition,
            @NotNull MyBatisMethodGenerationRequest request,
            @NotNull ParameterModel parameters) {
        String column = identifier(condition.field().columnName(), request);
        ConditionParameters values = parameters.conditionParameters().get(condition);
        return switch (condition.comparison()) {
            case EQUALS -> column + " = " + placeholder(values.only(), condition.field());
            case NOT_EQUALS -> column + " &lt;> " + placeholder(values.only(), condition.field());
            case LESS_THAN -> column + " &lt; " + placeholder(values.only(), condition.field());
            case LESS_THAN_OR_EQUAL -> column + " &lt;= " + placeholder(values.only(), condition.field());
            case GREATER_THAN -> column + " > " + placeholder(values.only(), condition.field());
            case GREATER_THAN_OR_EQUAL -> column + " >= " + placeholder(values.only(), condition.field());
            case BETWEEN -> column + " BETWEEN "
                    + placeholder(values.values().get(0), condition.field()) + " AND "
                    + placeholder(values.values().get(1), condition.field());
            case IN, NOT_IN -> column
                    + (condition.comparison() == MyBatisMethodComparison.NOT_IN ? " NOT IN " : " IN ")
                    + "<foreach collection=\"" + xmlAttribute(values.only().name())
                    + "\" item=\"item\" open=\"(\" separator=\",\" close=\")\">"
                    + itemPlaceholder(condition.field()) + "</foreach>";
            case LIKE, NOT_LIKE -> column
                    + (condition.comparison() == MyBatisMethodComparison.NOT_LIKE
                            ? " NOT LIKE " : " LIKE ")
                    + placeholder(values.only(), condition.field());
            case STARTING_WITH, ENDING_WITH, CONTAINING -> column + " LIKE #{"
                    + values.bindName().orElseThrow() + '}';
            case IS_NULL -> column + " IS NULL";
            case IS_NOT_NULL -> column + " IS NOT NULL";
            case TRUE -> column + " = " + booleanLiteral(true, request.dialect());
            case FALSE -> column + " = " + booleanLiteral(false, request.dialect());
        };
    }

    private static void appendBinds(
            @NotNull StringBuilder body,
            @NotNull List<MyBatisMethodCondition> conditions,
            @NotNull ParameterModel parameters) {
        for (MyBatisMethodCondition condition : conditions) {
            ConditionParameters values = parameters.conditionParameters().get(condition);
            if (values.bindName().isEmpty()) {
                continue;
            }
            String name = values.only().name();
            String expression = switch (condition.comparison()) {
                case STARTING_WITH -> name + " + '%'";
                case ENDING_WITH -> "'%' + " + name;
                case CONTAINING -> "'%' + " + name + " + '%'";
                default -> throw new IllegalStateException(MyBatisMethodSqlMessages.message(
                        "methodsql.generator.error.bind.unneeded", condition.comparison()));
            };
            body.append("<bind name=\"").append(xmlAttribute(values.bindName().orElseThrow()))
                    .append("\" value=\"").append(xmlAttribute(expression)).append("\"/>\n");
        }
    }

    private static void appendOrder(
            @NotNull StringBuilder body,
            @NotNull MyBatisMethodGenerationRequest request) {
        if (request.query().orders().isEmpty()) {
            return;
        }
        body.append(" ORDER BY ").append(request.query().orders().stream()
                .map(order -> identifier(order.field().columnName(), request) + " "
                        + (order.direction() == MyBatisMethodOrder.Direction.ASCENDING
                                ? "ASC" : "DESC"))
                .collect(Collectors.joining(", ")));
    }

    private static void appendLimit(
            @NotNull StringBuilder body,
            @NotNull MyBatisMethodGenerationRequest request,
            @NotNull ParameterModel parameters) {
        if (request.query().paged()) {
            if (request.dialect() == MyBatisSqlDialect.SQL_SERVER
                    && request.query().orders().isEmpty()) {
                throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                        "methodsql.generator.error.sqlserver.order.required"));
            }
            String offset = "#{" + parameters.offset().orElseThrow().name() + '}';
            String size = "#{" + parameters.pageSize().orElseThrow().name() + '}';
            switch (request.dialect()) {
                case MYSQL, POSTGRESQL, SQLITE, DAMENG, H2 ->
                        body.append(" LIMIT ").append(size).append(" OFFSET ").append(offset);
                case GENERIC, ORACLE, SQL_SERVER -> body.append(" OFFSET ")
                        .append(offset).append(" ROWS FETCH NEXT ")
                        .append(size).append(" ROWS ONLY");
            }
            return;
        }
        if (request.query().limit().isEmpty()
                || request.dialect() == MyBatisSqlDialect.SQL_SERVER) {
            return;
        }
        int limit = request.query().limit().orElseThrow();
        switch (request.dialect()) {
            case MYSQL, POSTGRESQL, SQLITE, DAMENG, H2 -> body.append(" LIMIT ").append(limit);
            case GENERIC, ORACLE -> body.append(" FETCH FIRST ")
                    .append(limit).append(" ROWS ONLY");
            case SQL_SERVER -> throw new IllegalStateException(MyBatisMethodSqlMessages.message(
                    "methodsql.generator.error.sqlserver.top.used"));
        }
    }

    private static @NotNull String statement(
            @NotNull MyBatisMethodGenerationRequest request,
            @NotNull String body) {
        String tag = switch (request.query().operation()) {
            case UPDATE -> "update";
            case DELETE -> "delete";
            default -> "select";
        };
        StringBuilder result = new StringBuilder("<").append(tag)
                .append(" id=\"").append(xmlAttribute(request.query().methodName())).append('"');
        if ("select".equals(tag)) {
            result.append(" resultType=\"").append(xmlAttribute(xmlResultType(request)))
                    .append('"');
        }
        return result.append(">\n  ")
                .append(body.replace("\n", "\n  "))
                .append("\n</").append(tag).append(">\n")
                .toString();
    }

    private static @NotNull String returnType(
            @NotNull MyBatisMethodGenerationRequest request) {
        MyBatisMethodQuery query = request.query();
        return switch (query.operation()) {
            case UPDATE, DELETE -> "int";
            case COUNT -> "long";
            case EXISTS -> "boolean";
            case SUM, AVERAGE -> "java.math.BigDecimal";
            case MINIMUM, MAXIMUM -> query.subjectFields().get(0).javaType();
            case SELECT -> {
                String element = selectElementType(request);
                if (query.singleResult()) {
                    yield "java.util.Optional<" + boxed(element) + ">";
                }
                yield "java.util.List<" + boxed(element) + ">";
            }
        };
    }

    private static @NotNull String selectElementType(
            @NotNull MyBatisMethodGenerationRequest request) {
        List<MyBatisMethodField> fields = request.query().subjectFields();
        if (fields.isEmpty()) {
            return request.entityType();
        }
        if (fields.size() == 1) {
            return fields.get(0).javaType();
        }
        return "java.util.Map<java.lang.String, java.lang.Object>";
    }

    private static @NotNull String xmlResultType(
            @NotNull MyBatisMethodGenerationRequest request) {
        return switch (request.query().operation()) {
            case COUNT -> "long";
            case EXISTS -> "boolean";
            case SUM, AVERAGE -> "java.math.BigDecimal";
            case MINIMUM, MAXIMUM -> request.query().subjectFields().get(0).javaType();
            case SELECT -> request.query().subjectFields().size() > 1
                    ? "map" : selectElementType(request);
            case UPDATE, DELETE -> throw new IllegalStateException(
                    MyBatisMethodSqlMessages.message(
                            "methodsql.generator.error.write.result.type"));
        };
    }

    private static @NotNull String identifier(
            @NotNull String name,
            @NotNull MyBatisMethodGenerationRequest request) {
        String sql;
        if (!request.escapeIdentifiers()) {
            sql = name;
        } else {
            sql = switch (request.dialect()) {
                case MYSQL -> "`" + name.replace("`", "``") + "`";
                case SQL_SERVER -> "[" + name.replace("]", "]]" ) + "]";
                case GENERIC, POSTGRESQL, ORACLE, SQLITE, DAMENG, H2 ->
                        "\"" + name.replace("\"", "\"\"") + "\"";
            };
        }
        return xmlText(sql);
    }

    private static @NotNull String placeholder(
            @NotNull MyBatisMethodParameter parameter,
            @NotNull MyBatisMethodField field) {
        StringBuilder value = new StringBuilder("#{").append(parameter.name())
                .append(",jdbcType=").append(jdbcTypeName(field.jdbcType()));
        field.typeHandler().ifPresent(handler -> value
                .append(",typeHandler=").append(handler));
        return value.append('}').toString();
    }

    private static @NotNull String itemPlaceholder(@NotNull MyBatisMethodField field) {
        StringBuilder value = new StringBuilder("#{item,jdbcType=")
                .append(jdbcTypeName(field.jdbcType()));
        field.typeHandler().ifPresent(handler -> value
                .append(",typeHandler=").append(handler));
        return value.append('}').toString();
    }

    private static @NotNull String optionalTest(@NotNull ConditionParameters parameters) {
        if (parameters.values().size() == 2) {
            return parameters.values().get(0).name() + " != null and "
                    + parameters.values().get(1).name() + " != null";
        }
        MyBatisMethodParameter parameter = parameters.only();
        if (parameter.role() == MyBatisMethodParameterRole.COLLECTION) {
            return parameter.name() + " != null and !" + parameter.name() + ".isEmpty()";
        }
        return parameter.name() + " != null";
    }

    private static boolean containsOr(MyBatisMethodPredicate predicate) {
        if (!(predicate instanceof MyBatisMethodJunction junction)) {
            return false;
        }
        return junction.kind() == MyBatisMethodJunctionKind.OR
                || junction.children().stream().anyMatch(MyBatisMethodSqlGenerator::containsOr);
    }

    private static @NotNull List<MyBatisMethodCondition> conditions(
            @NotNull Optional<MyBatisMethodPredicate> predicate) {
        if (predicate.isEmpty()) {
            return List.of();
        }
        List<MyBatisMethodCondition> result = new ArrayList<>();
        collectConditions(predicate.orElseThrow(), result);
        return List.copyOf(result);
    }

    private static void collectConditions(
            @NotNull MyBatisMethodPredicate predicate,
            @NotNull List<MyBatisMethodCondition> target) {
        if (predicate instanceof MyBatisMethodCondition condition) {
            target.add(condition);
            return;
        }
        for (MyBatisMethodPredicate child : ((MyBatisMethodJunction) predicate).children()) {
            collectConditions(child, target);
        }
    }

    private static boolean usesXmlElement(@NotNull MyBatisMethodCondition condition) {
        return condition.comparison() == MyBatisMethodComparison.IN
                || condition.comparison() == MyBatisMethodComparison.NOT_IN
                || condition.comparison() == MyBatisMethodComparison.STARTING_WITH
                || condition.comparison() == MyBatisMethodComparison.ENDING_WITH
                || condition.comparison() == MyBatisMethodComparison.CONTAINING;
    }

    private static @NotNull MyBatisMethodParameter parameter(
            @NotNull String name,
            @NotNull String type,
            @NotNull MyBatisMethodParameterRole role,
            MyBatisMethodField field) {
        return new MyBatisMethodParameter(name, type, role, Optional.ofNullable(field));
    }

    private static @NotNull String booleanLiteral(
            boolean value,
            @NotNull MyBatisSqlDialect dialect) {
        if (dialect == MyBatisSqlDialect.ORACLE || dialect == MyBatisSqlDialect.SQL_SERVER) {
            return value ? "1" : "0";
        }
        return Boolean.toString(value).toUpperCase(Locale.ROOT);
    }

    private static @NotNull String boxed(@NotNull String type) {
        return switch (type) {
            case "boolean" -> "java.lang.Boolean";
            case "byte" -> "java.lang.Byte";
            case "short" -> "java.lang.Short";
            case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            case "float" -> "java.lang.Float";
            case "double" -> "java.lang.Double";
            case "char" -> "java.lang.Character";
            default -> type;
        };
    }

    private static @NotNull String jdbcTypeName(int type) {
        return switch (type) {
            case Types.ARRAY -> "ARRAY";
            case Types.TINYINT -> "TINYINT";
            case Types.SMALLINT -> "SMALLINT";
            case Types.INTEGER -> "INTEGER";
            case Types.BIGINT -> "BIGINT";
            case Types.FLOAT -> "FLOAT";
            case Types.REAL -> "REAL";
            case Types.DOUBLE -> "DOUBLE";
            case Types.NUMERIC -> "NUMERIC";
            case Types.DECIMAL -> "DECIMAL";
            case Types.BIT -> "BIT";
            case Types.BOOLEAN -> "BOOLEAN";
            case Types.CHAR -> "CHAR";
            case Types.VARCHAR -> "VARCHAR";
            case Types.LONGVARCHAR -> "LONGVARCHAR";
            case Types.NCHAR -> "NCHAR";
            case Types.NVARCHAR -> "NVARCHAR";
            case Types.LONGNVARCHAR -> "LONGNVARCHAR";
            case Types.DATE -> "DATE";
            case Types.TIME -> "TIME";
            case Types.TIME_WITH_TIMEZONE -> "TIME_WITH_TIMEZONE";
            case Types.TIMESTAMP -> "TIMESTAMP";
            case Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP_WITH_TIMEZONE";
            case Types.BINARY -> "BINARY";
            case Types.VARBINARY -> "VARBINARY";
            case Types.LONGVARBINARY -> "LONGVARBINARY";
            case Types.BLOB -> "BLOB";
            case Types.CLOB -> "CLOB";
            case Types.NCLOB -> "NCLOB";
            case Types.SQLXML -> "SQLXML";
            case Types.NULL -> "NULL";
            case Types.OTHER -> "OTHER";
            default -> "OTHER";
        };
    }

    private static @NotNull String xmlAttribute(@NotNull String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static @NotNull String xmlText(@NotNull String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;");
    }

    private static @NotNull String xmlTextToPreview(@NotNull String value) {
        return value.replace("&lt;", "<").replace("&amp;", "&");
    }

    private record ParameterModel(
            @NotNull List<MyBatisMethodParameter> parameters,
            @NotNull Map<MyBatisMethodCondition, ConditionParameters> conditionParameters,
            @NotNull Optional<MyBatisMethodParameter> offset,
            @NotNull Optional<MyBatisMethodParameter> pageSize) {
    }

    private record ConditionParameters(
            @NotNull List<MyBatisMethodParameter> values,
            @NotNull Optional<String> bindName) {
        private static @NotNull ConditionParameters empty() {
            return new ConditionParameters(List.of(), Optional.empty());
        }

        private @NotNull MyBatisMethodParameter only() {
            if (values.size() != 1) {
                throw new IllegalStateException(MyBatisMethodSqlMessages.message(
                        "methodsql.generator.error.condition.parameter.count", values.size()));
            }
            return values.get(0);
        }
    }

    private static final class NameAllocator {
        private final Set<String> names = new LinkedHashSet<>();

        private @NotNull String allocate(@NotNull String preferred) {
            if (names.add(preferred)) {
                return preferred;
            }
            for (int suffix = 2; ; suffix++) {
                String candidate = preferred + suffix;
                if (names.add(candidate)) {
                    return candidate;
                }
            }
        }
    }
}
