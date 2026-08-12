package io.github.ns3154.mybatisassistant.methodsql;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 仅输出已由官方稳定 API 覆盖的 MyBatis-Plus/Flex Wrapper 代码。
 */
public final class MyBatisWrapperGenerator {
    private MyBatisWrapperGenerator() {
    }

    public static @NotNull MyBatisWrapperGeneration generate(
            @NotNull MyBatisWrapperGenerationRequest request) {
        validateVersion(request.framework(), request.frameworkVersion());
        validateOperation(request);
        List<MyBatisMethodCondition> conditions = conditions(request.query().predicate());
        validateOptionalIndexes(request.optionalConditionIndexes(), conditions);
        if (!request.optionalConditionIndexes().isEmpty()
                && request.query().predicate().filter(MyBatisWrapperGenerator::containsOr)
                        .isPresent()) {
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.wrapper.error.optional.or"));
        }
        Map<MyBatisMethodCondition, List<MyBatisMethodParameter>> parameters =
                conditionParameters(request, conditions);
        String wrapperType = wrapperType(request);
        StringBuilder code = new StringBuilder(wrapperType).append(" wrapper = ")
                .append(initializer(request)).append(";\n");
        appendSelection(code, request);
        appendUpdates(code, request);
        if (request.query().predicate().isPresent()) {
            code.append("wrapper")
                    .append(predicateCalls(
                            request.query().predicate().orElseThrow(),
                            request,
                            parameters,
                            new Counter()))
                    .append(";\n");
        }
        appendOrders(code, request);
        appendFlexLimit(code, request);
        return new MyBatisWrapperGeneration(wrapperType, "wrapper", code.toString());
    }

    private static void validateOperation(@NotNull MyBatisWrapperGenerationRequest request) {
        MyBatisMethodOperation operation = request.query().operation();
        if (operation == MyBatisMethodOperation.SUM
                || operation == MyBatisMethodOperation.AVERAGE
                || operation == MyBatisMethodOperation.MINIMUM
                || operation == MyBatisMethodOperation.MAXIMUM) {
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.wrapper.error.aggregate"));
        }
        if (request.framework() == MyBatisWrapperFramework.MYBATIS_FLEX
                && operation == MyBatisMethodOperation.UPDATE) {
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.wrapper.error.flex.update"));
        }
        if (request.framework() == MyBatisWrapperFramework.MYBATIS_PLUS
                && (request.query().limit().isPresent() || request.query().paged())) {
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.wrapper.error.plus.page"));
        }
    }

    private static void validateVersion(
            @NotNull MyBatisWrapperFramework framework,
            @NotNull String version) {
        framework.frameworkKind().requireSupportedVersion(version);
    }

    private static @NotNull String wrapperType(
            @NotNull MyBatisWrapperGenerationRequest request) {
        if (request.framework() == MyBatisWrapperFramework.MYBATIS_FLEX) {
            return "com.mybatisflex.core.query.QueryWrapper";
        }
        String type = request.query().operation() == MyBatisMethodOperation.UPDATE
                ? "com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper"
                : "com.baomidou.mybatisplus.core.conditions.query.QueryWrapper";
        return type + "<" + request.entityType() + ">";
    }

    private static @NotNull String initializer(
            @NotNull MyBatisWrapperGenerationRequest request) {
        if (request.framework() == MyBatisWrapperFramework.MYBATIS_FLEX) {
            return "com.mybatisflex.core.query.QueryWrapper.create().from(\""
                    + javaString(request.schema().tableName()) + "\")";
        }
        String type = request.query().operation() == MyBatisMethodOperation.UPDATE
                ? "com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper"
                : "com.baomidou.mybatisplus.core.conditions.query.QueryWrapper";
        return "new " + type + "<>()";
    }

