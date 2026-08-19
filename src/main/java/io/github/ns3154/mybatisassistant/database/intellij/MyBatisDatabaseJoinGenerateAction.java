package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.database.psi.DbElement;
import com.intellij.database.psi.DbTable;
import com.intellij.database.view.DatabaseView;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfiguration;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisJoinGeneration;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisJoinGenerationRequest;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisJoinRelation;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisJoinRelationshipVerifier;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisJoinSelection;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisJoinSpec;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisJoinSqlGenerator;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisJoinType;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodField;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodSchema;
import io.github.ns3154.mybatisassistant.util.MyBatisReadActionSupport;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 对两张已加载表执行显式 FK/PK Join 选择，只预览 SQL，不猜测业务关系。
 */
public final class MyBatisDatabaseJoinGenerateAction extends AnAction {
    public static final String ID = "MyBatisAssistant.Database.GenerateJoin";
    private static final String BASE_ALIAS = "t1";
    private static final String TARGET_ALIAS = "t2";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        DbTable[] tables = selectedTables(event);
        boolean enabled = project != null && validSelection(tables);
        event.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        DbTable[] tables = selectedTables(event);
        if (project == null || !validSelection(tables)) {
            return;
        }
        MyBatisGenerationOptionsDialog options = new MyBatisGenerationOptionsDialog(project);
        if (!options.showAndGet()) {
            return;
        }
        try {
            JoinModel model = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    () -> loadModel(tables, options.configuration()),
                    MyBatisAssistantBundle.message("database.join.progress.metadata"),
                    true,
                    project);
            RelationChoice relation = chooseRelation(project, model.relations());
            if (relation == null) {
                return;
            }
            MyBatisJoinType type = chooseJoinType(project, model.dialect());
            if (type == null) {
                return;
            }
            String defaultSelections = defaultSelections(model.base(), model.target());
            String selectionText = Messages.showInputDialog(
                    project,
                    MyBatisAssistantBundle.message("database.join.selection.prompt"),
                    MyBatisAssistantBundle.message("database.join.selection.title"),
                    Messages.getQuestionIcon(),
                    defaultSelections,
                    null);
            if (selectionText == null) {
                return;
            }
            MyBatisJoinGeneration generation = buildGeneration(
                    model, relation, type, selectionText);
            new MyBatisGeneratedTextPreviewDialog(
                    project,
                    MyBatisAssistantBundle.message("database.join.preview.title"),
                    "-- t1 = " + model.base().tableName()
                            + "\n-- t2 = " + model.target().tableName()
                            + "\n" + generation.sql() + "\n").show();
        } catch (ProcessCanceledException canceled) {
            throw canceled;
        } catch (RuntimeException failure) {
            Messages.showErrorDialog(
                    project,
                    failure.getMessage() == null
                            ? failure.getClass().getSimpleName() : failure.getMessage(),
                    MyBatisAssistantBundle.message("database.join.error.title"));
        }
    }

    static @NotNull JoinModel model(
            @NotNull MyBatisDatabaseTable baseTable,
            @NotNull MyBatisDatabaseTable targetTable,
            @NotNull MyBatisSqlDialect dialect,
            @NotNull MyBatisGenerationConfiguration configuration) {
        MyBatisMethodSchema base = MyBatisMethodSchema.from(baseTable, configuration);
        MyBatisMethodSchema target = MyBatisMethodSchema.from(targetTable, configuration);
        List<RelationChoice> relations = relationChoices(base, target);
        if (relations.isEmpty()) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "database.join.error.no.relation"));
        }
        return new JoinModel(base, target, dialect, relations);
    }

    static @NotNull List<RelationChoice> relationChoices(
            @NotNull MyBatisMethodSchema base,
            @NotNull MyBatisMethodSchema target) {
        List<RelationChoice> choices = new ArrayList<>();
        for (MyBatisMethodField baseField : base.fields()) {
            ProgressManager.checkCanceled();
            for (MyBatisMethodField targetField : target.fields()) {
                if (MyBatisJoinRelationshipVerifier.isVerifiedForeignKeyToPrimaryKey(
                        base, baseField, target, targetField)) {
                    choices.add(new RelationChoice(baseField, targetField));
                }
            }
        }
        return List.copyOf(choices);
    }

    static @NotNull MyBatisJoinGeneration buildGeneration(
            @NotNull JoinModel model,
            @NotNull RelationChoice relation,
            @NotNull MyBatisJoinType type,
            @NotNull String selectionText) {
        if (!model.relations().contains(relation)) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "database.join.error.relation.invalid"));
        }
        List<MyBatisJoinSelection> selections = parseSelections(
                selectionText, model.base(), model.target());
        return MyBatisJoinSqlGenerator.generate(new MyBatisJoinGenerationRequest(
                model.base(),
                BASE_ALIAS,
                List.of(new MyBatisJoinSpec(
                        type,
                        model.target(),
                        TARGET_ALIAS,
                        List.of(new MyBatisJoinRelation(
                                BASE_ALIAS,
                                relation.baseField(),
                                relation.targetField())))),
                selections,
                model.dialect(),
                true));
    }

    static @NotNull List<MyBatisJoinType> supportedJoinTypes(
            @NotNull MyBatisSqlDialect dialect) {
        if (dialect == MyBatisSqlDialect.SQLITE) {
            return List.of(MyBatisJoinType.INNER, MyBatisJoinType.LEFT);
        }
        if (dialect == MyBatisSqlDialect.MYSQL) {
            return List.of(
                    MyBatisJoinType.INNER, MyBatisJoinType.LEFT, MyBatisJoinType.RIGHT);
        }
        return List.of(MyBatisJoinType.values());
    }

    private static @NotNull JoinModel loadModel(
            @NotNull DbTable[] tables,
            @NotNull MyBatisGenerationConfiguration configuration) {
        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        if (indicator == null) {
            throw new IllegalStateException(MyBatisAssistantBundle.message(
                    "database.join.error.progress.context"));
        }
        return loadModel(
                tables,
                configuration,
                indicator,
                MyBatisDatabaseJoinGenerateAction::snapshot,
                MyBatisDatabaseJoinGenerateAction::buildModel);
    }

    static @NotNull JoinModel loadModel(
            @NotNull DbTable[] tables,
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull ProgressIndicator indicator,
            @NotNull JoinSnapshotFactory snapshotFactory,
            @NotNull JoinModelFactory modelFactory) {
        JoinSnapshot snapshot = MyBatisReadActionSupport.compute(() -> {
            indicator.checkCanceled();
            if (Arrays.stream(tables).anyMatch(table -> !table.isValid()
                    || table.getDataSource().isLoading())) {
                throw new IllegalStateException(MyBatisAssistantBundle.message(
                        "database.join.error.model.changed"));
            }
            if (tables[0].getDataSource() != tables[1].getDataSource()) {
                throw new IllegalStateException(MyBatisAssistantBundle.message(
                        "database.join.error.data.source"));
            }
            return snapshotFactory.snapshot(tables, indicator);
        });
        indicator.checkCanceled();
        return modelFactory.model(snapshot, configuration);
    }

    private static @NotNull JoinSnapshot snapshot(
            @NotNull DbTable[] tables,
            @NotNull ProgressIndicator indicator) {
        return new JoinSnapshot(
                DatabaseToolsMetadataProvider.table(tables[0].getDasObject(), indicator),
                DatabaseToolsMetadataProvider.table(tables[1].getDasObject(), indicator),
                DatabaseToolsMetadataProvider.dialect(tables[0].getDataSource().getDbms()));
    }

    private static @NotNull JoinModel buildModel(
            @NotNull JoinSnapshot snapshot,
            @NotNull MyBatisGenerationConfiguration configuration) {
        return model(snapshot.base(), snapshot.target(), snapshot.dialect(), configuration);
    }

    private static RelationChoice chooseRelation(
            @NotNull Project project,
            @NotNull List<RelationChoice> choices) {
        MyBatisChoiceDialog<RelationChoice> dialog = new MyBatisChoiceDialog<>(
                project,
                MyBatisAssistantBundle.message("database.join.relation.title"),
                MyBatisAssistantBundle.message("database.join.relation.prompt"),
                choices,
                RelationChoice::display);
        return dialog.showAndGet() ? dialog.selectedValue() : null;
    }

    private static MyBatisJoinType chooseJoinType(
            @NotNull Project project,
            @NotNull MyBatisSqlDialect dialect) {
        List<MyBatisJoinType> types = supportedJoinTypes(dialect);
        MyBatisChoiceDialog<MyBatisJoinType> dialog = new MyBatisChoiceDialog<>(
                project,
                MyBatisAssistantBundle.message("database.join.type.title"),
                MyBatisAssistantBundle.message("database.join.type.prompt"),
                types,
                MyBatisDatabaseJoinGenerateAction::displayType);
        return dialog.showAndGet() ? dialog.selectedValue() : null;
    }

    private static @NotNull List<MyBatisJoinSelection> parseSelections(
            @NotNull String text,
            @NotNull MyBatisMethodSchema base,
            @NotNull MyBatisMethodSchema target) {
        if (text.isBlank()) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "database.join.error.selection.empty"));
        }
        List<MyBatisJoinSelection> result = new ArrayList<>();
        Set<String> selected = new LinkedHashSet<>();
        for (String raw : text.split(",")) {
            String token = raw.trim();
            int separator = token.indexOf('.');
            if (separator <= 0 || separator == token.length() - 1
                    || token.indexOf('.', separator + 1) >= 0) {
                throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                        "database.join.error.selection.format", token));
            }
            String alias = token.substring(0, separator);
            String property = token.substring(separator + 1);
            MyBatisMethodSchema schema = switch (alias) {
                case BASE_ALIAS -> base;
                case TARGET_ALIAS -> target;
                default -> throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                        "database.join.error.alias.unknown", alias));
            };
            MyBatisMethodField field = schema.fields().stream()
                    .filter(candidate -> candidate.propertyName().equals(property))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(MyBatisAssistantBundle.message(
                            "database.join.error.property.missing", alias, property)));
            if (!selected.add(token)) {
                throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                        "database.join.error.selection.duplicate", token));
            }
            result.add(new MyBatisJoinSelection(
                    alias,
                    field,
                    Optional.of(alias + field.methodToken())));
        }
        return List.copyOf(result);
    }

    private static @NotNull String defaultSelections(
            @NotNull MyBatisMethodSchema base,
            @NotNull MyBatisMethodSchema target) {
        List<String> fields = new ArrayList<>();
        base.fields().forEach(field -> fields.add(BASE_ALIAS + "." + field.propertyName()));
        target.fields().forEach(field -> fields.add(TARGET_ALIAS + "." + field.propertyName()));
        return String.join(",", fields);
    }

    private static @NotNull String displayType(@NotNull MyBatisJoinType type) {
        return switch (type) {
            case INNER -> "INNER JOIN";
            case LEFT -> "LEFT JOIN";
            case RIGHT -> "RIGHT JOIN";
            case FULL -> "FULL JOIN";
        };
    }

    private static @NotNull DbTable[] selectedTables(@NotNull AnActionEvent event) {
        DbElement[] elements = event.getData(DatabaseView.DB_ELEMENTS);
        if (elements == null || elements.length == 0
                || Arrays.stream(elements).anyMatch(element -> !(element instanceof DbTable))) {
            return new DbTable[0];
        }
        return Arrays.stream(elements).map(DbTable.class::cast).toArray(DbTable[]::new);
    }

    private static boolean validSelection(@NotNull DbTable[] tables) {
        return tables.length == 2
                && Arrays.stream(tables).allMatch(table -> table.isValid()
                        && !table.getDataSource().isLoading())
                && tables[0].getDataSource() == tables[1].getDataSource();
    }

    record RelationChoice(
            @NotNull MyBatisMethodField baseField,
            @NotNull MyBatisMethodField targetField) {
        private @NotNull String display() {
            return BASE_ALIAS + "." + baseField.columnName() + " = "
                    + TARGET_ALIAS + "." + targetField.columnName();
        }
    }

    record JoinModel(
            @NotNull MyBatisMethodSchema base,
            @NotNull MyBatisMethodSchema target,
            @NotNull MyBatisSqlDialect dialect,
            @NotNull List<RelationChoice> relations) {
        JoinModel {
            relations = List.copyOf(relations);
        }
    }

    record JoinSnapshot(
            @NotNull MyBatisDatabaseTable base,
            @NotNull MyBatisDatabaseTable target,
            @NotNull MyBatisSqlDialect dialect) {
    }

    @FunctionalInterface
    interface JoinSnapshotFactory {
        @NotNull JoinSnapshot snapshot(
                @NotNull DbTable[] tables,
                @NotNull ProgressIndicator indicator);
    }

    @FunctionalInterface
    interface JoinModelFactory {
        @NotNull JoinModel model(
                @NotNull JoinSnapshot snapshot,
                @NotNull MyBatisGenerationConfiguration configuration);
    }
}
