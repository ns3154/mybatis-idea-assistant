package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodComparison;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodQuery;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 在方法 AST 已唯一解析后，让用户显式选择可因空参数省略的 AND 条件。
 */
final class MyBatisOptionalConditionDialog extends DialogWrapper {
    private final MyBatisOptionalConditionSelectionModel model;
    private final Map<Integer, JBCheckBox> boxes = new LinkedHashMap<>();
    private final Set<Integer> selectedIndexes = new LinkedHashSet<>();

    MyBatisOptionalConditionDialog(
            @NotNull Project project,
            @NotNull MyBatisOptionalConditionSelectionModel model) {
        super(project, true);
        this.model = model;
        setTitle(MyBatisAssistantBundle.message("database.optional.conditions.title"));
        setOKButtonText(MyBatisAssistantBundle.message("dialog.button.preview"));
        setResizable(true);
        init();
        refreshAvailability();
    }

    static @NotNull Optional<Set<Integer>> select(
            @NotNull Project project,
            @NotNull MyBatisMethodQuery query) {
        MyBatisOptionalConditionSelectionModel model =
                MyBatisOptionalConditionSelectionModel.from(query);
        if (!model.hasConditions()) {
            return Optional.of(Set.of());
        }
        MyBatisOptionalConditionDialog dialog =
                new MyBatisOptionalConditionDialog(project, model);
        return dialog.selectionResult(dialog.showAndGet());
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel conditions = new JPanel();
        conditions.setLayout(new BoxLayout(conditions, BoxLayout.Y_AXIS));
        for (MyBatisOptionalConditionSelectionModel.Option option : model.options()) {
            JBCheckBox box = new JBCheckBox(conditionLabel(option));
            int conditionIndex = option.conditionIndex();
            box.addActionListener(event -> {
                if (box.isSelected()) {
                    selectedIndexes.add(conditionIndex);
                } else {
                    selectedIndexes.remove(conditionIndex);
                }
                refreshAvailability();
            });
            boxes.put(conditionIndex, box);
            conditions.add(box);
        }
        JBScrollPane scrollPane = new JBScrollPane(conditions);
        scrollPane.setPreferredSize(new Dimension(680, 260));
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.add(new JBLabel(MyBatisAssistantBundle.message(
                "database.optional.conditions.prompt")), BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(new JBLabel(MyBatisAssistantBundle.message(
                "database.optional.conditions.safety")), BorderLayout.SOUTH);
        return panel;
    }

    @NotNull Set<Integer> optionalConditionIndexes() {
        return model.validatedIndexes(selectedIndexes);
    }

    @NotNull Optional<Set<Integer>> selectionResult(boolean accepted) {
        return accepted
                ? Optional.of(optionalConditionIndexes())
                : Optional.empty();
    }

    /**
     * 平台测试使用的受校验状态变更入口。
     */
    void setConditionOptional(int conditionIndex, boolean optional) {
        if (optional && !model.canSetOptional(conditionIndex, selectedIndexes)) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "database.optional.conditions.error.selection", conditionIndex + 1));
        }
        if (optional) {
            selectedIndexes.add(conditionIndex);
        } else {
            selectedIndexes.remove(conditionIndex);
        }
        JBCheckBox box = boxes.get(conditionIndex);
        if (box == null) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "database.optional.conditions.error.index", conditionIndex + 1));
        }
        box.setSelected(optional);
        refreshAvailability();
    }

    boolean isConditionEnabled(int conditionIndex) {
        JBCheckBox box = boxes.get(conditionIndex);
        if (box == null) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "database.optional.conditions.error.index", conditionIndex + 1));
        }
        return box.isEnabled();
    }

    @NotNull String conditionTooltip(int conditionIndex) {
        JBCheckBox box = boxes.get(conditionIndex);
        if (box == null) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "database.optional.conditions.error.index", conditionIndex + 1));
        }
        return box.getToolTipText() == null ? "" : box.getToolTipText();
    }

    private void refreshAvailability() {
        for (MyBatisOptionalConditionSelectionModel.Option option : model.options()) {
            JBCheckBox box = boxes.get(option.conditionIndex());
            boolean selected = selectedIndexes.contains(option.conditionIndex());
            boolean enabled = option.selectable()
                    && (selected || model.canSetOptional(
                            option.conditionIndex(), selectedIndexes));
            box.setEnabled(enabled);
            box.setToolTipText(disabledTooltip(option, enabled));
        }
    }

    private static @NotNull String disabledTooltip(
            @NotNull MyBatisOptionalConditionSelectionModel.Option option,
            boolean enabled) {
        if (enabled) {
            return "";
        }
        return switch (option.disabledReason()) {
            case OR_TREE -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.disabled.or");
            case PARAMETERLESS -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.disabled.parameterless");
            case PRIMITIVE_PARAMETER -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.disabled.primitive");
            case NONE -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.disabled.write.required");
        };
    }

    private static @NotNull String conditionLabel(
            @NotNull MyBatisOptionalConditionSelectionModel.Option option) {
        return MyBatisAssistantBundle.message(
                "database.optional.conditions.item",
                option.conditionIndex() + 1,
                option.condition().field().propertyName(),
                comparisonLabel(option.condition().comparison()));
    }

    private static @NotNull String comparisonLabel(
            @NotNull MyBatisMethodComparison comparison) {
        return switch (comparison) {
            case EQUALS -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.comparison.equals");
            case NOT_EQUALS -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.comparison.not.equals");
            case LESS_THAN -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.comparison.less.than");
            case LESS_THAN_OR_EQUAL -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.comparison.less.than.or.equal");
            case GREATER_THAN -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.comparison.greater.than");
            case GREATER_THAN_OR_EQUAL -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.comparison.greater.than.or.equal");
            case BETWEEN -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.comparison.between");
            case IN -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.comparison.in");
            case NOT_IN -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.comparison.not.in");
            case LIKE -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.comparison.like");
            case NOT_LIKE -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.comparison.not.like");
            case STARTING_WITH -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.comparison.starting.with");
            case ENDING_WITH -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.comparison.ending.with");
            case CONTAINING -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.comparison.containing");
            case IS_NULL -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.comparison.is.null");
            case IS_NOT_NULL -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.comparison.is.not.null");
            case TRUE -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.comparison.true");
            case FALSE -> MyBatisAssistantBundle.message(
                    "database.optional.conditions.comparison.false");
        };
    }
}