    private static void appendSelection(
            @NotNull StringBuilder code,
            @NotNull MyBatisWrapperGenerationRequest request) {
        if (request.query().operation() != MyBatisMethodOperation.SELECT
                || request.query().subjectFields().isEmpty()) {
            return;
        }
        if (request.framework() == MyBatisWrapperFramework.MYBATIS_PLUS) {
            List<String> columns = request.query().subjectFields().stream()
                    .map(field -> "\"" + javaString(field.columnName()) + "\"")
                    .collect(Collectors.toCollection(ArrayList::new));
            if (request.query().distinct()) {
                columns.set(0, "\"DISTINCT "
                        + javaString(request.query().subjectFields().get(0).columnName()) + "\"");
            }
            code.append("wrapper.select(").append(String.join(", ", columns)).append(");\n");
            return;
        }
        List<String> columns = request.query().subjectFields().stream()
                .map(field -> flexColumn(field.columnName()))
                .collect(Collectors.toCollection(ArrayList::new));
        if (request.query().distinct()) {
            columns.set(0, "com.mybatisflex.core.query.QueryMethods.distinct("
                    + columns.get(0) + ")");
        }
        code.append("wrapper.select(").append(String.join(", ", columns)).append(");\n");
    }

    private static void appendUpdates(
            @NotNull StringBuilder code,
            @NotNull MyBatisWrapperGenerationRequest request) {
        if (request.query().operation() != MyBatisMethodOperation.UPDATE) {
            return;
        }
        List<MyBatisMethodParameter> updateParameters = request.methodGeneration().parameters()
                .stream()
                .filter(parameter -> parameter.role() == MyBatisMethodParameterRole.UPDATE_VALUE)
                .toList();
        code.append("wrapper");
        for (int index = 0; index < request.query().subjectFields().size(); index++) {
            MyBatisMethodField field = request.query().subjectFields().get(index);
            code.append(".set(\"").append(javaString(field.columnName())).append("\", ")
                    .append(updateParameters.get(index).name()).append(')');
        }
        code.append(";\n");
    }

    private static @NotNull String predicateCalls(
            @NotNull MyBatisMethodPredicate predicate,
            @NotNull MyBatisWrapperGenerationRequest request,
            @NotNull Map<MyBatisMethodCondition, List<MyBatisMethodParameter>> parameters,
            @NotNull Counter counter) {
        List<MyBatisMethodPredicate> orGroups = predicate instanceof MyBatisMethodJunction junction
                && junction.kind() == MyBatisMethodJunctionKind.OR
                ? junction.children()
                : List.of(predicate);
        StringBuilder calls = new StringBuilder();
        appendAndGroup(calls, orGroups.get(0), request, parameters, counter, null);
        for (int index = 1; index < orGroups.size(); index++) {
            MyBatisMethodPredicate group = orGroups.get(index);
            List<MyBatisMethodCondition> groupConditions = conditions(Optional.of(group));
            if (groupConditions.size() == 1) {
                calls.append(".or()");
                appendCondition(calls, groupConditions.get(0), request, parameters, counter);
            } else {
                String name = "group" + counter.next();
                calls.append(".or(").append(name).append(" -> ").append(name);
                appendAndGroup(calls, group, request, parameters, counter, name);
                calls.append(')');
            }
        }
        return calls.toString();
    }

    private static void appendAndGroup(
            @NotNull StringBuilder target,
            @NotNull MyBatisMethodPredicate group,
            @NotNull MyBatisWrapperGenerationRequest request,
            @NotNull Map<MyBatisMethodCondition, List<MyBatisMethodParameter>> parameters,
            @NotNull Counter counter,
            String ignoredGroupName) {
        for (MyBatisMethodCondition condition : conditions(Optional.of(group))) {
            appendCondition(target, condition, request, parameters, counter);
        }
    }

