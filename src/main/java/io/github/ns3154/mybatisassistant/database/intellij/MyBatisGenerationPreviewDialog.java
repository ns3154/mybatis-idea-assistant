package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlan;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlanEntry;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlanStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 在任何写入前展示完整文件计划、候选文本和原生差异窗口。
 */
final class MyBatisGenerationPreviewDialog extends DialogWrapper {
    private final Project project;
    private final MyBatisGenerationPlan plan;
    private final PlanTableModel model;
    private final JBTable table;
    private final JBTextArea preview = new JBTextArea();
    private final Action diffAction = new AbstractAction(MyBatisAssistantBundle.message(
            "database.generation.preview.diff")) {
        @Override
        public void actionPerformed(ActionEvent event) {
            showDiff();
        }
    };

    MyBatisGenerationPreviewDialog(
            @NotNull Project project,
            @NotNull MyBatisGenerationPlan plan) {
        super(project, true);
        this.project = project;
        this.plan = plan;
        this.model = new PlanTableModel(plan.entries());
        this.table = new JBTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(event -> updatePreview());
        setTitle(MyBatisAssistantBundle.message("database.generation.preview.title"));
        setOKButtonText(MyBatisAssistantBundle.message(
                "database.generation.preview.generate"));
        setResizable(true);
        init();
        if (model.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }
        updateActions();
    }

    @Override
    protected @NotNull Action[] createLeftSideActions() {
        return new Action[]{diffAction};
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        table.getColumnModel().getColumn(0).setMaxWidth(52);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(570);
        preview.setEditable(false);
        preview.setLineWrap(false);
        JBScrollPane tablePane = new JBScrollPane(table);
        tablePane.setPreferredSize(new Dimension(760, 220));
        JBScrollPane previewPane = new JBScrollPane(preview);
        previewPane.setPreferredSize(new Dimension(760, 300));
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(tablePane, BorderLayout.NORTH);
        panel.add(previewPane, BorderLayout.CENTER);
        return panel;
    }

    @NotNull MyBatisGenerationPlan selectedPlan() {
        return plan.select(model.selectedPaths());
    }

    void setPathSelected(@NotNull String path, boolean selected) {
        int row = model.row(path);
        if (row < 0 || !model.isCellEditable(row, 0)) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "database.generation.preview.file.not.selectable", path));
        }
        model.setValueAt(selected, row, 0);
    }

    @NotNull String previewText() {
        return preview.getText();
    }

    private void updatePreview() {
        MyBatisGenerationPlanEntry entry = selectedEntry();
        if (entry == null) {
            preview.setText("");
            diffAction.setEnabled(false);
            return;
        }
        preview.setText(entry.proposedText().orElseGet(() -> entry.message().orElse("")));
        preview.setCaretPosition(0);
        diffAction.setEnabled(entry.status() == MyBatisGenerationPlanStatus.CREATE
                || entry.status() == MyBatisGenerationPlanStatus.UPDATE);
        updateActions();
    }

    private void updateActions() {
        setOKActionEnabled(!plan.hasConflicts() && !model.selectedPaths().isEmpty());
        if (plan.hasConflicts()) {
            setErrorText(MyBatisAssistantBundle.message(
                    "database.generation.preview.error.conflict"));
        } else if (model.selectedPaths().isEmpty()) {
            setErrorText(MyBatisAssistantBundle.message(
                    "database.generation.preview.error.selection"));
        } else {
            setErrorText(null);
        }
    }

    private void showDiff() {
        MyBatisGenerationPlanEntry entry = selectedEntry();
        if (entry == null || entry.proposedText().isEmpty()) {
            return;
        }
        String path = entry.artifact().relativePath();
        FileType fileType = FileTypeManager.getInstance().getFileTypeByFileName(path);
        DiffContentFactory factory = DiffContentFactory.getInstance();
        SimpleDiffRequest request = new SimpleDiffRequest(
                path,
                factory.create(project, entry.existingText().orElse(""), fileType),
                factory.create(project, entry.proposedText().orElseThrow(), fileType),
                MyBatisAssistantBundle.message(
                        entry.status() == MyBatisGenerationPlanStatus.CREATE
                                ? "database.generation.preview.diff.new"
                                : "database.generation.preview.diff.current"),
                MyBatisAssistantBundle.message("database.generation.preview.diff.candidate"));
        DiffManager.getInstance().showDiff(project, request);
    }

    private @Nullable MyBatisGenerationPlanEntry selectedEntry() {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.entry(row);
    }

    private final class PlanTableModel extends AbstractTableModel {
        private final List<MyBatisGenerationPlanEntry> entries;
        private final Set<String> selected = new HashSet<>();

        private PlanTableModel(List<MyBatisGenerationPlanEntry> entries) {
            this.entries = new ArrayList<>(entries);
            entries.stream()
                    .filter(entry -> entry.status() == MyBatisGenerationPlanStatus.CREATE
                            || entry.status() == MyBatisGenerationPlanStatus.UPDATE)
                    .map(entry -> entry.artifact().relativePath())
                    .forEach(selected::add);
        }

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> MyBatisAssistantBundle.message(
                        "database.generation.preview.column.generate");
                case 1 -> MyBatisAssistantBundle.message(
                        "database.generation.preview.column.status");
                default -> MyBatisAssistantBundle.message(
                        "database.generation.preview.column.file");
            };
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            MyBatisGenerationPlanStatus status = entries.get(row).status();
            return column == 0 && (status == MyBatisGenerationPlanStatus.CREATE
                    || status == MyBatisGenerationPlanStatus.UPDATE);
        }

        @Override
        public Object getValueAt(int row, int column) {
            MyBatisGenerationPlanEntry entry = entries.get(row);
            return switch (column) {
                case 0 -> selected.contains(entry.artifact().relativePath());
                case 1 -> displayStatus(entry.status());
                default -> entry.artifact().relativePath()
                        + entry.message().map(message -> " — " + message).orElse("");
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            String path = entries.get(row).artifact().relativePath();
            if (Boolean.TRUE.equals(value)) {
                selected.add(path);
            } else {
                selected.remove(path);
            }
            fireTableCellUpdated(row, column);
            updateActions();
        }

        private MyBatisGenerationPlanEntry entry(int row) {
            return entries.get(row);
        }

        private Set<String> selectedPaths() {
            return Set.copyOf(selected);
        }

        private int row(String path) {
            for (int index = 0; index < entries.size(); index++) {
                if (entries.get(index).artifact().relativePath().equals(path)) {
                    return index;
                }
            }
            return -1;
        }
    }

    private static String displayStatus(MyBatisGenerationPlanStatus status) {
        return switch (status) {
            case CREATE -> MyBatisAssistantBundle.message(
                    "database.generation.preview.status.create");
            case UPDATE -> MyBatisAssistantBundle.message(
                    "database.generation.preview.status.update");
            case UNCHANGED -> MyBatisAssistantBundle.message(
                    "database.generation.preview.status.unchanged");
            case CONFLICT -> MyBatisAssistantBundle.message(
                    "database.generation.preview.status.conflict");
        };
    }
}
