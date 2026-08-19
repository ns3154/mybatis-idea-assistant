package io.github.ns3154.mybatisassistant.methodsql;

import com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * 以字段词典和显式语法产生唯一 AST；任何多种合法切分都作为歧义失败。
 */
public final class MyBatisMethodNameParser {
    private static final int MAX_METHOD_NAME_LENGTH = 512;
    private static final int MAX_LIMIT = 10_000;
    private static final int CANDIDATE_LIMIT = 3;
    private static final List<OperationPrefix> OPERATIONS = List.of(
            new OperationPrefix("average", MyBatisMethodOperation.AVERAGE),
            new OperationPrefix("minimum", MyBatisMethodOperation.MINIMUM),
            new OperationPrefix("maximum", MyBatisMethodOperation.MAXIMUM),
            new OperationPrefix("select", MyBatisMethodOperation.SELECT),
            new OperationPrefix("modify", MyBatisMethodOperation.UPDATE),
            new OperationPrefix("delete", MyBatisMethodOperation.DELETE),
            new OperationPrefix("remove", MyBatisMethodOperation.DELETE),
            new OperationPrefix("exists", MyBatisMethodOperation.EXISTS),
            new OperationPrefix("update", MyBatisMethodOperation.UPDATE),
            new OperationPrefix("query", MyBatisMethodOperation.SELECT),
            new OperationPrefix("count", MyBatisMethodOperation.COUNT),
            new OperationPrefix("find", MyBatisMethodOperation.SELECT),
            new OperationPrefix("get", MyBatisMethodOperation.SELECT),
            new OperationPrefix("sum", MyBatisMethodOperation.SUM),
            new OperationPrefix("avg", MyBatisMethodOperation.AVERAGE),
            new OperationPrefix("min", MyBatisMethodOperation.MINIMUM),
            new OperationPrefix("max", MyBatisMethodOperation.MAXIMUM));
    private static final List<OperatorSuffix> OPERATORS = List.of(
            new OperatorSuffix("GreaterThanOrEqual", MyBatisMethodComparison.GREATER_THAN_OR_EQUAL),
            new OperatorSuffix("LessThanOrEqual", MyBatisMethodComparison.LESS_THAN_OR_EQUAL),
            new OperatorSuffix("GreaterThanEqual", MyBatisMethodComparison.GREATER_THAN_OR_EQUAL),
            new OperatorSuffix("LessThanEqual", MyBatisMethodComparison.LESS_THAN_OR_EQUAL),
            new OperatorSuffix("StartingWith", MyBatisMethodComparison.STARTING_WITH),
            new OperatorSuffix("EndingWith", MyBatisMethodComparison.ENDING_WITH),
            new OperatorSuffix("IsNotNull", MyBatisMethodComparison.IS_NOT_NULL),
            new OperatorSuffix("Containing", MyBatisMethodComparison.CONTAINING),
            new OperatorSuffix("GreaterThan", MyBatisMethodComparison.GREATER_THAN),
            new OperatorSuffix("LessThan", MyBatisMethodComparison.LESS_THAN),
            new OperatorSuffix("NotLike", MyBatisMethodComparison.NOT_LIKE),
            new OperatorSuffix("Between", MyBatisMethodComparison.BETWEEN),
            new OperatorSuffix("IsNot", MyBatisMethodComparison.NOT_EQUALS),
            new OperatorSuffix("IsNull", MyBatisMethodComparison.IS_NULL),
            new OperatorSuffix("NotIn", MyBatisMethodComparison.NOT_IN),
            new OperatorSuffix("Equals", MyBatisMethodComparison.EQUALS),
            new OperatorSuffix("Before", MyBatisMethodComparison.LESS_THAN),
            new OperatorSuffix("After", MyBatisMethodComparison.GREATER_THAN),
            new OperatorSuffix("Not", MyBatisMethodComparison.NOT_EQUALS),
            new OperatorSuffix("Like", MyBatisMethodComparison.LIKE),
            new OperatorSuffix("False", MyBatisMethodComparison.FALSE),
            new OperatorSuffix("True", MyBatisMethodComparison.TRUE),
            new OperatorSuffix("In", MyBatisMethodComparison.IN),
            new OperatorSuffix("Is", MyBatisMethodComparison.EQUALS),
            new OperatorSuffix("", MyBatisMethodComparison.EQUALS));

    private MyBatisMethodNameParser() {
    }

