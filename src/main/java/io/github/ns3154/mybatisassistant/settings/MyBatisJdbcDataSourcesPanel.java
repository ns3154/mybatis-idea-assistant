package io.github.ns3154.mybatisassistant.settings;

import com.intellij.openapi.project.Project;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.FormBuilder;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.database.jdbc.MyBatisJdbcCredentialStore;
import io.github.ns3154.mybatisassistant.database.jdbc.MyBatisJdbcDataSourceConfig;
import io.github.ns3154.mybatisassistant.database.jdbc.MyBatisJdbcDataSourceManager;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Community JDBC 数据源列表；列表本身不读取或持有已有密码。
 */
final class MyBatisJdbcDataSourcesPanel {
    private final Project project;
    private final CollectionListModel<Entry> model = new CollectionListModel<>();
    private final JBList<Entry> list = new JBList<>(model);
    private final JPanel panel;
    private final Map<String, MyBatisJdbcDataSourceConfig> baselineConfigs = new HashMap<>();
    private final Map<String, String> baselineUsers = new HashMap<>();
    private final Set<String> removedIds = new HashSet<>();

    MyBatisJdbcDataSourcesPanel(@NotNull Project project) {
        this.project = project;
        list.setCellRenderer(SimpleListCellRenderer.create(
                "",
                entry -> entry.config.displayName() + "  ·  " + entry.config.dialect()
                        + (entry.config.enabled() ? "" : MyBatisAssistantBundle.message(
                                "settings.jdbc.source.disabled.suffix"))));
        JComponent listPanel = ToolbarDecorator.createDecorator(list)
                .setAddAction(ignored -> addSource())
                .setEditAction(ignored -> editSelected())
                .setRemoveAction(ignored -> removeSelected())
                .disableUpDownActions()
                .createPanel();
        panel = FormBuilder.createFormBuilder()
                .addComponent(new JBLabel(MyBatisAssistantBundle.message(
                        "settings.jdbc.description")))
                .addComponentFillVertically(listPanel, 0)
                .getPanel();
    }

    @NotNull JComponent component() {
        return panel;
    }

    @NotNull JComponent preferredFocusedComponent() {
        return list;
    }

    void resetFrom(@NotNull MyBatisJdbcDataSourceManager manager) {
        clearPendingPasswords();
        List<MyBatisJdbcDataSourceConfig> baseline = manager.dataSources();
        baselineConfigs.clear();
        baselineUsers.clear();
        for (MyBatisJdbcDataSourceConfig config : baseline) {
            baselineConfigs.put(config.id(), config);
            baselineUsers.put(config.id(), config.username());
        }
        removedIds.clear();
        model.replaceAll(baseline.stream().map(config -> new Entry(config, null)).toList());
        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    boolean isModifiedFrom(@NotNull MyBatisJdbcDataSourceManager manager) {
        return !configurations().equals(manager.dataSources())
                || model.getItems().stream().anyMatch(entry -> entry.pendingPassword != null)
                || !removedIds.isEmpty();
    }

    void applyTo(@NotNull MyBatisJdbcDataSourceManager manager) {
        List<Entry> entries = List.copyOf(model.getItems());
        List<MyBatisJdbcDataSourceConfig> configs = entries.stream()
                .map(entry -> entry.config)
                .toList();
        manager.replace(configs);

        for (String removedId : removedIds) {
            String oldUser = baselineUsers.getOrDefault(removedId, "");
            MyBatisJdbcCredentialStore.storePassword(project, removedId, oldUser, null);
        }
        for (Entry entry : entries) {
            String oldUser = baselineUsers.get(entry.config.id());
            if (oldUser != null && !oldUser.equals(entry.config.username())) {
                MyBatisJdbcCredentialStore.storePassword(
                        project, entry.config.id(), oldUser, null);
            }
            MyBatisJdbcDataSourceConfig previous = baselineConfigs.get(entry.config.id());
            if (previous != null && previous.passwordRequired()
                    && !entry.config.passwordRequired()) {
                MyBatisJdbcCredentialStore.storePassword(
                        project, entry.config.id(), previous.username(), null);
            }
            if (entry.pendingPassword != null) {
                MyBatisJdbcCredentialStore.storePassword(
                        project,
                        entry.config.id(),
                        entry.config.username(),
                        entry.pendingPassword);
            }
        }
        clearPendingPasswords();
        baselineConfigs.clear();
        baselineUsers.clear();
        for (MyBatisJdbcDataSourceConfig config : configs) {
            baselineConfigs.put(config.id(), config);
            baselineUsers.put(config.id(), config.username());
        }
        removedIds.clear();
        model.replaceAll(configs.stream().map(config -> new Entry(config, null)).toList());
    }

    void dispose() {
        clearPendingPasswords();
        model.removeAll();
    }

    private void addSource() {
        MyBatisJdbcDataSourceDialog dialog = new MyBatisJdbcDataSourceDialog(project, null);
        if (dialog.showAndGet()) {
            Entry entry = dialog.entry();
            model.add(entry);
            list.setSelectedIndex(model.getSize() - 1);
        }
    }

    private void editSelected() {
        int index = list.getSelectedIndex();
        if (index < 0) {
            return;
        }
        Entry original = model.getElementAt(index);
        MyBatisJdbcDataSourceDialog dialog = new MyBatisJdbcDataSourceDialog(project, original);
        if (dialog.showAndGet()) {
            Entry replacement = dialog.entry();
            if (replacement.pendingPassword == null
                    && original.pendingPassword != null
                    && replacement.config.passwordRequired()
                    && original.config.username().equals(replacement.config.username())) {
                replacement.pendingPassword = original.pendingPassword.clone();
            }
            original.clearPassword();
            model.setElementAt(replacement, index);
        }
    }

    private void removeSelected() {
        int index = list.getSelectedIndex();
        if (index < 0) {
            return;
        }
        Entry removed = model.getElementAt(index);
        removed.clearPassword();
        if (baselineUsers.containsKey(removed.config.id())) {
            removedIds.add(removed.config.id());
        }
        model.remove(index);
    }

    private @NotNull List<MyBatisJdbcDataSourceConfig> configurations() {
        return model.getItems().stream().map(entry -> entry.config).toList();
    }

    private void clearPendingPasswords() {
        for (Entry entry : model.getItems()) {
            entry.clearPassword();
        }
    }

    void replaceForTest(
            @NotNull List<MyBatisJdbcDataSourceConfig> configs,
            char[] pendingPassword) {
        clearPendingPasswords();
        List<Entry> entries = new ArrayList<>();
        for (int index = 0; index < configs.size(); index++) {
            entries.add(new Entry(configs.get(index),
                    index == 0 ? pendingPassword : null));
        }
        model.replaceAll(entries);
    }

    static final class Entry {
        final MyBatisJdbcDataSourceConfig config;
        private char[] pendingPassword;

        Entry(@NotNull MyBatisJdbcDataSourceConfig config, char[] pendingPassword) {
            this.config = config;
            this.pendingPassword = pendingPassword == null ? null : pendingPassword.clone();
        }

        void clearPassword() {
            if (pendingPassword != null) {
                Arrays.fill(pendingPassword, '\0');
                pendingPassword = null;
            }
        }
    }
}