    private static void appendCondition(
            @NotNull StringBuilder target,
            @NotNull MyBatisMethodCondition condition,
            @NotNull MyBatisWrapperGenerationRequest request,
            @NotNull Map<MyBatisMethodCondition, List<MyBatisMethodParameter>> parameters,
            @NotNull Counter counter) {
        int conditionIndex = counter.conditionIndex();
        counter.incrementCondition();
        boolean optional = request.optionalConditionIndexes().contains(conditionIndex);
        List<MyBatisMethodParameter> values = parameters.get(condition);
        String method = wrapperMethod(condition.comparison(), request.framework());
        target.append('.').append(method).append('(');
        List<String> arguments = new ArrayList<>();
        String present = optional ? presentExpression(values) : "";
        if (optional && request.framework() == MyBatisWrapperFramework.MYBATIS_PLUS) {
            arguments.add(present);
        }
        arguments.add("\"" + javaString(condition.field().columnName()) + "\"");
        switch (condition.comparison()) {
            case IS_NULL, IS_NOT_NULL -> {
                // 无值参数。
            }
            case TRUE -> arguments.add("true");
            case FALSE -> arguments.add("false");
            case BETWEEN -> {
                arguments.add(values.get(0).name());
                arguments.add(values.get(1).name());
            }
            default -> arguments.add(values.get(0).name());
        }
        if (optional && request.framework() == MyBatisWrapperFramework.MYBATIS_FLEX) {
            arguments.add(present);
        }
        target.append(String.join(", ", arguments)).append(')');
    }

    private static @NotNull String wrapperMethod(
            @NotNull MyBatisMethodComparison comparison,
            @NotNull MyBatisWrapperFramework framework) {
        return switch (comparison) {
            case EQUALS, TRUE, FALSE -> "eq";
            case NOT_EQUALS -> "ne";
            case LESS_THAN -> "lt";
            case LESS_THAN_OR_EQUAL -> "le";
            case GREATER_THAN -> "gt";
            case GREATER_THAN_OR_EQUAL -> "ge";
            case BETWEEN -> "between";
            case IN -> "in";
            case NOT_IN -> "notIn";
            case LIKE, CONTAINING -> "like";
            case NOT_LIKE -> "notLike";
            case STARTING_WITH -> framework == MyBatisWrapperFramework.MYBATIS_PLUS
                    ? "likeRight" : "likeLeft";
            case ENDING_WITH -> framework == MyBatisWrapperFramework.MYBATIS_PLUS
                    ? "likeLeft" : "likeRight";
            case IS_NULL -> "isNull";
            case IS_NOT_NULL -> "isNotNull";
        };
    }

    private static void appendOrders(
            @NotNull StringBuilder code,
            @NotNull MyBatisWrapperGenerationRequest request) {
        for (MyBatisMethodOrder order : request.query().orders()) {
            if (request.framework() == MyBatisWrapperFramework.MYBATIS_PLUS) {
                code.append("wrapper.")
                        .append(order.direction() == MyBatisMethodOrder.Direction.ASCENDING
                                ? "orderByAsc" : "orderByDesc")
                        .append("(\"").append(javaString(order.field().columnName()))
                        .append("\");\n");
            } else {
                code.append("wrapper.orderBy(").append(flexColumn(order.field().columnName()))
                        .append(order.direction() == MyBatisMethodOrder.Direction.ASCENDING
                                ? ".asc()" : ".desc()")
                        .append(");\n");
            }
        }
    }

    private static void appendFlexLimit(
            @NotNull StringBuilder code,
            @NotNull MyBatisWrapperGenerationRequest request) {
        if (request.framework() != MyBatisWrapperFramework.MYBATIS_FLEX) {
            return;
        }
        if (request.query().limit().isPresent()) {
            code.append("wrapper.limit(").append(request.query().limit().orElseThrow())
                    .append(");\n");
        }
        if (request.query().paged()) {
            MyBatisMethodParameter offset = request.methodGeneration().parameters().stream()
                    .filter(parameter -> parameter.role() == MyBatisMethodParameterRole.PAGE_OFFSET)
                    .findFirst().orElseThrow();
            MyBatisMethodParameter size = request.methodGeneration().parameters().stream()
                    .filter(parameter -> parameter.role() == MyBatisMethodParameterRole.PAGE_SIZE)
                    .findFirst().orElseThrow();
            code.append("wrapper.limit(").append(size.name()).append(").offset(")
                    .append(offset.name()).append(");\n");
        }
    }