    public static @NotNull MyBatisMethodParseResult parse(
            @NotNull String methodName,
            @NotNull MyBatisMethodSchema schema) {
        if (methodName.isBlank()) {
            return failure(MyBatisMethodDiagnosticCode.EMPTY_NAME, 0, methodName.length(),
                    MyBatisMethodSqlMessages.message("methodsql.parser.error.name.empty"));
        }
        if (methodName.length() > MAX_METHOD_NAME_LENGTH) {
            return failure(
                    MyBatisMethodDiagnosticCode.METHOD_NAME_TOO_LONG,
                    MAX_METHOD_NAME_LENGTH,
                    methodName.length() - MAX_METHOD_NAME_LENGTH,
                    MyBatisMethodSqlMessages.message(
                            "methodsql.parser.error.name.too.long", MAX_METHOD_NAME_LENGTH));
        }
        if (methodName.startsWith("insertBatch")) {
            return parseBatchInsert(methodName, schema);
        }
        OperationPrefix operation = operation(methodName);
        if (operation == null) {
            return failure(MyBatisMethodDiagnosticCode.UNKNOWN_OPERATION, 0, methodName.length(),
                    MyBatisMethodSqlMessages.message(
                            "methodsql.parser.error.operation.prefix"));
        }
        String remainder = methodName.substring(operation.text().length());
        MyBatisMethodParseResult invalidLimit = invalidLimit(
                remainder, operation.text().length());
        if (invalidLimit != null) {
            return invalidLimit;
        }
        List<MyBatisMethodQuery> candidates = queryCandidates(
                methodName, operation, remainder, schema.fields());
        List<MyBatisMethodQuery> unique = candidates.stream().distinct().toList();
        if (unique.size() == 1) {
            return new MyBatisMethodParseResult.Success(unique.get(0));
        }
        if (unique.size() > 1) {
            return failure(
                    MyBatisMethodDiagnosticCode.AMBIGUOUS_SYNTAX,
                    operation.text().length(),
                    remainder.length(),
                    MyBatisMethodSqlMessages.message(
                            "methodsql.parser.error.syntax.ambiguous"));
        }
        if ((operation.operation() == MyBatisMethodOperation.UPDATE
                || operation.operation() == MyBatisMethodOperation.DELETE)
                && occurrences(remainder, "By").isEmpty()) {
            return failure(
                    MyBatisMethodDiagnosticCode.PREDICATE_REQUIRED,
                    methodName.length(),
                    0,
                    MyBatisMethodSqlMessages.message(
                            "methodsql.parser.error.predicate.required"));
        }
        if (remainder.contains("OrderBy")) {
            return failure(
                    MyBatisMethodDiagnosticCode.INVALID_ORDER,
                    operation.text().length() + remainder.indexOf("OrderBy"),
                    "OrderBy".length(),
                    MyBatisMethodSqlMessages.message(
                            "methodsql.parser.error.order.invalid"));
        }
        return failure(
                MyBatisMethodDiagnosticCode.UNKNOWN_FIELD,
                operation.text().length(),
                remainder.length(),
                MyBatisMethodSqlMessages.message(
                        "methodsql.parser.error.combination.unsupported"));
    }

    private static @NotNull MyBatisMethodParseResult parseBatchInsert(
            @NotNull String methodName,
            @NotNull MyBatisMethodSchema schema) {
        if (!"insertBatch".equals(methodName)) {
            return failure(
                    MyBatisMethodDiagnosticCode.UNSUPPORTED_COMBINATION,
                    "insertBatch".length(),
                    methodName.length() - "insertBatch".length(),
                    MyBatisMethodSqlMessages.message(
                            "methodsql.parser.error.batch.insert.exact"));
        }
        List<MyBatisMethodField> insertFields = schema.fields().stream()
                .filter(field -> !field.autoIncrement() && !field.generated())
                .toList();
        if (insertFields.isEmpty()) {
            return failure(
                    MyBatisMethodDiagnosticCode.INVALID_SUBJECT,
                    0,
                    methodName.length(),
                    MyBatisMethodSqlMessages.message(
                            "methodsql.parser.error.batch.insert.fields.empty"));
        }
        return new MyBatisMethodParseResult.Success(new MyBatisMethodQuery(
                methodName,
                MyBatisMethodOperation.INSERT_BATCH,
                insertFields,
                false,
                OptionalInt.empty(),
                false,
                false,
                Optional.empty(),
                List.of()));
    }

