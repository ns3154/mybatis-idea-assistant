package io.github.ns3154.mybatisassistant.index;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.indexing.FileBasedIndex;
import io.github.ns3154.mybatisassistant.model.MyBatisConfigurationEntries;
import io.github.ns3154.mybatisassistant.model.MyBatisConfigurationEntry;
import io.github.ns3154.mybatisassistant.model.MyBatisConfigurationEntryKind;
import io.github.ns3154.mybatisassistant.model.MyBatisConfigurationKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class MyBatisConfigurationLocator {
    private MyBatisConfigurationLocator() {
    }

    public static @NotNull List<XmlTag> find(
            @NotNull Project project,
            @NotNull MyBatisConfigurationEntryKind kind,
            @NotNull String name) {
        return find(project, kind, name, GlobalSearchScope.projectScope(project));
    }

    public static @NotNull List<XmlTag> find(
            @NotNull Project project,
            @NotNull MyBatisConfigurationEntryKind kind,
            @NotNull String name,
            @NotNull GlobalSearchScope scope) {
        return findByKey(project, kind, name, MyBatisConfigurationKey.of(kind, name), scope);
    }

    public static @NotNull List<XmlTag> findAll(
            @NotNull Project project,
            @NotNull MyBatisConfigurationEntryKind kind) {
        return findAll(project, kind, GlobalSearchScope.projectScope(project));
    }

    public static @NotNull List<XmlTag> findAll(
            @NotNull Project project,
            @NotNull MyBatisConfigurationEntryKind kind,
            @NotNull GlobalSearchScope scope) {
        return findByKey(project, kind, null, MyBatisConfigurationKey.all(kind), scope);
    }

    private static @NotNull List<XmlTag> findByKey(
            @NotNull Project project,
            @NotNull MyBatisConfigurationEntryKind kind,
            @Nullable String name,
            @NotNull String key,
            @NotNull GlobalSearchScope scope) {
        if (project.isDisposed() || !project.isOpen()) {
            return List.of();
        }
        ProgressManager.checkCanceled();
        Collection<VirtualFile> files = FileBasedIndex.getInstance().getContainingFiles(
                MyBatisConfigurationIndex.NAME,
                key,
                scope);
        PsiManager psiManager = PsiManager.getInstance(project);
        List<XmlTag> targets = new ArrayList<>();
        for (VirtualFile file : files) {
            ProgressManager.checkCanceled();
            if (project.isDisposed() || !project.isOpen()) {
                return List.of();
            }
            PsiFile psiFile = psiManager.findFile(file);
            if (psiFile instanceof XmlFile xmlFile) {
                collectMatchingTags(xmlFile, kind, name, targets);
            }
        }
        sortTargets(targets);
        return List.copyOf(targets);
    }

    private static void collectMatchingTags(
            @NotNull XmlFile xmlFile,
            @NotNull MyBatisConfigurationEntryKind kind,
            @Nullable String name,
            @NotNull List<XmlTag> targets) {
        XmlTag root = xmlFile.getRootTag();
        if (PsiTreeUtil.hasErrorElements(xmlFile)
                || root == null
                || !"configuration".equals(root.getName())) {
            return;
        }
        for (XmlTag section : root.getSubTags()) {
            ProgressManager.checkCanceled();
            if (!"typeAliases".equals(section.getName()) && !"mappers".equals(section.getName())) {
                continue;
            }
            for (XmlTag entryTag : section.getSubTags()) {
                ProgressManager.checkCanceled();
                List<MyBatisConfigurationEntry> entries = MyBatisConfigurationEntries.fromTag(
                        section.getName(),
                        entryTag.getName(),
                        attributeName -> unprefixedAttributeValue(entryTag, attributeName));
                if (entries.stream().anyMatch(entry -> matches(entry, kind, name))) {
                    targets.add(entryTag);
                }
            }
        }
    }

    private static boolean matches(
            @NotNull MyBatisConfigurationEntry entry,
            @NotNull MyBatisConfigurationEntryKind kind,
            @Nullable String name) {
        if (entry.kind() != kind) {
            return false;
        }
        return name == null || entry.indexKey().equals(MyBatisConfigurationKey.of(kind, name));
    }

    private static @Nullable String unprefixedAttributeValue(
            @NotNull XmlTag tag,
            @NotNull String attributeName) {
        XmlAttribute attribute = tag.getAttribute(attributeName);
        return attribute != null && attributeName.equals(attribute.getName())
                ? attribute.getValue()
                : null;
    }

    private static void sortTargets(@NotNull List<XmlTag> targets) {
        targets.sort((left, right) -> {
            ProgressManager.checkCanceled();
            int byPath = left.getContainingFile()
                    .getVirtualFile()
                    .getPath()
                    .compareTo(right.getContainingFile().getVirtualFile().getPath());
            return byPath != 0
                    ? byPath
                    : Integer.compare(left.getTextOffset(), right.getTextOffset());
        });
        ProgressManager.checkCanceled();
    }
}
