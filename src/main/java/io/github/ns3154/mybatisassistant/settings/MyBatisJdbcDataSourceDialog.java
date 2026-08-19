package io.github.ns3154.mybatisassistant.settings;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.database.jdbc.MyBatisJdbcDataSourceConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 新增或编辑单个 JDBC 数据源；已有密码从不回填到界面。
 */
final class MyBatisJdbcDataSourceDialog extends DialogWrapper {
    private final String id;
    private final boolean newSource;
    private final String originalUsername;
    private final JBCheckBox enabled = new JBCheckBox(MyBatisAssistantBundle.message(
            "settings.jdbc.source.enabled"), true);
    private final JBTextField displayName = new JBTextField();
    private final JComboBox<MyBatisSqlDialect> dialect = new JComboBox<>(Arrays.stream(
            MyBatisSqlDialect.values())
            .filter(value -> value != MyBatisSqlDialect.GENERIC)
            .toArray(MyBatisSqlDialect[]::new));
    private final JBTextField jdbcUrl = new JBTextField();
    private final JBTextField driverClass = new JBTextField();
    private final JBTextArea driverJars = new JBTextArea(3, 40);
    private final JBTextField username = new JBTextField();
    private final JBCheckBox passwordRequired = new JBCheckBox(MyBatisAssistantBundle.message(
            "settings.jdbc.source.password.safe"), true);
    private final JBPasswordField password = new JBPasswordField();
    private final JBTextField catalog = new JBTextField();
    private final JBTextField schema = new JBTextField();
    private MyBatisJdbcDataSourcesPanel.Entry result;

    MyBatisJdbcDataSourceDialog(
            @NotNull Project project,
            @Nullable MyBatisJdbcDataSourcesPanel.Entry original) {
        super(project, true);
        newSource = original == null;
        id = newSource ? UUID.randomUUID().toString() : original.config.id();
        originalUsername = newSource ? "" : original.config.username();
        setTitle(MyBatisAssistantBundle.message(newSource
                ? "settings.jdbc.source.title.add"
                : "settings.jdbc.source.title.edit"));
        if (original != null) {
            MyBatisJdbcDataSourceConfig config = original.config;
            enabled.setSelected(config.enabled());
            displayName.setText(config.displayName());
            dialect.setSelectedItem(config.dialect());
            jdbcUrl.setText(config.jdbcUrl());
            driverClass.setText(config.driverClassName());
            driverJars.setText(String.join("\n", config.driverJarPaths()));
            username.setText(config.username());
            passwordRequired.setSelected(config.passwordRequired());
            catalog.setText(config.catalog().orElse(""));
            schema.setText(config.schema().orElse(""));
        }
        password.setToolTipText(MyBatisAssistantBundle.message(newSource
                ? "settings.jdbc.source.password.tip.new"
                : "settings.jdbc.source.password.tip.edit"));
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addComponent(enabled)
                .addLabeledComponent(MyBatisAssistantBundle.message(
                        "settings.jdbc.source.label.name"), displayName)
                .addLabeledComponent(MyBatisAssistantBundle.message(
                        "settings.jdbc.source.label.dialect"), dialect)
                .addLabeledComponent(MyBatisAssistantBundle.message(
                        "settings.jdbc.source.label.url"), jdbcUrl)
                .addLabeledComponent(MyBatisAssistantBundle.message(
                        "settings.jdbc.source.label.driver.class"), driverClass)
                .addLabeledComponent(MyBatisAssistantBundle.message(
                        "settings.jdbc.source.label.driver.jars"),
                        new JBScrollPane(driverJars))
                .addLabeledComponent(MyBatisAssistantBundle.message(
                        "settings.jdbc.source.label.username"), username)
                .addComponent(passwordRequired)
                .addLabeledComponent(MyBatisAssistantBundle.message(newSource
                        ? "settings.jdbc.source.label.password"
                        : "settings.jdbc.source.label.password.new"), password)
                .addLabeledComponent(MyBatisAssistantBundle.message(
                        "settings.jdbc.source.label.catalog"), catalog)
                .addLabeledComponent(MyBatisAssistantBundle.message(
                        "settings.jdbc.source.label.schema"), schema)
                .getPanel();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return displayName;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        try {
            MyBatisJdbcDataSourceConfig config = createConfig();
            if (config.driverJarPaths().isEmpty()) {
                return new ValidationInfo(MyBatisAssistantBundle.message(
                        "settings.jdbc.source.validation.driver.jar"), driverJars);
            }
            if (config.passwordRequired()
                    && (newSource || !originalUsername.equals(config.username()))
                    && !hasEnteredPassword()) {
                return new ValidationInfo(
                        MyBatisAssistantBundle.message(
                                "settings.jdbc.source.validation.password"), password);
            }
            return null;
        } catch (IllegalArgumentException failure) {
            return new ValidationInfo(failure.getMessage());
        }
    }

    @Override
    protected void doOKAction() {
        char[] enteredPassword = password.getPassword();
        try {
            MyBatisJdbcDataSourceConfig config = createConfig();
            result = new MyBatisJdbcDataSourcesPanel.Entry(config,
                    config.passwordRequired() && enteredPassword.length > 0
                            ? enteredPassword
                            : null);
            super.doOKAction();
        } finally {
            Arrays.fill(enteredPassword, '\0');
            password.setText("");
        }
    }

    @NotNull MyBatisJdbcDataSourcesPanel.Entry entry() {
        if (result == null) {
            throw new IllegalStateException(MyBatisAssistantBundle.message(
                    "settings.jdbc.source.error.not.confirmed"));
        }
        return result;
    }

    private @NotNull MyBatisJdbcDataSourceConfig createConfig() {
        List<String> jars = driverJars.getText().lines()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        return new MyBatisJdbcDataSourceConfig(
                id,
                displayName.getText(),
                java.util.Objects.requireNonNull((MyBatisSqlDialect) dialect.getSelectedItem()),
                jdbcUrl.getText(),
                driverClass.getText(),
                jars,
                username.getText(),
                passwordRequired.isSelected(),
                Optional.of(catalog.getText()),
                Optional.of(schema.getText()),
                enabled.isSelected());
    }

    private boolean hasEnteredPassword() {
        char[] enteredPassword = password.getPassword();
        try {
            return enteredPassword.length > 0;
        } finally {
            Arrays.fill(enteredPassword, '\0');
        }
    }
}