    private static List<MyBatisMethodQuery> queryCandidates(
            @NotNull String methodName,
            @NotNull OperationPrefix operation,
            @NotNull String remainder,
            @NotNull List<MyBatisMethodField> fields) {
        List<MyBatisMethodQuery> results = new ArrayList<>();
        List<Integer> byPositions = new ArrayList<>();
        byPositions.add(-1);
        byPositions.addAll(occurrences(remainder, "By"));
        for (int by : byPositions) {
            ProgressManager.checkCanceled();
            String beforeBy = by < 0 ? remainder : remainder.substring(0, by);
            String afterBy = by < 0 ? "" : remainder.substring(by + 2);
            String orderSource = by < 0 ? beforeBy : afterBy;
            List<Integer> orderPositions = new ArrayList<>();
            orderPositions.add(-1);
            orderPositions.addAll(occurrences(orderSource, "OrderBy"));
            for (int order : orderPositions) {
                String subjectText;
                String predicateText;
                String orderText;
                if (by < 0) {
                    subjectText = order < 0 ? beforeBy : beforeBy.substring(0, order);
                    predicateText = "";
                    orderText = order < 0 ? "" : beforeBy.substring(order + 7);
                } else {
                    subjectText = beforeBy;
                    predicateText = order < 0 ? afterBy : afterBy.substring(0, order);
                    orderText = order < 0 ? "" : afterBy.substring(order + 7);
                }
                if (by >= 0 && predicateText.isEmpty() || order >= 0 && orderText.isEmpty()) {
                    continue;
                }
                List<Subject> subjects = subjects(subjectText, fields);
                List<ConditionSequence> predicates = by < 0
                        ? List.of(ConditionSequence.empty())
                        : conditionSequences(predicateText, fields, 0);
                List<List<MyBatisMethodOrder>> orders = order < 0
                        ? List.of(List.of())
                        : orderSequences(orderText, fields, 0);
                for (Subject subject : subjects) {
                    for (ConditionSequence predicate : predicates) {
                        for (List<MyBatisMethodOrder> sort : orders) {
                            MyBatisMethodQuery query = query(
                                    methodName, operation, subject, predicate, sort);
                            if (query != null) {
                                addLimited(results, query);
                            }
                        }
                    }
                }
            }
        }
        return results;
    }

    private static MyBatisMethodQuery query(
            @NotNull String methodName,
            @NotNull OperationPrefix operationPrefix,
            @NotNull Subject subject,
            @NotNull ConditionSequence conditions,
            @NotNull List<MyBatisMethodOrder> orders) {
        if (subject.limit().isPresent() && subject.paged()) {
            return null;
        }
        MyBatisMethodOperation operation = operationPrefix.operation();
        switch (operation) {
            case INSERT_BATCH -> {
                // insertBatch 由独立语法入口构造，不进入通用字段/条件切分。
                return null;
            }
            case SELECT -> {
                // 查询允许全实体、字段投影、排序、limit 与显式分页。
            }
            case UPDATE -> {
                if (subject.fields().isEmpty() || subject.distinct()
                        || subject.limit().isPresent() || subject.paged() || !orders.isEmpty()
                        || new LinkedHashSet<>(subject.fields()).size()
                                != subject.fields().size()
                        || subject.fields().stream().anyMatch(field ->
                                field.autoIncrement() || field.generated())
                        || conditions.conditions().isEmpty()) {
                    return null;
                }
            }
            case DELETE -> {
                if (!subject.fields().isEmpty() || subject.distinct()
                        || subject.limit().isPresent() || subject.paged() || !orders.isEmpty()
                        || conditions.conditions().isEmpty()) {
                    return null;
                }
            }
            case COUNT -> {
                if (subject.fields().size() > 1 || subject.limit().isPresent()
                        || subject.paged() || !orders.isEmpty()
                        || subject.distinct() && subject.fields().isEmpty()) {
                    return null;
                }
            }
            case EXISTS -> {
                if (!subject.fields().isEmpty() || subject.distinct()
                        || subject.limit().isPresent() || subject.paged() || !orders.isEmpty()) {
                    return null;
                }
            }
            case SUM, AVERAGE, MINIMUM, MAXIMUM -> {
                if (subject.fields().size() != 1 || subject.limit().isPresent()
                        || subject.paged() || !orders.isEmpty()) {
                    return null;
                }
            }
            default -> throw new IllegalStateException(MyBatisMethodSqlMessages.message(
                    "methodsql.error.operation.unknown", operation));
        }
        Optional<MyBatisMethodPredicate> predicate = conditions.conditions().isEmpty()
                ? Optional.empty()
                : Optional.of(predicate(conditions));
        return new MyBatisMethodQuery(
                methodName,
                operation,
                subject.fields(),
                subject.distinct(),
                subject.limit(),
                subject.paged(),
                operation == MyBatisMethodOperation.SELECT
                        && (operationPrefix.singleResult()
                                || subject.limit().orElse(-1) == 1),
                predicate,
                orders);
    }