    private static @NotNull Map<MyBatisMethodCondition, List<MyBatisMethodParameter>>
            conditionParameters(
                    @NotNull MyBatisWrapperGenerationRequest request,
                    @NotNull List<MyBatisMethodCondition> conditions) {
        List<MyBatisMethodParameter> values = request.methodGeneration().parameters().stream()
                .filter(parameter -> parameter.role() != MyBatisMethodParameterRole.UPDATE_VALUE
                        && parameter.role() != MyBatisMethodParameterRole.PAGE_OFFSET
                        && parameter.role() != MyBatisMethodParameterRole.PAGE_SIZE)
                .toList();
        Map<MyBatisMethodCondition, List<MyBatisMethodParameter>> result =
                new IdentityHashMap<>();
        int cursor = 0;
        for (MyBatisMethodCondition condition : conditions) {
            int count = condition.comparison().parameterCount();
            result.put(condition, List.copyOf(values.subList(cursor, cursor + count)));
            cursor += count;
        }
        if (cursor != values.size()) {
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.wrapper.error.parameters.mismatch"));
        }
        return result;
    }

    private static void validateOptionalIndexes(
            @NotNull Set<Integer> indexes,
            @NotNull List<MyBatisMethodCondition> conditions) {
        for (int index : indexes) {
            if (index < 0 || index >= conditions.size()) {
                throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                        "methodsql.wrapper.error.optional.index", index));
            }
            if (conditions.get(index).comparison().parameterCount() == 0) {
                throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                        "methodsql.wrapper.error.optional.parameterless", index));
            }
        }
    }

    private static @NotNull String presentExpression(
            @NotNull List<MyBatisMethodParameter> values) {
        if (values.size() == 2) {
            return values.get(0).name() + " != null && " + values.get(1).name() + " != null";
        }
        MyBatisMethodParameter value = values.get(0);
        if (value.role() == MyBatisMethodParameterRole.COLLECTION) {
            return value.name() + " != null && !" + value.name() + ".isEmpty()";
        }
        return value.name() + " != null";
    }

    private static @NotNull List<MyBatisMethodCondition> conditions(
            @NotNull Optional<MyBatisMethodPredicate> predicate) {
        if (predicate.isEmpty()) {
            return List.of();
        }
        List<MyBatisMethodCondition> result = new ArrayList<>();
        collect(predicate.orElseThrow(), result);
        return List.copyOf(result);
    }

    private static void collect(
            @NotNull MyBatisMethodPredicate predicate,
            @NotNull List<MyBatisMethodCondition> target) {
        if (predicate instanceof MyBatisMethodCondition condition) {
            target.add(condition);
            return;
        }
        for (MyBatisMethodPredicate child : ((MyBatisMethodJunction) predicate).children()) {
            collect(child, target);
        }
    }

    private static boolean containsOr(@NotNull MyBatisMethodPredicate predicate) {
        if (predicate instanceof MyBatisMethodCondition) {
            return false;
        }
        MyBatisMethodJunction junction = (MyBatisMethodJunction) predicate;
        return junction.kind() == MyBatisMethodJunctionKind.OR
                || junction.children().stream().anyMatch(MyBatisWrapperGenerator::containsOr);
    }

    private static @NotNull String flexColumn(@NotNull String column) {
        return "com.mybatisflex.core.query.QueryMethods.column(\""
                + javaString(column) + "\")";
    }

    private static @NotNull String javaString(@NotNull String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static final class Counter {
        private int lambdaIndex;
        private int conditionIndex;

        private int next() {
            return ++lambdaIndex;
        }

        private int conditionIndex() {
            return conditionIndex;
        }

        private void incrementCondition() {
            conditionIndex++;
        }
    }
}
