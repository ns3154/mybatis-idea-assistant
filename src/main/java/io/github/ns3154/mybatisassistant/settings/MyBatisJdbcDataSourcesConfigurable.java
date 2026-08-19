package io.github.ns3154.mybatisassistant.settings;

import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.database.jdbc.MyBatisJdbcDataSourceManager;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

/**
 * 项目级 Community JDBC 数据源设置入口。
 */
public final class MyBatisJdbcDataSourcesConfigurable implements SearchableConfigurable {
    public static final String ID = "io.github.ns3154.mybatisassistant.settings.jdbc";

    private final Project project;
    private MyBatisJdbcDataSourcesPanel panel;

    public MyBatisJdbcDataSourcesConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @Nls String getDisplayName() {
        return MyBatisAssistantBundle.message("settings.jdbc.display.name");
    }

    @Override
    public @Nullable JComponent createComponent() {
        panel = new MyBatisJdbcDataSourcesPanel(project);
        panel.resetFrom(MyBatisJdbcDataSourceManager.getInstance(project));
        return panel.component();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return panel == null ? null : panel.preferredFocusedComponent();
    }

    @Override
    public boolean isModified() {
        return panel != null && panel.isModifiedFrom(MyBatisJdbcDataSourceManager.getInstance(project));
    }

    @Override
    public void apply() {
        if (panel != null) {
            panel.applyTo(MyBatisJdbcDataSourceManager.getInstance(project));
        }
    }

    @Override
    public void reset() {
        if (panel != null) {
            panel.resetFrom(MyBatisJdbcDataSourceManager.getInstance(project));
        }
    }

    @Override
    public void disposeUIResources() {
        if (panel != null) {
            panel.dispose();
            panel = null;
        }
    }

    MyBatisJdbcDataSourcesPanel panel() {
        return panel;
    }
}