    private static @NotNull MyBatisMethodPredicate predicate(
            @NotNull ConditionSequence sequence) {
        List<MyBatisMethodPredicate> orGroups = new ArrayList<>();
        List<MyBatisMethodPredicate> currentAnd = new ArrayList<>();
        currentAnd.add(sequence.conditions().get(0));
        for (int index = 0; index < sequence.connectors().size(); index++) {
            MyBatisMethodCondition next = sequence.conditions().get(index + 1);
            if (sequence.connectors().get(index) == MyBatisMethodJunctionKind.AND) {
                currentAnd.add(next);
            } else {
                orGroups.add(junction(MyBatisMethodJunctionKind.AND, currentAnd));
                currentAnd = new ArrayList<>();
                currentAnd.add(next);
            }
        }
        orGroups.add(junction(MyBatisMethodJunctionKind.AND, currentAnd));
        return junction(MyBatisMethodJunctionKind.OR, orGroups);
    }

    private static @NotNull MyBatisMethodPredicate junction(
            @NotNull MyBatisMethodJunctionKind kind,
            @NotNull List<MyBatisMethodPredicate> children) {
        return children.size() == 1 ? children.get(0) : new MyBatisMethodJunction(kind, children);
    }

    private static @NotNull List<Subject> subjects(
            @NotNull String text,
            @NotNull List<MyBatisMethodField> fields) {
        List<SubjectPrefix> prefixes = new ArrayList<>();
        prefixes.add(new SubjectPrefix(0, false, OptionalInt.empty(), false));
        int cursor = 0;
        boolean distinct = false;
        if (text.startsWith("Distinct", cursor)) {
            distinct = true;
            cursor += "Distinct".length();
            prefixes.add(new SubjectPrefix(cursor, true, OptionalInt.empty(), false));
        }
        List<SubjectPrefix> withLimit = new ArrayList<>(prefixes);
        for (SubjectPrefix prefix : prefixes) {
            LimitPrefix limit = limit(text, prefix.cursor());
            if (limit != null) {
                withLimit.add(new SubjectPrefix(
                        limit.cursor(), prefix.distinct(), OptionalInt.of(limit.value()), false));
            }
        }
        List<SubjectPrefix> allPrefixes = new ArrayList<>(withLimit);
        for (SubjectPrefix prefix : withLimit) {
            if (text.startsWith("Paged", prefix.cursor())) {
                allPrefixes.add(new SubjectPrefix(
                        prefix.cursor() + "Paged".length(),
                        prefix.distinct(), prefix.limit(), true));
            }
        }
        List<Subject> results = new ArrayList<>();
        for (SubjectPrefix prefix : allPrefixes) {
            String fieldText = text.substring(prefix.cursor());
            if (fieldText.isEmpty() || "All".equals(fieldText)) {
                addLimited(results, new Subject(
                        List.of(), prefix.distinct(), prefix.limit(), prefix.paged()));
                continue;
            }
            for (List<MyBatisMethodField> selection : fieldSequences(fieldText, fields, 0)) {
                addLimited(results, new Subject(
                        selection, prefix.distinct(), prefix.limit(), prefix.paged()));
            }
        }
        return results;
    }

    private static @NotNull List<List<MyBatisMethodField>> fieldSequences(
            @NotNull String text,
            @NotNull List<MyBatisMethodField> fields,
            int cursor) {
        List<List<MyBatisMethodField>> results = new ArrayList<>();
        for (MyBatisMethodField field : matchingFields(text, fields, cursor)) {
            int next = cursor + field.methodToken().length();
            if (next == text.length()) {
                addLimited(results, List.of(field));
            } else if (text.startsWith("And", next)) {
                for (List<MyBatisMethodField> tail : fieldSequences(text, fields, next + 3)) {
                    List<MyBatisMethodField> combined = new ArrayList<>();
                    combined.add(field);
                    combined.addAll(tail);
                    addLimited(results, List.copyOf(combined));
                }
            }
        }
        return distinctLimited(results);
    }

