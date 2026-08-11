package io.github.ns3154.mybatisassistant.index;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.FileBasedIndex;
import io.github.ns3154.mybatisassistant.model.MyBatisBootConfigurationEntry;
import io.github.ns3154.mybatisassistant.model.MyBatisBootConfigurationEntryKind;
import io.github.ns3154.mybatisassistant.model.MyBatisBootConfigurationKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public final class MyBatisBootConfigurationLocator {
    private MyBatisBootConfigurationLocator() {
    }

    public static @NotNull List<MyBatisBootConfigurationEntry> find(
            @NotNull Project project,
            @NotNull MyBatisBootConfigurationEntryKind kind,
            @NotNull String value) {
        return find(project, kind, value, GlobalSearchScope.projectScope(project));
    }

    public static @NotNull List<MyBatisBootConfigurationEntry> find(
            @NotNull Project project,
            @NotNull MyBatisBootConfigurationEntryKind kind,
            @NotNull String value,
            @NotNull GlobalSearchScope scope) {
        return findByKey(project, kind, value, MyBatisBootConfigurationKey.of(kind, value), scope);
    }

    public static @NotNull List<MyBatisBootConfigurationEntry> findAll(
            @NotNull Project project,
            @NotNull MyBatisBootConfigurationEntryKind kind) {
        return findAll(project, kind, GlobalSearchScope.projectScope(project));
    }

    public static @NotNull List<MyBatisBootConfigurationEntry> findAll(
            @NotNull Project project,
            @NotNull MyBatisBootConfigurationEntryKind kind,
            @NotNull GlobalSearchScope scope) {
        return findByKey(project, kind, null, MyBatisBootConfigurationKey.all(kind), scope);
    }

    private static @NotNull List<MyBatisBootConfigurationEntry> findByKey(
            @NotNull Project project,
            @NotNull MyBatisBootConfigurationEntryKind kind,
            @Nullable String value,
            @NotNull String key,
            @NotNull GlobalSearchScope scope) {
        if (project.isDisposed() || !project.isOpen()) {
            return List.of();
        }
        ProgressManager.checkCanceled();
        Collection<VirtualFile> files = FileBasedIndex.getInstance().getContainingFiles(
                MyBatisBootConfigurationIndex.NAME,
                key,
                scope);
        PsiManager psiManager = PsiManager.getInstance(project);
        List<MyBatisBootConfigurationEntry> entries = new ArrayList<>();
        for (VirtualFile file : files) {
            ProgressManager.checkCanceled();
            if (project.isDisposed() || !project.isOpen()) {
                return List.of();
            }
            PsiFile psiFile = psiManager.findFile(file);
            String extension = file.getExtension();
            if (psiFile == null || extension == null) {
                continue;
            }
            for (MyBatisBootConfigurationEntry entry : MyBatisBootConfigurationScanner.scan(
                    psiFile.getText(),
                    extension)) {
                ProgressManager.checkCanceled();
                if (entry.kind() == kind
                        && (value == null
                        || entry.indexKey().equals(MyBatisBootConfigurationKey.of(kind, value)))) {
                    entries.add(entry);
                }
            }
        }
        entries.sort(Comparator.comparing(MyBatisBootConfigurationEntry::value));
        return List.copyOf(entries);
    }
}
