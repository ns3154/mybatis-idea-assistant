package io.github.ns3154.mybatisassistant.database.intellij;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodCondition;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodComparison;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodJunction;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodJunctionKind;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodOperation;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodPredicate;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodQuery;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Database Tools 两个方法生成入口共享的动态条件选择模型。
 *
 * <p>条件序号严格按生成器的 AST 前序顺序排列；模型只允许用户选择有参数的纯 AND
 * 条件，并在写操作中始终保留至少一个必选谓词。</p>
 */
final class MyBatisOptionalConditionSelectionModel {
    private final MyBatisMethodOperation operation;
    private final List<Option> options;

    private MyBatisOptionalConditionSelectionModel(
            @NotNull MyBatisMethodOperation operation,
            @NotNull List<Option> options) {
        this.operation = operation;
        this.options = List.copyOf(options);
    }

    static @NotNull MyBatisOptionalConditionSelectionModel from(
            @NotNull MyBatisMethodQuery query) {
        List<MyBatisMethodCondition> conditions = new ArrayList<>();
        query.predicate().ifPresent(predicate -> collectConditions(predicate, conditions));
        boolean containsOr = query.predicate()
                .filter(MyBatisOptionalConditionSelectionModel::containsOr)
                .isPresent();
        List<Option> options = new ArrayList<>();
        for (int index = 0; index < conditions.size(); index++) {
            MyBatisMethodCondition condition = conditions.get(index);
            options.add(new Option(
                    index, condition, disabledReason(condition, containsOr)));
        }
        return new MyBatisOptionalConditionSelectionModel(query.operation(), options);
    }

    @NotNull List<Option> options() {
        return options;
    }

    boolean hasConditions() {
        return !options.isEmpty();
    }

    /**
     * 判断当前选择状态下能否把指定条件改为可选；已选条件始终允许取消选择。
     */
    boolean canSetOptional(int conditionIndex, @NotNull Set<Integer> selectedIndexes) {
        Option option = option(conditionIndex);
        if (selectedIndexes.contains(conditionIndex)) {
            return true;
        }
        if (!option.selectable()) {
            return false;
        }
        Set<Integer> candidate = new LinkedHashSet<>(selectedIndexes);
        candidate.add(conditionIndex);
        return !isConditionalWrite() || candidate.size() < options.size();
    }

    /**
     * 生成确定性、不可变的条件序号集合，并再次执行失败关闭校验。
     */
    @NotNull Set<Integer> validatedIndexes(@NotNull Set<Integer> selectedIndexes) {
        List<Integer> sorted = selectedIndexes.stream().sorted().toList();
        Set<Integer> validated = new LinkedHashSet<>();
        for (int index : sorted) {
            Option option = option(index);
            if (!option.selectable()) {
                throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                        "database.optional.conditions.error.not.selectable", index + 1));
            }
            validated.add(index);
        }
        if (isConditionalWrite() && !options.isEmpty()
                && validated.size() == options.size()) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "database.optional.conditions.error.write.required"));
        }
        return Collections.unmodifiableSet(validated);
    }

    private @NotNull Option option(int conditionIndex) {
        if (conditionIndex < 0 || conditionIndex >= options.size()) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "database.optional.conditions.error.index", conditionIndex + 1));
        }
        return options.get(conditionIndex);
    }

    private boolean isConditionalWrite() {
        return operation == MyBatisMethodOperation.UPDATE
                || operation == MyBatisMethodOperation.DELETE;
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

    private static boolean containsOr(@NotNull MyBatisMethodPredicate predicate) {
        if (!(predicate instanceof MyBatisMethodJunction junction)) {
            return false;
        }
        return junction.kind() == MyBatisMethodJunctionKind.OR
                || junction.children().stream()
                        .anyMatch(MyBatisOptionalConditionSelectionModel::containsOr);
    }

    private static @NotNull DisabledReason disabledReason(
            @NotNull MyBatisMethodCondition condition,
            boolean containsOr) {
        if (containsOr) {
            return DisabledReason.OR_TREE;
        }
        if (condition.comparison().parameterCount() == 0) {
            return DisabledReason.PARAMETERLESS;
        }
        if (usesPrimitiveScalarParameter(condition)) {
            return DisabledReason.PRIMITIVE_PARAMETER;
        }
        return DisabledReason.NONE;
    }

    private static boolean usesPrimitiveScalarParameter(
            @NotNull MyBatisMethodCondition condition) {
        if (condition.comparison() == MyBatisMethodComparison.IN
                || condition.comparison() == MyBatisMethodComparison.NOT_IN) {
            return false;
        }
        return switch (condition.field().javaType()) {
            case "boolean", "byte", "short", "int", "long", "float", "double", "char" ->
                    true;
            default -> false;
        };
    }

    record Option(
            int conditionIndex,
            @NotNull MyBatisMethodCondition condition,
            @NotNull DisabledReason disabledReason) {
        boolean selectable() {
            return disabledReason == DisabledReason.NONE;
        }
    }

    enum DisabledReason {
        NONE,
        OR_TREE,
        PARAMETERLESS,
        PRIMITIVE_PARAMETER
    }
}