    private static @NotNull List<ConditionSequence> conditionSequences(
            @NotNull String text,
            @NotNull List<MyBatisMethodField> fields,
            int cursor) {
        List<ConditionSequence> results = new ArrayList<>();
        for (MyBatisMethodField field : matchingFields(text, fields, cursor)) {
            int afterField = cursor + field.methodToken().length();
            for (OperatorSuffix suffix : OPERATORS) {
                if (!text.startsWith(suffix.text(), afterField)) {
                    continue;
                }
                int next = afterField + suffix.text().length();
                MyBatisMethodCondition condition = new MyBatisMethodCondition(
                        field, suffix.comparison());
                if (next == text.length()) {
                    addLimited(results, ConditionSequence.single(condition));
                    continue;
                }
                for (Connector connector : connectors(text, next)) {
                    List<ConditionSequence> tails = conditionSequences(
                            text, fields, connector.cursor());
                    for (ConditionSequence tail : tails) {
                        addLimited(results, tail.prepend(condition, connector.kind()));
                    }
                }
            }
        }
        return distinctLimited(results);
    }

    private static @NotNull List<List<MyBatisMethodOrder>> orderSequences(
            @NotNull String text,
            @NotNull List<MyBatisMethodField> fields,
            int cursor) {
        List<List<MyBatisMethodOrder>> results = new ArrayList<>();
        for (MyBatisMethodField field : matchingFields(text, fields, cursor)) {
            int afterField = cursor + field.methodToken().length();
            for (OrderSuffix suffix : orderSuffixes(text, afterField)) {
                MyBatisMethodOrder order = new MyBatisMethodOrder(field, suffix.direction());
                if (suffix.cursor() == text.length()) {
                    addLimited(results, List.of(order));
                    continue;
                }
                for (List<MyBatisMethodOrder> tail : orderSequences(
                        text, fields, suffix.cursor())) {
                    List<MyBatisMethodOrder> combined = new ArrayList<>();
                    combined.add(order);
                    combined.addAll(tail);
                    addLimited(results, List.copyOf(combined));
                }
            }
        }
        return distinctLimited(results);
    }

    private static @NotNull List<OrderSuffix> orderSuffixes(
            @NotNull String text,
            int cursor) {
        List<OrderSuffix> results = new ArrayList<>();
        if (text.startsWith("Asc", cursor)) {
            results.add(new OrderSuffix(
                    cursor + 3, MyBatisMethodOrder.Direction.ASCENDING));
        }
        if (text.startsWith("Desc", cursor)) {
            results.add(new OrderSuffix(
                    cursor + 4, MyBatisMethodOrder.Direction.DESCENDING));
        }
        results.add(new OrderSuffix(cursor, MyBatisMethodOrder.Direction.ASCENDING));
        return results;
    }

    private static @NotNull List<Connector> connectors(
            @NotNull String text,
            int cursor) {
        List<Connector> results = new ArrayList<>();
        if (text.startsWith("And", cursor)) {
            results.add(new Connector(cursor + 3, MyBatisMethodJunctionKind.AND));
        }
        if (text.startsWith("Or", cursor)) {
            results.add(new Connector(cursor + 2, MyBatisMethodJunctionKind.OR));
        }
        return results;
    }

    private static @NotNull List<MyBatisMethodField> matchingFields(
            @NotNull String text,
            @NotNull List<MyBatisMethodField> fields,
            int cursor) {
        ProgressManager.checkCanceled();
        return fields.stream()
                .filter(field -> text.startsWith(field.methodToken(), cursor))
                .sorted(Comparator.comparingInt((MyBatisMethodField field) ->
                        field.methodToken().length()).reversed())
                .toList();
    }

