package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.database.model.DasColumn;
import com.intellij.database.model.DasObject;
import com.intellij.database.model.DasTable;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbElement;
import com.intellij.database.psi.DbPsiFacade;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * 只在 Database Tools 已加载模型中定位表列，不触发连接或刷新。
 */
final class MyBatisDatabaseObjectLocator {
    private MyBatisDatabaseObjectLocator() {
    }

    static @Nullable PsiElement find(
            @NotNull Project project,
            @NotNull MyBatisDatabaseObjectTarget target) {
        if (project.isDisposed() || !project.isOpen()) {
            return null;
        }
        DbDataSource dataSource = DbPsiFacade.getInstance(project)
                .findDataSource(target.dataSourceId());
        return dataSource == null ? null : find(dataSource, target);
    }

    static @Nullable PsiElement find(
            @NotNull DbDataSource dataSource,
            @NotNull MyBatisDatabaseObjectTarget target) {
        ProgressManager.checkCanceled();
        Project project = dataSource.getProject();
        if (!dataSource.isValid()
                || project.isDisposed()
                || !project.isOpen()
                || dataSource.isLoading()
                || !target.dataSourceId().equals(dataSource.getUniqueId())
                || !target.dataSourceDisplayName().equals(dataSource.getName())
                || target.dataSourceModificationCount()
                        != Math.max(0, dataSource.getModificationTracker()
                                .getModificationCount())) {
            return null;
        }
        DasTable matchedTable = null;
        for (DasObject object : dataSource.getModel().traverser()) {
            ProgressManager.checkCanceled();
            if (object instanceof DasTable table && matches(table, target)) {
                if (matchedTable != null) {
                    return null;
                }
                matchedTable = table;
            }
        }
        if (matchedTable == null) {
            return null;
        }
        DasObject object = matchedTable;
        if (target.columnName().isPresent()) {
            object = uniqueColumn(matchedTable, target.columnName().orElseThrow());
            if (object == null) {
                return null;
            }
        }
        ProgressManager.checkCanceled();
        if (!dataSource.isValid()
                || project.isDisposed()
                || !project.isOpen()
                || target.dataSourceModificationCount()
                        != Math.max(0, dataSource.getModificationTracker()
                                .getModificationCount())) {
            return null;
        }
        DbElement element = dataSource.findElement(object);
        return element != null && element.isValid() ? element : null;
    }

    private static @Nullable DasColumn uniqueColumn(
            @NotNull DasTable table,
            @NotNull String columnName) {
        DasColumn matched = null;
        for (DasObject child : table.getDasChildren(ObjectKind.COLUMN)) {
            ProgressManager.checkCanceled();
            if (child instanceof DasColumn column
                    && equalsName(columnName, column.getName())) {
                if (matched != null) {
                    return null;
                }
                matched = column;
            }
        }
        return matched;
    }

    private static boolean matches(
            @NotNull DasTable table,
            @NotNull MyBatisDatabaseObjectTarget target) {
        return equalsName(target.tableName(), table.getName())
                && namespace(table, ObjectKind.DATABASE).equals(target.catalog())
                && namespace(table, ObjectKind.SCHEMA).equals(target.schema());
    }

    private static @NotNull Optional<String> namespace(
            @NotNull DasObject object,
            @NotNull ObjectKind kind) {
        DasObject current = object.getDasParent();
        while (current != null) {
            ProgressManager.checkCanceled();
            if (kind.equals(current.getKind())) {
                String name = current.getName();
                return name == null || name.isBlank()
                        ? Optional.empty()
                        : Optional.of(name);
            }
            current = current.getDasParent();
        }
        return Optional.empty();
    }

    private static boolean equalsName(@NotNull String first, @Nullable String second) {
        return second != null && first.equalsIgnoreCase(second);
    }
}