    private static LimitPrefix limit(@NotNull String text, int cursor) {
        String prefix;
        if (text.startsWith("First", cursor)) {
            prefix = "First";
        } else if (text.startsWith("Top", cursor)) {
            prefix = "Top";
        } else {
            return null;
        }
        int digitsStart = cursor + prefix.length();
        int end = digitsStart;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end == digitsStart) {
            return new LimitPrefix(end, 1);
        }
        try {
            int value = Integer.parseInt(text.substring(digitsStart, end));
            return value > 0 && value <= MAX_LIMIT ? new LimitPrefix(end, value) : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static MyBatisMethodParseResult invalidLimit(
            @NotNull String remainder,
            int absoluteOffset) {
        int cursor = remainder.startsWith("Distinct") ? "Distinct".length() : 0;
        String prefix = remainder.startsWith("First", cursor)
                ? "First"
                : remainder.startsWith("Top", cursor) ? "Top" : null;
        if (prefix == null) {
            return null;
        }
        int digitsStart = cursor + prefix.length();
        int end = digitsStart;
        while (end < remainder.length() && Character.isDigit(remainder.charAt(end))) {
            end++;
        }
        if (end == digitsStart) {
            return null;
        }
        try {
            int value = Integer.parseInt(remainder.substring(digitsStart, end));
            if (value > 0 && value <= MAX_LIMIT) {
                return null;
            }
        } catch (NumberFormatException ignored) {
            // 统一返回位置化的条数错误。
        }
        return failure(
                MyBatisMethodDiagnosticCode.INVALID_LIMIT,
                absoluteOffset + digitsStart,
                end - digitsStart,
                MyBatisMethodSqlMessages.message(
                        "methodsql.parser.error.limit.range", MAX_LIMIT));
    }

    private static OperationPrefix operation(@NotNull String methodName) {
        return OPERATIONS.stream()
                .filter(candidate -> methodName.startsWith(candidate.text()))
                .max(Comparator.comparingInt(candidate -> candidate.text().length()))
                .orElse(null);
    }

    private static @NotNull List<Integer> occurrences(
            @NotNull String text,
            @NotNull String token) {
        List<Integer> positions = new ArrayList<>();
        int cursor = text.indexOf(token);
        while (cursor >= 0) {
            positions.add(cursor);
            cursor = text.indexOf(token, cursor + 1);
        }
        return positions;
    }

    private static <T> void addLimited(@NotNull List<T> target, @NotNull T value) {
        if (target.size() < CANDIDATE_LIMIT && !target.contains(value)) {
            target.add(value);
        }
    }

    private static <T> @NotNull List<T> distinctLimited(@NotNull List<T> values) {
        Set<T> unique = new LinkedHashSet<>(values);
        return unique.stream().limit(CANDIDATE_LIMIT).toList();
    }

    private static @NotNull MyBatisMethodParseResult.Failure failure(
            @NotNull MyBatisMethodDiagnosticCode code,
            int offset,
            int length,
            @NotNull String message) {
        return new MyBatisMethodParseResult.Failure(
                new MyBatisMethodDiagnostic(code, offset, length, message));
    }

    private record OperationPrefix(
            @NotNull String text,
            @NotNull MyBatisMethodOperation operation) {
        private boolean singleResult() {
            return "get".equals(text);
        }
    }

    private record OperatorSuffix(
            @NotNull String text,
            @NotNull MyBatisMethodComparison comparison) {
    }

    private record Connector(int cursor, @NotNull MyBatisMethodJunctionKind kind) {
    }

    private record OrderSuffix(int cursor, @NotNull MyBatisMethodOrder.Direction direction) {
    }

    private record LimitPrefix(int cursor, int value) {
    }

    private record SubjectPrefix(
            int cursor,
            boolean distinct,
            @NotNull OptionalInt limit,
            boolean paged) {
    }

    private record Subject(
            @NotNull List<MyBatisMethodField> fields,
            boolean distinct,
            @NotNull OptionalInt limit,
            boolean paged) {
    }

    private record ConditionSequence(
            @NotNull List<MyBatisMethodCondition> conditions,
            @NotNull List<MyBatisMethodJunctionKind> connectors) {
        private static @NotNull ConditionSequence empty() {
            return new ConditionSequence(List.of(), List.of());
        }

        private static @NotNull ConditionSequence single(
                @NotNull MyBatisMethodCondition condition) {
            return new ConditionSequence(List.of(condition), List.of());
        }

        private @NotNull ConditionSequence prepend(
                @NotNull MyBatisMethodCondition condition,
                @NotNull MyBatisMethodJunctionKind connector) {
            List<MyBatisMethodCondition> combinedConditions = new ArrayList<>();
            combinedConditions.add(condition);
            combinedConditions.addAll(conditions);
            List<MyBatisMethodJunctionKind> combinedConnectors = new ArrayList<>();
            combinedConnectors.add(connector);
            combinedConnectors.addAll(connectors);
            return new ConditionSequence(combinedConditions, combinedConnectors);
        }
    }
}
